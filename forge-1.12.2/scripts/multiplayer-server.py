# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Prepare and supervise an authenticated, loopback-only Forge smoke server.

Preparation reads only pinned artifacts already cached by the development setup.
Run never starts a client, changes an account, or places Remix on the server.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import socket
import stat
import subprocess
import threading
import time
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SERVER_ROOT = ROOT / "work" / "multiplayer-server"
FORGE = "1.12.2-14.23.5.2860"
INSTALLER_SHA256 = "ea7c33ba95e3993a98d0e9e38168c0759ec323a18675a71d938e1f3f70e6e8e7"
SERVER_SHA1 = "886945bfb2b978778c3a0288fd7fab09d315b25f"
RLCRAFT_ARCHIVE = "RLCraft Server Pack 1.12.2 - Release v2.9.3.zip"
# Official CurseForge file 4612990; its CDN ETag also matches MD5 5549bd68ede26c8d225518da1f87ba14.
RLCRAFT_SHA256 = "a29b3c0c99b41e1c7d404a78f48b7f9698f15b068141a042d5ca1108a8636c55"
IDENTIFIER = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9-]{0,47}\Z")


def digest(path, algorithm="sha1"):
    with path.open("rb") as source:
        return hashlib.file_digest(source, algorithm).hexdigest()


def checked(path, expected, algorithm="sha1"):
    if not path.is_file() or digest(path, algorithm) != expected:
        raise RuntimeError(f"Missing or mismatched pinned artifact: {path}")
    return path


def install_root():
    # Neither preparation nor run may follow a junction into another checkout.
    expected = ROOT.resolve() / "work" / "multiplayer-server"
    if SERVER_ROOT.resolve() != expected:
        raise RuntimeError("Multiplayer server directory must not be a link")
    return expected


def library_source(artifact):
    relative = Path(artifact["path"])
    if relative.is_absolute() or ".." in relative.parts:
        raise RuntimeError("Invalid pinned library path")
    direct = ROOT / ".tools" / "PrismLauncher" / "libraries" / relative
    if direct.is_file():
        return checked(direct, artifact["sha1"])
    matches = list((ROOT / ".gradle-home" / "caches" / "modules-2" / "files-2.1").glob(
        f"*/*/*/{artifact['sha1']}/{relative.name}"))
    if len(matches) != 1:
        raise RuntimeError(f"Pinned library is not cached: {relative}")
    return checked(matches[0], artifact["sha1"])


def copy_checked(source, target, expected, algorithm="sha1"):
    if target.exists():
        checked(target, expected, algorithm)
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    checked(target, expected, algorithm)


def safe_relative(name):
    path = Path(name)
    if not name or path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
        raise RuntimeError("Unsafe server-pack path: " + name)
    for part in name.rstrip("/").split("/"):
        if not part or part in (".", "..") or part.endswith((" ", ".")) or part.split(".")[0].upper() in {
                "CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}:
            raise RuntimeError("Unsafe server-pack component: " + name)
    return path


def pack_inventory(archive):
    entries = archive.infolist()
    if len(entries) > 20000 or sum(entry.file_size for entry in entries) > 2 * 1024**3:
        raise RuntimeError("Server archive exceeds the bounded extraction envelope")
    seen = set()
    for entry in entries:
        safe_relative(entry.filename)
        key = entry.filename.rstrip("/").lower()
        if key in seen or stat.S_ISLNK(entry.external_attr >> 16):
            raise RuntimeError("Duplicate or linked server-pack entry: " + entry.filename)
        seen.add(key)
    result = {}
    for entry in entries:
        if not entry.is_dir():
            with archive.open(entry) as source:
                result[entry.filename] = hashlib.file_digest(source, "sha256").hexdigest()
    return result


def verify_pack(folder, inventory):
    actual = set()
    for path in folder.rglob("*"):
        relative = path.relative_to(folder).as_posix()
        if path.resolve() != folder / safe_relative(relative):
            raise RuntimeError("Server pack contains a filesystem link: " + relative)
        if path.is_file():
            actual.add(relative)
    if actual != set(inventory):
        raise RuntimeError("Pristine server pack file inventory changed")
    for relative, sha256 in inventory.items():
        checked(folder / safe_relative(relative), sha256, "sha256")


def rlcraft_pack(create=False):
    source = checked(ROOT / "work" / "downloads" / RLCRAFT_ARCHIVE, RLCRAFT_SHA256, "sha256")
    folder = ROOT.resolve() / "work" / "rlcraft-server-2.9.3"
    if folder.resolve() != folder:
        raise RuntimeError("Pristine server pack directory must not be a link")
    with zipfile.ZipFile(source) as archive:
        inventory = pack_inventory(archive)
        if not folder.exists():
            if not create:
                raise RuntimeError("Prepare the RLCraft pack before starting its server")
            folder.mkdir()
            for relative in inventory:
                target = folder / safe_relative(relative)
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(relative) as src, target.open("xb") as dst:
                    shutil.copyfileobj(src, dst)
    verify_pack(folder, inventory)
    return folder, inventory


def copy_pack(source, target, inventory):
    if target.resolve() != target or any(target.iterdir()):
        raise RuntimeError("Server pack destination must be a fresh, unlinked run directory")
    for relative, sha256 in inventory.items():
        destination = target / safe_relative(relative)
        copy_checked(source / relative, destination, sha256, "sha256")
    verify_pack(target, inventory)


def prepare(pack="forge"):
    folder = install_root()
    installer = checked(ROOT / "work" / "downloads" / f"forge-{FORGE}-installer.jar",
                        INSTALLER_SHA256, "sha256")
    server = checked(ROOT / ".gradle-home" / "caches" / "forge_gradle" / "mcp_repo"
                     / "de" / "oceanlabs" / "mcp" / "mcp_config" / "1.12.2"
                     / "joined" / "downloadServer" / "server.jar", SERVER_SHA1)
    with zipfile.ZipFile(installer) as archive:
        version = json.loads(archive.read("version.json"))
        forge_entry = f"maven/net/minecraftforge/forge/{FORGE}/forge-{FORGE}.jar"
        forge_bytes = archive.read(forge_entry)
        forge_sha1 = hashlib.sha1(forge_bytes).hexdigest()
        libraries = []
        # Validate every source before creating any installation directories.
        for library in version["libraries"]:
            if library["name"].startswith("net.minecraftforge:forge:"):
                continue
            artifact = library["downloads"]["artifact"]
            libraries.append((library_source(artifact), artifact))
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / f"forge-{FORGE}.jar"
    if target.exists():
        checked(target, forge_sha1)
    else:
        target.write_bytes(forge_bytes)
        checked(target, forge_sha1)
    inventory = {target.name: forge_sha1, "minecraft_server.1.12.2.jar": SERVER_SHA1}
    copy_checked(server, folder / "minecraft_server.1.12.2.jar", SERVER_SHA1)
    for source, artifact in libraries:
        relative = "libraries/" + artifact["path"]
        copy_checked(source, folder / relative, artifact["sha1"])
        inventory[relative] = artifact["sha1"]
    manifest = {"forge": FORGE, "installerSha256": INSTALLER_SHA256, "files": inventory}
    (folder / "installation.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"PREPARED {folder} files={len(inventory)} downloaded=0 serverStarted=false")
    if pack == "rlcraft-2.9.3":
        pristine, inventory = rlcraft_pack(create=True)
        print(f"PACK_VERIFIED {pristine} files={len(inventory)} sha256={RLCRAFT_SHA256}")


def fixture_uuid(identifier):
    if not IDENTIFIER.fullmatch(identifier):
        raise ValueError("Fixture ID must contain 1-48 ASCII letters, digits, or hyphens")
    # Same raw-name MD5 convention as java.util.UUID.nameUUIDFromBytes.
    return uuid.UUID(bytes=hashlib.md5(("mrtx-multiplayer-" + identifier).encode("ascii")).digest(), version=3)


def signed_long(value):
    return value if value < 1 << 63 else value - (1 << 64)


def setup_commands(identifier):
    entity = fixture_uuid(identifier)
    most, least = signed_long(entity.int >> 64), signed_long(entity.int & ((1 << 64) - 1))
    return [
        "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
        "gamerule doMobSpawning false", "gamerule doFireTick false", "gamerule spawnRadius 0",
        "time set 6000", "weather clear",
        "fill -16 64 -16 16 64 20 minecraft:stonebrick",
        "setblock 3 65 3 minecraft:torch", "setworldspawn 0 65 -8",
        'summon minecraft:cow 0.5 65 4.5 {NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,'
        f'UUIDMost:{most}L,UUIDLeast:{least}L,CustomName:"mrtx-smoke-{identifier}"}}',
        f"say MRTXS_READY_{identifier}",
    ]


def properties(identifier, port, pack="forge", base=""):
    fixture_uuid(identifier)
    if not 1024 <= port <= 65535:
        raise ValueError("Loopback server port must be 1024 through 65535")
    values = dict(line.strip().split("=", 1) for line in base.splitlines()
                  if line.strip() and not line.lstrip().startswith("#") and "=" in line)
    overrides = [
        "server-ip=127.0.0.1", f"server-port={port}", "online-mode=true",
        "enable-rcon=false", "enable-query=false", "max-players=1", "view-distance=6",
        "gamemode=1", "force-gamemode=true", "difficulty=0", "spawn-protection=0",
        "spawn-animals=false", "spawn-monsters=false", "spawn-npcs=false", "pvp=false",
        "allow-flight=true", "allow-nether=false", "generate-structures=false", "level-type=FLAT",
        "generator-settings=3;minecraft:bedrock,2*minecraft:dirt,minecraft:grass;1;",
        "level-seed=7348932", "level-name=rlcraft-remix-smoke-multiplayer-" + identifier,
        "motd=RTX Remix authenticated localhost fixture " + identifier,
    ]
    values.update(line.split("=", 1) for line in overrides)
    if pack == "rlcraft-2.9.3":
        # Preserve the author's required server behavior inside the disposable flat fixture.
        values.update({"difficulty": "3", "enable-command-block": "true", "max-tick-time": "-1"})
    return "\n".join(f"{key}={value}" for key, value in values.items()) + "\n"


def run(args):
    folder = install_root()
    manifest = json.loads((folder / "installation.json").read_text(encoding="utf-8"))
    if manifest["forge"] != FORGE or manifest["installerSha256"] != INSTALLER_SHA256:
        raise RuntimeError("Unexpected dedicated server installation")
    for relative, sha1 in manifest["files"].items():
        source = (folder / relative).resolve()
        if not source.is_relative_to(folder):
            raise RuntimeError("Server inventory escapes its owned directory")
        checked(source, sha1)
    java = ROOT / ".tools" / "jdk8" / "jdk8u504-b01" / "bin" / "java.exe"
    if not java.is_file():
        raise RuntimeError("The pinned Java 8 runtime is not installed")
    fixture_uuid(args.fixture_id)
    if not args.accept_eula:
        raise RuntimeError("Run requires --accept-eula after reviewing https://www.minecraft.net/eula")
    heap = args.heap_mib if args.heap_mib is not None else (4096 if args.pack == "rlcraft-2.9.3" else 1536)
    if not 1024 <= heap <= 8192:
        raise ValueError("Heap must be 1024 through 8192 MiB")
    pristine, pack_files = rlcraft_pack() if args.pack == "rlcraft-2.9.3" else (None, {})
    # An existing run is never reused; this protects both saved worlds and readiness markers.
    run_dir = folder / "runs" / args.fixture_id
    if run_dir.resolve() != run_dir:
        raise RuntimeError("Server run directory must not be a link")
    run_dir.mkdir(parents=True, exist_ok=False)
    base = ""
    if pristine is not None:
        copy_pack(pristine, run_dir, pack_files)
        base = (run_dir / "server.properties").read_text(encoding="utf-8")
    (run_dir / "server.properties").write_text(properties(args.fixture_id, args.port, args.pack, base), encoding="ascii")
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="ascii")
    inventory = {"pack": args.pack, "archiveSha256": RLCRAFT_SHA256 if pristine is not None else None,
                 "files": pack_files, "fixtureOverrides": ["server.properties", "eula.txt"]}
    (run_dir / "pack-inventory.json").write_text(json.dumps(inventory, indent=2) + "\n", encoding="utf-8")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", args.port))
    process = None
    ready = False
    lines = queue.Queue(maxsize=64)
    deadline = time.monotonic() + args.seconds
    log_path = run_dir / "server-console.log"
    try:
        # The absolute Forge JAR resolves its manifest libraries beside the installation,
        # while the process working directory contains only this run's world/configuration.
        process = subprocess.Popen([str(java), "-Xms512m", f"-Xmx{heap}m", "-Dlog4j2.formatMsgNoLookups=true",
                                    "-jar", str(folder / f"forge-{FORGE}.jar"), "nogui"],
                                   cwd=run_dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)

        def read_lines():
            with log_path.open("w", encoding="utf-8") as log:
                for line in process.stdout:
                    log.write(line)
                    log.flush()
                    if "Done (" in line or f"[Server] MRTXS_READY_{args.fixture_id}" in line or "logged in with entity id" in line:
                        lines.put(line)
                lines.put(None)

        reader = threading.Thread(target=read_lines, name="forge-smoke-console", daemon=True)
        reader.start()

        def send(commands):
            if process.poll() is not None:
                raise RuntimeError("Owned Forge server exited before console command")
            process.stdin.write("\n".join(commands) + "\n")
            process.stdin.flush()

        print(f"STARTED pid={process.pid} fixture={args.fixture_id} port={args.port} pack={args.pack} heapMiB={heap}", flush=True)
        while time.monotonic() < deadline and not (run_dir / "stop.request").exists():
            try:
                line = lines.get(timeout=0.25)
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError(f"Owned Forge server exited; inspect {log_path}")
            if 'Done (' in line and 'For help' in line:
                send(setup_commands(args.fixture_id))
            if f"[Server] MRTXS_READY_{args.fixture_id}" in line:
                ready = True
                data = {"fixtureId": args.fixture_id, "port": args.port, "pid": process.pid,
                        "cowUuid": str(fixture_uuid(args.fixture_id)), "onlineMode": True, "pack": args.pack}
                (run_dir / "ready.json").write_text(json.dumps(data, indent=2) + "\n", encoding="ascii")
                print(f"READY fixture={args.fixture_id} port={args.port} onlineMode=true", flush=True)
            if "logged in with entity id" in line:
                send(["gamemode creative @a", "tp @a 0.5 65 -8.5 0 8"])
        if not ready:
            raise RuntimeError(f"Server did not become ready; inspect {log_path}")
    finally:
        if process is not None and process.poll() is None:
            try:
                process.stdin.write("stop\n")
                process.stdin.flush()
            except OSError:
                pass  # A server exiting concurrently has already closed its console.
            try:
                process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                process.kill()  # Only the child created and owned by this invocation.
                process.wait(timeout=10)
        (run_dir / "stopped.json").write_text(json.dumps({"ready": ready,
            "exitCode": None if process is None else process.returncode}) + "\n", encoding="ascii")
        print(f"STOPPED fixture={args.fixture_id}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    preparation = sub.add_parser("prepare")
    preparation.add_argument("--pack", choices=("forge", "rlcraft-2.9.3"), default="forge")
    launch = sub.add_parser("run")
    launch.add_argument("--fixture-id", required=True)
    launch.add_argument("--port", type=int, default=25575)
    launch.add_argument("--seconds", type=int, default=900, choices=range(60, 1801), metavar="60..1800")
    launch.add_argument("--accept-eula", action="store_true")
    launch.add_argument("--pack", choices=("forge", "rlcraft-2.9.3"), default="forge")
    launch.add_argument("--heap-mib", type=int, help="1024..8192; defaults to 1536 for Forge or 4096 for RLCraft")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.pack)
    else:
        run(args)


if __name__ == "__main__":
    main()

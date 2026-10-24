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


def environment_commands():
    return ["gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
            "gamerule doMobSpawning false", "gamerule doFireTick false", "gamerule spawnRadius 0",
            "gamerule sendCommandFeedback true", "gamerule logAdminCommands true",
            "time set 6000", "weather clear", "setworldspawn 0 65 -8"]


def setup_commands(identifier):
    entity = fixture_uuid(identifier)
    most, least = signed_long(entity.int >> 64), signed_long(entity.int & ((1 << 64) - 1))
    return [
        "fill -16 64 -16 16 64 20 minecraft:stonebrick",
        "setblock 3 65 3 minecraft:torch",
        'summon minecraft:cow 0.5 65 4.5 {Health:10.0f,NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,'
        f'UUIDMost:{most}L,UUIDLeast:{least}L,CustomName:"mrtx-smoke-{identifier}"}}',
    ]


def console_message(line):
    match = re.search(r"\[Server thread/INFO\] \[[^\]]*(?:DedicatedServer|MinecraftServer)\]: (.*)$", line.rstrip())
    return match[1] if match else None


def joined_player(line):
    match = re.search(r"\[Server thread/INFO\] \[[^\]]*PlayerList\]: "
                      r"([A-Za-z0-9_]{1,16})\[/127\.0\.0\.1:\d+\] logged in with entity id ", line)
    return match[1] if match else None


class FixtureSetup:
    """Fenced server-console protocol; readiness requires observed command results."""

    CHUNKS = tuple((x, 0, z) for x in (-16, 0, 16) for z in (-16, 0, 16))
    BLOCKS = ((-16, 64, -16, "stonebrick"), (-16, 64, 20, "stonebrick"),
              (16, 64, -16, "stonebrick"), (16, 64, 20, "stonebrick"),
              (0, 64, 0, "stonebrick"), (3, 65, 3, "torch"))
    MAX_ATTEMPTS = 10
    TIMEOUT = 90
    ACK_TIMEOUT = 10

    def __init__(self, identifier):
        self.identifier = identifier
        self.entity = fixture_uuid(identifier)
        self.accepting = self.ready = False
        self.phase = "boot"
        self.joins = self.attempt = self.retirement_checks = self.sequence = 0
        self.player = None
        self.deadline = self.next_probe = self.ack_deadline = 0
        self.token = None
        self.inside = False
        self.expected = set()
        self.observed = set()
        self.last_missing = []

    def accept(self):
        if self.accepting:
            return []
        self.accepting = True
        self.phase = "accepting"
        return environment_commands()

    def join(self, name, now):
        if not self.accepting or not re.fullmatch(r"[A-Za-z0-9_]{1,16}", name):
            raise RuntimeError("Fixture login requires an authenticated player on the accepting server")
        self.joins += 1
        self.player = name
        self.attempt = 0
        self.retirement_checks = 0
        self.ready = False
        self.deadline = now + self.TIMEOUT
        return self._batch("teleport", [f"gamemode creative {name}", f"tp {name} 0.5 65 -8.5 0 8"],
                           {f"Teleported {name} to "}, now)

    @staticmethod
    def block_proof(x, y, z):
        return f"Successfully found the block at {x}, {y}, {z}"

    def _batch(self, phase, commands, expected, now):
        self.phase = phase
        self.sequence += 1
        self.token = f"MRTXS_{self.identifier}_{self.joins}_{self.attempt}_{self.sequence}_{phase}"
        self.inside = False
        self.expected, self.observed = set(expected), set()
        self.ack_deadline = now + self.ACK_TIMEOUT
        return [f"say {self.token}_BEGIN", *commands, f"say {self.token}_END"]

    def _delay(self, now):
        self.phase = "wait-chunks"
        self.token = None
        self.next_probe = now + 2
        return []

    def tick(self, now):
        if not self.joins or self.ready:
            return []
        if now >= self.deadline:
            raise RuntimeError(f"Fixture setup timed out in {self.phase}; missing={self.last_missing}")
        if self.token and now >= self.ack_deadline:
            raise RuntimeError(f"Fixture console acknowledgment timed out in {self.phase}")
        if self.phase == "wait-retire" and now >= self.next_probe:
            if self.retirement_checks >= self.MAX_ATTEMPTS:
                raise RuntimeError("Owned fixture cow did not retire after 10 checks")
            self.retirement_checks += 1
            return self._batch("retire-cow", [f"testfor {self.entity}"],
                               {f"Entity '{self.entity}' cannot be found"}, now)
        if self.phase != "wait-chunks" or now < self.next_probe:
            return []
        if self.attempt >= self.MAX_ATTEMPTS:
            raise RuntimeError(f"Fixture setup failed after {self.attempt} attempts; missing={self.last_missing}")
        self.attempt += 1
        commands = [f"testforblock {x} {y} {z} minecraft:bedrock" for x, y, z in self.CHUNKS]
        return self._batch("chunks", commands, {self.block_proof(*pos) for pos in self.CHUNKS}, now)

    def observe(self, line, now):
        message = console_message(line)
        if not self.token or message is None:
            return []
        if message == f"[Server] {self.token}_BEGIN":
            self.inside = True
            return []
        if not self.inside:
            return []
        if message != f"[Server] {self.token}_END":
            for proof in self.expected:
                if message == proof or (proof.startswith("Teleported ") and message.startswith(proof)):
                    self.observed.add(proof)
            return []
        self.last_missing = sorted(self.expected - self.observed)
        if self.last_missing:
            if self.phase == "teleport":
                raise RuntimeError("Player teleport failed before fixture setup")
            if self.phase == "retire-cow":
                return self._wait_retirement(now)
            return self._delay(now)
        if self.phase == "teleport":
            return self._delay(now)
        if self.phase == "chunks":
            # Living entities remain addressable during their 20-tick death animation.
            # A wall-clock delay cannot prove retirement on a slow server.
            self.retirement_checks = 0
            return self._batch("remove-cow", [f"kill {self.entity}"], set(), now)
        if self.phase == "remove-cow":
            return self._wait_retirement(now)
        if self.phase == "retire-cow":
            # Creative players can fall before the elevated platform exists. Restore
            # the camera pose after construction, then verify the complete scene.
            commands = setup_commands(self.identifier) + [f"tp {self.player} 0.5 65 -8.5 0 8"]
            return self._batch("place", commands,
                               {"Object successfully summoned", f"Teleported {self.player} to "}, now)
        if self.phase == "place":
            commands = [f"testforblock {x} {y} {z} minecraft:{block}" for x, y, z, block in self.BLOCKS]
            most = signed_long(self.entity.int >> 64)
            least = signed_long(self.entity.int & ((1 << 64) - 1))
            commands.append(f'testfor {self.entity} {{UUIDMost:{most}L,UUIDLeast:{least}L,'
                            f'Health:10.0f,NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,CustomName:"mrtx-smoke-{self.identifier}"}}')
            expected = {self.block_proof(x, y, z) for x, y, z, _ in self.BLOCKS}
            expected.add(f"Found mrtx-smoke-{self.identifier}")
            return self._batch("verify", commands, expected, now)
        if self.phase == "verify":
            self.ready = True
            self.phase = "ready"
            self.token = None
            return []
        raise RuntimeError("Unexpected fixture console phase")

    def _wait_retirement(self, now):
        self.phase = "wait-retire"
        self.token = None
        self.next_probe = now + 1
        return []


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
        # WorldServer kills existing animals each tick when spawn-animals is false,
        # including commanded cows. The environment gamerule prevents natural spawning.
        "spawn-animals=true", "spawn-monsters=false", "spawn-npcs=false", "pvp=false",
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
    fixture = FixtureSetup(args.fixture_id)
    lines = queue.Queue(maxsize=256)
    overflow = threading.Event()
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
                    if not overflow.is_set() and (console_message(line) is not None or joined_player(line)):
                        try:
                            lines.put_nowait(line)
                        except queue.Full:
                            overflow.set()  # Drain stdout regardless, so shutdown cannot deadlock on a full queue.
                try:
                    lines.put_nowait(None)
                except queue.Full:
                    overflow.set()

        reader = threading.Thread(target=read_lines, name="forge-smoke-console", daemon=True)
        reader.start()

        def send(commands):
            if not commands:
                return
            if process.poll() is not None:
                raise RuntimeError("Owned Forge server exited before console command")
            process.stdin.write("\n".join(commands) + "\n")
            process.stdin.flush()

        def publish_readiness():
            data = {"fixtureId": args.fixture_id, "port": args.port, "pid": process.pid,
                    "cowUuid": str(fixture.entity), "onlineMode": True, "pack": args.pack,
                    "status": "fixture-ready" if fixture.ready else "accepting",
                    "fixtureReady": fixture.ready, "joins": fixture.joins}
            temporary = run_dir / "ready.json.tmp"
            temporary.write_text(json.dumps(data, indent=2) + "\n", encoding="ascii")
            temporary.replace(run_dir / "ready.json")
            verified = run_dir / "fixture-ready.json"
            if fixture.ready:
                verified.write_text(json.dumps(data, indent=2) + "\n", encoding="ascii")
            else:
                verified.unlink(missing_ok=True)

        print(f"STARTED pid={process.pid} fixture={args.fixture_id} port={args.port} pack={args.pack} heapMiB={heap}", flush=True)
        while time.monotonic() < deadline and not (run_dir / "stop.request").exists():
            if overflow.is_set():
                raise RuntimeError("Server console event queue overflowed; readiness cannot be verified")
            send(fixture.tick(time.monotonic()))
            try:
                line = lines.get(timeout=0.25)
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError(f"Owned Forge server exited; inspect {log_path}")
            message = console_message(line)
            if message and message.startswith('Done (') and 'For help' in message and not fixture.accepting:
                send(fixture.accept())
                publish_readiness()
                print(f"ACCEPTING fixture={args.fixture_id} port={args.port} onlineMode=true fixtureReady=false", flush=True)
            name = joined_player(line)
            if name:
                send(fixture.join(name, time.monotonic()))
                publish_readiness()
                print(f"FIXTURE_SETUP fixture={args.fixture_id} join={fixture.joins} phase=teleport", flush=True)
            previously_ready = fixture.ready
            send(fixture.observe(line, time.monotonic()))
            if fixture.ready and not previously_ready:
                publish_readiness()
                print(f"FIXTURE_READY fixture={args.fixture_id} join={fixture.joins} attempt={fixture.attempt} verified=true", flush=True)
        if not fixture.accepting:
            raise RuntimeError(f"Server did not become accepting; inspect {log_path}")
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
        (run_dir / "stopped.json").write_text(json.dumps({"accepting": fixture.accepting, "fixtureReady": fixture.ready,
            "joins": fixture.joins,
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

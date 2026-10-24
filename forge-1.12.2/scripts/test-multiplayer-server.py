# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Ownership and console-boundary tests; these never launch a server."""

import importlib.util
import io
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock
import zipfile


SPEC = importlib.util.spec_from_file_location("multiplayer_server", Path(__file__).with_name("multiplayer-server.py"))
server = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server)


class MultiplayerServerTest(unittest.TestCase):
    def test_identifiers_cannot_escape_world_directory_or_console_command(self):
        for identifier in ("../escape", "x\nsay injected", "x\"}", "", "a" * 49, "x/y", "x\\y", "x:25565"):
            with self.subTest(identifier=identifier), self.assertRaises(ValueError):
                server.setup_commands(identifier)
        self.assertEqual(len(server.setup_commands("20261024-test-1")), 12)

    def test_server_always_authenticates_and_only_binds_loopback(self):
        properties = dict(line.split("=", 1) for line in server.properties("test-1", 25575).splitlines())
        self.assertEqual(properties["server-ip"], "127.0.0.1")
        self.assertEqual(properties["online-mode"], "true")
        self.assertEqual(properties["enable-rcon"], "false")
        self.assertEqual(properties["enable-query"], "false")
        self.assertEqual(properties["level-name"], "rlcraft-remix-smoke-multiplayer-test-1")
        for port in (0, 1023, 65536):
            with self.assertRaises(ValueError):
                server.properties("test", port)

    def test_rlcraft_preserves_required_settings_and_sanitizes_network_bindings(self):
        original = "online-mode=false\nserver-ip=0.0.0.0\nenable-rcon=true\nlevel-type=DEFAULT\ncustom-key=kept\n"
        values = dict(line.split("=", 1) for line in server.properties("test", 25575, "rlcraft-2.9.3", original).splitlines())
        self.assertEqual(values["online-mode"], "true")
        self.assertEqual(values["server-ip"], "127.0.0.1")
        self.assertEqual(values["enable-rcon"], "false")
        self.assertEqual(values["difficulty"], "3")
        self.assertEqual(values["enable-command-block"], "true")
        self.assertEqual(values["max-tick-time"], "-1")
        self.assertEqual(values["allow-flight"], "true")
        self.assertEqual(values["custom-key"], "kept")
        self.assertEqual(values["level-type"], "FLAT")

    def test_archive_rejects_windows_paths_traversal_aliases_and_links_before_copy(self):
        for name in ("../outside", "C:/outside", "/outside", "config\\outside", "config/NUL.txt", "config/end.", "config/./x"):
            with self.subTest(name=name), self.assertRaises(RuntimeError):
                server.safe_relative(name)
        for mode in ("duplicate", "symlink"):
            data = io.BytesIO()
            with zipfile.ZipFile(data, "w") as archive:
                archive.writestr("config/a.txt", b"a")
                if mode == "duplicate":
                    archive.writestr("CONFIG/A.txt", b"b")
                else:
                    entry = zipfile.ZipInfo("linked")
                    entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                    archive.writestr(entry, b"../outside")
            with zipfile.ZipFile(data) as archive, self.assertRaises(RuntimeError):
                server.pack_inventory(archive)

    def test_pack_copy_checks_complete_inventory_and_never_changes_pristine_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            source, target = root / "source", root / "target"
            (source / "config").mkdir(parents=True)
            target.mkdir()
            (source / "config/a.txt").write_bytes(b"original")
            inventory = {"config/a.txt": server.digest(source / "config/a.txt", "sha256")}
            server.copy_pack(source, target, inventory)
            self.assertEqual((target / "config/a.txt").read_bytes(), b"original")
            (target / "config/a.txt").write_bytes(b"mutated run")
            with self.assertRaises(RuntimeError):
                server.verify_pack(target, inventory)
            self.assertEqual((source / "config/a.txt").read_bytes(), b"original")
            (source / "extra.jar").write_bytes(b"extra")
            with self.assertRaises(RuntimeError):
                server.verify_pack(source, inventory)

    def test_uuid_nbt_round_trips_signed_64_bit_components(self):
        entity = server.fixture_uuid("test-1")
        most, least = server.signed_long(entity.int >> 64), server.signed_long(entity.int & ((1 << 64) - 1))
        self.assertEqual(((most & ((1 << 64) - 1)) << 64) | (least & ((1 << 64) - 1)), entity.int)
        command = next(line for line in server.setup_commands("test-1") if line.startswith("summon "))
        self.assertIn(f"UUIDMost:{most}L,UUIDLeast:{least}L", command)
        self.assertEqual(entity.version, 3)

    def test_existing_mismatched_artifact_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / "source", Path(directory) / "target"
            source.write_bytes(b"pinned")
            target.write_bytes(b"other")
            with self.assertRaises(RuntimeError):
                server.copy_checked(source, target, server.digest(source))
            self.assertEqual(target.read_bytes(), b"other")

    def test_untrusted_inventory_paths_cannot_select_an_external_library(self):
        for path in ("../../outside.jar", "/outside.jar"):
            with self.subTest(path=path), self.assertRaises(RuntimeError):
                server.library_source({"path": path, "sha1": "unused"})

    def test_run_requires_explicit_eula_before_creating_run_or_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "installation.json").write_text('{"forge":"' + server.FORGE
                + '","installerSha256":"' + server.INSTALLER_SHA256 + '","files":{}}')
            java = root / ".tools/jdk8/jdk8u504-b01/bin/java.exe"
            java.parent.mkdir(parents=True)
            java.write_bytes(b"unused")
            args = mock.Mock(fixture_id="test", accept_eula=False)
            with mock.patch.object(server, "ROOT", root), mock.patch.object(server, "install_root", return_value=root), \
                    mock.patch.object(server.subprocess, "Popen") as process:
                with self.assertRaisesRegex(RuntimeError, "accept-eula"):
                    server.run(args)
                process.assert_not_called()
            self.assertFalse((root / "runs").exists())


if __name__ == "__main__":
    unittest.main()

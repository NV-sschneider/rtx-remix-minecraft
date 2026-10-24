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
        self.assertTrue(server.setup_commands("20261024-test-1")[0].startswith("fill "))
        self.assertFalse(any("READY" in command for command in server.setup_commands("20261024-test-1")))

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

    def test_owned_cow_survives_entity_ticks_while_natural_spawning_remains_disabled(self):
        # Pinned WorldServer.updateEntityWithOptionalForce kills EntityAnimal when
        # getCanSpawnAnimals is false; doMobSpawning gates the spawner separately.
        for pack in ("forge", "rlcraft-2.9.3"):
            with self.subTest(pack=pack):
                values = dict(line.split("=", 1) for line in server.properties(
                    "test", 25575, pack, "spawn-animals=false\n").splitlines())
                self.assertEqual(values["spawn-animals"], "true")
                self.assertEqual(values["spawn-monsters"], "false")
                fixture = server.FixtureSetup("test")
                self.assertIn("gamerule doMobSpawning false", fixture.accept())
                self.assertTrue(any(command.startswith("summon minecraft:cow ")
                                    for command in server.setup_commands("test")))
                self.assertFalse(any(command == "gamerule doMobSpawning true"
                                     for command in server.setup_commands("test")))

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

    @staticmethod
    def console(message):
        return "[01:04:29] [Server thread/INFO] [minecraft/DedicatedServer]: " + message

    def acknowledge(self, fixture, now, proofs=None):
        token = fixture.token
        fixture.observe(self.console(f"[Server] {token}_BEGIN"), now)
        for proof in fixture.expected if proofs is None else proofs:
            message = proof + "0.50, 65.00, -8.50" if proof.startswith("Teleported ") else proof
            fixture.observe(self.console(message), now)
        return fixture.observe(self.console(f"[Server] {token}_END"), now)

    def loaded_fixture(self):
        fixture = server.FixtureSetup("test-1")
        commands = fixture.accept()
        self.assertFalse(any(command.startswith(("fill ", "summon ")) for command in commands))
        self.assertTrue(fixture.accepting)
        self.assertFalse(fixture.ready)
        commands = fixture.join("Player_1", 0)
        self.assertIn("tp Player_1 0.5 65 -8.5 0 8", commands)
        self.acknowledge(fixture, 0)
        self.assertEqual(fixture.tick(1.9), [])
        commands = fixture.tick(2)
        self.assertEqual(sum(command.startswith("testforblock ") for command in commands), 9)
        commands = self.acknowledge(fixture, 2)
        self.assertEqual(commands[1:-1], ["kill " + str(fixture.entity)])
        self.assertFalse(any(command.startswith("summon ") for command in commands))
        self.acknowledge(fixture, 2)
        self.assertEqual(fixture.tick(2.9), [])
        commands = fixture.tick(3)
        self.assertEqual(commands[1:-1], ["testfor " + str(fixture.entity)])
        commands = self.acknowledge(fixture, 3)
        self.assertEqual(sum(command.startswith("summon ") for command in commands), 1)
        self.assertLess(next(i for i, cmd in enumerate(commands) if cmd.startswith("fill ")),
                        commands.index("tp Player_1 0.5 65 -8.5 0 8"))
        self.assertFalse(fixture.ready)
        return fixture

    def test_scene_is_created_only_after_login_chunks_and_entity_retirement_then_verified(self):
        fixture = self.loaded_fixture()
        commands = self.acknowledge(fixture, 3)
        self.assertTrue(any(command.startswith("testfor " + str(fixture.entity)) for command in commands))
        self.assertTrue(any("Health:10.0f" in command for command in commands))
        self.assertFalse(fixture.ready)
        self.acknowledge(fixture, 3)
        self.assertTrue(fixture.ready)
        self.assertEqual(fixture.phase, "ready")
        self.assertEqual(fixture.tick(1000), [])
        self.assertEqual(fixture.accept(), [])
        commands = fixture.join("Player_1", 10)
        self.assertFalse(fixture.ready)
        self.assertEqual(fixture.joins, 2)
        self.assertFalse(any(command.startswith("summon ") for command in commands))

    def test_slow_death_animation_requires_exact_absence_with_unique_bounded_probes(self):
        fixture = server.FixtureSetup("test-1")
        fixture.accept()
        fixture.join("Player", 0)
        self.acknowledge(fixture, 0)
        fixture.tick(2)
        self.acknowledge(fixture, 2)
        self.acknowledge(fixture, 2)
        previous_token = None
        for check in range(1, fixture.MAX_ATTEMPTS + 1):
            commands = fixture.tick(2 + check)
            self.assertEqual(commands[1:-1], ["testfor " + str(fixture.entity)])
            self.assertNotEqual(fixture.token, previous_token)
            previous_token = fixture.token
            # The dead entity can still satisfy the old UUID and custom name query.
            self.assertEqual(self.acknowledge(fixture, 2 + check, {"Found mrtx-smoke-test-1"}), [])
            self.assertFalse(fixture.ready)
            self.assertEqual(fixture.phase, "wait-retire")
        with self.assertRaisesRegex(RuntimeError, "did not retire after 10 checks"):
            fixture.tick(13)

    def test_retirement_rejects_wrong_uuid_and_stale_fence_then_places_after_exact_absence(self):
        fixture = server.FixtureSetup("test-1")
        fixture.accept()
        fixture.join("Player", 0)
        self.acknowledge(fixture, 0)
        fixture.tick(2)
        self.acknowledge(fixture, 2)
        self.acknowledge(fixture, 2)
        fixture.tick(3)
        old_token = fixture.token
        self.acknowledge(fixture, 3, {"Entity '00000000-0000-0000-0000-000000000000' cannot be found"})
        self.assertEqual(fixture.phase, "wait-retire")
        fixture.tick(4)
        fixture.observe(self.console(f"[Server] {old_token}_BEGIN"), 4)
        fixture.observe(self.console(f"Entity '{fixture.entity}' cannot be found"), 4)
        fixture.observe(self.console(f"[Server] {old_token}_END"), 4)
        self.assertFalse(fixture.observed)
        commands = self.acknowledge(fixture, 4)
        self.assertEqual(sum(command.startswith("summon ") for command in commands), 1)
        self.assertTrue(any("Health:10.0f" in command for command in commands))
        self.assertEqual(fixture.phase, "place")

    def test_failed_summon_cannot_reuse_an_old_cow_proof(self):
        fixture = self.loaded_fixture()
        self.acknowledge(fixture, 3, {f"Teleported {fixture.player} to "})
        self.assertEqual(fixture.phase, "wait-chunks")
        self.assertFalse(fixture.ready)
        self.assertEqual(fixture.last_missing, ["Object successfully summoned"])

    def test_failed_fill_or_summon_cannot_become_ready_via_sentinel_or_partial_proofs(self):
        fixture = self.loaded_fixture()
        fixture.observe(self.console("Cannot place blocks outside of the world"), 3)
        self.acknowledge(fixture, 3)
        fixture.observe(self.console("[Server] MRTXS_READY_test-1"), 3)
        self.assertFalse(fixture.ready)
        self.acknowledge(fixture, 3, {"Found mrtx-smoke-test-1"})
        self.assertFalse(fixture.ready)
        self.assertEqual(fixture.phase, "wait-chunks")
        self.assertIn("Successfully found the block at 0, 64, 0", fixture.last_missing)
        self.assertEqual(fixture.tick(4.9), [])
        self.assertTrue(fixture.tick(5))
        self.assertEqual(fixture.attempt, 2)

    def test_unloaded_chunks_retry_without_placing_anything_and_are_bounded(self):
        fixture = server.FixtureSetup("test-1")
        fixture.accept()
        fixture.join("Player", 0)
        self.acknowledge(fixture, 0)
        for attempt in range(1, fixture.MAX_ATTEMPTS + 1):
            commands = fixture.tick(attempt * 2)
            self.assertFalse(any(command.startswith(("fill ", "summon ")) for command in commands))
            self.assertEqual(self.acknowledge(fixture, attempt * 2, set()), [])
        with self.assertRaisesRegex(RuntimeError, "after 10 attempts"):
            fixture.tick(22)

    def test_teleport_requires_proof_and_command_fences_have_a_deadline(self):
        fixture = server.FixtureSetup("test-1")
        fixture.accept()
        fixture.join("Player", 0)
        with self.assertRaisesRegex(RuntimeError, "teleport failed"):
            self.acknowledge(fixture, 0, set())
        fixture.join("Player", 1)
        with self.assertRaisesRegex(RuntimeError, "acknowledgment timed out"):
            fixture.tick(11)
        with self.assertRaisesRegex(RuntimeError, "setup timed out"):
            fixture.tick(100)

    def test_success_outside_current_fence_or_from_player_chat_is_not_proof(self):
        fixture = self.loaded_fixture()
        self.acknowledge(fixture, 3)
        token = fixture.token
        for proof in fixture.expected:
            fixture.observe(self.console(proof), 3)  # Before BEGIN.
        fixture.observe(self.console(f"[Server] {token}_BEGIN"), 3)
        for proof in fixture.expected:
            fixture.observe("[Server thread/INFO] [minecraft/DedicatedServer]: <Player> " + proof, 3)
        fixture.observe(self.console(f"[Server] {token}_END"), 3)
        self.assertFalse(fixture.ready)
        self.assertEqual(len(fixture.last_missing), 7)

    def test_login_parser_accepts_only_the_server_loopback_playerlist_event(self):
        prefix = "[01:00:00] [Server thread/INFO] [minecraft/PlayerList]: "
        self.assertEqual(server.joined_player(prefix + "Player_1[/127.0.0.1:4123] logged in with entity id 9 at (0, 64, 0)"), "Player_1")
        for body in ("Player_1[/10.0.0.2:4123] logged in with entity id 9",
                     "bad/name[/127.0.0.1:4123] logged in with entity id 9"):
            self.assertIsNone(server.joined_player(prefix + body))
        self.assertIsNone(server.joined_player(prefix.replace("PlayerList", "DedicatedServer") +
                                              "Player_1[/127.0.0.1:4123] logged in with entity id 9"))

    def test_logout_parser_requires_the_exact_authenticated_server_event(self):
        prefix = "[01:48:46] [Server thread/INFO] [minecraft/"
        for logger, body in (("NetHandlerPlayServer", "Player_1 lost connection: Disconnected"),
                             ("DedicatedServer", "Player_1 left the game"),
                             ("MinecraftServer", "Player_1 left the game")):
            with self.subTest(logger=logger):
                self.assertEqual(server.left_player(prefix + logger + "]: " + body + "\r\n"), "Player_1")
        for logger, body in (("NetHandlerLoginServer", "Player_1 lost connection: Disconnected"),
                             ("PlayerList", "Player_1 left the game"),
                             ("DedicatedServer", "<Player_2> Player_1 left the game"),
                             ("DedicatedServer", "[Server] Player_1 left the game"),
                             ("DedicatedServer", "Player_1 left the game extra"),
                             ("NetHandlerPlayServer", "bad/name lost connection: Disconnected")):
            with self.subTest(logger=logger, body=body):
                self.assertIsNone(server.left_player(prefix + logger + "]: " + body))
        forged = prefix + "DedicatedServer]: <Player_2> " + prefix + "NetHandlerPlayServer]: Player_1 lost connection: Disconnected"
        self.assertIsNone(server.left_player(forged))
        self.assertIsNone(server.left_player((prefix + "NetHandlerPlayServer]: Player_1 lost connection: Disconnected")
                                            .replace("Server thread", "Netty Server IO #1")))

    def test_logout_cancels_inflight_fences_and_all_deadlines_until_a_fresh_join(self):
        fixture = self.loaded_fixture()
        self.acknowledge(fixture, 3)  # Verification is in flight when the client exits.
        old_token, old_proofs = fixture.token, set(fixture.expected)
        fixture.observe(self.console(f"[Server] {old_token}_BEGIN"), 3)
        departure = "[01:48:46] [Server thread/INFO] [minecraft/NetHandlerPlayServer]: Player_1 lost connection: Disconnected"
        self.assertEqual(fixture.observe(departure, 4), [])
        self.assertEqual(fixture.phase, "accepting"); self.assertIsNone(fixture.player)
        self.assertFalse(fixture.ready); self.assertFalse(fixture.inside); self.assertIsNone(fixture.token)
        self.assertFalse(fixture.expected); self.assertFalse(fixture.observed)
        self.assertEqual((fixture.deadline, fixture.next_probe, fixture.ack_deadline), (0, 0, 0))
        for proof in old_proofs:
            self.assertEqual(fixture.observe(self.console(proof), 1000), [])
        self.assertEqual(fixture.observe(self.console(f"[Server] {old_token}_END"), 1000), [])
        self.assertEqual(fixture.tick(1000), []); self.assertFalse(fixture.ready)
        commands = fixture.join("Player_1", 1001)
        self.assertEqual(fixture.joins, 2); self.assertEqual(fixture.deadline, 1091)
        self.assertEqual(fixture.attempt, 0); self.assertNotEqual(fixture.token, old_token)
        self.assertFalse(any(command.startswith(("fill ", "summon ", "kill ")) for command in commands))
        fixture.observe(self.console(f"[Server] {old_token}_BEGIN"), 1001)
        for proof in old_proofs:
            fixture.observe(self.console(proof), 1001)
        fixture.observe(self.console(f"[Server] {old_token}_END"), 1001)
        self.assertFalse(fixture.observed); self.assertFalse(fixture.ready)
        self.acknowledge(fixture, 1001)
        self.assertEqual(fixture.phase, "wait-chunks")
        self.assertTrue(fixture.tick(1003))

    def test_logout_only_cancels_the_owned_player_and_revokes_previously_ready_state(self):
        fixture = self.loaded_fixture()
        self.acknowledge(fixture, 3); self.acknowledge(fixture, 3)
        self.assertTrue(fixture.ready)
        self.assertFalse(fixture.leave("SomeoneElse")); self.assertTrue(fixture.ready)
        fixture.observe(self.console("SomeoneElse left the game"), 4)
        self.assertTrue(fixture.ready)
        fixture.observe(self.console("Player_1 left the game"), 4)
        self.assertFalse(fixture.ready); self.assertEqual(fixture.tick(1000), [])
        self.assertFalse(fixture.leave("Player_1")); self.assertEqual(fixture.joins, 1)

    def test_logout_cancels_scheduled_chunk_and_retirement_retries(self):
        for phase in ("wait-chunks", "wait-retire"):
            with self.subTest(phase=phase):
                fixture = server.FixtureSetup("test-1"); fixture.accept(); fixture.join("Player_1", 0)
                self.acknowledge(fixture, 0)
                if phase == "wait-retire":
                    fixture.tick(2); self.acknowledge(fixture, 2); self.acknowledge(fixture, 2)
                self.assertEqual(fixture.phase, phase)
                self.assertTrue(fixture.leave("Player_1"))
                self.assertEqual(fixture.tick(1000), []); self.assertFalse(fixture.ready)


if __name__ == "__main__":
    unittest.main()

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class FixtureWindowCaptureTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void requiresTheExactRunAndCheckpointWithoutWritingOrRepeatedlyLogging() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("capture-ack.txt");
        FixtureWindowCapture capture = new FixtureWindowCapture(file.toString());
        List<String> events = new ArrayList<>();
        BiConsumer<String, String> log = (event, status) -> events.add(event + " " + status);
        assertTrue(capture.configured());
        assertFalse(capture.await("run-a", "STARTUP_ENABLED", "frames=180", log));
        assertFalse(Files.exists(file));
        assertFalse(capture.await("run-a", "STARTUP_ENABLED", "frames=181", log));
        assertEquals(1, events.size());
        assertEquals("WINDOW_CAPTURE_READY checkpoint=STARTUP_ENABLED token=run-a:STARTUP_ENABLED frames=180", events.get(0));
        write(file, "run-b:STARTUP_ENABLED");
        assertFalse(capture.await("run-a", "STARTUP_ENABLED", "", log));
        write(file, "run-a:FIRST_ENABLED");
        assertFalse(capture.await("run-a", "STARTUP_ENABLED", "", log));
        write(file, "run-a:STARTUP_ENABLED\r\n");
        assertTrue(capture.await("run-a", "STARTUP_ENABLED", "", log));
        assertTrue(capture.await("run-a", "STARTUP_ENABLED", "", log));
        assertEquals(2, events.size());
        assertFalse(capture.await("run-a", "FIRST_ENABLED", "", log));
        assertEquals(3, events.size());
        write(file, "run-a:FIRST_ENABLED");
        assertTrue(capture.await("run-a", "FIRST_ENABLED", "", log));
        assertEquals(4, events.size());
    }

    @Test public void optionalCaptureDoesNotBlockAndInvalidPathsCannotAcknowledge() throws Exception {
        FixtureWindowCapture optional = new FixtureWindowCapture("");
        assertFalse(optional.configured());
        assertTrue(optional.await("run", "FIRST_ENABLED", "", (event, status) -> { }));
        assertThrows(IllegalArgumentException.class, () -> new FixtureWindowCapture("relative.txt"));
        FixtureWindowCapture directory = new FixtureWindowCapture(temporary.getRoot().toString());
        assertThrows(IllegalStateException.class, () -> directory.await("run", "FIRST_ENABLED", "", (event, status) -> { }));
        Path file = temporary.newFile("oversized.txt").toPath(); Files.write(file, new byte[513]);
        FixtureWindowCapture oversized = new FixtureWindowCapture(file.toString());
        assertThrows(IllegalStateException.class, () -> oversized.await("run", "FIRST_ENABLED", "", (event, status) -> { }));
    }

    private static void write(Path file, String value) throws Exception { Files.write(file, value.getBytes(StandardCharsets.UTF_8)); }
}

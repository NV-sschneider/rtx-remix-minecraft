/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class VisibilitySettingsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void absentPreferenceDoesNotCreateFilesOrOverrideNativeConfiguration() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("config").resolve(VisibilitySettings.FILE_NAME);
        VisibilitySettings settings = new VisibilitySettings(file);
        Map<String, String> writes = new LinkedHashMap<>(); settings.apply(writes::put);
        assertFalse(settings.configured()); assertEquals(0, settings.revision());
        assertTrue(writes.isEmpty()); assertFalse(Files.exists(file.getParent()));
    }

    @Test public void explicitPreferenceSurvivesRestartAndResetWithoutTouchingNativeFile() throws Exception {
        Path folder = temporary.getRoot().toPath(), file = folder.resolve(VisibilitySettings.FILE_NAME);
        Path nativeFile = folder.resolve("rtx.conf");
        byte[] nativeBytes = "rtx.localtonemap.shadows = 4\nrtx.localtonemap.exposure = 0.5\n".getBytes(StandardCharsets.UTF_8);
        Files.write(nativeFile, nativeBytes);
        VisibilitySettings settings = new VisibilitySettings(file); settings.setShadows(2.5f);
        VisibilitySettings loaded = new VisibilitySettings(file);
        Map<String, String> writes = new LinkedHashMap<>(); loaded.apply(writes::put);
        assertEquals(2, writes.size()); assertEquals("1", writes.get(VisibilitySettings.MODE_KEY));
        assertEquals("2.5", writes.get(VisibilitySettings.SHADOWS_KEY));
        loaded.reset(); assertEquals(2, new VisibilitySettings(file).shadows(), 0);
        assertArrayEquals(nativeBytes, Files.readAllBytes(nativeFile));
    }

    @Test public void invalidValuesCannotChangeEitherSavedOrLivePreference() throws Exception {
        Path file = temporary.getRoot().toPath().resolve(VisibilitySettings.FILE_NAME);
        VisibilitySettings settings = new VisibilitySettings(file); settings.setShadows(2.5f);
        byte[] before = Files.readAllBytes(file); long revision = settings.revision();
        for (String value : new String[] {"NaN", "Infinity", "-0.1", "5.1", "bright"}) {
            try { settings.setShadows(VisibilitySettings.parseShadows(value)); fail(value); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("0 to 5")); }
        }
        assertArrayEquals(before, Files.readAllBytes(file)); assertEquals(2.5f, settings.shadows(), 0);
        assertEquals(revision, settings.revision());
        assertEquals(0, VisibilitySettings.parseShadows("0"), 0); assertEquals(5, VisibilitySettings.parseShadows("5"), 0);
    }

    @Test public void failedSaveLeavesPendingRuntimeRevisionUnchanged() throws Exception {
        Path parent = temporary.getRoot().toPath().resolve("not-a-directory"); Files.write(parent, new byte[] {1});
        VisibilitySettings settings = new VisibilitySettings(parent.resolve(VisibilitySettings.FILE_NAME));
        try { settings.setShadows(3); fail(); } catch (java.io.IOException expected) { }
        assertFalse(settings.configured()); assertEquals(0, settings.revision());
    }
}

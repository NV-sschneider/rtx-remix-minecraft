/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TerrainCloseTest {
    @Test public void failedPagesKeepTexturesAliveAndStillReleaseIndependentLights() {
        TerrainClose lifecycle = new TerrainClose();
        List<String> calls = new ArrayList<String>();
        Runnable detach = () -> calls.add("detach");
        Runnable textures = () -> calls.add("textures");
        Runnable lights = () -> calls.add("lights");
        try {
            lifecycle.close(detach, () -> { calls.add("pages-fail"); throw new IllegalStateException("busy"); }, textures, lights);
            fail("Expected failed page release");
        } catch (IllegalStateException expected) { }
        assertEquals(Arrays.asList("detach", "pages-fail", "lights"), calls);
        lifecycle.close(detach, () -> calls.add("pages-ok"), textures, lights);
        assertEquals(Arrays.asList("detach", "pages-fail", "lights", "pages-ok", "textures", "lights"), calls);
        int completedCalls = calls.size();
        lifecycle.close(detach, () -> fail("Completed close must be idle"), textures, lights);
        assertEquals(completedCalls, calls.size());
    }

    @Test public void failedTextureAndLightReleaseRemainRetryableWithoutDetachingAgain() {
        TerrainClose lifecycle = new TerrainClose();
        int[] detach = {0}, pages = {0}, textures = {0}, lights = {0};
        Runnable detachAction = () -> detach[0]++;
        Runnable pagesAction = () -> pages[0]++;
        Runnable textureAction = () -> {
            if (textures[0]++ == 0) throw new UnsatisfiedLinkError("texture release");
        };
        Runnable lightAction = () -> {
            if (lights[0]++ == 0) throw new IllegalStateException("light release");
        };
        try { lifecycle.close(detachAction, pagesAction, textureAction, lightAction); fail(); }
        catch (IllegalStateException expected) {
            assertTrue(expected.getCause() instanceof UnsatisfiedLinkError);
            assertEquals(1, expected.getCause().getSuppressed().length);
        }
        lifecycle.close(detachAction, pagesAction, textureAction, lightAction);
        assertArrayEquals(new int[] {1, 2, 2, 2}, new int[] {detach[0], pages[0], textures[0], lights[0]});
    }

    @Test public void failedListenerDetachDoesNotPreventNativeCleanupAndRetriesDetach() {
        TerrainClose lifecycle = new TerrainClose();
        int[] detach = {0}, releases = {0};
        Runnable detachAction = () -> {
            if (detach[0]++ == 0) throw new IllegalStateException("listener");
        };
        Runnable release = () -> releases[0]++;
        try { lifecycle.close(detachAction, release, release, release); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(3, releases[0]);
        lifecycle.close(detachAction, release, release, release);
        assertEquals(2, detach[0]);
        assertEquals(6, releases[0]);
    }
}

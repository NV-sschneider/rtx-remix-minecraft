/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.fog;

import com.nvidia.rtxremix.CameraInfoParameterizedEXT;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

public class FogHistoryTest {
    @Test public void stationaryPoseWarmsUpThenKeepsABoundedRunningWeight() {
        FogHistory history = new FogHistory();
        FogState fog = fog(); CameraInfoParameterizedEXT camera = new CameraInfoParameterizedEXT();
        for (int i = 1; i <= 96; i++) {
            assertEquals(1.0f / Math.min(i, 32), observe(history, fog, camera), 0);
            assertEquals(Math.min(i, 32), history.samples());
        }
        assertEquals(0, history.resets());
    }

    @Test public void everyCameraComponentAndProjectionChangeImmediatelyRejectsHistory() {
        changedCamera(camera -> camera.position.x = Math.nextUp(camera.position.x));
        changedCamera(camera -> camera.position.y = Math.nextUp(camera.position.y));
        changedCamera(camera -> camera.position.z = Math.nextUp(camera.position.z));
        changedCamera(camera -> camera.forward.x = Math.nextUp(camera.forward.x));
        changedCamera(camera -> camera.forward.y = Math.nextUp(camera.forward.y));
        changedCamera(camera -> camera.forward.z = Math.nextUp(camera.forward.z));
        changedCamera(camera -> camera.up.x = Math.nextUp(camera.up.x));
        changedCamera(camera -> camera.up.y = Math.nextUp(camera.up.y));
        changedCamera(camera -> camera.up.z = Math.nextUp(camera.up.z));
        changedCamera(camera -> camera.right.x = Math.nextUp(camera.right.x));
        changedCamera(camera -> camera.right.y = Math.nextUp(camera.right.y));
        changedCamera(camera -> camera.right.z = Math.nextUp(camera.right.z));
        changedCamera(camera -> camera.nearPlane = Math.nextUp(camera.nearPlane));
        changedCamera(camera -> camera.farPlane = Math.nextUp(camera.farPlane));
        changedCamera(camera -> camera.fovYInDegrees = Math.nextUp(camera.fovYInDegrees));
        changedCamera(camera -> camera.aspect = Math.nextUp(camera.aspect));
    }

    @Test public void changingFogColorKeepsCoverageButChangingItsDistanceLawResetsIt() {
        FogHistory history = new FogHistory();
        FogState fog = fog(); CameraInfoParameterizedEXT camera = new CameraInfoParameterizedEXT();
        assertEquals(1, observe(history, fog, camera), 0);
        fog.set(true, FogState.LINEAR, true, 0.9f, 0.8f, 0.7f, 0.05f, 8, 24);
        assertEquals(0.5f, observe(history, fog, camera), 0);
        assertEquals(0, history.resets());
        fog.set(true, FogState.LINEAR, false, 0.9f, 0.8f, 0.7f, 0.05f, 8, 24);
        assertEquals(1, observe(history, fog, camera), 0);
        fog.set(true, FogState.EXP, false, 0.9f, 0.8f, 0.7f, 0.05f, 8, 24);
        assertEquals(1, observe(history, fog, camera), 0);
        fog.set(true, FogState.EXP, false, 0.9f, 0.8f, 0.7f, 0.051f, 8, 24);
        assertEquals(1, observe(history, fog, camera), 0);
        fog.set(true, FogState.EXP, false, 0.9f, 0.8f, 0.7f, 0.051f, 9, 24);
        assertEquals(1, observe(history, fog, camera), 0);
        fog.set(true, FogState.EXP, false, 0.9f, 0.8f, 0.7f, 0.051f, 9, 25);
        assertEquals(1, observe(history, fog, camera), 0);
        assertEquals(5, history.resets());
    }

    @Test public void resizeSkippedFrameAndReloadCannotUseOldCoverage() {
        FogHistory history = new FogHistory();
        FogState fog = fog(); CameraInfoParameterizedEXT camera = new CameraInfoParameterizedEXT();
        observe(history, fog, camera); observe(history, fog, camera);
        assertEquals(1, history.observe(fog, camera, 2560, 1440, 853, 480), 0);
        assertEquals(1, history.observe(fog, camera, 2560, 1440, 1485, 835), 0);
        history.discard(); assertEquals(0, history.samples()); assertEquals(3, history.resets());
        history.discard(); assertEquals(3, history.resets());
        assertEquals(1, observe(history, fog, camera), 0);
        assertEquals(0.5f, observe(history, fog, camera), 0);
        history.discard(); assertEquals(1, observe(history, fog, camera), 0);
    }

    @Test public void pixelCapacityHandlesLargeDimensionsWithoutOverflow() {
        assertTrue(FogHistory.fits(3840, 2160));
        assertTrue(FogHistory.fits(4096, 2048));
        assertFalse(FogHistory.fits(4096, 2049));
        assertFalse(FogHistory.fits(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertFalse(FogHistory.fits(0, 480)); assertFalse(FogHistory.fits(853, -1));
    }

    @Test public void disoccludedInteriorsClearImmediatelyWithoutTurningFractionalEdgesIntoBinaryMasks() {
        assertEquals(0, FogMath.accumulate(0.8, 0, 0, 0, 1.0 / 32), 0);
        assertEquals(1, FogMath.accumulate(0.2, 1, 1, 1, 1.0 / 32), 0);
        assertEquals(0.5 * 31 / 32, FogMath.accumulate(0.5, 0, 0, 1, 1.0 / 32), 0);
        assertEquals(0.5 * 31 / 32 + 1.0 / 32, FogMath.accumulate(0.5, 1, 0, 1, 1.0 / 32), 0);
    }

    @Test public void stationaryChangingSampleCoverageSettlesWithoutKnowingItsJitterPhase() {
        FogHistory history = new FogHistory();
        FogState fog = fog(); CameraInfoParameterizedEXT camera = new CameraInfoParameterizedEXT();
        double coverage = 0, low = 1, high = 0;
        for (int i = 0; i < 256; i++) {
            coverage = FogMath.accumulate(coverage, i & 1, 0, 1, observe(history, fog, camera));
            if (i >= 128) { low = Math.min(low, coverage); high = Math.max(high, coverage); }
        }
        assertEquals(0.5, (low + high) * 0.5, 0.0002);
        assertTrue("A full-opacity alternating edge must settle to fractional coverage", high - low < 0.017);
    }

    private static void changedCamera(Consumer<CameraInfoParameterizedEXT> change) {
        FogHistory history = new FogHistory();
        FogState fog = fog(); CameraInfoParameterizedEXT camera = new CameraInfoParameterizedEXT();
        observe(history, fog, camera); observe(history, fog, camera);
        change.accept(camera); // Mutate the same input object, proving the snapshot is independent.
        assertEquals(1, observe(history, fog, camera), 0);
        assertEquals(1, history.samples()); assertEquals(1, history.resets());
        assertEquals(0.5f, observe(history, fog, camera), 0);
    }
    private static float observe(FogHistory history, FogState fog, CameraInfoParameterizedEXT camera) {
        return history.observe(fog, camera, 1280, 720, 853, 480);
    }
    private static FogState fog() {
        FogState fog = new FogState(); fog.set(true, FogState.LINEAR, true, 0.1f, 0.6f, 0.2f, 0.05f, 8, 24); return fog;
    }
}

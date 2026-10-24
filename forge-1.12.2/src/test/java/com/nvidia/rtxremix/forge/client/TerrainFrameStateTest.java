/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class TerrainFrameStateTest {
    @Test public void skipsNeedACompletedNativeCopyEvenWhenCaptureWasCleared() {
        TerrainFrameState state = new TerrainFrameState();
        state.begin();
        assertTrue(state.finish(9, 3)); state.recovered(true);
        assertFalse(state.finish(0, 0));
        assertEquals(9, state.skipped()); assertEquals(3, state.vanilla());
        assertEquals(9, state.skippedTotal()); assertEquals(1, state.fallbackFrames());
        long previous = state.frame();
        state.begin(); state.copied(); assertFalse(state.finish(7, 2));
        assertEquals(previous + 1, state.frame()); assertEquals(16, state.skippedTotal());
        assertEquals(1, state.fallbackFrames());
    }

    @Test public void failedRecoveryIsCountedAndNeverLeaksSuccessIntoTheNextFrame() {
        TerrainFrameState state = new TerrainFrameState();
        state.begin(); state.copied(); assertFalse(state.finish(1, 0));
        state.begin(); assertTrue(state.finish(1, 0)); state.recovered(false);
        assertEquals(1, state.fallbackFailures()); assertEquals(0, state.fallbackFrames());
        state.begin(); assertFalse(state.finish(0, 8)); assertEquals(2, state.skippedTotal());
    }
}

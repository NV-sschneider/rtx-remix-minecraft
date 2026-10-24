/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class TerrainDrawGateTest {
    @Test public void requiresFramePermissionKnownSolidDrawAndCurrentCompleteReplacement() {
        TerrainDrawGate gate = new TerrainDrawGate();
        TerrainDrawGate.Replacement replacement = (x, y, z) -> x == -1 && y == 4 && z == 9;
        gate.beginFrame(false, replacement); assertFalse(gate.skip(true, -1, 4, 9));
        gate.beginFrame(true, replacement);
        assertFalse(gate.skip(false, -1, 4, 9)); assertFalse(gate.skip(true, 0, 4, 9));
        assertTrue(gate.skip(true, -1, 4, 9)); assertEquals(1, gate.skippedDrawCount()); assertEquals(2, gate.vanillaDrawCount());
        gate.endFrame(); assertFalse(gate.skip(true, -1, 4, 9)); assertEquals(1, gate.skippedDrawCount());
    }

    @Test public void failureKeepsEarlierSkipEvidenceForFallbackAndStopsFurtherSuppression() {
        TerrainDrawGate gate = new TerrainDrawGate();
        RuntimeException failure = new IllegalStateException("generation unavailable");
        gate.beginFrame(true, (x, y, z) -> { if (x == 0) return true; throw failure; });
        assertTrue(gate.skip(true, 0, 0, 0)); assertFalse(gate.skip(true, 1, 0, 0));
        assertSame(failure, gate.failure()); assertFalse(gate.skip(true, 0, 0, 0));
        assertEquals(1, gate.skippedDrawCount());
        gate.endFrame(); assertSame(failure, gate.failure()); assertEquals(1, gate.skippedDrawCount());
        gate.discard(); assertNull(gate.failure()); assertEquals(0, gate.skippedDrawCount());
        gate.beginFrame(true, null); assertFalse(gate.skip(true, 0, 0, 0));
    }

    @Test public void workerCannotConsumeFramePermissionAndNextFrameMustBeRearmed() throws Exception {
        TerrainDrawGate gate = new TerrainDrawGate(); gate.beginFrame(true, (x, y, z) -> true);
        AtomicBoolean skipped = new AtomicBoolean(true);
        Thread worker = new Thread(() -> skipped.set(gate.skip(true, 0, 0, 0))); worker.start(); worker.join();
        assertFalse(skipped.get()); assertEquals(0, gate.skippedDrawCount()); assertEquals(0, gate.vanillaDrawCount());
        assertTrue(gate.skip(true, 0, 0, 0)); gate.beginFrame(false, (x, y, z) -> true);
        assertFalse(gate.skip(true, 0, 0, 0)); assertEquals(0, gate.skippedDrawCount());
    }
}

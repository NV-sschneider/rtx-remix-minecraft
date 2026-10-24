/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class FixtureSoakTest {
    @Test public void shortDurationCannotSkipRouteReloadOrCompletedReenable() {
        assertFalse(FixtureSoak.canFinish(600, 60, 1, 0, 0));
        assertFalse(FixtureSoak.canFinish(600, 60, 4, 1, 0));
        assertFalse(FixtureSoak.canFinish(600, 60, 4, 0, 1));
        assertFalse(FixtureSoak.canFinish(59, 60, 4, 1, 1));
        assertTrue(FixtureSoak.canFinish(60, 60, 4, 1, 1));
    }

    @Test public void cycleThroughputUsesDeltasIncludingStreamingWait() {
        FixtureSoak.CycleMetrics metrics = new FixtureSoak.CycleMetrics();
        assertEquals(0, metrics.framesPerSecond(), 0);
        metrics.record(1000, 1300, 5_000_000_000L, 35_000_000_000L);
        assertEquals(300, metrics.frames);
        assertEquals(30_000_000_000L, metrics.nanos);
        assertEquals(10, metrics.framesPerSecond(), 0);
        metrics.record(1300, 1900, 40_000_000_000L, 60_000_000_000L);
        assertEquals(600, metrics.frames);
        assertEquals(30, metrics.framesPerSecond(), 0);
    }

    @Test(expected = IllegalStateException.class) public void stoppedFramesCannotBecomeACompletedCycle() {
        new FixtureSoak.CycleMetrics().record(12, 12, 1, 1_000_000_001L);
    }

    @Test(expected = IllegalStateException.class) public void invalidClockDoesNotProduceInfiniteThroughput() {
        new FixtureSoak.CycleMetrics().record(12, 60, 1, 1);
    }
}

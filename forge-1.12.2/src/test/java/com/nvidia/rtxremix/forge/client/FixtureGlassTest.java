/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FixtureGlassTest {
    @Test public void stablePartialReloadCountsCannotBecomeTheRemovalBaseline() {
        FixtureGlass.PublicationSettlement settlement = new FixtureGlass.PublicationSettlement();
        // The local panels republish first. Other fixture scenes still contribute
        // glass after many frames with these apparently stable partial totals.
        for (int frame = 0; frame < 100; frame++) {
            assertFalse(settlement.update(true, 9158, 0, 120, 240, frame));
        }
        for (int frame = 100; frame < 120; frame++) {
            assertFalse(settlement.update(true, 0, 0, 728, 600, frame));
        }
        assertTrue(settlement.update(true, 0, 0, 728, 600, 120));

        settlement.reset();
        assertFalse(settlement.update(true, 2, 0, 608, 360, 121));
        for (int frame = 122; frame < 142; frame++) {
            assertFalse(settlement.update(true, 0, 0, 608, 360, frame));
        }
        assertTrue(settlement.update(true, 0, 0, 608, 360, 142));
    }

    @Test public void newCaptureAndRetirementWorkRestartTheFullSettleWindow() {
        FixtureGlass.PublicationSettlement settlement = new FixtureGlass.PublicationSettlement();
        assertFalse(settlement.update(true, 0, 0, 728, 600, 0));
        assertFalse(settlement.update(true, 1, 0, 728, 600, 19));
        assertFalse(settlement.update(true, 0, 0, 728, 600, 20));
        assertFalse(settlement.update(true, 0, 1, 728, 600, 39));
        assertFalse(settlement.update(true, 0, 0, 728, 600, 40));
        assertFalse(settlement.update(true, 0, 0, 728, 600, 59));
        assertTrue(settlement.update(true, 0, 0, 728, 600, 60));
    }

    @Test public void changedCountsReadinessAndPhaseResetRequireFreshCompletedFrames() {
        FixtureGlass.PublicationSettlement settlement = new FixtureGlass.PublicationSettlement();
        assertFalse(settlement.update(true, 0, 0, 120, 240, 0));
        assertFalse(settlement.update(true, 0, 0, 728, 600, 19));
        assertFalse(settlement.update(true, 0, 0, 728, 600, 20));
        assertFalse(settlement.update(false, 0, 0, 728, 600, 38));
        assertFalse(settlement.update(true, 0, 0, 728, 600, 39));
        assertFalse(settlement.update(true, 0, 0, 728, 600, 58));
        assertTrue(settlement.update(true, 0, 0, 728, 600, 59));
        settlement.reset();
        assertFalse(settlement.update(true, 0, 0, 728, 600, 60));
        assertFalse(settlement.update(true, 0, 0, 728, 600, 79));
        assertTrue(settlement.update(true, 0, 0, 728, 600, 80));
    }
}

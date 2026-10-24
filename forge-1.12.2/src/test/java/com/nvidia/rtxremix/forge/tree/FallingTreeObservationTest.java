/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.tree;

import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public class FallingTreeObservationTest {
    private final UUID owner = new UUID(1, 2);

    @Test public void shortLivedAnimationRequiresRealDataChangingNativePoseAndRetirement() {
        FallingTreeObservation observed = new FallingTreeObservation(owner);
        observed.sample(owner, 10, true, false, 0, 0, 0);
        observed.sample(owner, 11, true, true, 5, 1, 0);
        observed.sample(owner, 12, true, true, 5, 1, 1);
        observed.sample(owner, 12, true, true, 5, 1, 2);
        assertFalse(observed.animated());
        observed.sample(owner, 13, true, true, 5, 1, 2);
        assertTrue(observed.animated()); assertFalse(observed.passed());
        observed.sample(owner, 14, false, false, 0, 1, 0);
        assertFalse(observed.passed());
        observed.sample(owner, 15, false, false, 0, 0, 0);
        assertTrue(observed.passed()); assertEquals(3, observed.capturedFrames());
        assertEquals(5, observed.maximumBranches()); assertEquals(2, observed.transformUpdates());
    }

    @Test public void absentOrEmptyOrUncapturedTreesCannotPassAfterDisappearing() {
        for (int missing = 0; missing < 4; missing++) {
            FallingTreeObservation observed = new FallingTreeObservation(owner);
            for (int frame = 0; frame < 5; frame++) observed.sample(owner, frame,
                    missing != 0, missing != 1, missing == 2 ? 0 : 5, missing == 3 ? 0 : 1, frame);
            observed.sample(owner, 5, false, false, 0, 0, 0);
            assertFalse(observed.passed());
        }
    }

    @Test public void stationaryMeshesAndDifferentOwnersDoNotSatisfyAnimationEvidence() {
        FallingTreeObservation observed = new FallingTreeObservation(owner);
        for (int frame = 0; frame < 4; frame++) observed.sample(owner, frame, true, true, 5, 1, 7);
        observed.sample(owner, 4, false, false, 0, 0, 0);
        assertFalse(observed.passed());
        try { observed.sample(new UUID(3, 4), 5, true, true, 5, 1, 10); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("owner")); }
    }
}

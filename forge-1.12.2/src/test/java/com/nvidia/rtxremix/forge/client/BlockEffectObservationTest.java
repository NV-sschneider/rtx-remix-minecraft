/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class BlockEffectObservationTest {
    @Test public void staleFramesAndUncapturedOrExpiredAnimationCannotPass() {
        BlockEffectObservation proof = new BlockEffectObservation(20, 0);
        proof.sample(20, 1, true, .1f, 6, 100);
        proof.sample(21, 0, true, .1f, 6, 100);
        proof.sample(22, 1, true, .1f, 0, 100);
        proof.sample(23, 9, true, .1f, 6, 100);
        proof.sample(24, 2, false, .1f, 6, 100);
        proof.sample(25, 2, true, Float.NaN, 6, 100);
        assertEquals(0, proof.samples()); assertFalse(proof.passed());
    }

    @Test public void requiresBothARealPoseChangeAndNativeGeometryAcrossDistinctFrames() {
        BlockEffectObservation proof = new BlockEffectObservation(20, 60);
        proof.sample(21, 1, true, .1f, 60, 100);
        assertEquals(0, proof.samples());
        proof.sample(21, 1, true, .1f, 66, 100);
        proof.sample(21, 2, true, .08f, 72, 200);
        assertEquals(1, proof.samples());
        proof.sample(22, 2, true, .1f, 72, 200);
        proof.sample(23, 3, true, .08f, 78, 100);
        assertFalse(proof.passed());
        proof.sample(24, 4, true, .07f, 84, 200);
        assertTrue(proof.passed());
    }
}

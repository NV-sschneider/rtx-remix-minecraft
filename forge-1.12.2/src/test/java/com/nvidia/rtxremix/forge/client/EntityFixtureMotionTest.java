/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EntityFixtureMotionTest {
    @Test
    public void takesBoundedRoundTripAndHoldsReferencePose() {
        EntityFixtureMotion motion = new EntityFixtureMotion();
        double greatestOffset = 0;
        boolean facedBack = false;
        for (int i = 0; i < 80; i++) {
            assertFalse(motion.isComplete());
            motion.advance();
            greatestOffset = Math.max(greatestOffset, motion.offsetX());
            facedBack |= motion.yaw() == 90f;
            // The cow remains clear of the pig and the fixture's glass/pillars.
            assertTrue(motion.offsetX() >= 0 && motion.offsetX() <= 2.0);
        }
        assertTrue(motion.isComplete());
        assertTrue(facedBack);
        assertEquals(2.0, greatestOffset, 0.0);
        for (int i = 0; i < 20; i++) motion.advance();
        assertEquals(0.0, motion.offsetX(), 0.0);
        assertEquals(270f, motion.yaw(), 0f);
    }

    @Test
    public void reenableRepeatsIdenticalMotionFromReferencePose() {
        EntityFixtureMotion motion = new EntityFixtureMotion();
        double[] offsets = new double[80];
        for (int i = 0; i < offsets.length; i++) {
            motion.advance();
            offsets[i] = motion.offsetX();
        }
        motion.reset();
        assertFalse(motion.isComplete());
        assertEquals(0.0, motion.offsetX(), 0.0);
        for (int i = 0; i < offsets.length; i++) {
            motion.advance();
            assertEquals(offsets[i], motion.offsetX(), 0.0);
        }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class FixtureFoliageTest {
    @Test public void flatWorldSandHasRoomForPermanentSupport() {
        FixtureFoliage.Layout layout = new FixtureFoliage.Layout(176, FixtureFoliage.floorHeight(3), 0);
        assertEquals(16, layout.y);
        assertEquals(16, layout.sand.getY());
        assertEquals(15, layout.support.getY());
        assertEquals(layout.sand, layout.support.up());
        assertEquals(layout.cactus, layout.sand.up());
        assertEquals(layout.y >> 4, layout.cactus.up(2).getY() >> 4);
        assertEquals(layout.y >> 4, layout.leaf.getY() >> 4);
        assertEquals(layout.y >> 4, layout.grass.getY() >> 4);
    }

    @Test public void fixtureFloorNeverRoundsIntoTheGroundOrOutOfTheBuildLimit() {
        for (int ground = 0; ground <= 240; ground++) {
            int floor = FixtureFoliage.floorHeight(ground);
            assertEquals(0, floor & 15);
            assertTrue(floor >= ground);
            assertTrue(floor >= 16);
            assertTrue(floor + 15 <= 255);
        }
        assertEquals(64, FixtureFoliage.floorHeight(63));
        assertEquals(64, FixtureFoliage.floorHeight(64));
    }

    @Test(expected = IllegalArgumentException.class) public void nearCeilingTerrainFailsExplicitly() {
        FixtureFoliage.floorHeight(241);
    }
}

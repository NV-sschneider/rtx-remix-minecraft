/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.light;

import org.junit.Test;
import static org.junit.Assert.*;

public class SectionLightsTest {
    @Test public void keepsStrongestPerCellIncludingPackedOrigin() {
        SectionLights lights = new SectionLights();
        lights.add(0, 0, 0, 0, 15);
        lights.add(1, 0, 0, 123, 7);
        lights.add(4, 0, 0, 456, 14);
        assertArrayEquals(new long[] {0, 456}, lights.snapshot());
        lights.add(4, 1, 0, 789, 15);
        assertArrayEquals(new long[] {0, 789}, lights.snapshot());
    }
    @Test public void boundedForFullyLuminousSectionAndSnapshotIsDetached() {
        SectionLights lights = new SectionLights();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            lights.add(x, y, z, x + 16 * z + 256 * y, 15);
        long[] snapshot = lights.snapshot();
        assertEquals(64, snapshot.length);
        snapshot[0] = -1;
        assertEquals(0, lights.snapshot()[0]);
    }
    @Test public void noSourcesForZeroEmission() {
        SectionLights lights = new SectionLights();
        lights.add(1, 2, 3, 55, 0);
        assertEquals(0, lights.snapshot().length);
    }
}

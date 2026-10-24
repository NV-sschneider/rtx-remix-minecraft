/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import org.junit.Test;
import static org.junit.Assert.*;

public class LostTextureRegionsTest {
    @Test public void fourSidedSubtractionKeepsEveryUnrepairedPixelHidden() {
        LostTextureRegions lost = new LostTextureRegions(8, 8); lost.all();
        lost.repair(2, 2, 4, 4); assertFalse(lost.empty()); assertEquals(4, lost.count());
        lost.repair(0, 0, 8, 2); lost.repair(0, 6, 8, 2); lost.repair(0, 2, 2, 4);
        assertFalse(lost.empty()); lost.repair(6, 2, 2, 4); assertTrue(lost.empty());
    }
    @Test public void repeatedLossIsCoalescedAndMetadataOverflowFallsBackConservatively() {
        LostTextureRegions lost = new LostTextureRegions(256, 4);
        lost.lose(10, 1, 3, 2); lost.lose(11, 1, 1, 1); assertEquals(1, lost.count());
        lost.lose(9, 0, 5, 4); assertEquals(1, lost.count()); lost.repair(9, 0, 5, 4); assertTrue(lost.empty());
        for (int i = 0; i <= LostTextureRegions.MAX_REGIONS; i++) lost.lose(i * 2, 0, 1, 1);
        assertEquals(1, lost.count()); lost.repair(0, 0, 256, 1); assertFalse(lost.empty());
        lost.repair(0, 1, 256, 3); assertTrue(lost.empty());
    }
}

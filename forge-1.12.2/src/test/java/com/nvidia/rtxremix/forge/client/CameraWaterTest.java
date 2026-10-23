/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraWaterTest {
    @Test public void sourceAndFallingWaterReachTheTopOfTheirCell() {
        for (int level : new int[] {0, 8, 15}) {
            Grid grid = new Grid(level);
            assertEquals(Integer.valueOf(level), CameraWater.resolve(grid, 0.5, 0.999, 0.5));
            assertEquals(Integer.valueOf(CameraWater.NOT_LIQUID), CameraWater.resolve(grid, 0.5, 1, 0.5));
        }
    }
    @Test public void flowingWaterUsesExactPinnedSurfaceAndSelectsAirAtTheBoundary() {
        float[] pinned = {1, 0.8888889f, 0.7777778f, 0.6666666f, 0.5555555f,
                0.44444442f, 0.3333333f, 0.22222221f};
        for (int level = 1; level < 8; level++) {
            Grid grid = new Grid(level);
            double boundary = pinned[level];
            assertEquals(Integer.valueOf(level), CameraWater.resolve(grid, 0.5, boundary - 0.00001, 0.5));
            assertEquals(Integer.valueOf(CameraWater.NOT_LIQUID), CameraWater.resolve(grid, 0.5, boundary, 0.5));
        }
    }
    @Test public void cameraAcrossTheBankCanDifferFromPlayersEyes() {
        Grid grid = new Grid(0);
        assertEquals(Integer.valueOf(0), CameraWater.resolve(grid, 0.5, 0.5, 0.5));
        assertEquals(Integer.valueOf(CameraWater.NOT_LIQUID), CameraWater.resolve(grid, -0.1, 0.5, 0.5));
        assertEquals(-1, grid.viewX);
    }
    @Test public void forgeViewpointOverrideSeesResolvedStateAndOriginalPoint() {
        Grid grid = new Grid(7);
        grid.override = 0;
        assertEquals(Integer.valueOf(0), CameraWater.resolve(grid, 0.5, 0.9, 0.5));
        assertEquals(CameraWater.NOT_LIQUID, grid.resolved);
        assertEquals(0, grid.viewY);
        assertEquals(0.9, grid.pointY, 0);
    }
    @Test public void nonVanillaLiquidStillReceivesForgeViewpointHook() {
        Grid grid = new Grid(CameraWater.CUSTOM_LIQUID);
        assertEquals(Integer.valueOf(CameraWater.CUSTOM_LIQUID), CameraWater.resolve(grid, 0.5, 0.99, 0.5));
        assertEquals(CameraWater.CUSTOM_LIQUID, grid.resolved);
    }
    @Test public void surfaceLookupCanResolveToAnotherWaterBlockAbove() {
        Grid grid = new Grid(7);
        grid.above = 0;
        assertEquals(Integer.valueOf(0), CameraWater.resolve(grid, 0.5, 0.9, 0.5));
        assertEquals(0, grid.resolved);
    }

    private static final class Grid implements CameraWater.States<Integer> {
        final int level;
        Integer override;
        int above = CameraWater.NOT_LIQUID;
        int resolved, viewX, viewY;
        double pointY;
        Grid(int level) { this.level = level; }
        public Integer get(int x, int y, int z) {
            if (x != 0 || z != 0) return CameraWater.NOT_LIQUID;
            if (y == 0) return level;
            return y == 1 ? above : CameraWater.NOT_LIQUID;
        }
        public int liquidLevel(Integer state) { return state; }
        public Integer atViewpoint(Integer state, int x, int y, int z, double px, double py, double pz) {
            resolved = state; viewX = x; viewY = y; pointY = py;
            return override == null ? state : override;
        }
    }
}

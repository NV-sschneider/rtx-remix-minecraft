/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.fog;

import org.junit.Test;
import static org.junit.Assert.*;

public class FogCoverageTest {
    private static final double NEAR = 0.05, FAR = 1024;

    @Test public void clearForegroundAndSkyNeverInventAnIntermediateFoggedSurface() {
        FogState fog = state(FogState.LINEAR, false);
        double foreground = projected(8);
        double clear = opacity(fog, foreground), sky = opacity(fog, 1);
        assertEquals(0, clear, 1e-12); assertEquals(0, sky, 0);
        assertEquals(0, FogMath.coverage(clear, sky, clear, sky, 0.5, 0.5), 1e-12);
        // Filtering projected depth would create a fictitious hit around z=16.
        assertTrue(opacity(fog, (foreground + 1) * 0.5) > 0.45);
    }

    @Test public void silhouetteKeepsFractionalCoverageInsteadOfFoggingItsSkyNeighbor() {
        FogState fog = state(FogState.LINEAR, false);
        double opaque = opacity(fog, projected(32)), sky = opacity(fog, 1);
        assertEquals(1, opaque, 0);
        assertEquals(0.25, FogMath.coverage(opaque, sky, sky, sky, 0.5, 0.5), 0);
        assertEquals(0.75, FogMath.coverage(opaque, opaque, opaque, sky, 0.5, 0.5), 0);
        for (double x : new double[] {0, 0.125, 0.25, 0.5, 0.75, 0.875, 1})
            assertEquals(1 - x, FogMath.coverage(opaque, sky, opaque, sky, x, 0.3), 1e-15);
    }

    @Test public void constantSurfacesPreserveEveryCapturedFogLawAndFullResolutionTexelCenters() {
        for (int mode : new int[] {FogState.LINEAR, FogState.EXP, FogState.EXP2}) {
            FogState fog = state(mode, false);
            double alpha = opacity(fog, projected(16));
            assertEquals(1 - FogMath.transmission(fog, 16), alpha, 1e-12);
            for (double x : new double[] {0, 0.17, 0.5, 1}) for (double y : new double[] {0, 0.61, 1})
                assertEquals(alpha, FogMath.coverage(alpha, alpha, alpha, alpha, x, y), 1e-15);
            // At a texel center the other neighbors cannot change that sample.
            assertEquals(alpha, FogMath.coverage(alpha, 1, 0, 0.2, 0, 0), 0);
        }
    }

    @Test public void radialSamplesUseTheirOwnRayAndMissesNeverReuseFarPlaneOpacity() {
        FogState fog = state(FogState.EXP, true);
        double tangent = 0.75, aspect = 1.5, x = 0.6, y = -0.4;
        double distance = 16 * Math.sqrt(1 + Math.pow(x * tangent * aspect, 2) + Math.pow(y * tangent, 2));
        assertEquals(1 - FogMath.transmission(fog, distance),
                FogMath.opacity(fog, projected(16), NEAR, FAR, x, y, tangent, aspect), 1e-12);
        for (double miss : new double[] {-1, 1, 1.001, Double.NaN, Double.POSITIVE_INFINITY})
            assertEquals(0, opacity(fog, miss), 0);
        fog.clear(); assertEquals(0, opacity(fog, projected(16)), 0);
    }

    private static double opacity(FogState fog, double depth) {
        return FogMath.opacity(fog, depth, NEAR, FAR, 0, 0, 0.75, 1.5);
    }
    private static double projected(double z) { return FAR / (FAR - NEAR) - FAR * NEAR / ((FAR - NEAR) * z); }
    private static FogState state(int mode, boolean radial) {
        FogState fog = new FogState();
        assertTrue(fog.set(true, mode, radial, 0.1f, 0.6f, 0.2f, 0.05f, 8, 24));
        return fog;
    }
}

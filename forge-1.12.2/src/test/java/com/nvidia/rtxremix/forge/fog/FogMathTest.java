/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.fog;

import org.junit.Test;
import static org.junit.Assert.*;

public class FogMathTest {
    @Test public void linearFogPreservesBothEndpointsAndClearNearRange() {
        FogState fog = state(FogState.LINEAR, 0, 48, 64);
        assertEquals(1, FogMath.transmission(fog, 0), 0);
        assertEquals(1, FogMath.transmission(fog, 48), 0);
        assertEquals(0.5, FogMath.transmission(fog, 56), 0);
        assertEquals(0, FogMath.transmission(fog, 64), 0);
        assertEquals(0, FogMath.transmission(fog, 1024), 0);
        // A finite reversed range is legal GL state; do not silently rewrite it.
        fog = state(FogState.LINEAR, 0, 64, 48);
        assertEquals(0, FogMath.transmission(fog, 40), 0);
        assertEquals(1, FogMath.transmission(fog, 80), 0);
    }

    @Test public void waterDensityAndSquaredFogRetainDistinctDistanceCurves() {
        FogState exponential = state(FogState.EXP, 0.1f, 0, 1);
        FogState squared = state(FogState.EXP2, 0.1f, 0, 1);
        assertEquals(1, FogMath.transmission(exponential, 0), 0);
        assertEquals(Math.exp(-2), FogMath.transmission(exponential, 20), 1e-7);
        assertEquals(Math.exp(-4), FogMath.transmission(squared, 20), 1e-7);
        assertEquals(1, FogMath.transmission(state(FogState.EXP, 0, 0, 1), 100000), 0);
        assertEquals(0, FogMath.transmission(state(FogState.EXP2, Float.MAX_VALUE, 0, 1), 100000), 0);
    }

    @Test public void projectedDepthReconstructsWorldUnitsAcrossTheCameraRange() {
        double near = 0.05, far = 1024;
        assertEquals(near, FogMath.eyeDepth(0, near, far), 1e-12);
        assertEquals(far, FogMath.eyeDepth(1, near, far), 1e-8);
        for (double z : new double[] {0.05, 0.5, 5, 48, 64, 256, 512}) {
            double projected = far / (far - near) - far * near / ((far - near) * z);
            assertEquals(z, FogMath.eyeDepth(projected, near, far), 1e-8);
        }
    }

    @Test public void radialFogUsesActualRayLengthAndPlanarFogKeepsEyeDepth() {
        double tangent = Math.tan(Math.toRadians(90) / 2);
        assertEquals(10, FogMath.distance(10, 1, 1, tangent, 2, false), 0);
        assertEquals(10, FogMath.distance(10, 0, 0, tangent, 2, true), 0);
        assertEquals(10 * Math.sqrt(6), FogMath.distance(10, 1, 1, tangent, 2, true), 1e-10);
    }

    @Test public void invalidCaptureNeverKeepsEarlierFogAndDisabledFogIsClear() {
        FogState fog = state(FogState.LINEAR, 0, 2, 10);
        assertFalse(fog.set(true, 1234, true, 0, 0, 0, 0, 2, 10)); assertFalse(fog.valid());
        assertFalse(fog.set(true, FogState.EXP, false, 0, Float.NaN, 0, 1, 0, 1));
        assertFalse(fog.set(true, FogState.EXP, false, 0, 0, 0, -1, 0, 1));
        assertFalse(fog.set(true, FogState.LINEAR, false, 0, 0, 0, 0, 2, 2));
        assertTrue(fog.set(false, FogState.LINEAR, false, 0.1f, 0.2f, 0.3f, 0, 2, 10));
        assertTrue(fog.valid()); assertEquals(1, FogMath.transmission(fog, 1000), 0);
        fog.clear(); assertFalse(fog.valid()); assertFalse(fog.enabled());
    }

    @Test public void invalidDepthAndCameraCannotInventFogDistance() {
        for (double depth : new double[] {-1, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            try { FogMath.eyeDepth(depth, 0.05, 1024); fail(); } catch (IllegalArgumentException expected) { }
        }
        try { FogMath.eyeDepth(0.5, 0, 1024); fail(); } catch (IllegalArgumentException expected) { }
        try { FogMath.eyeDepth(0.5, 2, 2); fail(); } catch (IllegalArgumentException expected) { }
    }

    private static FogState state(int mode, float density, float start, float end) {
        FogState state = new FogState(); assertTrue(state.set(true, mode, true, 0.2f, 0.4f, 0.6f, density, start, end)); return state;
    }
}

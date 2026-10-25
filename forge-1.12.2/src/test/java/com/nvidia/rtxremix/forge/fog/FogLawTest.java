/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.fog;

import org.junit.Test;
import static org.junit.Assert.*;

public class FogLawTest {
    @Test public void linearSupremumUsesBreakpointsIncludingReversedAndNegativeRanges() {
        FogState normal = law(FogState.LINEAR, 0.05f, 8, 24);
        assertEquals(1.0 / 16, difference(normal, law(FogState.LINEAR, 0.05f, 9, 25)), 0);
        assertEquals(0.5, difference(normal, law(FogState.LINEAR, 0.05f, 16, 32)), 0);
        assertEquals(1, difference(normal, law(FogState.LINEAR, 0.05f, 24, 8)), 0);
        assertEquals(0, difference(law(FogState.LINEAR, 0, -20, -10), law(FogState.LINEAR, 1, -2, -1)), 0);
        for (float[] range : new float[][] {{-20, 8}, {0, 12}, {20, 40}, {80, 4}})
            assertBounds(normal, law(FogState.LINEAR, 0.05f, range[0], range[1]));
    }

    @Test public void exponentialSupremumMatchesTheAnalyticalExtremumAndAllSampledDistances() {
        for (int mode : new int[] {FogState.EXP, FogState.EXP2}) {
            // A doubled exponent rate gives a maximum difference of exactly1/4.
            float density = mode == FogState.EXP ? 0.5f : 0.25f;
            float other = mode == FogState.EXP ? 1 : (float) (0.25 * Math.sqrt(2));
            assertEquals(0.25, difference(law(mode, density, 8, 24), law(mode, other, 8, 24)), 3e-8);
            assertEquals(1, difference(law(mode, 0, 8, 24), law(mode, 0.05f, 8, 24)), 0);
            for (float d : new float[] {0.001f, 0.0499f, 0.05f, Math.nextUp(0.05f), 0.2f, 1})
                assertBounds(law(mode, 0.05f, 8, 24), law(mode, d, -10, 500));
        }
    }

    @Test public void inactiveParametersAndColorsDoNotChangeOpacityButLawIdentityDoes() {
        assertEquals(0, difference(law(FogState.LINEAR, 0, 8, 24), law(FogState.LINEAR, 1, 8, 24)), 0);
        for (int mode : new int[] {FogState.EXP, FogState.EXP2})
            assertEquals(0, difference(law(mode, 0.05f, 8, 24), law(mode, 0.05f, -1000, 2000)), 0);
        assertEquals(1, difference(law(FogState.EXP, 0.05f, 8, 24), law(FogState.EXP2, 0.05f, 8, 24)), 0);
    }

    @Test public void finiteFloatExtremesCannotOverflowTheDensityCalculation() {
        for (int mode : new int[] {FogState.EXP, FogState.EXP2}) {
            double bound = difference(law(mode, Float.MIN_VALUE, 0, 1), law(mode, Float.MAX_VALUE, 0, 1));
            assertTrue(Double.isFinite(bound)); assertEquals(1, bound, 1e-12);
            bound = difference(law(mode, Float.MAX_VALUE, 0, 1), law(mode, Math.nextDown(Float.MAX_VALUE), 0, 1));
            assertTrue(Double.isFinite(bound)); assertTrue(bound > 0 && bound < 1e-6);
        }
    }

    private static void assertBounds(FogState first, FogState second) {
        double maximum = difference(first, second);
        assertEquals(maximum, difference(second, first), 1e-15);
        for (int i = 0; i <= 4096; i++) {
            double distance = i * 0.125;
            double actual = Math.abs(FogMath.transmission(first, distance) - FogMath.transmission(second, distance));
            assertTrue("Supremum must bound the current-law difference at every distance", actual <= maximum + 1e-14);
        }
    }

    private static double difference(FogState first, FogState second) {
        return FogMath.maximumOpacityDifference(first, second);
    }
    private static FogState law(int mode, float density, float start, float end) {
        FogState value = new FogState();
        assertTrue(value.set(true, mode, false, 0.1f, 0.6f, 0.2f, density, start, end)); return value;
    }
}

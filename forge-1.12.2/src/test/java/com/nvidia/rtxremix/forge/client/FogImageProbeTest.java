/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import org.junit.Test;
import static org.junit.Assert.*;

public class FogImageProbeTest {
    @Test public void measuresInteriorTargetsWithoutAcceptingTheUnrelatedBackground() {
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        for (int y = 110; y <= 130; y++) for (int x = 145; x <= 175; x++) image.setRGB(x, y, 0x4080c0);
        assertArrayEquals(new double[] {64, 128, 192}, FogImageProbe.sample(image, 0.5, 0.5), 0);
        assertArrayEquals(new double[] {0, 0, 0}, FogImageProbe.sample(image, 0.1, 0.1), 0);
        try { FogImageProbe.sample(image, -0.1, 0.5); fail(); } catch (IllegalStateException expected) { }
        try { FogImageProbe.sample(image, Double.NaN, 0.5); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void distanceLawCheckRejectsMissingUniformAndWrongColorFog() {
        double[] clear = {200, 200, 200};
        double[] color = {25.5, 153, 51};
        double[] halfway = {112.75, 176.5, 125.5};
        assertEquals(0, FogImageProbe.error(clear, color, 0.1f, 0.6f, 0.2f, 0), 1e-5);
        assertEquals(0, FogImageProbe.error(clear, halfway, 0.1f, 0.6f, 0.2f, 0.5), 1e-5);
        assertTrue(FogImageProbe.error(clear, clear, 0.1f, 0.6f, 0.2f, 0) > 100);
        assertTrue(FogImageProbe.error(clear, halfway, 0.1f, 0.6f, 0.2f, 1) > 50);
        assertTrue(FogImageProbe.error(clear, color, 0.65f, 0.1f, 0.45f, 0) > 100);
    }

    @Test public void squaredAndOrdinaryExponentialControlsRemainDistinguishable() {
        double[] clear = {210, 210, 210};
        double[] exp = blend(clear, Math.exp(-1.4));
        double[] exp2 = blend(clear, Math.exp(-1.4 * 1.4));
        assertEquals(0, FogImageProbe.error(clear, exp, 0.1f, 0.6f, 0.2f, Math.exp(-1.4)), 1e-5);
        assertEquals(0, FogImageProbe.error(clear, exp2, 0.1f, 0.6f, 0.2f, Math.exp(-1.4 * 1.4)), 1e-5);
        assertTrue(FogImageProbe.difference(exp, exp2) > 10);
        assertEquals(Math.exp(-1.4), FogImageProbe.transmission(clear, exp, 0.1f, 0.6f, 0.2f), 1e-7);
        double[] nearExp = blend(clear, Math.exp(-0.4)), nearExp2 = blend(clear, Math.exp(-0.16));
        assertTrue(Math.abs(FogImageProbe.transmission(clear, nearExp, 0.1f, 0.6f, 0.2f)
                - FogImageProbe.transmission(clear, nearExp2, 0.1f, 0.6f, 0.2f)) > 0.18);
    }

    private static double[] blend(double[] clear, double transmission) {
        double[] result = new double[3], fog = {25.5, 153, 51};
        for (int i = 0; i < 3; i++) result[i] = clear[i] * transmission + fog[i] * (1 - transmission);
        return result;
    }
}

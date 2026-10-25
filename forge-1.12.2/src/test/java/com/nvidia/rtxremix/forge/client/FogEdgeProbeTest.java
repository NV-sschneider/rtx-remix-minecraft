/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import org.junit.Test;
import static org.junit.Assert.*;

public class FogEdgeProbeTest {
    @Test public void stableTextureDoesNotBecomeTemporalNoise() {
        BufferedImage image = checker(0);
        FogEdgeProbe probe = new FogEdgeProbe(image, 0, 0, 1, 1);
        for (int i = 0; i < FogEdgeProbe.SAMPLES; i++) probe.sample(image);
        assertEquals(0, probe.meanDifference(), 0); assertEquals(0, probe.changedFraction(), 0);
        assertEquals(0, probe.deviationPercentile(0.95), 0);
        try { probe.sample(image); fail(); } catch (IllegalStateException expected) { }
    }

    @Test public void oppositeEdgesCannotCancelThroughSpatialAveraging() {
        BufferedImage first = checker(0), second = checker(1);
        FogEdgeProbe probe = new FogEdgeProbe(first, 0, 0, 1, 1);
        probe.sample(first); probe.sample(second);
        assertEquals(255, probe.meanDifference(), 0); assertEquals(1, probe.changedFraction(), 0);
        assertEquals(127.5, probe.deviationPercentile(0.95), 0);
    }

    @Test public void unrelatedOutsidePixelsAndResizeDoNotCorruptTheRegion() {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        FogEdgeProbe probe = new FogEdgeProbe(image, 0.25, 0.25, 0.75, 0.75);
        probe.sample(image); image.setRGB(0, 0, 0xffffff); probe.sample(image);
        assertEquals(0, probe.meanDifference(), 0);
        try { probe.sample(new BufferedImage(21, 20, BufferedImage.TYPE_INT_RGB)); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(2, probe.samples());
    }

    private static BufferedImage checker(int phase) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            image.setRGB(x, y, ((x + y + phase) & 1) == 0 ? 0 : 0xffffff);
        return image;
    }
}

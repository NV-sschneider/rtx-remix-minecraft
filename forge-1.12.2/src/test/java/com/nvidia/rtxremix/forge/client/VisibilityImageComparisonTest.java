/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import org.junit.Test;
import static org.junit.Assert.*;

public class VisibilityImageComparisonTest {
    @Test public void comparesBaselineSelectedPixelsAndAcceptsBrighterShadowsWithIntactHighlights() {
        VisibilityImageComparison result = new VisibilityImageComparison(image(40, 200), image(55, 205));
        assertTrue(result.shadowsImproved()); assertTrue(result.highlightsPreserved());
        assertEquals(40, result.shadowBefore, 0.0001); assertEquals(55, result.shadowAfter, 0.0001);
    }
    @Test public void clippingAndBlankScenesCannotSatisfyVisibilityValidation() {
        VisibilityImageComparison clipped = new VisibilityImageComparison(image(40, 200), image(60, 255));
        assertTrue(clipped.shadowsImproved()); assertFalse(clipped.highlightsPreserved());
        VisibilityImageComparison blank = new VisibilityImageComparison(image(0, 0), image(30, 30));
        assertFalse(blank.shadowsImproved()); assertFalse(blank.highlightsPreserved());
        assertFalse(new VisibilityImageComparison(image(40, 200), image(40, 200)).shadowsImproved());
    }
    private static BufferedImage image(int shadow, int highlight) {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) {
            int value = x < 50 ? shadow : highlight; image.setRGB(x, y, value * 0x010101);
        }
        return image;
    }
}

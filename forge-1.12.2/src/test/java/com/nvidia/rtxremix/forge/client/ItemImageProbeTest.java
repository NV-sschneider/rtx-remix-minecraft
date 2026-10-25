/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import org.junit.Test;
import static org.junit.Assert.*;

public class ItemImageProbeTest {
    @Test public void itemSurfaceMustBeVisibleAndCannotBeReplacedByItsDarkShadow() {
        BufferedImage empty = image(0x101018), shadow = image(0x101018), item = image(0x101018);
        for (int y = 45; y <= 55; y++) for (int x = 45; x <= 55; x++) {
            shadow.setRGB(x, y, 0); item.setRGB(x, y, 0x786044);
        }
        assertFalse(ItemImageProbe.measure(empty, shadow, .5, .5, 12).visible(false));
        assertFalse(ItemImageProbe.measure(empty, empty, .5, .5, 12).visible(false));
        ItemImageProbe visible = ItemImageProbe.measure(empty, item, .5, .5, 12);
        assertEquals(121, visible.pixels); assertTrue(visible.visible(false));
        assertFalse(ItemImageProbe.measure(empty, item, .2, .2, 12).visible(false));
    }
    private static BufferedImage image(int color) {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) image.setRGB(x, y, color);
        return image;
    }
}

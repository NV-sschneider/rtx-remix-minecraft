/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import org.junit.Test;
import static org.junit.Assert.*;

public class FixtureFirstPersonImageTest {
    @Test public void visibleBladeAtTheBottomRightPassesAtBothFixtureResolutions() {
        for (int scale : new int[] {1, 2}) {
            BufferedImage empty = image(scale), sword = image(scale);
            rectangle(sword, 1170 * scale, 660 * scale, 1260 * scale, 720 * scale, 0x008f78);
            FixtureFirstPersonImage.Result proof = FixtureFirstPersonImage.sword(empty, sword, 3 * scale);
            assertTrue(proof.describe(), proof.visible()); assertEquals(5400 * scale * scale, proof.bladePixels);
        }
    }

    @Test public void hotbarCrosshairAndItemNameCannotStandInForAnInvisibleSword() {
        BufferedImage empty = image(1), sword = image(1);
        rectangle(sword, 364, 654, 910, 720, 0x008f78);
        rectangle(sword, 625, 345, 655, 375, 0x008f78);
        rectangle(sword, 530, 580, 750, 610, 0x008f78);
        FixtureFirstPersonImage.Result proof = FixtureFirstPersonImage.sword(empty, sword, 3);
        assertFalse(proof.visible()); assertEquals(0, proof.bladePixels);
    }

    @Test public void grayArenaBlueSkyAndExistingTurquoisePixelsDoNotProveANewBlade() {
        BufferedImage empty = image(1), unchanged = image(1);
        rectangle(empty, 1000, 330, 1200, 600, 0x008f78);
        rectangle(unchanged, 1000, 330, 1200, 600, 0x008f78);
        assertFalse(FixtureFirstPersonImage.sword(empty, unchanged, 3).visible());
        BufferedImage sky = image(1); rectangle(sky, 940, 288, 1280, 720, 0x779bc2);
        assertFalse(FixtureFirstPersonImage.sword(image(1), sky, 3).visible());
        assertFalse(FixtureFirstPersonImage.sword(image(1), image(1), 3).visible());
    }

    @Test public void tinyFragmentsThinBandsAndScatteredNoiseCannotPassTheBladeProof() {
        BufferedImage empty = image(1), fragment = image(1), band = image(1), noise = image(1);
        rectangle(fragment, 1190, 700, 1210, 720, 0x008f78);
        assertFalse(FixtureFirstPersonImage.sword(empty, fragment, 3).visible());
        rectangle(band, 940, 710, 1280, 720, 0x008f78);
        FixtureFirstPersonImage.Result thin = FixtureFirstPersonImage.sword(empty, band, 3);
        assertTrue(thin.bladePixels * 100L >= thin.samples); assertFalse(thin.visible());
        for (int y = 288; y < 720; y++) noise.setRGB(1100, y, 0x008f78);
        assertFalse(FixtureFirstPersonImage.sword(empty, noise, 3).visible());
    }

    @Test public void resizedOrUnusableViewportsCannotReuseAnOldBaseline() {
        assertThrows(IllegalArgumentException.class, () -> FixtureFirstPersonImage.sword(image(1), image(2), 3));
        assertThrows(IllegalArgumentException.class, () -> FixtureFirstPersonImage.sword(image(1), image(1), 0));
        assertFalse(FixtureFirstPersonImage.sword(image(1), image(1), 7).visible());
    }

    private static BufferedImage image(int scale) {
        BufferedImage image = new BufferedImage(1280 * scale, 720 * scale, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            image.setRGB(x, y, ((x / (64 * scale) + y / (64 * scale)) & 1) == 0 ? 0xdddddd : 0x606060);
        return image;
    }
    private static void rectangle(BufferedImage image, int left, int top, int right, int bottom, int color) {
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) image.setRGB(x, y, color);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaterialImageTest {
    @Test public void staticSidecarRepeatsForEveryAlbedoFrame() throws Exception {
        MaterialImage image = MaterialImage.read(png(2, 2), 2, 2, 4);
        assertEquals(1, image.frames);
        assertEquals(image.pixel(0, 1, 1), image.pixel(3, 1, 1));
    }

    @Test public void stripsRetainTheirPhysicalFrameOrder() throws Exception {
        MaterialImage image = MaterialImage.read(png(2, 8), 2, 2, 4);
        assertEquals(4, image.frames);
        assertEquals(0xff000003, image.pixel(1, 1, 1));
        assertEquals(0xff000007, image.pixel(3, 0, 1));
    }

    @Test public void incompatibleDimensionsAndMalformedImagesAreRejected() throws Exception {
        reject(png(3, 4), 2, 2, 2);
        reject(png(2, 3), 2, 2, 2);
        reject(png(2, 6), 2, 2, 2);
        reject(new ByteArrayInputStream(new byte[] {0, 1, 2}), 2, 2, 1);
    }

    private static void reject(ByteArrayInputStream data, int width, int height, int frames) throws Exception {
        try { MaterialImage.read(data, width, height, frames); fail("Invalid material must not be decoded"); }
        catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }
    static ByteArrayInputStream png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, 0xff000000 | y);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ImageIO.write(image, "png", data);
        return new ByteArrayInputStream(data.toByteArray());
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class EntityTexturesTest {
    @Test public void opacityRequiresEverySampleAndPreservesTheUploadRange() {
        ByteBuffer pixels = EntityMesh.direct(20);
        for (int offset = 0; offset < 20; offset += 4) pixels.putInt(offset, -1);
        pixels.put(3, (byte) 0); pixels.put(19, (byte) 0);
        pixels.position(4).mark(); pixels.limit(16);
        assertTrue(EntityTextures.opaquePixels(pixels));
        assertEquals(4, pixels.position()); assertEquals(16, pixels.limit()); pixels.reset();
        pixels.put(11, (byte) 254); assertFalse(EntityTextures.opaquePixels(pixels));
        pixels.put(11, (byte) 0); assertFalse(EntityTextures.opaquePixels(pixels));
        pixels.put(11, (byte) 255); assertTrue(EntityTextures.opaquePixels(pixels));
        pixels.limit(15); assertFalse(EntityTextures.opaquePixels(pixels));
        pixels.limit(4); assertFalse(EntityTextures.opaquePixels(pixels));
    }

    @Test public void binaryProofAllowsTransparentCutoutsButRejectsEveryFractionalAlpha() {
        ByteBuffer pixels = EntityMesh.direct(20);
        for (int offset = 0; offset < 20; offset += 4) pixels.putInt(offset, -1);
        pixels.put(3, (byte) 1); pixels.put(19, (byte) 254);
        pixels.put(7, (byte) 0);
        pixels.position(4).mark(); pixels.limit(16);
        assertTrue(EntityTextures.binaryPixels(pixels));
        assertFalse(EntityTextures.opaquePixels(pixels));
        for (int alpha = 1; alpha < 255; alpha++) {
            pixels.put(11, (byte) alpha);
            assertFalse("Fractional alpha " + alpha, EntityTextures.binaryPixels(pixels));
        }
        pixels.put(11, (byte) 0); pixels.put(15, (byte) 0);
        assertTrue(EntityTextures.binaryPixels(pixels));
        assertEquals(4, pixels.position()); assertEquals(16, pixels.limit()); pixels.reset();
        pixels.limit(15); assertFalse(EntityTextures.binaryPixels(pixels));
        pixels.limit(4); assertFalse(EntityTextures.binaryPixels(pixels));
    }
}

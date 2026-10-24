/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class LabPbrPixelsTest {
    @Test public void specularChannelsHaveIndependentMeaningAndOpaqueAlphaDoesNotEmit() {
        assertEquals(255, LabPbrPixels.roughness(0xff000000));
        assertEquals(0, LabPbrPixels.roughness(0xffff0000));
        assertEquals(63, LabPbrPixels.roughness(0xff800000));
        assertEquals(0, LabPbrPixels.metallic(0xff00e500));
        assertEquals(255, LabPbrPixels.metallic(0xff00e600));
        assertEquals(0, LabPbrPixels.emission(0xff000000));
        assertEquals(0, LabPbrPixels.emission(0x00000000));
        assertEquals(255, LabPbrPixels.emission(0xfe000000));
    }

    @Test public void normalsUseThePinnedRuntimeOrdinaryOctahedralEncoding() {
        ByteBuffer data = ByteBuffer.allocate(2);
        LabPbrPixels.write(data, 0, LabPbrPixels.NORMAL, 0xff808000, 0xff808000, 0, 0);
        assertEquals(128, data.get(0) & 255);
        assertEquals(128, data.get(1) & 255);
        // XY diagonals lie at the diamond edge midpoints, not square edges.
        int[] sources = {0xffffff00, 0xffff0000, 0xff00ff00, 0xff000000};
        int[][] expected = {{191, 191}, {191, 64}, {64, 191}, {64, 64}};
        for (int i = 0; i < sources.length; i++) {
            LabPbrPixels.write(data, 0, LabPbrPixels.NORMAL, sources[i], sources[i], 0, 0);
            assertEquals(expected[i][0], data.get(0) & 255);
            assertEquals(expected[i][1], data.get(1) & 255);
        }
        // This oblique direction also anchors the native NORMAL_DX/OTH GPU comparison.
        LabPbrPixels.write(data, 0, LabPbrPixels.NORMAL, 0xfff28000, 0xfff28000, 0, 0);
        assertEquals(213, data.get(0) & 255);
        assertEquals(128, data.get(1) & 255);
    }

    @Test public void octahedralDecodePreservesEveryLabPbrDirectionIncludingOutOfDiskInputs() {
        ByteBuffer data = ByteBuffer.allocate(2);
        for (int red = 0; red < 256; red++) for (int green = 0; green < 256; green++) {
            int source = red << 16 | green << 8;
            LabPbrPixels.write(data, 0, LabPbrPixels.NORMAL, source, source, 0, 0);
            // Decode independently by lifting onto the octahedron. Rounding at
            // the equator can put a texel one byte step onto a lower face.
            double x = (data.get(0) & 255) / 127.5 - 1, y = (data.get(1) & 255) / 127.5 - 1;
            double z = 1 - Math.abs(x) - Math.abs(y);
            if (z < 0) {
                double previousX = x;
                x = (1 - Math.abs(y)) * (x < 0 ? -1 : 1);
                y = (1 - Math.abs(previousX)) * (y < 0 ? -1 : 1);
            }
            double decodedLength = Math.sqrt(x * x + y * y + z * z);
            double expectedX = red / 127.5 - 1, expectedY = green / 127.5 - 1;
            double expectedZ = Math.sqrt(Math.max(0, 1 - expectedX * expectedX - expectedY * expectedY));
            double expectedLength = Math.sqrt(expectedX * expectedX + expectedY * expectedY + expectedZ * expectedZ);
            double cosine = (x * expectedX + y * expectedY + z * expectedZ) / (decodedLength * expectedLength);
            assertTrue("Normal direction changed at " + red + "," + green, cosine > 0.9998);
            assertTrue("Normal exceeds equator quantization tolerance", z >= -1.0 / 255 - 1e-12);
        }
    }

    @Test public void animatedNormalsInterpolateBeforeNonlinearOctahedralEncoding() {
        ByteBuffer data = ByteBuffer.allocate(4);
        data.put(0, (byte) 23).put(3, (byte) 47);
        LabPbrPixels.write(data, 1, LabPbrPixels.NORMAL, 0xffff8000, 0xff80ff00, 0.5f, 0);
        assertEquals(165, data.get(1) & 255);
        assertEquals(165, data.get(2) & 255);
        assertEquals(23, data.get(0) & 255);
        assertEquals(47, data.get(3) & 255);
    }

    @Test public void normalDefaultsUseTwoBytesPerTexelWithoutChangingTheUploadSlice() {
        ByteBuffer data = ByteBuffer.allocateDirect(8);
        data.put(6, (byte) 37).limit(6);
        LabPbrPixels.defaults(data, LabPbrPixels.NORMAL);
        assertEquals(2, LabPbrPixels.STRIDES[LabPbrPixels.NORMAL]);
        assertEquals(6, data.remaining());
        for (int i = 0; i < 6; i++) assertEquals(128, data.get(i) & 255);
        data.limit(8);
        assertEquals(37, data.get(6) & 255);
    }

    @Test public void emissionInterpolationDecodesTheDisabledAlphaSentinelBeforeBlending() {
        ByteBuffer data = ByteBuffer.allocate(4);
        LabPbrPixels.write(data, 0, LabPbrPixels.EMISSIVE, 0xfe000000, 0xff000000, 0.5f, 0xff8040);
        assertEquals(187, data.get(0) & 255);
        assertEquals(92, data.get(1) & 255);
        assertEquals(44, data.get(2) & 255);
    }

    @Test public void channelDefaultsRespectTheActiveSliceOfAReusedUploadBuffer() {
        ByteBuffer data = ByteBuffer.allocateDirect(16);
        data.limit(4);
        LabPbrPixels.defaults(data, LabPbrPixels.ROUGHNESS);
        assertEquals(4, data.remaining());
        for (int i = 0; i < 4; i++) assertEquals(255, data.get(i) & 255);
    }
}

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

    @Test public void normalZIsReconstructedAndEncodedInTheUnsignedNormalDomain() {
        ByteBuffer data = ByteBuffer.allocate(4);
        LabPbrPixels.write(data, 0, LabPbrPixels.NORMAL, 0xff808000, 0xff808000, 0, 0);
        assertEquals(128, data.get(0) & 255);
        assertEquals(128, data.get(1) & 255);
        assertEquals(254, data.get(2) & 255);
        LabPbrPixels.write(data, 0, LabPbrPixels.NORMAL, 0xffff8000, 0xffff8000, 0, 0);
        assertEquals(127, data.get(2) & 255);
        assertEquals(255, data.get(3) & 255);
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

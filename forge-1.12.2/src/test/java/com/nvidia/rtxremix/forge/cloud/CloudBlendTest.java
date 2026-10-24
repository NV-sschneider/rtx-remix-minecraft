/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.cloud;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import org.junit.Test;
import static org.junit.Assert.*;

public class CloudBlendTest {
    @Test public void preservesVanillaCloudAlphaAndAdditiveSkyState() {
        InstanceInfoBlendEXT cloud = CloudBlend.extension(CloudBlend.key(true, 770, 771, 32774, 1, 0, 32774, true, 516, 26, true));
        assertEquals(1, cloud.alphaBlendEnabled); assertEquals(6, cloud.srcColorBlendFactor); assertEquals(7, cloud.dstColorBlendFactor);
        assertEquals(1, cloud.srcAlphaBlendFactor); assertEquals(0, cloud.dstAlphaBlendFactor); assertEquals(26, cloud.alphaTestReferenceValue);
        InstanceInfoBlendEXT sky = CloudBlend.extension(CloudBlend.key(true, 770, 1, 32774, 0, 1, 32774, false, 0, 0, false));
        assertEquals(1, sky.dstColorBlendFactor); assertEquals(0, sky.alphaTestEnabled);
        assertEquals(InstanceInfoBlendEXT.RT_TEXTURE_ARG_VERTEX_COLOR, sky.textureColorArg1Source);
        assertEquals(InstanceInfoBlendEXT.RT_TEX_OP_SELECT_ARG1, sky.textureColorOperation);
    }
    @Test public void rejectsUnsupportedEquationsAndFactorsRatherThanReclassifyingThem() {
        assertEquals(-1, CloudBlend.key(true, 770, 771, 32778, 1, 0, 32774, false, 0, 0, true));
        assertEquals(-1, CloudBlend.key(true, 774, 771, 32774, 1, 0, 32774, false, 0, 0, true));
    }
}

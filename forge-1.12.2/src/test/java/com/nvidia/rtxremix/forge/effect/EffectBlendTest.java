/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import org.junit.Test;
import static org.junit.Assert.*;

public class EffectBlendTest {
    @Test public void preservesVertexAlphaAndSeparateAlphaBlendFactors() {
        long key = EffectBlend.key(true, 770, 771, 32774, 770, 771, 32774, true, 516, 128);
        InstanceInfoBlendEXT state = EffectBlend.extension(key);
        assertEquals(1, state.alphaBlendEnabled);
        assertEquals(6, state.srcColorBlendFactor);
        assertEquals(7, state.dstColorBlendFactor);
        assertEquals(6, state.srcAlphaBlendFactor);
        assertEquals(7, state.dstAlphaBlendFactor);
        assertEquals(4, state.alphaTestCompareOp);
        assertEquals(128, state.alphaTestReferenceValue & 255);
        assertEquals(InstanceInfoBlendEXT.RT_TEXTURE_ARG_VERTEX_COLOR, state.textureAlphaArg2Source);
        assertEquals(InstanceInfoBlendEXT.RT_TEX_OP_MODULATE, state.textureAlphaOperation);
    }
    @Test public void disabledBlendAndAlphaTestIgnoreStaleGlSettings() {
        long key = EffectBlend.key(false, -1, -1, -1, -1, -1, -1, false, -1, 99);
        InstanceInfoBlendEXT state = EffectBlend.extension(key);
        assertEquals(0, state.alphaBlendEnabled);
        assertEquals(0, state.alphaTestEnabled);
    }
    @Test public void rejectsUnrepresentedAdditivePremultipliedAndBlendEquations() {
        assertEquals(-1, EffectBlend.key(true, 770, 1, 32774, 1, 0, 32774, false, 519, 0));
        assertEquals(-1, EffectBlend.key(true, 1, 771, 32774, 1, 0, 32774, false, 519, 0));
        assertEquals(-1, EffectBlend.key(true, 770, 771, 32778, 1, 0, 32774, false, 519, 0));
    }
}

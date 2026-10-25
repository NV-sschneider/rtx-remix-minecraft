/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.forge.texture.SpriteOpacity;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import org.junit.Test;
import static org.junit.Assert.*;

public class EffectBlendTest {
    @Test public void provenOpaquePlacementKeepsItsAlphaTestWithoutPromotingEmissionOrDamage() {
        long ordinary = EffectBlend.key(true, 770, 771, 32774, 1, 0, 32774, true, 516, 26);
        InstanceInfoBlendEXT state = EffectBlend.extension(EffectBlend.opaquePlacement(ordinary, SpriteOpacity.OPAQUE));
        assertEquals(0, state.alphaBlendEnabled);
        assertEquals(1, state.alphaTestEnabled);
        assertEquals(4, state.alphaTestCompareOp);
        assertEquals(26, state.alphaTestReferenceValue & 255);
        for (long key : new long[] {-1,
                EffectBlend.key(true, 770, 1, 32774, 1, 0, 32774, true, 516, 26),
                EffectBlend.key(true, 1, 1, 32774, 1, 1, 32774, true, 516, 26),
                EffectBlend.key(true, true, 774, 768, 32774, 1, 0, 32774, true, 516, 26)})
            assertEquals(key, EffectBlend.opaquePlacement(key, SpriteOpacity.OPAQUE));
    }

    @Test public void binaryPlacementOnlyDropsBlendingWhenItsCapturedTestRejectsZero() {
        for (int compare = 512; compare <= 519; compare++) for (int reference : new int[] {0, 1, 26, 255}) {
            boolean[] zeroPasses = {false, 0 < reference, 0 == reference, 0 <= reference,
                    0 > reference, 0 != reference, 0 >= reference, true};
            long original = EffectBlend.key(true, 770, 771, 32774, 1, 0, 32774, true, compare, reference);
            InstanceInfoBlendEXT state = EffectBlend.extension(EffectBlend.opaquePlacement(original, SpriteOpacity.CUTOUT));
            assertEquals(zeroPasses[compare - 512] ? 1 : 0, state.alphaBlendEnabled);
            assertEquals(compare - 512, state.alphaTestCompareOp);
            assertEquals(reference, state.alphaTestReferenceValue & 255);
            assertEquals(original, EffectBlend.opaquePlacement(original, SpriteOpacity.UNKNOWN));
        }
        long disabled = EffectBlend.key(true, 770, 771, 32774, 1, 0, 32774, false, 516, 1);
        assertEquals(disabled, EffectBlend.opaquePlacement(disabled, SpriteOpacity.CUTOUT));
    }

    @Test public void damageUsesSupportedAlphaCracksOnlyInsideItsExactMultiplicativeScope() {
        long key = EffectBlend.key(true, true, 774, 768, 32774, 1, 0, 32774, true, 516, 26);
        assertTrue(key >= 0);
        InstanceInfoBlendEXT state = EffectBlend.extension(key);
        assertEquals(6, state.srcColorBlendFactor);
        assertEquals(7, state.dstColorBlendFactor);
        assertFalse(EffectBlend.emissive(key));
        assertEquals(1, state.srcAlphaBlendFactor);
        assertEquals(0, state.dstAlphaBlendFactor);
        assertEquals(0, state.alphaTestReferenceValue & 255);
        assertEquals(-1, EffectBlend.key(false, true, 774, 768, 32774, 1, 0, 32774, true, 516, 26));
        assertEquals(-1, EffectBlend.key(true, true, 770, 771, 32774, 1, 0, 32774, true, 516, 26));
        assertEquals(-1, EffectBlend.key(true, true, 774, 768, 32774, 1, 0, 32774, true, 516, 128));
    }
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
    @Test public void additiveMaterialsKeepAlphaWeightedAndUnweightedEmissionDistinct() {
        long alpha = EffectBlend.key(true, 770, 1, 32774, 1, 0, 32774, false, 519, 0);
        long direct = EffectBlend.key(true, 1, 1, 32774, 1, 1, 32774, false, 519, 0);
        assertTrue(EffectBlend.emissive(alpha)); assertTrue(EffectBlend.emissive(direct));
        assertNotEquals(alpha, direct);
        assertEquals(6, EffectBlend.extension(alpha).srcColorBlendFactor);
        assertEquals(1, EffectBlend.extension(direct).srcColorBlendFactor);
        assertEquals(1, EffectBlend.extension(alpha).dstColorBlendFactor);
        assertEquals(1, EffectBlend.extension(direct).dstColorBlendFactor);
        assertEquals(0, EffectBlend.extension(alpha).dstAlphaBlendFactor);
        assertEquals(1, EffectBlend.extension(direct).dstAlphaBlendFactor);
        long ordinary = EffectBlend.key(true, 770, 771, 32774, 1, 0, 32774, false, 519, 0);
        assertFalse(EffectBlend.emissive(ordinary));
        assertEquals(0, EffectScene.material(42, false).emissiveTexture);
        assertEquals(42, EffectScene.material(42, true).emissiveTexture);
        assertEquals(100, EffectScene.material(42, true).emissiveIntensity, 0);
    }
    @Test public void rejectsUnrepresentedPremultipliedAndBlendEquations() {
        assertEquals(-1, EffectBlend.key(true, 1, 771, 32774, 1, 0, 32774, false, 519, 0));
        assertEquals(-1, EffectBlend.key(true, 770, 771, 32778, 1, 0, 32774, false, 519, 0));
        assertEquals(-1, EffectBlend.key(true, 1, 1, 32774, 1, 1, 32778, false, 519, 0));
    }
}

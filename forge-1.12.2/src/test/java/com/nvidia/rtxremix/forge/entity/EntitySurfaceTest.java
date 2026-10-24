/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.MaterialInfo;
import org.junit.Test;
import static org.junit.Assert.*;

public class EntitySurfaceTest {
    @Test public void separatedGlintKeepsOwnerVisibilityAndUsesEmissiveInsteadOfDecalResolve() {
        int decals = InstanceCategoryBit.DECAL_DYNAMIC | InstanceCategoryBit.DECAL_NO_OFFSET;
        int[] owners = {0, InstanceCategoryBit.VIEW_MODEL,
                InstanceCategoryBit.THIRD_PERSON_PLAYER_MODEL | InstanceCategoryBit.THIRD_PERSON_PLAYER_BODY};
        for (int owner : owners) {
            assertEquals(owner, EntitySurface.categories(owner, EntitySurface.GLINT, true));
            assertEquals(owner, EntitySurface.categories(owner, EntitySurface.ORDINARY, false));
            assertEquals(owner | decals, EntitySurface.categories(owner, EntitySurface.GLINT, false));
            assertEquals(owner | decals, EntitySurface.categories(owner, EntitySurface.EYES, false));
        }
    }

    @Test public void additiveEyesAndExplicitGlintPreserveTheirDifferentBlendEquations() {
        assertEquals(EntitySurface.ORDINARY, EntitySurface.classify(false, 0, 0, 0, false));
        assertEquals(EntitySurface.ORDINARY, EntitySurface.classify(true, 770, 771, 32774, false));
        assertEquals(EntitySurface.EYES, EntitySurface.classify(true, 1, 1, 32774, false));
        assertEquals(EntitySurface.GLINT, EntitySurface.classify(true, 768, 1, 32774, true));
        assertEquals(-1, EntitySurface.classify(true, 768, 1, 32774, false));
        assertEquals(-1, EntitySurface.classify(true, 1, 1, 32774, true));
        assertEquals(-1, EntitySurface.classify(false, 768, 1, 32774, true));
        assertEquals(-1, EntitySurface.classify(true, 1, 1, 32778, false));
        InstanceInfoBlendEXT eyes = EntityScene.blendState(0xff8844cc, true, 7, 0, false, EntitySurface.EYES);
        assertEquals(1, eyes.alphaBlendEnabled); assertEquals(1, eyes.srcColorBlendFactor); assertEquals(1, eyes.dstColorBlendFactor);
        assertEquals(InstanceInfoBlendEXT.RT_TEXTURE_ARG_TFACTOR, eyes.textureColorArg2Source); assertEquals(0xff8844cc, eyes.tFactor);
        InstanceInfoBlendEXT glint = EntityScene.blendState(-1, true, 4, 25, true, EntitySurface.GLINT);
        assertEquals(2, glint.srcColorBlendFactor); assertEquals(1, glint.dstColorBlendFactor);
        assertEquals(2, glint.srcAlphaBlendFactor); assertEquals(1, glint.dstAlphaBlendFactor);
        assertEquals(InstanceInfoBlendEXT.RT_TEXTURE_ARG_VERTEX_COLOR, glint.textureColorArg2Source);
        assertEquals(1, glint.alphaTestEnabled); assertEquals(4, glint.alphaTestCompareOp); assertEquals(25, glint.alphaTestReferenceValue);
    }

    @Test public void emissiveVariantBorrowsTheSameAnimatedPixelsAndSampler() {
        EntityTextures.Asset texture = new EntityTextures.Asset(new Object(), 2, 123, 0, 4096);
        texture.wrapU = MaterialInfo.WRAP_MODE_REPEAT; texture.wrapV = MaterialInfo.WRAP_MODE_MIRRORED_REPEAT;
        MaterialInfo ordinary = EntityTextures.material(texture, 10, false), emissive = EntityTextures.material(texture, 11, true);
        assertEquals(123, ordinary.albedoTexture); assertEquals(0, ordinary.emissiveTexture);
        assertEquals(123, emissive.albedoTexture); assertEquals(123, emissive.emissiveTexture);
        assertEquals(100, emissive.emissiveIntensity, 0);
        assertEquals(texture.wrapU, emissive.wrapModeU); assertEquals(texture.wrapV, emissive.wrapModeV);
        assertEquals(1, emissive.opaqueExt.useDrawCallAlphaState);
    }

    @Test public void alphaWeightedSpellEmissionKeepsItsSourceAlphaDistinctFromEyes() {
        assertEquals(EntitySurface.ADDITIVE_ALPHA, EntitySurface.classify(true, 770, 1, 32774, false));
        assertEquals(-1, EntitySurface.classify(true, 770, 1, 32774, true));
        assertEquals(-1, EntitySurface.classify(true, 1, 771, 32774, false));
        InstanceInfoBlendEXT spell = EntityScene.blendState(0x408844cc, true, 4, 12, true, EntitySurface.ADDITIVE_ALPHA);
        assertEquals(6, spell.srcColorBlendFactor); assertEquals(1, spell.dstColorBlendFactor);
        assertEquals(6, spell.srcAlphaBlendFactor); assertEquals(1, spell.dstAlphaBlendFactor);
        assertEquals(InstanceInfoBlendEXT.RT_TEXTURE_ARG_VERTEX_COLOR, spell.textureAlphaArg2Source);
        assertEquals(InstanceInfoBlendEXT.RT_TEX_OP_MODULATE, spell.textureAlphaOperation);
    }
}

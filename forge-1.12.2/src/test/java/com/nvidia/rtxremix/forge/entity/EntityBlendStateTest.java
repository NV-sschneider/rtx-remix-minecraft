/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import org.junit.Test;
import static com.nvidia.rtxremix.InstanceInfoBlendEXT.*;
import static org.junit.Assert.*;

public class EntityBlendStateTest {
    @Test public void cutoutPreservesTheCapturedComparisonAndReference() {
        InstanceInfoBlendEXT state = EntityScene.blendState(0xFF336699, false, VK_COMPARE_OP_GREATER, 25);
        assertEquals(1, state.alphaTestEnabled);
        assertEquals(VK_COMPARE_OP_GREATER, state.alphaTestCompareOp);
        assertEquals(25, state.alphaTestReferenceValue & 255);
        assertEquals(0, state.alphaBlendEnabled);
        assertEquals(0xFF336699, state.tFactor);
        assertEquals(RT_TEXTURE_ARG_TFACTOR, state.textureColorArg2Source);
        assertEquals(RT_TEXTURE_ARG_TFACTOR, state.textureAlphaArg2Source);
        assertEquals(RT_TEX_OP_MODULATE, state.textureAlphaOperation);
    }

    @Test public void translucentShellUsesTextureAlphaForStandardBlending() {
        InstanceInfoBlendEXT state = EntityScene.blendState(0xFFFFFFFF, true, VK_COMPARE_OP_GREATER, 25);
        assertEquals(1, state.alphaBlendEnabled);
        assertEquals(VK_BLEND_FACTOR_SRC_ALPHA, state.srcColorBlendFactor);
        assertEquals(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, state.dstColorBlendFactor);
        assertEquals(VK_BLEND_OP_ADD, state.colorBlendOp);
        assertEquals(RT_TEXTURE_ARG_TEXTURE, state.textureAlphaArg1Source);
        assertEquals(RT_TEXTURE_ARG_TFACTOR, state.textureAlphaArg2Source);
        assertEquals(RT_TEX_OP_MODULATE, state.textureAlphaOperation);
        assertEquals(1, state.alphaTestEnabled);
        assertEquals(25, state.alphaTestReferenceValue & 255);
    }

    @Test public void disabledAlphaTestStaysDisabledOnOpaqueAndBlendedDraws() {
        for (boolean blend : new boolean[] {false, true}) {
            InstanceInfoBlendEXT state = EntityScene.blendState(0xFFFFFFFF, blend, VK_COMPARE_OP_ALWAYS, 0);
            assertEquals(0, state.alphaTestEnabled);
            assertEquals(VK_COMPARE_OP_ALWAYS, state.alphaTestCompareOp);
            assertEquals(blend ? 1 : 0, state.alphaBlendEnabled);
        }
    }

    @Test public void zeroReferenceDoesNotDisableNeverOrEqualComparisons() {
        InstanceInfoBlendEXT never = EntityScene.blendState(0xFFFFFFFF, false, VK_COMPARE_OP_NEVER, 0);
        InstanceInfoBlendEXT equal = EntityScene.blendState(0xFFFFFFFF, false, VK_COMPARE_OP_EQUAL, 255);
        assertEquals(1, never.alphaTestEnabled);
        assertEquals(VK_COMPARE_OP_NEVER, never.alphaTestCompareOp);
        assertEquals(1, equal.alphaTestEnabled);
        assertEquals(VK_COMPARE_OP_EQUAL, equal.alphaTestCompareOp);
        assertEquals(255, equal.alphaTestReferenceValue & 255);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;

/** Exact fixed-function alpha state for the supported ordinary alpha-blended passes. */
final class EffectBlend {
    private EffectBlend() {}

    static long key(boolean blend, int source, int destination, int equation,
                    int sourceAlpha, int destinationAlpha, int alphaEquation,
                    boolean alphaTest, int compare, int reference) {
        // Premultiplied, additive and shader-specific effects need separate material semantics.
        if (blend && (source != 770 || destination != 771 || equation != 32774
                || alphaEquation != 32774 || factor(sourceAlpha) < 0 || factor(destinationAlpha) < 0)) return -1;
        if (alphaTest && (compare < 512 || compare > 519)) return -1;
        int op = alphaTest ? compare - 512 : 7;
        return (blend ? 1L : 0) | (long) op << 1 | (long) Math.max(0, Math.min(255, reference)) << 4
                | (long) (blend ? factor(sourceAlpha) : 1) << 12
                | (long) (blend ? factor(destinationAlpha) : 0) << 16;
    }

    static InstanceInfoBlendEXT extension(long key) {
        InstanceInfoBlendEXT ext = InstanceInfoBlendEXT.textureTimesVertexColor();
        ext.alphaBlendEnabled = (int) key & 1;
        ext.alphaTestCompareOp = (int) (key >> 1) & 7;
        ext.alphaTestEnabled = ext.alphaTestCompareOp == 7 ? 0 : 1;
        ext.alphaTestReferenceValue = (byte) (key >> 4);
        if (ext.alphaBlendEnabled != 0) {
            ext.srcColorBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_SRC_ALPHA;
            ext.dstColorBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            ext.srcAlphaBlendFactor = (int) (key >> 12) & 15;
            ext.dstAlphaBlendFactor = (int) (key >> 16) & 15;
        }
        return ext;
    }

    private static int factor(int gl) {
        switch (gl) {
            case 0: return 0;
            case 1: return 1;
            case 770: return 6;
            case 771: return 7;
            default: return -1;
        }
    }
}

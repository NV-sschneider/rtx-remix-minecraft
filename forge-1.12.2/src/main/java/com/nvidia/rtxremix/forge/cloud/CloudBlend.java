/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.cloud;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;

/** The fixed-function combinations used by vanilla clouds and common alpha/additive sky layers. */
final class CloudBlend {
    private CloudBlend() {}

    static long key(boolean enabled, int source, int destination, int equation, int sourceAlpha, int destinationAlpha,
            int alphaEquation, boolean alpha, int compare, int reference, boolean textured) {
        if (enabled && (factor(source) < 0 || factor(destination) < 0 || factor(sourceAlpha) < 0
                || factor(destinationAlpha) < 0 || equation != 32774 || alphaEquation != 32774)) return -1;
        if (alpha && (compare < 512 || compare > 519)) return -1;
        return (enabled ? 1L : 0) | (long) (alpha ? compare - 512 : 7) << 1
                | (long) Math.max(0, Math.min(255, reference)) << 4
                | (long) (enabled ? factor(source) : 1) << 12 | (long) (enabled ? factor(destination) : 0) << 16
                | (long) (enabled ? factor(sourceAlpha) : 1) << 20 | (long) (enabled ? factor(destinationAlpha) : 0) << 24
                | (textured ? 1L << 28 : 0);
    }

    static InstanceInfoBlendEXT extension(long key) {
        InstanceInfoBlendEXT ext = InstanceInfoBlendEXT.textureTimesVertexColor();
        ext.alphaBlendEnabled = (int) key & 1;
        ext.alphaTestCompareOp = (int) (key >> 1) & 7;
        ext.alphaTestEnabled = ext.alphaTestCompareOp == 7 ? 0 : 1;
        ext.alphaTestReferenceValue = (byte) (key >> 4);
        ext.srcColorBlendFactor = (int) (key >> 12) & 15;
        ext.dstColorBlendFactor = (int) (key >> 16) & 15;
        ext.srcAlphaBlendFactor = (int) (key >> 20) & 15;
        ext.dstAlphaBlendFactor = (int) (key >> 24) & 15;
        if ((key & 1L << 28) == 0) {
            ext.textureColorArg1Source = ext.textureAlphaArg1Source = InstanceInfoBlendEXT.RT_TEXTURE_ARG_VERTEX_COLOR;
            ext.textureColorOperation = ext.textureAlphaOperation = InstanceInfoBlendEXT.RT_TEX_OP_SELECT_ARG1;
        }
        return ext;
    }

    private static int factor(int gl) {
        switch (gl) { case 0: return 0; case 1: return 1; case 770: return 6; case 771: return 7; default: return -1; }
    }
}

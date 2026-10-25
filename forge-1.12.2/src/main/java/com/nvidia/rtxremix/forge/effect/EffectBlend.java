/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.forge.texture.SpriteOpacity;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;

/** Supported fixed-function surfaces, including the alpha approximation of vanilla's crack decal. */
final class EffectBlend {
    private static final long DAMAGE = 1L << 20;
    private static final long ADDITIVE_ALPHA = 1L << 21;
    private static final long ADDITIVE = 1L << 22;
    private EffectBlend() {}

    static long key(boolean blend, int source, int destination, int equation,
                    int sourceAlpha, int destinationAlpha, int alphaEquation,
                    boolean alphaTest, int compare, int reference) {
        return key(false, blend, source, destination, equation, sourceAlpha, destinationAlpha,
                alphaEquation, alphaTest, compare, reference);
    }

    static long key(boolean damage, boolean blend, int source, int destination, int equation,
                    int sourceAlpha, int destinationAlpha, int alphaEquation,
                    boolean alphaTest, int compare, int reference) {
        // Minecraft's crack pass uses DST_COLOR + SRC_COLOR (2 * source * destination).
        // Remix has no multiplicative surface material. DamageAtlas converts its
        // grayscale darkening into black alpha coverage, including source-alpha
        // rejection. Unrelated multiplies and custom alpha tests stay rejected.
        if (damage) {
            if (!blend || source != 774 || destination != 768 || equation != 32774
                    || sourceAlpha != 1 || destinationAlpha != 0 || alphaEquation != 32774
                    || !alphaTest || compare != 516 || reference != 26) return -1;
            long ordinary = key(false, true, 770, 771, equation, sourceAlpha, destinationAlpha,
                    alphaEquation, alphaTest, compare, reference);
            return ordinary < 0 ? -1 : ordinary | DAMAGE;
        }
        // ONE / ONE_MINUS_SRC_ALPHA is ambiguous to the runtime's legacy blend
        // translator. Do not silently reinterpret premultiplied albedo as emission.
        long mode = source == 770 && destination == 1 ? ADDITIVE_ALPHA
                : source == 1 && destination == 1 ? ADDITIVE : 0;
        if (blend && (mode == 0 && (source != 770 || destination != 771) || equation != 32774
                || alphaEquation != 32774 || factor(sourceAlpha) < 0 || factor(destinationAlpha) < 0)) return -1;
        if (alphaTest && (compare < 512 || compare > 519)) return -1;
        int op = alphaTest ? compare - 512 : 7;
        return (blend ? 1L | mode : 0) | (long) op << 1 | (long) Math.max(0, Math.min(255, reference)) << 4
                | (long) (blend ? factor(sourceAlpha) : 1) << 12
                | (long) (blend ? factor(destinationAlpha) : 0) << 16;
    }

    static boolean emissive(long key) { return (key & (ADDITIVE_ALPHA | ADDITIVE)) != 0; }

    static long opaquePlacement(long key, int coverage) {
        // The surrounding particle pass enables blending even for a fully
        // opaque or cutout block model. Keep fractional alpha and the alpha test.
        if (key < 0 || (key & DAMAGE) != 0 || emissive(key)) return key;
        return SpriteOpacity.unblended(coverage, (int) (key >> 1) & 7, (int) (key >> 4) & 255) ? key & ~1L : key;
    }

    static InstanceInfoBlendEXT extension(long key) {
        InstanceInfoBlendEXT ext = InstanceInfoBlendEXT.textureTimesVertexColor();
        ext.alphaBlendEnabled = (int) key & 1;
        ext.alphaTestCompareOp = (int) (key >> 1) & 7;
        ext.alphaTestEnabled = ext.alphaTestCompareOp == 7 ? 0 : 1;
        ext.alphaTestReferenceValue = (byte) (key >> 4);
        if ((key & DAMAGE) != 0) ext.alphaTestReferenceValue = 0;
        if (ext.alphaBlendEnabled != 0) {
            ext.srcColorBlendFactor = (key & ADDITIVE) != 0 ? InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE
                    : InstanceInfoBlendEXT.VK_BLEND_FACTOR_SRC_ALPHA;
            ext.dstColorBlendFactor = emissive(key) ? InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE
                    : InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
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

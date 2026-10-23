/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Instance blend extension for controlling texture/vertex color blending.
 * All blend factor, blend op, and compare op fields use Vulkan enum values
 * (VkBlendFactor, VkBlendOp, VkCompareOp).
 */
public class InstanceInfoBlendEXT {

    // VkCompareOp
    public static final int VK_COMPARE_OP_NEVER            = 0;
    public static final int VK_COMPARE_OP_LESS             = 1;
    public static final int VK_COMPARE_OP_EQUAL            = 2;
    public static final int VK_COMPARE_OP_LESS_OR_EQUAL    = 3;
    public static final int VK_COMPARE_OP_GREATER          = 4;
    public static final int VK_COMPARE_OP_NOT_EQUAL        = 5;
    public static final int VK_COMPARE_OP_GREATER_OR_EQUAL = 6;
    public static final int VK_COMPARE_OP_ALWAYS           = 7;

    // VkBlendFactor
    public static final int VK_BLEND_FACTOR_ZERO                = 0;
    public static final int VK_BLEND_FACTOR_ONE                 = 1;
    public static final int VK_BLEND_FACTOR_SRC_ALPHA           = 6;
    public static final int VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA = 7;

    // VkBlendOp
    public static final int VK_BLEND_OP_ADD = 0;

    // RtTextureArgSource
    public static final int RT_TEXTURE_ARG_NONE         = 0;
    public static final int RT_TEXTURE_ARG_TEXTURE      = 1;
    public static final int RT_TEXTURE_ARG_VERTEX_COLOR = 2;
    public static final int RT_TEXTURE_ARG_TFACTOR      = 3;

    // DxvkRtTextureOperation
    public static final int RT_TEX_OP_DISABLE         = 0;
    public static final int RT_TEX_OP_SELECT_ARG1     = 1;
    public static final int RT_TEX_OP_SELECT_ARG2     = 2;
    public static final int RT_TEX_OP_MODULATE        = 3;
    public static final int RT_TEX_OP_MODULATE2X      = 4;
    public static final int RT_TEX_OP_MODULATE4X      = 5;
    public static final int RT_TEX_OP_ADD             = 6;
    public static final int RT_TEX_OP_FORCE_MODULATE2X = 7;

    public int alphaTestEnabled = 0;
    public byte alphaTestReferenceValue = 0;
    public int alphaTestCompareOp = VK_COMPARE_OP_ALWAYS;
    public int alphaBlendEnabled = 0;
    public int srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
    public int dstColorBlendFactor = VK_BLEND_FACTOR_ZERO;
    public int colorBlendOp = VK_BLEND_OP_ADD;
    public int textureColorArg1Source = RT_TEXTURE_ARG_TEXTURE;
    public int textureColorArg2Source = RT_TEXTURE_ARG_NONE;
    public int textureColorOperation = RT_TEX_OP_MODULATE;
    public int textureAlphaArg1Source = RT_TEXTURE_ARG_TEXTURE;
    public int textureAlphaArg2Source = RT_TEXTURE_ARG_NONE;
    public int textureAlphaOperation = RT_TEX_OP_SELECT_ARG1;
    public int tFactor = 0xFFFFFFFF;
    public int isTextureFactorBlend = 0;
    public int srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
    public int dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
    public int alphaBlendOp = VK_BLEND_OP_ADD;
    public int writeMask = 0xF;
    public int isVertexColorBakedLighting = 0;

    /** Cached C-heap pointer for this extension. */
    public long nativePtr;

    public InstanceInfoBlendEXT() {}

    /**
     * Allocates a C-heap copy of this extension and returns the pointer.
     * @param chainNext pNext to chain after this extension (0 = none)
     * @return C-heap pointer to the allocated struct
     */
    public long allocNative(long chainNext) {
        nativePtr = RemixBridge.allocBlendExt(
                alphaTestEnabled != 0, alphaTestReferenceValue & 0xFF, alphaTestCompareOp,
                alphaBlendEnabled != 0,
                srcColorBlendFactor, dstColorBlendFactor, colorBlendOp,
                textureColorArg1Source, textureColorArg2Source, textureColorOperation,
                textureAlphaArg1Source, textureAlphaArg2Source, textureAlphaOperation,
                tFactor, isTextureFactorBlend != 0,
                srcAlphaBlendFactor, dstAlphaBlendFactor, alphaBlendOp,
                writeMask, isVertexColorBakedLighting != 0,
                chainNext);
        return nativePtr;
    }

    public void freeNative() {
        if (nativePtr != 0) {
            RemixBridge.freeExt(nativePtr);
            nativePtr = 0;
        }
    }

    /**
     * Fully opaque: texture * vertex color, no alpha test, no blend.
     */
    public static InstanceInfoBlendEXT textureTimesVertexColor() {
        InstanceInfoBlendEXT ext = new InstanceInfoBlendEXT();
        ext.textureColorArg1Source = RT_TEXTURE_ARG_TEXTURE;
        ext.textureColorArg2Source = RT_TEXTURE_ARG_VERTEX_COLOR;
        ext.textureColorOperation = RT_TEX_OP_MODULATE;
        ext.textureAlphaArg1Source = RT_TEXTURE_ARG_TEXTURE;
        ext.textureAlphaArg2Source = RT_TEXTURE_ARG_VERTEX_COLOR;
        ext.textureAlphaOperation = RT_TEX_OP_MODULATE;
        return ext;
    }

    /**
     * Alpha-tested cutout: texture * vertex color, alpha test GREATER ref,
     * no alpha blend.
     */
    public static InstanceInfoBlendEXT textureTimesVertexColorAlphaTest(int referenceValue) {
        InstanceInfoBlendEXT ext = textureTimesVertexColor();
        ext.alphaTestEnabled = 1;
        ext.alphaTestCompareOp = VK_COMPARE_OP_GREATER;
        ext.alphaTestReferenceValue = (byte) referenceValue;
        return ext;
    }

    /**
     * Alpha-blended: texture * vertex color, alpha test GREATER ref,
     * standard alpha blending (SRC_ALPHA, ONE_MINUS_SRC_ALPHA).
     */
    public static InstanceInfoBlendEXT textureTimesVertexColorAlphaBlend(int alphaTestRef) {
        InstanceInfoBlendEXT ext = textureTimesVertexColor();
        ext.alphaTestEnabled = 1;
        ext.alphaTestCompareOp = VK_COMPARE_OP_GREATER;
        ext.alphaTestReferenceValue = (byte) alphaTestRef;
        ext.alphaBlendEnabled = 1;
        ext.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        ext.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        ext.colorBlendOp = VK_BLEND_OP_ADD;
        ext.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        ext.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        ext.alphaBlendOp = VK_BLEND_OP_ADD;
        return ext;
    }

    /**
     * Alpha-blended decal-style layer: color is texture * vertex color, opacity
     * comes only from the texture alpha channel.
     */
    public static InstanceInfoBlendEXT textureTimesVertexColorTextureAlphaBlend(int alphaTestRef) {
        InstanceInfoBlendEXT ext = textureTimesVertexColorAlphaBlend(alphaTestRef);
        ext.textureAlphaArg1Source = RT_TEXTURE_ARG_TEXTURE;
        ext.textureAlphaArg2Source = RT_TEXTURE_ARG_NONE;
        ext.textureAlphaOperation = RT_TEX_OP_SELECT_ARG1;
        return ext;
    }
}

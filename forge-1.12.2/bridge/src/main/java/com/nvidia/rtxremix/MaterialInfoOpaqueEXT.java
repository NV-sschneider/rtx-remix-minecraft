/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Opaque material extension information.
 * Texture fields are native handles (long) obtained from createTexture* calls.
 */
public class MaterialInfoOpaqueEXT {
    public long roughnessTexture;
    public long metallicTexture;
    public float anisotropy = 0.0f;
    public Float3D albedoConstant = new Float3D(0.2f, 0.2f, 0.2f);
    public float opacityConstant = 1.0f;
    public float roughnessConstant = 0.5f;
    public float metallicConstant = 0.0f;
    public int thinFilmThickness_hasvalue = 0;
    public float thinFilmThickness_value = 200.0f;
    public int alphaIsThinFilmThickness = 0;
    public long heightTexture;
    public float displaceIn = 0.0f;
    public int useDrawCallAlphaState = 1;
    public int blendType_hasvalue = 0;
    public int blendType_value = 0;
    public int invertedBlend = 0;
    public int alphaTestType = 7;
    public byte alphaReferenceValue = 0;
    public float displaceOut = 0.0f;

    public MaterialInfoOpaqueSubsurfaceEXT subsurfaceExt;

    public MaterialInfoOpaqueEXT() {}

    public static final int BLEND_TYPE_ALPHA = 0;
    public static final int BLEND_TYPE_ALPHA_EMISSIVE = 1;
    public static final int BLEND_TYPE_REVERSE_ALPHA_EMISSIVE = 2;
    public static final int BLEND_TYPE_COLOR = 3;
    public static final int BLEND_TYPE_COLOR_EMISSIVE = 4;
    public static final int BLEND_TYPE_REVERSE_COLOR_EMISSIVE = 5;
    public static final int BLEND_TYPE_EMISSIVE = 6;
    public static final int BLEND_TYPE_MULTIPLICATIVE = 7;
    public static final int BLEND_TYPE_DOUBLE_MULTIPLICATIVE = 8;
    public static final int BLEND_TYPE_REVERSE_ALPHA = 9;
    public static final int BLEND_TYPE_REVERSE_COLOR = 10;

    public static class Builder {
        private long roughnessTexture;
        private long metallicTexture;
        private long heightTexture;
        private Float3D albedoConstant = new Float3D(0.2f, 0.2f, 0.2f);
        private float opacityConstant = 1.0f;
        private float roughnessConstant = 0.5f;
        private float metallicConstant = 0.0f;
        private int alphaTestType = 7;
        private byte alphaReferenceValue = 0;
        private float displaceIn = 0.0f;
        private float displaceOut = 0.0f;
        private boolean hasBlendType = false;
        private int blendType = 0;
        private boolean useDrawCallAlphaState = false;
        private MaterialInfoOpaqueSubsurfaceEXT subsurfaceExt;

        public Builder roughnessTexture(long handle) { this.roughnessTexture = handle; return this; }
        public Builder metallicTexture(long handle) { this.metallicTexture = handle; return this; }
        public Builder heightTexture(long handle) { this.heightTexture = handle; return this; }
        public Builder displaceIn(float v) { this.displaceIn = v; return this; }
        public Builder displaceOut(float v) { this.displaceOut = v; return this; }

        public Builder subsurfaceExt(MaterialInfoOpaqueSubsurfaceEXT ext) {
            this.subsurfaceExt = ext;
            return this;
        }

        public Builder albedoConstant(float r, float g, float b) {
            this.albedoConstant = new Float3D(r, g, b);
            return this;
        }

        public Builder opacityConstant(float opacity) {
            this.opacityConstant = opacity;
            return this;
        }

        public Builder roughnessConstant(float roughness) {
            this.roughnessConstant = roughness;
            return this;
        }

        public Builder metallicConstant(float metallic) {
            this.metallicConstant = metallic;
            return this;
        }

        /**
         * Sets the blend type for this material.
         * Use BLEND_TYPE_* constants (e.g., BLEND_TYPE_ALPHA = 0).
         */
        public Builder blendType(int type) {
            this.hasBlendType = true;
            this.blendType = type;
            return this;
        }

        /**
         * Controls whether this opaque material is classified as alpha blended.
         *
         * <p>The C API field is named {@code blendType_hasvalue}, but dxvk-remix
         * consumes it as "blend enabled" and reads {@code blendType_value} only
         * when this flag is true.
         */
        public Builder blendEnabled(boolean enable) {
            this.hasBlendType = enable;
            return this;
        }

        /**
         * When true, alpha test and blend state come from {@link InstanceInfoBlendEXT}
         * on the instance (draw call state). When false, the material's own alphaTestType
         * and blendType control classification.
         */
        public Builder useDrawCallAlphaState(boolean enable) {
            this.useDrawCallAlphaState = enable;
            return this;
        }

        /**
         * Sets alpha test type using VkCompareOp values:
         * 0=NEVER, 1=LESS, 2=EQUAL, 3=LESS_OR_EQUAL, 4=GREATER,
         * 5=NOT_EQUAL, 6=GREATER_OR_EQUAL, 7=ALWAYS.
         */
        public Builder alphaTestType(int type) {
            this.alphaTestType = type;
            return this;
        }

        /**
         * Sets alpha reference value (0-255) for alpha testing.
         * Pixels with alpha below this value are discarded when alphaTestType is set.
         */
        public Builder alphaReferenceValue(int value) {
            this.alphaReferenceValue = (byte) value;
            return this;
        }

        public MaterialInfoOpaqueEXT build() {
            MaterialInfoOpaqueEXT ext = new MaterialInfoOpaqueEXT();
            ext.roughnessTexture = this.roughnessTexture;
            ext.metallicTexture = this.metallicTexture;
            ext.heightTexture = this.heightTexture;
            ext.albedoConstant = this.albedoConstant;
            ext.opacityConstant = this.opacityConstant;
            ext.roughnessConstant = this.roughnessConstant;
            ext.metallicConstant = this.metallicConstant;
            ext.alphaTestType = this.alphaTestType;
            ext.alphaReferenceValue = this.alphaReferenceValue;
            ext.displaceIn = this.displaceIn;
            ext.displaceOut = this.displaceOut;
            ext.useDrawCallAlphaState = this.useDrawCallAlphaState ? 1 : 0;
            ext.blendType_hasvalue = this.hasBlendType ? 1 : 0;
            ext.blendType_value = this.blendType;
            ext.subsurfaceExt = this.subsurfaceExt;
            return ext;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

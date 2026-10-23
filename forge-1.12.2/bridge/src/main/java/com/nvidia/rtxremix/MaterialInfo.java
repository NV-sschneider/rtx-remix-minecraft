/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Material information.
 * Texture fields are native handles (long) obtained from createTexture* calls.
 */
public class MaterialInfo {
    public static final byte WRAP_MODE_CLAMP = 0;
    public static final byte WRAP_MODE_REPEAT = 1;
    public static final byte WRAP_MODE_MIRRORED_REPEAT = 2;

    public long hash;
    public long albedoTexture;
    public long normalTexture;
    public long tangentTexture;
    public long emissiveTexture;
    public float emissiveIntensity = 0.0f;
    public Float3D emissiveColorConstant = new Float3D(0, 0, 0);
    public byte spriteSheetRow = 1;
    public byte spriteSheetCol = 1;
    public byte spriteSheetFps = 0;
    public byte filterMode = 1;
    public byte wrapModeU = WRAP_MODE_REPEAT;
    public byte wrapModeV = WRAP_MODE_REPEAT;

    public MaterialInfoOpaqueEXT opaqueExt;
    public MaterialInfoTranslucentEXT translucentExt;

    public MaterialInfo() {}

    public MaterialInfo(long hash) {
        this.hash = hash;
    }

    public static class Builder {
        private long hash;
        private long albedoTexture;
        private long normalTexture;
        private long tangentTexture;
        private long emissiveTexture;
        private float emissiveIntensity = 0.0f;
        private Float3D emissiveColorConstant = new Float3D(0, 0, 0);
        private byte filterMode = 1;
        private byte spriteSheetRow = 1;
        private byte spriteSheetCol = 1;
        private byte spriteSheetFps = 0;
        private byte wrapModeU = WRAP_MODE_REPEAT;
        private byte wrapModeV = WRAP_MODE_REPEAT;
        private MaterialInfoOpaqueEXT opaqueExt;
        private MaterialInfoTranslucentEXT translucentExt;

        public Builder hash(long hash) {
            this.hash = hash;
            return this;
        }

        /**
         * Sets the albedo texture handle.
         * @param textureHandle Handle from createTextureFromMemory or createTextureFromFile
         */
        public Builder albedoTexture(long textureHandle) {
            this.albedoTexture = textureHandle;
            return this;
        }

        /**
         * Sets the normal texture handle.
         * @param textureHandle Handle from createTextureFromMemory or createTextureFromFile
         */
        public Builder normalTexture(long textureHandle) {
            this.normalTexture = textureHandle;
            return this;
        }

        /**
         * Sets the tangent texture handle.
         * @param textureHandle Handle from createTextureFromMemory or createTextureFromFile
         */
        public Builder tangentTexture(long textureHandle) {
            this.tangentTexture = textureHandle;
            return this;
        }

        /**
         * Sets the emissive texture handle.
         * @param textureHandle Handle from createTextureFromMemory or createTextureFromFile
         */
        public Builder emissiveTexture(long textureHandle) {
            this.emissiveTexture = textureHandle;
            return this;
        }

        public Builder emissiveIntensity(float intensity) {
            this.emissiveIntensity = intensity;
            return this;
        }

        public Builder emissiveColorConstant(float r, float g, float b) {
            this.emissiveColorConstant = new Float3D(r, g, b);
            return this;
        }

        public Builder opaqueExt(MaterialInfoOpaqueEXT ext) {
            this.opaqueExt = ext;
            return this;
        }

        public Builder translucentExt(MaterialInfoTranslucentEXT ext) {
            this.translucentExt = ext;
            return this;
        }

        /**
         * Sets the texture filter mode.
         * @param mode 0 = Nearest (for pixel art), 1 = Linear (default)
         */
        public Builder filterMode(int mode) {
            this.filterMode = (byte) mode;
            return this;
        }

        public Builder wrapMode(int u, int v) {
            this.wrapModeU = (byte) u;
            this.wrapModeV = (byte) v;
            return this;
        }

        public Builder spriteSheetRow(int rows) {
            this.spriteSheetRow = (byte) rows;
            return this;
        }

        public Builder spriteSheetCol(int cols) {
            this.spriteSheetCol = (byte) cols;
            return this;
        }

        public Builder spriteSheetFps(int fps) {
            this.spriteSheetFps = (byte) fps;
            return this;
        }

        public MaterialInfo build() {
            MaterialInfo info = new MaterialInfo();
            info.hash = this.hash;
            info.albedoTexture = this.albedoTexture;
            info.normalTexture = this.normalTexture;
            info.tangentTexture = this.tangentTexture;
            info.emissiveTexture = this.emissiveTexture;
            info.emissiveIntensity = this.emissiveIntensity;
            info.emissiveColorConstant = this.emissiveColorConstant;
            info.filterMode = this.filterMode;
            info.wrapModeU = this.wrapModeU;
            info.wrapModeV = this.wrapModeV;
            info.spriteSheetRow = this.spriteSheetRow;
            info.spriteSheetCol = this.spriteSheetCol;
            info.spriteSheetFps = this.spriteSheetFps;
            info.opaqueExt = this.opaqueExt;
            info.translucentExt = this.translucentExt;
            return info;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

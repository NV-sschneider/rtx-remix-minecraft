/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Distant (directional) light extension.
 */
public class LightInfoDistantEXT {
    public Float3D direction = new Float3D(0, -1, 0);
    public float angularDiameterDegrees = 0.5f;
    public float volumetricRadianceScale = 1.0f;

    public LightInfoDistantEXT() {}

    public static class Builder {
        private Float3D direction = new Float3D(0, -1, 0);
        private float angularDiameterDegrees = 0.5f;
        private float volumetricRadianceScale = 1.0f;

        public Builder direction(float x, float y, float z) {
            this.direction = new Float3D(x, y, z);
            return this;
        }

        public Builder angularDiameterDegrees(float degrees) {
            this.angularDiameterDegrees = degrees;
            return this;
        }

        public Builder volumetricRadianceScale(float scale) {
            this.volumetricRadianceScale = scale;
            return this;
        }

        public LightInfoDistantEXT build() {
            LightInfoDistantEXT ext = new LightInfoDistantEXT();
            ext.direction = this.direction;
            ext.angularDiameterDegrees = this.angularDiameterDegrees;
            ext.volumetricRadianceScale = this.volumetricRadianceScale;
            return ext;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

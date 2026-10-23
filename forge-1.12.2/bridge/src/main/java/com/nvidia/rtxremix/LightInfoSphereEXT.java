/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Sphere light extension.
 * Shaping parameters are flattened from LightInfoLightShaping.
 */
public class LightInfoSphereEXT {
    public Float3D position = new Float3D(0, 0, 0);
    public float radius = 0.1f;
    public boolean shaping_hasvalue = false;
    public Float3D shaping_direction = new Float3D();
    public float shaping_coneAngleDegrees;
    public float shaping_coneSoftness;
    public float shaping_focusExponent;
    public float volumetricRadianceScale = 1.0f;

    public LightInfoSphereEXT() {}

    public static class Builder {
        private Float3D position = new Float3D(0, 0, 0);
        private float radius = 0.1f;
        private boolean shapingEnabled = false;
        private Float3D shapingDirection = new Float3D();
        private float shapingConeAngleDegrees;
        private float shapingConeSoftness;
        private float shapingFocusExponent;
        private float volumetricRadianceScale = 1.0f;

        public Builder position(float x, float y, float z) {
            this.position = new Float3D(x, y, z);
            return this;
        }

        public Builder radius(float radius) {
            this.radius = radius;
            return this;
        }

        public Builder shaping(LightInfoLightShaping shaping) {
            this.shapingEnabled = true;
            this.shapingDirection = shaping.direction;
            this.shapingConeAngleDegrees = shaping.coneAngleDegrees;
            this.shapingConeSoftness = shaping.coneSoftness;
            this.shapingFocusExponent = shaping.focusExponent;
            return this;
        }

        public Builder volumetricRadianceScale(float scale) {
            this.volumetricRadianceScale = scale;
            return this;
        }

        public LightInfoSphereEXT build() {
            LightInfoSphereEXT ext = new LightInfoSphereEXT();
            ext.position = this.position;
            ext.radius = this.radius;
            ext.shaping_hasvalue = this.shapingEnabled;
            ext.shaping_direction = this.shapingDirection;
            ext.shaping_coneAngleDegrees = this.shapingConeAngleDegrees;
            ext.shaping_coneSoftness = this.shapingConeSoftness;
            ext.shaping_focusExponent = this.shapingFocusExponent;
            ext.volumetricRadianceScale = this.volumetricRadianceScale;
            return ext;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

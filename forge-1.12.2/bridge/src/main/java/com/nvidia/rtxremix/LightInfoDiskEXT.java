/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Disk light extension.
 * Shaping parameters are flattened from LightInfoLightShaping.
 */
public class LightInfoDiskEXT {
    public Float3D position = new Float3D(0, 0, 0);
    public Float3D xAxis = new Float3D(1, 0, 0);
    public float xRadius = 0.5f;
    public Float3D yAxis = new Float3D(0, 1, 0);
    public float yRadius = 0.5f;
    public Float3D direction = new Float3D(0, 0, -1);
    public boolean shaping_hasvalue = false;
    public Float3D shaping_direction = new Float3D();
    public float shaping_coneAngleDegrees;
    public float shaping_coneSoftness;
    public float shaping_focusExponent;
    public float volumetricRadianceScale = 1.0f;

    public LightInfoDiskEXT() {}

    public static class Builder {
        private Float3D position = new Float3D(0, 0, 0);
        private Float3D xAxis = new Float3D(1, 0, 0);
        private float xRadius = 0.5f;
        private Float3D yAxis = new Float3D(0, 1, 0);
        private float yRadius = 0.5f;
        private Float3D direction = new Float3D(0, 0, -1);
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

        public Builder xAxis(float x, float y, float z) {
            this.xAxis = new Float3D(x, y, z);
            return this;
        }

        public Builder xRadius(float radius) {
            this.xRadius = radius;
            return this;
        }

        public Builder yAxis(float x, float y, float z) {
            this.yAxis = new Float3D(x, y, z);
            return this;
        }

        public Builder yRadius(float radius) {
            this.yRadius = radius;
            return this;
        }

        public Builder direction(float x, float y, float z) {
            this.direction = new Float3D(x, y, z);
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

        public LightInfoDiskEXT build() {
            LightInfoDiskEXT ext = new LightInfoDiskEXT();
            ext.position = this.position;
            ext.xAxis = this.xAxis;
            ext.xRadius = this.xRadius;
            ext.yAxis = this.yAxis;
            ext.yRadius = this.yRadius;
            ext.direction = this.direction;
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

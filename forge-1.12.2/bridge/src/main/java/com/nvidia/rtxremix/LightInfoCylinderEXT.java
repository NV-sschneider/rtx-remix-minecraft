/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Cylinder light extension.
 */
public class LightInfoCylinderEXT {
    public Float3D position = new Float3D(0, 0, 0);
    public float radius = 0.1f;
    public Float3D axis = new Float3D(0, 1, 0);
    public float axisLength = 1.0f;
    public float volumetricRadianceScale = 1.0f;

    public LightInfoCylinderEXT() {}

    public static class Builder {
        private Float3D position = new Float3D(0, 0, 0);
        private float radius = 0.1f;
        private Float3D axis = new Float3D(0, 1, 0);
        private float axisLength = 1.0f;
        private float volumetricRadianceScale = 1.0f;

        public Builder position(float x, float y, float z) {
            this.position = new Float3D(x, y, z);
            return this;
        }

        public Builder radius(float radius) {
            this.radius = radius;
            return this;
        }

        public Builder axis(float x, float y, float z) {
            this.axis = new Float3D(x, y, z);
            return this;
        }

        public Builder axisLength(float axisLength) {
            this.axisLength = axisLength;
            return this;
        }

        public Builder volumetricRadianceScale(float scale) {
            this.volumetricRadianceScale = scale;
            return this;
        }

        public LightInfoCylinderEXT build() {
            LightInfoCylinderEXT ext = new LightInfoCylinderEXT();
            ext.position = this.position;
            ext.radius = this.radius;
            ext.axis = this.axis;
            ext.axisLength = this.axisLength;
            ext.volumetricRadianceScale = this.volumetricRadianceScale;
            return ext;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

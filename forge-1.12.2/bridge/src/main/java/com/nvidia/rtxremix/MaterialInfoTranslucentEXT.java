/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Translucent material extension information.
 * Used for materials like water, glass, ice that have refraction and transmittance.
 */
public class MaterialInfoTranslucentEXT {
    public long transmittanceTexture;
    public float refractiveIndex = 1.33f;
    public Float3D transmittanceColor = new Float3D(0.9f, 0.95f, 1.0f);
    public float transmittanceMeasurementDistance = 10.0f;
    public int thinWallThickness_hasvalue = 0;
    public float thinWallThickness_value = 0.001f;
    public int useDiffuseLayer = 0;

    public MaterialInfoTranslucentEXT() {}

    public static class Builder {
        private long transmittanceTexture = 0;
        private float refractiveIndex = 1.33f;
        private Float3D transmittanceColor = new Float3D(0.9f, 0.95f, 1.0f);
        private float transmittanceMeasurementDistance = 10.0f;
        private boolean useThinWall = false;
        private float thinWallThickness = 0.001f;
        private boolean useDiffuseLayer = false;

        /**
         * Sets the refractive index of the material.
         * Common values: Air=1.0, Water=1.33, Glass=1.5, Diamond=2.4
         */
        public Builder refractiveIndex(float ior) {
            this.refractiveIndex = ior;
            return this;
        }

        /**
         * Sets the transmittance color (what light passes through).
         * Values close to 1.0 = clearer, lower values = more colored tint.
         */
        public Builder transmittanceColor(float r, float g, float b) {
            this.transmittanceColor = new Float3D(r, g, b);
            return this;
        }

        /**
         * Sets the distance at which transmittance color was measured.
         * Shorter distance = denser/murkier medium.
         */
        public Builder transmittanceMeasurementDistance(float meters) {
            this.transmittanceMeasurementDistance = meters;
            return this;
        }

        public Builder transmittanceTexture(long textureHandle) {
            this.transmittanceTexture = textureHandle;
            return this;
        }

        /**
         * Enables thin wall mode for thin translucent surfaces like glass panes.
         * @param thickness The thickness in world units
         */
        public Builder thinWall(float thickness) {
            this.useThinWall = true;
            this.thinWallThickness = thickness;
            return this;
        }

        /**
         * Enables the diffuse layer on top of translucent material.
         * When enabled, the albedo texture acts as a diffuse overlay.
         */
        public Builder useDiffuseLayer(boolean enable) {
            this.useDiffuseLayer = enable;
            return this;
        }

        public MaterialInfoTranslucentEXT build() {
            MaterialInfoTranslucentEXT ext = new MaterialInfoTranslucentEXT();
            ext.transmittanceTexture = this.transmittanceTexture;
            ext.refractiveIndex = this.refractiveIndex;
            ext.transmittanceColor = this.transmittanceColor;
            ext.transmittanceMeasurementDistance = this.transmittanceMeasurementDistance;
            ext.thinWallThickness_hasvalue = this.useThinWall ? 1 : 0;
            ext.thinWallThickness_value = this.thinWallThickness;
            ext.useDiffuseLayer = this.useDiffuseLayer ? 1 : 0;
            return ext;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

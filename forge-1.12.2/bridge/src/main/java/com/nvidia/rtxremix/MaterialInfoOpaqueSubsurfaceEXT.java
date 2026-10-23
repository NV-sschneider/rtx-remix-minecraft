/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Opaque subsurface material extension.
 * Enables subsurface scattering for materials like skin, wax, marble, clouds, leaves.
 */
public class MaterialInfoOpaqueSubsurfaceEXT {
    public long subsurfaceTransmittanceTexture;
    public long subsurfaceThicknessTexture;
    public long subsurfaceSingleScatteringAlbedoTexture;
    public Float3D subsurfaceTransmittanceColor = new Float3D(0.5f, 0.5f, 0.5f);
    public float subsurfaceMeasurementDistance = 1.0f;
    public Float3D subsurfaceSingleScatteringAlbedo = new Float3D(0.5f, 0.5f, 0.5f);
    public float subsurfaceVolumetricAnisotropy = 0.0f;
    public int subsurfaceDiffusionProfile = 0;
    public Float3D subsurfaceRadius = new Float3D(1.0f, 1.0f, 1.0f);
    public float subsurfaceRadiusScale = 1.0f;
    public float subsurfaceMaxSampleRadius = 1.0f;
    public long subsurfaceRadiusTexture;

    public MaterialInfoOpaqueSubsurfaceEXT() {}

    public static class Builder {
        private long transmittanceTexture;
        private long thicknessTexture;
        private long singleScatteringAlbedoTexture;
        private long radiusTexture;
        private Float3D subsurfaceTransmittanceColor = new Float3D(0.5f, 0.5f, 0.5f);
        private float subsurfaceMeasurementDistance = 1.0f;
        private Float3D subsurfaceSingleScatteringAlbedo = new Float3D(0.5f, 0.5f, 0.5f);
        private float subsurfaceVolumetricAnisotropy = 0.0f;
        private int subsurfaceDiffusionProfile = 0;
        private Float3D subsurfaceRadius = new Float3D(1.0f, 1.0f, 1.0f);
        private float subsurfaceRadiusScale = 1.0f;
        private float subsurfaceMaxSampleRadius = 1.0f;

        public Builder transmittanceTexture(long handle) { this.transmittanceTexture = handle; return this; }
        public Builder thicknessTexture(long handle) { this.thicknessTexture = handle; return this; }
        public Builder singleScatteringAlbedoTexture(long handle) { this.singleScatteringAlbedoTexture = handle; return this; }
        public Builder radiusTexture(long handle) { this.radiusTexture = handle; return this; }

        public Builder transmittanceColor(float r, float g, float b) {
            this.subsurfaceTransmittanceColor = new Float3D(r, g, b);
            return this;
        }

        public Builder measurementDistance(float distance) {
            this.subsurfaceMeasurementDistance = distance;
            return this;
        }

        public Builder singleScatteringAlbedo(float r, float g, float b) {
            this.subsurfaceSingleScatteringAlbedo = new Float3D(r, g, b);
            return this;
        }

        public Builder volumetricAnisotropy(float anisotropy) {
            this.subsurfaceVolumetricAnisotropy = anisotropy;
            return this;
        }

        public Builder diffusionProfile(boolean enabled) {
            this.subsurfaceDiffusionProfile = enabled ? 1 : 0;
            return this;
        }

        public Builder radius(float r, float g, float b) {
            this.subsurfaceRadius = new Float3D(r, g, b);
            return this;
        }

        public Builder radiusScale(float scale) {
            this.subsurfaceRadiusScale = scale;
            return this;
        }

        public Builder maxSampleRadius(float maxRadius) {
            this.subsurfaceMaxSampleRadius = maxRadius;
            return this;
        }

        public MaterialInfoOpaqueSubsurfaceEXT build() {
            MaterialInfoOpaqueSubsurfaceEXT ext = new MaterialInfoOpaqueSubsurfaceEXT();
            ext.subsurfaceTransmittanceTexture = this.transmittanceTexture;
            ext.subsurfaceThicknessTexture = this.thicknessTexture;
            ext.subsurfaceSingleScatteringAlbedoTexture = this.singleScatteringAlbedoTexture;
            ext.subsurfaceRadiusTexture = this.radiusTexture;
            ext.subsurfaceTransmittanceColor = this.subsurfaceTransmittanceColor;
            ext.subsurfaceMeasurementDistance = this.subsurfaceMeasurementDistance;
            ext.subsurfaceSingleScatteringAlbedo = this.subsurfaceSingleScatteringAlbedo;
            ext.subsurfaceVolumetricAnisotropy = this.subsurfaceVolumetricAnisotropy;
            ext.subsurfaceDiffusionProfile = this.subsurfaceDiffusionProfile;
            ext.subsurfaceRadius = this.subsurfaceRadius;
            ext.subsurfaceRadiusScale = this.subsurfaceRadiusScale;
            ext.subsurfaceMaxSampleRadius = this.subsurfaceMaxSampleRadius;
            return ext;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

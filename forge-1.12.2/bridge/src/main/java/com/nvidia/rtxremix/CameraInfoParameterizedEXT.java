/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Parameterized camera extension: an easier way to set up a camera
 * using position/forward/up/right vectors and projection parameters.
 */
public class CameraInfoParameterizedEXT {
    public Float3D position = new Float3D(0, 0, 0);
    public Float3D forward = new Float3D(0, 0, 1);
    public Float3D up = new Float3D(0, 1, 0);
    public Float3D right = new Float3D(1, 0, 0);
    public float fovYInDegrees = 70.0f;
    public float aspect = 16.0f / 9.0f;
    public float nearPlane = 0.1f;
    public float farPlane = 1000.0f;

    public CameraInfoParameterizedEXT() {}

    public static class Builder {
        private Float3D position = new Float3D(0, 0, 0);
        private Float3D forward = new Float3D(0, 0, 1);
        private Float3D up = new Float3D(0, 1, 0);
        private Float3D right = new Float3D(1, 0, 0);
        private float fovYInDegrees = 70.0f;
        private float aspect = 16.0f / 9.0f;
        private float nearPlane = 0.1f;
        private float farPlane = 1000.0f;

        public Builder position(float x, float y, float z) {
            this.position = new Float3D(x, y, z);
            return this;
        }

        public Builder forward(float x, float y, float z) {
            this.forward = new Float3D(x, y, z);
            return this;
        }

        public Builder up(float x, float y, float z) {
            this.up = new Float3D(x, y, z);
            return this;
        }

        public Builder right(float x, float y, float z) {
            this.right = new Float3D(x, y, z);
            return this;
        }

        public Builder fovYInDegrees(float fov) {
            this.fovYInDegrees = fov;
            return this;
        }

        public Builder aspect(float aspect) {
            this.aspect = aspect;
            return this;
        }

        public Builder nearPlane(float near) {
            this.nearPlane = near;
            return this;
        }

        public Builder farPlane(float far) {
            this.farPlane = far;
            return this;
        }

        public CameraInfoParameterizedEXT build() {
            CameraInfoParameterizedEXT ext = new CameraInfoParameterizedEXT();
            ext.position = this.position;
            ext.forward = this.forward;
            ext.up = this.up;
            ext.right = this.right;
            ext.fovYInDegrees = this.fovYInDegrees;
            ext.aspect = this.aspect;
            ext.nearPlane = this.nearPlane;
            ext.farPlane = this.farPlane;
            return ext;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

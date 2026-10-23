/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Camera information.
 */
public class CameraInfo {
    public int type = CameraType.WORLD;
    public float[] view = new float[16];
    public float[] projection = new float[16];
    public CameraInfoParameterizedEXT parameterizedExt;

    public CameraInfo() {
        setIdentityMatrix(view);
        setIdentityMatrix(projection);
    }

    private void setIdentityMatrix(float[] matrix) {
        for (int i = 0; i < 16; i++) {
            matrix[i] = 0.0f;
        }
        matrix[0] = 1.0f;
        matrix[5] = 1.0f;
        matrix[10] = 1.0f;
        matrix[15] = 1.0f;
    }

    public static class Builder {
        private int type = CameraType.WORLD;
        private CameraInfoParameterizedEXT parameterizedExt;
        private float[] view = new float[16];
        private float[] projection = new float[16];

        public Builder() {
            for (int i = 0; i < 16; i++) {
                view[i] = 0.0f;
                projection[i] = 0.0f;
            }
            view[0] = view[5] = view[10] = view[15] = 1.0f;
            projection[0] = projection[5] = projection[10] = projection[15] = 1.0f;
        }

        public Builder type(int type) {
            this.type = type;
            return this;
        }

        public Builder parameterizedExt(CameraInfoParameterizedEXT ext) {
            this.parameterizedExt = ext;
            return this;
        }

        public Builder view(float[] view) {
            if (view != null && view.length == 16) {
                System.arraycopy(view, 0, this.view, 0, 16);
            }
            return this;
        }

        public Builder projection(float[] projection) {
            if (projection != null && projection.length == 16) {
                System.arraycopy(projection, 0, this.projection, 0, 16);
            }
            return this;
        }

        public CameraInfo build() {
            CameraInfo info = new CameraInfo();
            info.type = this.type;
            System.arraycopy(this.view, 0, info.view, 0, 16);
            System.arraycopy(this.projection, 0, info.projection, 0, 16);
            info.parameterizedExt = this.parameterizedExt;
            return info;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

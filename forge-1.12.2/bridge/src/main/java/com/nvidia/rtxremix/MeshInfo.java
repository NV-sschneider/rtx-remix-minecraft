/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Mesh information: holds a hash and an array of surface descriptions.
 */
public class MeshInfo {
    public long hash;
    public MeshInfoSurfaceTriangles[] surfaces;

    public MeshInfo() {}

    public MeshInfo(long hash) {
        this.hash = hash;
    }

    public static class Builder {
        private long hash;
        private MeshInfoSurfaceTriangles[] surfaces;

        public Builder hash(long hash) {
            this.hash = hash;
            return this;
        }

        public Builder surfaces(MeshInfoSurfaceTriangles... surfaces) {
            this.surfaces = surfaces;
            return this;
        }

        public MeshInfo build() {
            MeshInfo info = new MeshInfo();
            info.hash = this.hash;
            info.surfaces = this.surfaces;
            return info;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Instance information for drawing mesh instances.
 */
public class InstanceInfo {
    public int categoryFlags = 0;
    public long mesh;
    public Transform transform = new Transform();
    public int doubleSided = 0;
    public long pNext = 0;

    public InstanceInfoBlendEXT blendExt;
    public InstanceInfoBoneTransformsEXT boneTransformsExt;
    public InstanceInfoGpuInstancingEXT gpuInstancingExt;

    public InstanceInfo() {}

    public static class Builder {
        private int categoryFlags = 0;
        private long mesh;
        private Transform transform = Transform.identity();
        private boolean doubleSided = false;
        private InstanceInfoBlendEXT blendExt;
        private InstanceInfoBoneTransformsEXT boneTransformsExt;
        private InstanceInfoGpuInstancingEXT gpuInstancingExt;

        public Builder categoryFlags(int flags) {
            this.categoryFlags = flags;
            return this;
        }

        public Builder mesh(long meshHandle) {
            this.mesh = meshHandle;
            return this;
        }

        public Builder transform(Transform transform) {
            this.transform = transform;
            return this;
        }

        public Builder doubleSided(boolean doubleSided) {
            this.doubleSided = doubleSided;
            return this;
        }

        /**
         * Adds a blend extension for texture/vertex color blending.
         */
        public Builder blendExt(InstanceInfoBlendEXT blendExt) {
            this.blendExt = blendExt;
            return this;
        }

        /**
         * Adds per-instance bone transforms for skinned animation.
         */
        public Builder boneTransformsExt(InstanceInfoBoneTransformsEXT boneTransformsExt) {
            this.boneTransformsExt = boneTransformsExt;
            return this;
        }

        /**
         * Adds GPU instancing transforms for drawing multiple copies of this mesh.
         */
        public Builder gpuInstancingExt(InstanceInfoGpuInstancingEXT gpuInstancingExt) {
            this.gpuInstancingExt = gpuInstancingExt;
            return this;
        }

        public InstanceInfo build() {
            InstanceInfo info = new InstanceInfo();
            info.categoryFlags = this.categoryFlags;
            info.mesh = this.mesh;
            info.transform = this.transform;
            info.doubleSided = this.doubleSided ? 1 : 0;
            info.blendExt = this.blendExt;
            info.boneTransformsExt = this.boneTransformsExt;
            info.gpuInstancingExt = this.gpuInstancingExt;
            return info;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Light information.
 */
public class LightInfo {
    public long hash;
    public Float3D radiance = new Float3D(1, 1, 1);

    public LightInfoSphereEXT sphereExt;
    public LightInfoDistantEXT distantExt;
    public LightInfoRectEXT rectExt;
    public LightInfoDiskEXT diskExt;
    public LightInfoCylinderEXT cylinderExt;
    public LightInfoDomeEXT domeExt;

    public LightInfo() {}

    public LightInfo(long hash, float radianceR, float radianceG, float radianceB) {
        this.hash = hash;
        this.radiance = new Float3D(radianceR, radianceG, radianceB);
    }

    public static class Builder {
        private long hash;
        private Float3D radiance = new Float3D(1, 1, 1);
        private LightInfoSphereEXT sphereExt;
        private LightInfoDistantEXT distantExt;
        private LightInfoRectEXT rectExt;
        private LightInfoDiskEXT diskExt;
        private LightInfoCylinderEXT cylinderExt;
        private LightInfoDomeEXT domeExt;

        public Builder hash(long hash) {
            this.hash = hash;
            return this;
        }

        public Builder radiance(float r, float g, float b) {
            this.radiance = new Float3D(r, g, b);
            return this;
        }

        public Builder sphereExt(LightInfoSphereEXT ext) {
            this.sphereExt = ext;
            return this;
        }

        public Builder distantExt(LightInfoDistantEXT ext) {
            this.distantExt = ext;
            return this;
        }

        public Builder rectExt(LightInfoRectEXT ext) {
            this.rectExt = ext;
            return this;
        }

        public Builder diskExt(LightInfoDiskEXT ext) {
            this.diskExt = ext;
            return this;
        }

        public Builder cylinderExt(LightInfoCylinderEXT ext) {
            this.cylinderExt = ext;
            return this;
        }

        public Builder domeExt(LightInfoDomeEXT ext) {
            this.domeExt = ext;
            return this;
        }

        public LightInfo build() {
            LightInfo info = new LightInfo();
            info.hash = this.hash;
            info.radiance = this.radiance;
            info.sphereExt = this.sphereExt;
            info.distantExt = this.distantExt;
            info.rectExt = this.rectExt;
            info.diskExt = this.diskExt;
            info.cylinderExt = this.cylinderExt;
            info.domeExt = this.domeExt;
            return info;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

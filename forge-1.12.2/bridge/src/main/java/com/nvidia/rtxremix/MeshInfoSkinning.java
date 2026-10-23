/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.nio.ByteBuffer;

/**
 * Mesh skinning information for skeletal animation.
 */
public class MeshInfoSkinning {
    public int bonesPerVertex;
    public ByteBuffer blendWeights;
    public int blendWeightsCount;
    public ByteBuffer blendIndices;
    public int blendIndicesCount;

    public MeshInfoSkinning() {}
}

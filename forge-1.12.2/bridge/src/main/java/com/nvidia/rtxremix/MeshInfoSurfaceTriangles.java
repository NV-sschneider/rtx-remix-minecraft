/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.nio.ByteBuffer;

/**
 * Mesh surface triangles: contains vertices, indices, material handle,
 * and optional skinning data as direct ByteBuffers.
 */
public class MeshInfoSurfaceTriangles {
    public static final int TEXCOORD_BOUNDS = 1 << 0;

    public ByteBuffer vertexBuffer;
    public int vertexCount;
    public ByteBuffer indexBuffer;
    public int indexCount;
    public long material;
    public int flags;
    public boolean skinning_hasvalue;
    public int skinning_bonesPerVertex;
    public ByteBuffer skinning_blendWeights;
    public int skinning_blendWeightsCount;
    public ByteBuffer skinning_blendIndices;
    public int skinning_blendIndicesCount;

    public MeshInfoSurfaceTriangles() {}
}

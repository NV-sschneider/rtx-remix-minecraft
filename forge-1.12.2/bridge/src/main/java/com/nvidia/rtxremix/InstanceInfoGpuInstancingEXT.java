/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.nio.ByteBuffer;

/**
 * GPU instancing extension: holds an array of per-instance transforms
 * as a direct ByteBuffer of packed float[12] (48 bytes each).
 */
public class InstanceInfoGpuInstancingEXT {
    public ByteBuffer transformData;
    public int count;

    public InstanceInfoGpuInstancingEXT() {}
}

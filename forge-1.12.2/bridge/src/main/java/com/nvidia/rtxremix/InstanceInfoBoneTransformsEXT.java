/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Per-instance bone transforms extension for skinned mesh animation.
 *
 * <p>Holds a direct ByteBuffer of packed float[12] transforms (48 bytes each)
 * and a count. The buffer is passed directly to the C bridge.</p>
 */
public class InstanceInfoBoneTransformsEXT {

    public static final int MAX_BONES = 256;
    private static final int FLOATS_PER_BONE = 12;
    private static final int BYTES_PER_BONE = FLOATS_PER_BONE * 4;

    public ByteBuffer transformData;
    public int count;

    public InstanceInfoBoneTransformsEXT() {}

    /**
     * Packs an array of {@link Transform} objects into the internal
     * direct ByteBuffer for submission to the C bridge.
     */
    public void setBoneTransforms(Transform[] transforms) {
        count = transforms.length;
        if (transformData == null || transformData.capacity() < count * BYTES_PER_BONE) {
            transformData = ByteBuffer.allocateDirect(count * BYTES_PER_BONE)
                    .order(ByteOrder.LITTLE_ENDIAN);
        }
        transformData.clear();
        for (int i = 0; i < count; i++) {
            float[] m = transforms[i].matrix;
            for (int j = 0; j < FLOATS_PER_BONE; j++) {
                transformData.putFloat(m[j]);
            }
        }
        transformData.flip();
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

/** A placement must reach two rendered intermediate poses, with native geometry changing alongside it. */
final class BlockEffectObservation {
    private final long spawnedFrame;
    private long frame = -1, firstHash;
    private long corrected;
    private float firstHeight;
    private int samples;
    private boolean changed;

    BlockEffectObservation(long spawnedFrame, long correctedBaseline) {
        this.spawnedFrame = spawnedFrame; corrected = correctedBaseline;
    }

    void sample(long renderedFrame, int age, boolean alive, float height, long correctedQuads, long geometryHash) {
        if (renderedFrame <= spawnedFrame || renderedFrame <= frame || !alive || age < 1 || age > 8
                || correctedQuads <= corrected || !Float.isFinite(height) || height <= 0) return;
        frame = renderedFrame; corrected = correctedQuads;
        if (samples++ == 0) { firstHash = geometryHash; firstHeight = height; }
        else changed |= firstHash != geometryHash && Math.abs(firstHeight - height) > 0.00001f;
    }

    boolean passed() { return samples >= 2 && changed; }
    int samples() { return samples; }
}

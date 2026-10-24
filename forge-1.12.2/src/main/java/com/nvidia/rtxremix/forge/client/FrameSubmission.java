/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

/** One prepared world pass may submit once, after hand capture or at the world-pass tail. */
final class FrameSubmission {
    private Object world;
    private float partialTicks;

    void clear() { world = null; }
    void prepare(Object world, float partialTicks) {
        if (world == null) throw new IllegalArgumentException("A prepared frame needs a world");
        this.world = world;
        this.partialTicks = partialTicks;
    }
    boolean consume(Object currentWorld) {
        boolean ready = world != null && world == currentWorld;
        world = null;
        return ready;
    }
    float partialTicks() { return partialTicks; }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

/** Native mesh ownership includes staged and retired pages until their actual destruction. */
final class TerrainBudget {
    static final long FRAME_NANOS = 2_000_000;
    static final int COLUMNS_PER_FRAME = 32;
    static final int BLOCKS_PER_FRAME = 4096;
    static final int RETIREMENTS_PER_FRAME = 4;
    static final int PAGE_VERTICES = 16384;
    static final long MAX_MESH_BYTES = 512L * 1024 * 1024;
    private long bytes;

    static long bytes(int vertices) { return (long) vertices * MeshPacking.STRIDE + (long) vertices / 4 * 6 * 4; }
    boolean canReserve(long count) { return count >= 0 && count <= MAX_MESH_BYTES - bytes; }
    void reserve(long count) {
        if (!canReserve(count)) throw new IllegalStateException("Terrain mesh memory budget exceeded");
        bytes += count;
    }
    void release(long count) {
        if (count < 0 || count > bytes) throw new IllegalStateException("Terrain mesh memory released twice");
        bytes -= count;
    }
    long bytes() { return bytes; }
}

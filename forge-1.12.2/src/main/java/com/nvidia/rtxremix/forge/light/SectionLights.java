/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.light;

import java.util.Arrays;

/** Bounded light candidates: retain the brightest source in each four-block cell. */
public final class SectionLights {
    private static final long[] EMPTY = new long[0];
    private long[] positions;
    private byte[] levels;

    public void add(int localX, int localY, int localZ, long position, int level) {
        if ((localX | localY | localZ) < 0 || localX > 15 || localY > 15 || localZ > 15
                || level < 0 || level > 15) throw new IllegalArgumentException("Invalid section light");
        if (level == 0) return;
        if (positions == null) { positions = new long[64]; levels = new byte[64]; }
        int cell = (localX >> 2) | ((localZ >> 2) << 2) | ((localY >> 2) << 4);
        if (level > levels[cell]) {
            positions[cell] = position;
            levels[cell] = (byte) level;
        }
    }

    public long[] snapshot() {
        if (positions == null) return EMPTY;
        long[] result = new long[64];
        int size = 0;
        for (int i = 0; i < 64; i++) if (levels[i] != 0) result[size++] = positions[i];
        return Arrays.copyOf(result, size);
    }
}

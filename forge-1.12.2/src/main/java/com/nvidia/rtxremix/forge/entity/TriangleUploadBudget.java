/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.util.List;

/** Rotating admission prevents continually animated groups from monopolizing native uploads. */
final class TriangleUploadBudget {
    static final int MAX_UPLOADS = 8;
    static final long MAX_BYTES = 4L * 1024 * 1024;
    private int cursor;
    private int uploads;
    private long bytes;

    int beginFrame(List<CapturedEntityPart> parts, int count) {
        int triangles = 0;
        for (int i = 0; i < count; i++) if (parts.get(i).triangles != null) triangles++;
        int ordinal = beginFrame(triangles);
        for (int i = 0; i < count; i++) if (parts.get(i).triangles != null && ordinal-- == 0) return i;
        return 0;
    }

    int beginFrame(int partCount) {
        uploads = 0;
        bytes = 0;
        int first = partCount == 0 ? 0 : cursor % partCount;
        cursor = partCount == 0 ? 0 : (first + 1) % partCount;
        return first;
    }

    boolean admit(long requiredBytes) {
        if (requiredBytes < 0 || uploads == MAX_UPLOADS || requiredBytes > MAX_BYTES - bytes) return false;
        uploads++;
        bytes += requiredBytes;
        return true;
    }
}

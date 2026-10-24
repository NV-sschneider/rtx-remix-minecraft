/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

/** A separate hand allowance prevents world animation from postponing local equip and swing poses. */
public final class ViewModelUploadBudget {
    static final int MAX_UPLOADS = 8;
    static final long MAX_BYTES = 4L * 1024 * 1024;
    private int uploads;
    private int cursor;
    private long bytes;
    public void beginFrame() { uploads = 0; bytes = 0; }
    public int beginFrame(int parts) {
        beginFrame();
        int first = parts == 0 ? 0 : cursor % parts;
        cursor = parts == 0 ? 0 : (first + 1) % parts;
        return first;
    }
    public boolean admit(long required) {
        if (required < 0 || uploads == MAX_UPLOADS || required > MAX_BYTES - bytes) return false;
        uploads++; bytes += required; return true;
    }
}

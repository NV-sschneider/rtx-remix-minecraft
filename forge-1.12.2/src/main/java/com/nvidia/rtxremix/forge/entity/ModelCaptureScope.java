/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

/** Nested renderer calls suspend capture without replacing the outer model's identity. */
final class ModelCaptureScope {
    private int depth;
    private Object owner;
    private boolean active;

    boolean enter(Object candidate, boolean activate) {
        if (++depth != 1) return false;
        owner = candidate;
        active = activate;
        return true;
    }
    void activate() { if (depth == 1) active = true; }
    void deactivate() { if (depth == 1) active = false; }
    Object owner() { return depth == 1 && active ? owner : null; }
    Object outerOwner() { return depth == 1 ? owner : null; }
    boolean isOpen() { return depth != 0; }
    void reject() { owner = null; }
    void leave() { if (depth > 0 && --depth == 0) clear(); }
    void clear() { depth = 0; owner = null; active = false; }
}

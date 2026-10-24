/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

/** Identity plus native category; a player's body and hands own independent occurrences. */
public final class CaptureOwnerKey {
    public Object owner;
    private int category;

    /** Only lookup probes may be changed after insertion into a map. */
    public CaptureOwnerKey set(Object owner, int category) { this.owner = owner; this.category = category; return this; }
    public int category() { return category; }
    @Override public int hashCode() { return 31 * System.identityHashCode(owner) + category; }
    @Override public boolean equals(Object value) {
        if (!(value instanceof CaptureOwnerKey)) return false;
        CaptureOwnerKey other = (CaptureOwnerKey) value;
        return owner == other.owner && category == other.category;
    }
}

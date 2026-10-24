/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.util.Arrays;

/** Fixed-capacity, stable nearest-first selection without per-frame sorting allocations. */
final class ClosestEntities<T> {
    private final Object[] owners;
    private final double[] distances;
    private int count;

    ClosestEntities(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Entity capacity must be positive");
        owners = new Object[capacity];
        distances = new double[capacity];
    }

    void offer(T owner, double distanceSquared) {
        if (owner == null || !Double.isFinite(distanceSquared) || distanceSquared < 0) return;
        int index = count;
        while (index > 0 && distanceSquared < distances[index - 1]) index--;
        if (index == owners.length) return;
        int end = Math.min(count, owners.length - 1);
        for (int i = end; i > index; i--) {
            owners[i] = owners[i - 1];
            distances[i] = distances[i - 1];
        }
        owners[index] = owner;
        distances[index] = distanceSquared;
        if (count < owners.length) count++;
    }

    int size() { return count; }
    @SuppressWarnings("unchecked") T get(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        return (T) owners[index];
    }
    void clear() { Arrays.fill(owners, 0, count, null); count = 0; }
}

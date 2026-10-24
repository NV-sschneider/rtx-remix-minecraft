/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.light;

import net.minecraft.util.math.AxisAlignedBB;

/** Java 8 slab intersection and asymmetric smoothing for the camera-locked light's collision clearance. */
final class HeldLightPlacement {
    static final double DISTANCE = 0.72, CLEARANCE = 0.02;
    private HeldLightPlacement() { }

    static double advance(double previous, double target, float seconds) {
        if (target <= previous) return target;
        return Math.min(target, previous + 3 * Math.max(0, Math.min(0.1f, seconds)));
    }

    static double entry(double x, double y, double z, double dx, double dz, double limit,
            AxisAlignedBB box, double expansion) {
        if (y < box.minY - expansion || y > box.maxY + expansion) return Double.POSITIVE_INFINITY;
        double minimum = 0, maximum = limit;
        if (Math.abs(dx) < 1.0e-12) {
            if (x < box.minX - expansion || x > box.maxX + expansion) return Double.POSITIVE_INFINITY;
        } else {
            double a = (box.minX - expansion - x) / dx, b = (box.maxX + expansion - x) / dx;
            minimum = Math.max(minimum, Math.min(a, b)); maximum = Math.min(maximum, Math.max(a, b));
            if (minimum > maximum) return Double.POSITIVE_INFINITY;
        }
        if (Math.abs(dz) < 1.0e-12) {
            if (z < box.minZ - expansion || z > box.maxZ + expansion) return Double.POSITIVE_INFINITY;
        } else {
            double a = (box.minZ - expansion - z) / dz, b = (box.maxZ + expansion - z) / dz;
            minimum = Math.max(minimum, Math.min(a, b)); maximum = Math.min(maximum, Math.max(a, b));
            if (minimum > maximum) return Double.POSITIVE_INFINITY;
        }
        return minimum;
    }
}

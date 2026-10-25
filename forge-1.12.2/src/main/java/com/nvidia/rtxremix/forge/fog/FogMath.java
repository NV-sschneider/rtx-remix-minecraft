/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.fog;

/** Fixed-function fog equations and non-reversed projected-depth reconstruction. */
final class FogMath {
    private FogMath() {}

    static double transmission(FogState state, double distance) {
        if (!state.enabled()) return 1;
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("Invalid fog distance");
        switch (state.mode()) {
            case FogState.LINEAR: return clamp((state.end() - distance) / (state.end() - state.start()));
            case FogState.EXP: return Math.exp(-state.density() * distance);
            case FogState.EXP2: double opticalDepth = state.density() * distance; return Math.exp(-opticalDepth * opticalDepth);
            default: throw new IllegalArgumentException("Unsupported fog mode");
        }
    }

    static double eyeDepth(double projectedDepth, double near, double far) {
        if (!Double.isFinite(projectedDepth) || projectedDepth < 0 || projectedDepth > 1
                || !Double.isFinite(near) || !Double.isFinite(far) || near <= 0 || far <= near)
            throw new IllegalArgumentException("Invalid projected depth or camera range");
        return near * far / (far - projectedDepth * (far - near));
    }

    static double distance(double eyeDepth, double xNdc, double yNdc, double tanHalfFovY, double aspect, boolean radial) {
        if (!radial) return eyeDepth;
        double x = xNdc * tanHalfFovY * aspect, y = yNdc * tanHalfFovY;
        return eyeDepth * Math.sqrt(1 + x * x + y * y);
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}

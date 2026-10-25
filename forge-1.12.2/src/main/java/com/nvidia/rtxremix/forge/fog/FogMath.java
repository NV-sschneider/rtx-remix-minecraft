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

    /** Reference for the shader's finite-hit coverage; sky and invalid samples carry no fog. */
    static double opacity(FogState state, double depth, double near, double far,
                          double xNdc, double yNdc, double tanHalfFovY, double aspect) {
        if (!(depth >= 0 && depth < 1)) return 0;
        return 1 - transmission(state, distance(eyeDepth(depth, near, far),
                xNdc, yNdc, tanHalfFovY, aspect, state.radial()));
    }

    /** Blend reconstructed opacity, never the nonlinear depth samples that produced it. */
    static double coverage(double lowerLeft, double lowerRight, double upperLeft, double upperRight,
                           double x, double y) {
        if (!(x >= 0 && x <= 1 && y >= 0 && y <= 1))
            throw new IllegalArgumentException("Coverage weights must be inside one texel footprint");
        return (lowerLeft * (1 - x) + lowerRight * x) * (1 - y)
                + (upperLeft * (1 - x) + upperRight * x) * y;
    }

    /** Neighborhood clipping immediately rejects stale interiors while preserving fractional edge coverage. */
    static double accumulate(double previous, double current, double neighborhoodMin, double neighborhoodMax,
                             double weight) {
        double clipped = Math.max(neighborhoodMin, Math.min(neighborhoodMax, previous));
        return clipped * (1 - weight) + current * weight;
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}

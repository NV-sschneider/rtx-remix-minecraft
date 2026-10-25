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

    /** Supremum over all nonnegative distances, without sampling an arbitrary depth range. */
    static double maximumOpacityDifference(FogState first, FogState second) {
        if (!first.enabled() && !second.enabled()) return 0;
        if (first.enabled() != second.enabled() || first.mode() != second.mode() || first.radial() != second.radial())
            return 1;
        if (first.mode() == FogState.LINEAR) {
            if (first.start() == second.start() && first.end() == second.end()) return 0;
            // The difference of two clamped affine laws is piecewise affine. Its
            // extrema lie at zero or one of the four nonnegative breakpoints.
            double difference = differenceAt(first, second, 0);
            difference = Math.max(difference, differenceAt(first, second, Math.max(0, first.start())));
            difference = Math.max(difference, differenceAt(first, second, Math.max(0, first.end())));
            difference = Math.max(difference, differenceAt(first, second, Math.max(0, second.start())));
            return Math.max(difference, differenceAt(first, second, Math.max(0, second.end())));
        }
        if (first.density() == second.density()) return 0;
        double low = Math.min(first.density(), second.density()), high = Math.max(first.density(), second.density());
        if (low == 0) return 1; // The other law approaches full opacity at infinity.
        if (first.mode() == FogState.EXP2) { low *= low; high *= high; }
        // EXP2 uses distance squared as the independent variable; the same
        // unique extremum of exp(-low*x)-exp(-high*x) applies to both laws.
        double separation = high - low;
        double at = Math.log1p(separation / low) / separation;
        return Math.exp(-low * at) * -Math.expm1(-separation * at);
    }

    private static double differenceAt(FogState first, FogState second, double distance) {
        return Math.abs(transmission(first, distance) - transmission(second, distance));
    }

    /** Sign records which side of the current neighborhood contradicts the retained coverage. */
    static int rejectionStreak(double previous, double neighborhoodMin, double neighborhoodMax, int lastStreak) {
        if (previous < neighborhoodMin - FogHistory.COVERAGE_EPSILON)
            return lastStreak < 0 ? Math.max(-FogHistory.REJECTION_FRAMES, lastStreak - 1) : -1;
        if (previous > neighborhoodMax + FogHistory.COVERAGE_EPSILON)
            return lastStreak > 0 ? Math.min(FogHistory.REJECTION_FRAMES, lastStreak + 1) : 1;
        return 0;
    }

    /** Isolated misses retain coverage; sustained disocclusion clips on its third confirming frame. */
    static double accumulate(double previous, double current, double neighborhoodMin, double neighborhoodMax,
                             double weight, int rejectionStreak) {
        if (weight == 1) return current;
        double clipped = Math.abs(rejectionStreak) >= FogHistory.REJECTION_FRAMES
                ? Math.max(neighborhoodMin, Math.min(neighborhoodMax, previous)) : previous;
        return clipped * (1 - weight) + current * weight;
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}

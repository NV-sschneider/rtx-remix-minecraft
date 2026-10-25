/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.fog;

import com.nvidia.rtxremix.CameraInfoParameterizedEXT;

/** Stationary-camera admission with a bounded contribution from earlier fog laws. */
final class FogHistory {
    static final int MAX_SAMPLES = 32;
    static final int REJECTION_FRAMES = 3;
    // More than one half-float rounding step in [0,1], below one 8-bit color step.
    static final double COVERAGE_EPSILON = 1.0 / 1024;
    static final double MAX_LAW_ERROR = 1.0 / 255;
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final int BYTES_PER_PIXEL = 2 * 2 * 2; // Two RG16F images: opacity plus signed rejection streak.
    static final long MAX_PIXELS = MAX_BYTES / BYTES_PER_PIXEL;
    private final CameraInfoParameterizedEXT previous = new CameraInfoParameterizedEXT();
    private final FogState last = new FogState();
    private int width, height, depthWidth, depthHeight, samples;
    private long resets, poseResets, lawResets, sizeResets, discardResets;
    private double lawError;
    private float weight;

    float observe(FogState fog, CameraInfoParameterizedEXT camera, int width, int height,
                  int depthWidth, int depthHeight) {
        double lawDifference = 0;
        if (samples != 0) {
            boolean size = this.width != width || this.height != height
                    || this.depthWidth != depthWidth || this.depthHeight != depthHeight;
            boolean pose = !sameCamera(camera);
            lawDifference = FogMath.maximumOpacityDifference(last, fog);
            boolean law = last.enabled() != fog.enabled() || last.mode() != fog.mode() || last.radial() != fog.radial()
                    || lawDifference > MAX_LAW_ERROR;
            if (size || pose || law) {
                if (size) sizeResets++;
                if (pose) poseResets++;
                if (law) lawResets++;
                reset(); lawDifference = 0;
            }
        }
        this.width = width; this.height = height; this.depthWidth = depthWidth; this.depthHeight = depthHeight;
        copyCamera(camera);
        last.set(fog.enabled(), fog.mode(), fog.radial(), fog.red(), fog.green(), fog.blue(),
                fog.density(), fog.start(), fog.end());
        samples = Math.min(MAX_SAMPLES, samples + 1);
        // The previous aggregate's law error grows by at most the exact
        // supremum between consecutive laws. Blending current-law coverage with
        // weight w leaves at most (1-w)*(E+delta). Increase w only as needed to
        // stay within one 8-bit opacity step; continuous morning fog need not
        // periodically discard a stable silhouette. This bound covers law drift,
        // independently of depth sampling and half-float storage error.
        double uncertainty = lawError + lawDifference;
        double minimum = uncertainty > MAX_LAW_ERROR ? 1 - MAX_LAW_ERROR / uncertainty : 0;
        weight = Math.max(1.0f / samples, (float) minimum);
        if (weight < minimum) weight = Math.nextUp(weight);
        lawError = (1.0 - weight) * uncertainty;
        return weight;
    }

    void discard() { if (samples != 0) { discardResets++; reset(); } }
    void resized() { if (samples != 0) { sizeResets++; reset(); } }
    private void reset() { resets++; samples = 0; lawError = 0; weight = 0; }
    int samples() { return samples; }
    long resets() { return resets; }
    long poseResets() { return poseResets; }
    long lawResets() { return lawResets; }
    long sizeResets() { return sizeResets; }
    long discardResets() { return discardResets; }
    double lawError() { return lawError; }
    float weight() { return weight; }
    static boolean fits(int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= MAX_PIXELS;
    }

    private boolean sameCamera(CameraInfoParameterizedEXT camera) {
        return previous.position.x == camera.position.x && previous.position.y == camera.position.y
                && previous.position.z == camera.position.z
                && previous.forward.x == camera.forward.x && previous.forward.y == camera.forward.y
                && previous.forward.z == camera.forward.z
                && previous.up.x == camera.up.x && previous.up.y == camera.up.y && previous.up.z == camera.up.z
                && previous.right.x == camera.right.x && previous.right.y == camera.right.y && previous.right.z == camera.right.z
                && previous.nearPlane == camera.nearPlane && previous.farPlane == camera.farPlane
                && previous.fovYInDegrees == camera.fovYInDegrees && previous.aspect == camera.aspect;
    }
    private void copyCamera(CameraInfoParameterizedEXT camera) {
        previous.position.x = camera.position.x; previous.position.y = camera.position.y; previous.position.z = camera.position.z;
        previous.forward.x = camera.forward.x; previous.forward.y = camera.forward.y; previous.forward.z = camera.forward.z;
        previous.up.x = camera.up.x; previous.up.y = camera.up.y; previous.up.z = camera.up.z;
        previous.right.x = camera.right.x; previous.right.y = camera.right.y; previous.right.z = camera.right.z;
        previous.nearPlane = camera.nearPlane; previous.farPlane = camera.farPlane;
        previous.fovYInDegrees = camera.fovYInDegrees; previous.aspect = camera.aspect;
    }
}

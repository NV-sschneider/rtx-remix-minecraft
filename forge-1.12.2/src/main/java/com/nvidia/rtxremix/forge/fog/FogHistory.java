/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.fog;

import com.nvidia.rtxremix.CameraInfoParameterizedEXT;

/** Exact stationary-camera admission. Camera motion never borrows another pose's fog coverage. */
final class FogHistory {
    static final int MAX_SAMPLES = 32;
    static final long MAX_PIXELS = 8L * 1024 * 1024;
    private final CameraInfoParameterizedEXT previous = new CameraInfoParameterizedEXT();
    private final FogState last = new FogState();
    private int width, height, depthWidth, depthHeight, samples;
    private long resets;

    float observe(FogState fog, CameraInfoParameterizedEXT camera, int width, int height,
                  int depthWidth, int depthHeight) {
        if (samples != 0 && (this.width != width || this.height != height
                || this.depthWidth != depthWidth || this.depthHeight != depthHeight
                || !sameCamera(camera) || !sameFog(fog))) discard();
        this.width = width; this.height = height; this.depthWidth = depthWidth; this.depthHeight = depthHeight;
        copyCamera(camera);
        last.set(fog.enabled(), fog.mode(), fog.radial(), fog.red(), fog.green(), fog.blue(),
                fog.density(), fog.start(), fog.end());
        samples = Math.min(MAX_SAMPLES, samples + 1);
        // Initial samples form an arithmetic average. At the cap this becomes a
        // running exponential average, retaining bounded state rather than a frame ring.
        return 1.0f / samples;
    }

    void discard() { if (samples != 0) resets++; samples = 0; }
    int samples() { return samples; }
    long resets() { return resets; }
    static boolean fits(int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= MAX_PIXELS;
    }

    private boolean sameFog(FogState fog) {
        // Color is applied to the final coverage every frame. Daylight-driven
        // color changes must not restart an otherwise stationary opacity average.
        return last.enabled() == fog.enabled() && last.mode() == fog.mode() && last.radial() == fog.radial()
                && last.density() == fog.density() && last.start() == fog.start() && last.end() == fog.end();
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

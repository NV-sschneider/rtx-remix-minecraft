/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;

/** Broad sky visibility and change checks, independent of exposure or exact renderer colors. */
final class FixtureSkyImage {
    private FixtureSkyImage() {}

    static Result evaluate(BufferedImage image) {
        // The sky probe points upward. This upper-center rectangle avoids the
        // horizon, crosshair, chat, hand, and the pack's corner overlays.
        int left = image.getWidth() * 25 / 100;
        int right = image.getWidth() * 75 / 100;
        int top = image.getHeight() * 10 / 100;
        int bottom = image.getHeight() * 35 / 100;
        int samples = 0, visible = 0;
        long red = 0, green = 0, blue = 0;
        for (int y = top; y < bottom; y += 4) {
            for (int x = left; x < right; x += 4) {
                int pixel = image.getRGB(x, y);
                int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
                samples++;
                red += r;
                green += g;
                blue += b;
                if (Math.max(r, Math.max(g, b)) >= 24) visible++;
            }
        }
        return new Result(samples, visible, red, green, blue);
    }

    static final class Result {
        private final int samples, visible;
        private final double red, green, blue;

        private Result(int samples, int visible, long red, long green, long blue) {
            this.samples = samples;
            this.visible = visible;
            this.red = samples == 0 ? 0 : red / (double) samples;
            this.green = samples == 0 ? 0 : green / (double) samples;
            this.blue = samples == 0 ? 0 : blue / (double) samples;
        }

        boolean isVisible() { return samples > 0 && visible * 100L >= samples * 80L; }

        double difference(Result other) {
            return (Math.abs(red - other.red) + Math.abs(green - other.green)
                    + Math.abs(blue - other.blue)) / 3;
        }

        String describe() {
            return "skySamples=" + samples + " skyVisible=" + visible
                    + " skyMeanRGB=" + Math.round(red) + "," + Math.round(green) + "," + Math.round(blue);
        }
    }
}

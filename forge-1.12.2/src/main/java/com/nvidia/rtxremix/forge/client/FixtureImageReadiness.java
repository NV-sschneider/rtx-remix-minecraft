/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;

/** Rejects blank smoke-fixture output; this is not a visual correctness comparison. */
final class FixtureImageReadiness {
    private FixtureImageReadiness() {}

    static Result evaluate(BufferedImage image) {
        // The fixed fixture pose puts wool and floor here, clear of the crosshair,
        // right-hand model, hotbar, and lower-left chat. Image origin is top-left.
        int left = image.getWidth() * 15 / 100;
        int right = image.getWidth() * 45 / 100;
        int top = image.getHeight() * 35 / 100;
        int bottom = image.getHeight() * 65 / 100;
        int samples = 0;
        int visible = 0;
        int colored = 0;
        for (int y = top; y < bottom; y += 4) {
            for (int x = left; x < right; x += 4) {
                int pixel = image.getRGB(x, y);
                int red = (pixel >>> 16) & 255;
                int green = (pixel >>> 8) & 255;
                int blue = pixel & 255;
                int max = Math.max(red, Math.max(green, blue));
                int min = Math.min(red, Math.min(green, blue));
                samples++;
                if (max >= 24) visible++;
                if (max >= 32 && max - min >= 24) colored++;
            }
        }
        return new Result(samples, visible, colored);
    }

    static final class Result {
        private final int samples;
        private final int visible;
        private final int colored;

        private Result(int samples, int visible, int colored) {
            this.samples = samples;
            this.visible = visible;
            this.colored = colored;
        }

        boolean isReady() {
            return samples > 0 && visible * 100L >= samples * 20L
                    && colored * 100L >= samples * 2L;
        }

        String describe() {
            return "imageSamples=" + samples + " imageVisible=" + visible + " imageColored=" + colored;
        }
    }
}

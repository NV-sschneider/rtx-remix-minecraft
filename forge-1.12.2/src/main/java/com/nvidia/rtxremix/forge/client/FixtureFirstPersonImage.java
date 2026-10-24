/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;

/** Proves visible diamond-blade pixels in the gray arena; it does not judge the complete hand pose. */
final class FixtureFirstPersonImage {
    private FixtureFirstPersonImage() { }

    static Result sword(BufferedImage empty, BufferedImage equipped, int guiScale) {
        if (empty.getWidth() != equipped.getWidth() || empty.getHeight() != equipped.getHeight())
            throw new IllegalArgumentException("First-person image proof requires an unchanged viewport");
        if (guiScale < 1) throw new IllegalArgumentException("Invalid GUI scale");
        int width = equipped.getWidth(), height = equipped.getHeight();
        // Beyond the 182-GUI-pixel hotbar, centered item name and crosshair.
        // Keep the bottom edge: an equipped blade can first enter the viewport there.
        int left = Math.max(width * 3 / 5, width / 2 + 100 * guiScale), top = height * 2 / 5;
        int samples = Math.max(0, width - left) * (height - top), pixels = 0, rows = 0;
        for (int y = top; y < height; y++) {
            int rowPixels = 0;
            for (int x = left; x < width; x++) {
                if (turquoise(equipped.getRGB(x, y)) && !turquoise(empty.getRGB(x, y))) rowPixels++;
            }
            pixels += rowPixels;
            if (rowPixels >= Math.max(2, width / 320)) rows++;
        }
        return new Result(samples, pixels, rows, Math.max(1, height / 30));
    }

    private static boolean turquoise(int rgb) {
        int red = (rgb >>> 16) & 255, green = (rgb >>> 8) & 255, blue = rgb & 255;
        return green >= 64 && blue >= 48 && green - red >= 32 && blue - red >= 24 && green >= blue - 8;
    }

    static final class Result {
        final int samples, bladePixels, bladeRows, requiredRows;
        Result(int samples, int bladePixels, int bladeRows, int requiredRows) {
            this.samples = samples; this.bladePixels = bladePixels; this.bladeRows = bladeRows; this.requiredRows = requiredRows;
        }
        boolean visible() { return samples > 0 && bladePixels * 100L >= samples && bladeRows >= requiredRows; }
        String describe() {
            return "bladeSamples=" + samples + " bladePixels=" + bladePixels + " bladeRows=" + bladeRows
                    + " requiredRows=" + requiredRows + " visible=" + visible();
        }
    }
}

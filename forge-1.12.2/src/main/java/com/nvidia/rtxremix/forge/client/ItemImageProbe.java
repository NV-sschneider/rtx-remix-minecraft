/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;

/** Isolates bright item surface pixels against the same camera's empty obsidian platform. */
final class ItemImageProbe {
    final int pixels;
    final double luminance;
    private ItemImageProbe(int pixels, double luminance) { this.pixels = pixels; this.luminance = luminance; }

    static ItemImageProbe measure(BufferedImage empty, BufferedImage item, double centerX, double centerY, int radius) {
        if (empty.getWidth() != item.getWidth() || empty.getHeight() != item.getHeight()
                || !Double.isFinite(centerX) || !Double.isFinite(centerY) || radius < 1)
            throw new IllegalArgumentException("Invalid dropped-item image pair");
        int x = (int) Math.round(centerX * item.getWidth()), y = (int) Math.round(centerY * item.getHeight());
        if (x - radius < 0 || y - radius < 0 || x + radius >= item.getWidth() || y + radius >= item.getHeight())
            throw new IllegalStateException("Dropped-item target lies outside the image");
        int count = 0; double total = 0;
        for (int yy = y - radius; yy <= y + radius; yy++) for (int xx = x - radius; xx <= x + radius; xx++) {
            double before = luma(empty.getRGB(xx, yy)), after = luma(item.getRGB(xx, yy));
            // Shadows darken the platform; they cannot satisfy an item's visible-surface check.
            if (after - before < 10) continue;
            total += after; count++;
        }
        return new ItemImageProbe(count, count == 0 ? 0 : total / count);
    }
    private static double luma(int color) {
        return 0.2126 * (color >> 16 & 255) + 0.7152 * (color >> 8 & 255) + 0.0722 * (color & 255);
    }
    boolean visible(boolean shade) { return pixels >= 12 && luminance >= (shade ? 16 : 28); }
    @Override public String toString() { return pixels + "/" + luminance; }
}

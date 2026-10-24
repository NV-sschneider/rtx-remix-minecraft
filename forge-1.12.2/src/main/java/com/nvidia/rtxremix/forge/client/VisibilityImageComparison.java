/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;

/** Measures the same baseline-selected shadow and highlight pixels at an unchanged camera pose. */
final class VisibilityImageComparison {
    final int samples, shadowSamples, highlightSamples, clippedBefore, clippedAfter;
    final double shadowBefore, shadowAfter, highlightBefore, highlightAfter;

    VisibilityImageComparison(BufferedImage before, BufferedImage after) {
        if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight())
            throw new IllegalArgumentException("Visibility comparison requires an unchanged viewport");
        int count = 0, shadows = 0, highlights = 0, beforeClips = 0, afterClips = 0;
        double beforeShadow = 0, afterShadow = 0, beforeHighlight = 0, afterHighlight = 0;
        for (int y = before.getHeight() * 15 / 100; y < before.getHeight() * 80 / 100; y += 3) {
            for (int x = before.getWidth() * 15 / 100; x < before.getWidth() * 85 / 100; x += 3) {
                double first = luma(before.getRGB(x, y)), second = luma(after.getRGB(x, y)); count++;
                if (first >= 8 && first <= 112) { shadows++; beforeShadow += first; afterShadow += second; }
                if (first >= 160 && first <= 245) { highlights++; beforeHighlight += first; afterHighlight += second; }
                if (first >= 250) beforeClips++;
                if (second >= 250) afterClips++;
            }
        }
        samples = count; shadowSamples = shadows; highlightSamples = highlights;
        clippedBefore = beforeClips; clippedAfter = afterClips;
        shadowBefore = shadows == 0 ? 0 : beforeShadow / shadows; shadowAfter = shadows == 0 ? 0 : afterShadow / shadows;
        highlightBefore = highlights == 0 ? 0 : beforeHighlight / highlights;
        highlightAfter = highlights == 0 ? 0 : afterHighlight / highlights;
    }
    boolean shadowsImproved() { return shadowSamples >= 32 && shadowAfter >= shadowBefore + 2; }
    boolean highlightsPreserved() {
        return highlightSamples >= 32 && highlightAfter < 250 && clippedAfter <= clippedBefore + Math.max(2, samples / 100);
    }
    private static double luma(int rgb) {
        return ((rgb >>> 16) & 255) * 0.2126 + ((rgb >>> 8) & 255) * 0.7152 + (rgb & 255) * 0.0722;
    }
    String describe() {
        return "shadowSamples=" + shadowSamples + " shadowBefore=" + shadowBefore + " shadowAfter=" + shadowAfter
                + " highlightSamples=" + highlightSamples + " highlightBefore=" + highlightBefore + " highlightAfter=" + highlightAfter
                + " clippedBefore=" + clippedBefore + " clippedAfter=" + clippedAfter + " samples=" + samples;
    }
}

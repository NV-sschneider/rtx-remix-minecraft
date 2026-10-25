/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Locale;

/** Bounded stationary-image statistics; no spatial averaging can hide a flickering silhouette pixel. */
final class FogEdgeProbe {
    static final int SAMPLES = 32;
    private static final int MAX_SIDE = 128;
    private final int imageWidth, imageHeight, x, y, width, height;
    private final int[] previous;
    private final double[] sum, squares;
    private int samples;
    private long difference, changed;

    FogEdgeProbe(BufferedImage image, double left, double top, double right, double bottom) {
        if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(right) || !Double.isFinite(bottom)
                || left < 0 || top < 0 || right > 1 || bottom > 1 || left >= right || top >= bottom)
            throw new IllegalArgumentException("Fog edge region must be inside the image");
        imageWidth = image.getWidth(); imageHeight = image.getHeight();
        int minX = (int) Math.floor(left * imageWidth), minY = (int) Math.floor(top * imageHeight);
        int maxX = Math.min(imageWidth, (int) Math.ceil(right * imageWidth));
        int maxY = Math.min(imageHeight, (int) Math.ceil(bottom * imageHeight));
        width = Math.min(MAX_SIDE, maxX - minX); height = Math.min(MAX_SIDE, maxY - minY);
        if (width < 2 || height < 2) throw new IllegalArgumentException("Fog edge region is too small");
        x = minX + (maxX - minX - width) / 2; y = minY + (maxY - minY - height) / 2;
        previous = new int[width * height]; sum = new double[previous.length * 3]; squares = new double[sum.length];
    }

    void sample(BufferedImage image) {
        if (image.getWidth() != imageWidth || image.getHeight() != imageHeight)
            throw new IllegalStateException("Fog edge image resized during stationary measurement");
        if (samples == SAMPLES) throw new IllegalStateException("Fog edge sample limit reached");
        for (int dy = 0; dy < height; dy++) for (int dx = 0; dx < width; dx++) {
            int index = dy * width + dx, rgb = image.getRGB(x + dx, y + dy), largest = 0;
            for (int channel = 0; channel < 3; channel++) {
                int value = rgb >>> (channel * 8) & 255;
                int offset = index * 3 + channel;
                sum[offset] += value; squares[offset] += value * value;
                if (samples != 0) {
                    int delta = Math.abs(value - (previous[index] >>> (channel * 8) & 255));
                    difference += delta; largest = Math.max(largest, delta);
                }
            }
            if (samples != 0 && largest > 8) changed++;
            previous[index] = rgb;
        }
        samples++;
    }

    int samples() { return samples; }
    double meanDifference() { return samples < 2 ? 0 : (double) difference / ((samples - 1) * previous.length * 3); }
    double changedFraction() { return samples < 2 ? 0 : (double) changed / ((samples - 1) * previous.length); }
    double deviationPercentile(double percentile) {
        if (samples == 0 || percentile < 0 || percentile > 1 || !Double.isFinite(percentile))
            throw new IllegalArgumentException("Invalid fog edge statistic");
        double[] deviation = new double[previous.length];
        for (int pixel = 0; pixel < previous.length; pixel++) {
            double variance = 0;
            for (int channel = 0; channel < 3; channel++) {
                int offset = pixel * 3 + channel;
                double mean = sum[offset] / samples;
                variance += Math.max(0, squares[offset] / samples - mean * mean);
            }
            deviation[pixel] = Math.sqrt(variance / 3);
        }
        Arrays.sort(deviation);
        return deviation[(int) Math.ceil(percentile * (deviation.length - 1))];
    }
    String summary() {
        return String.format(Locale.ROOT, "samples=%d roi=%d,%d,%d,%d meanFrameDelta=%.6f changedOver8=%.6f p95TemporalDeviation=%.6f",
                samples, x, y, width, height, meanDifference(), changedFraction(), deviationPercentile(0.95));
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;

/** Measures an interior target patch against that renderer's own unfogged reference. */
final class FogImageProbe {
    private FogImageProbe() {}

    static double[] sample(BufferedImage image, double screenX, double screenY) {
        if (!Double.isFinite(screenX) || !Double.isFinite(screenY)) throw new IllegalArgumentException("Nonfinite fog target projection");
        int x = (int) Math.round(screenX * image.getWidth()), y = (int) Math.round(screenY * image.getHeight());
        int radius = Math.max(3, Math.min(image.getWidth(), image.getHeight()) / 100);
        if (x < radius || y < radius || x + radius >= image.getWidth() || y + radius >= image.getHeight())
            throw new IllegalStateException("Fog target lies outside the framebuffer");
        double[] result = new double[3]; int count = 0;
        for (int yy = y - radius; yy <= y + radius; yy++) for (int xx = x - radius; xx <= x + radius; xx++) {
            int pixel = image.getRGB(xx, yy); result[0] += pixel >>> 16 & 255;
            result[1] += pixel >>> 8 & 255; result[2] += pixel & 255; count++;
        }
        for (int channel = 0; channel < 3; channel++) result[channel] /= count;
        return result;
    }

    static double error(double[] clear, double[] actual, float red, float green, float blue, double transmission) {
        double[] fog = {red * 255, green * 255, blue * 255}; double result = 0;
        for (int channel = 0; channel < 3; channel++)
            result = Math.max(result, Math.abs(actual[channel] - (clear[channel] * transmission + fog[channel] * (1 - transmission))));
        return result;
    }

    static double difference(double[] first, double[] second) {
        double result = 0;
        for (int channel = 0; channel < 3; channel++) result += Math.abs(first[channel] - second[channel]);
        return result / 3;
    }

    static double transmission(double[] clear, double[] actual, float red, float green, float blue) {
        double[] fog = {red * 255, green * 255, blue * 255}; double dot = 0, length = 0;
        for (int channel = 0; channel < 3; channel++) {
            double contrast = clear[channel] - fog[channel];
            dot += (actual[channel] - fog[channel]) * contrast; length += contrast * contrast;
        }
        return length == 0 ? Double.NaN : dot / length;
    }
}

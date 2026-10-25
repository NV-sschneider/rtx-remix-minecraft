/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;

/** Requires localized, increasing crack contrast and an unchanged neighboring block. */
final class CrackImageObservation {
    private final BufferedImage baseline, noise;
    private final int[] target, control;
    private int earlyPixels;

    CrackImageObservation(BufferedImage baseline, BufferedImage noise, int[] target, int[] control) {
        dimensions(baseline, noise);
        validate(target, baseline); validate(control, baseline);
        this.baseline = baseline; this.noise = noise; this.target = target; this.control = control;
    }

    Result stage(BufferedImage image, boolean late) {
        dimensions(baseline, image);
        int pixels = 0, samples = 0; double clearLight = 0, currentLight = 0;
        for (int y = target[1]; y < target[3]; y++) for (int x = target[0]; x < target[2]; x++) {
            double clear = light(baseline.getRGB(x, y)), current = light(image.getRGB(x, y));
            double variation = Math.abs(clear - light(noise.getRGB(x, y)));
            if (clear - current >= Math.max(20, Math.max(clear * .2, variation * 3 + 4))) pixels++;
            clearLight += clear; currentLight += current; samples++;
        }
        double drift = difference(image, baseline, control), idleDrift = difference(noise, baseline, control);
        boolean visible = clearLight / samples >= 70 && pixels >= Math.max(3, samples * (late ? .08 : .01))
                && pixels < samples * .7 && currentLight >= clearLight * .35 && drift <= Math.max(8, idleDrift * 3 + 2)
                && (!late || pixels >= earlyPixels + Math.max(3, samples * .03));
        if (!late) earlyPixels = pixels;
        return new Result(samples, pixels, drift, visible);
    }

    boolean restored(BufferedImage image) {
        dimensions(baseline, image);
        return difference(image, baseline, target) <= Math.max(8, difference(noise, baseline, target) * 3 + 2)
                && difference(image, baseline, control) <= Math.max(8, difference(noise, baseline, control) * 3 + 2);
    }

    /** Interior of the front face for the fixture's fixed yaw 180 / pitch 18 camera. */
    static int[] face(int width, int height, double dx, double bottom, double distance, float projectionX, float projectionY) {
        double angle = Math.toRadians(18), cosine = Math.cos(angle), sine = Math.sin(angle);
        double left = 0, right = 1, top = 1, lower = 0;
        for (double y : new double[] {bottom + .15, bottom + .85}) {
            double depth = distance * cosine - y * sine;
            if (!(depth > 0) || !(projectionX > 0) || !(projectionY > 0))
                throw new IllegalStateException("Crack target projection is not ready");
            left = Math.max(left, .5 + .5 * (dx + .15) * projectionX / depth);
            right = Math.min(right, .5 + .5 * (dx + .85) * projectionX / depth);
            double screenY = .5 - .5 * (y * cosine + distance * sine) * projectionY / depth;
            top = Math.min(top, screenY); lower = Math.max(lower, screenY);
        }
        return new int[] {(int) Math.ceil(left * width), (int) Math.ceil(top * height),
                (int) Math.floor(right * width), (int) Math.floor(lower * height)};
    }

    private static void dimensions(BufferedImage first, BufferedImage second) {
        if (first.getWidth() != second.getWidth() || first.getHeight() != second.getHeight())
            throw new IllegalStateException("Crack proof requires an unchanged viewport");
    }
    private static void validate(int[] box, BufferedImage image) {
        if (box.length != 4 || box[0] < 0 || box[1] < 0 || box[2] > image.getWidth() || box[3] > image.getHeight()
                || box[2] - box[0] < 8 || box[3] - box[1] < 8)
            throw new IllegalStateException("Crack proof target is outside or too small in the framebuffer");
    }
    private static double light(int rgb) {
        return .2126 * (rgb >>> 16 & 255) + .7152 * (rgb >>> 8 & 255) + .0722 * (rgb & 255);
    }
    private static double difference(BufferedImage first, BufferedImage second, int[] box) {
        double difference = 0;
        for (int y = box[1]; y < box[3]; y++) for (int x = box[0]; x < box[2]; x++)
            difference += Math.abs(light(first.getRGB(x, y)) - light(second.getRGB(x, y)));
        return difference / ((box[2] - box[0]) * (box[3] - box[1]));
    }
    static final class Result {
        final int samples, darkPixels;
        final double controlDrift;
        final boolean visible;
        Result(int samples, int darkPixels, double controlDrift, boolean visible) {
            this.samples = samples; this.darkPixels = darkPixels; this.controlDrift = controlDrift; this.visible = visible;
        }
        String describe() { return "crackSamples=" + samples + " crackPixels=" + darkPixels
                + " controlDrift=" + controlDrift + " visible=" + visible; }
    }
}

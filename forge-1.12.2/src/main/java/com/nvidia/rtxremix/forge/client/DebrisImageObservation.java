/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;

/** Measures colored, lit debris against two empty frames and an unchanged backdrop control. */
final class DebrisImageObservation {
    private final BufferedImage baseline, noise;
    private final int[] target, control;
    private final boolean plant;
    private final double referenceLight, minimumLight;
    private final double[] referenceColor;
    private long frame = -1, hash;
    private int samples;

    DebrisImageObservation(BufferedImage baseline, BufferedImage noise, int[] target, int[] control, boolean plant) {
        this(baseline, noise, target, control, plant, false);
    }

    DebrisImageObservation(BufferedImage baseline, BufferedImage noise, int[] target, int[] control,
            boolean plant, boolean shaded) {
        dimensions(baseline, noise); validate(target, baseline); validate(control, baseline);
        if (target[0] < control[2] && target[2] > control[0] && target[1] < control[3] && target[3] > control[1])
            throw new IllegalArgumentException("Debris and backdrop-control regions overlap");
        this.baseline = baseline; this.noise = noise; this.target = target; this.control = control; this.plant = plant;
        referenceColor = shaded ? meanColor(noise, control) : null;
        referenceLight = shaded ? luminance(referenceColor[0], referenceColor[1], referenceColor[2]) : 0;
        // A sheltered reference must itself remain visibly lit. Comparing to a
        // black reference would make black particles a vacuous success.
        if (shaded && referenceLight < 24) throw new IllegalStateException("Shaded stone reference is too dark: " + referenceLight);
        minimumLight = shaded ? Math.max(12, referenceLight * .45) : 36;
    }

    Result sample(BufferedImage image, long renderedFrame, long geometryHash) {
        dimensions(baseline, image);
        int lit = 0, changed = 0; double light = 0;
        for (int y = target[1]; y < target[3]; y++) for (int x = target[0]; x < target[2]; x++) {
            int before = baseline.getRGB(x, y), now = image.getRGB(x, y);
            double threshold = Math.max(16, contrast(before, noise.getRGB(x, y)) * 4 + 6);
            if (contrast(before, now) < threshold) continue;
            changed++;
            int r = now >>> 16 & 255, g = now >>> 8 & 255, b = now & 255;
            double luminance = luminance(r, g, b);
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            // A black silhouette or its shadow cannot establish a lit surface.
            // Plant debris must retain green tint; stone allows warm daylight.
            boolean color = plant ? g >= r + 6 && g >= b + 6 : referenceColor != null
                    ? stoneColor(r, g, b, referenceColor) : max - min <= Math.max(24, max * .4);
            if (luminance >= minimumLight && color) { lit++; light += luminance; }
        }
        double drift = difference(baseline, image, control), idle = difference(baseline, noise, control);
        int area = (target[2] - target[0]) * (target[3] - target[1]);
        boolean visible = lit >= Math.max(12, area / 10000) && drift <= Math.max(8, idle * 3 + 3);
        if (visible && renderedFrame > frame && (samples == 0 || geometryHash != hash)) {
            frame = renderedFrame; hash = geometryHash; samples++;
        }
        return new Result(changed, lit, lit == 0 ? 0 : light / lit, drift, referenceLight, minimumLight, visible, samples);
    }

    boolean passed() { return samples >= 3; }

    /** Projects a bounded volume for the fixture's yaw 180 / pitch 18 camera. */
    static int[] box(int width, int height, double minX, double maxX, double minY, double maxY,
            double near, double far, float projectionX, float projectionY) {
        double sin = Math.sin(Math.toRadians(18)), cos = Math.cos(Math.toRadians(18));
        double left = 1, right = 0, top = 1, bottom = 0;
        for (double x : new double[] {minX, maxX}) for (double y : new double[] {minY, maxY})
            for (double distance : new double[] {near, far}) {
                double depth = distance * cos - y * sin;
                if (!(depth > 0) || !(projectionX > 0) || !(projectionY > 0))
                    throw new IllegalStateException("Debris projection is not ready");
                double sx = .5 + .5 * x * projectionX / depth;
                double sy = .5 - .5 * (y * cos + distance * sin) * projectionY / depth;
                left = Math.min(left, sx); right = Math.max(right, sx); top = Math.min(top, sy); bottom = Math.max(bottom, sy);
            }
        return new int[] {(int) Math.ceil(Math.max(0, left) * width), (int) Math.ceil(Math.max(0, top) * height),
                (int) Math.floor(Math.min(1, right) * width), (int) Math.floor(Math.min(1, bottom) * height)};
    }

    private static void dimensions(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
            throw new IllegalStateException("Debris proof requires an unchanged viewport");
    }
    private static void validate(int[] box, BufferedImage image) {
        if (box.length != 4 || box[0] < 0 || box[1] < 0 || box[2] > image.getWidth() || box[3] > image.getHeight()
                || box[2] - box[0] < 8 || box[3] - box[1] < 8)
            throw new IllegalStateException("Debris proof region is outside or too small in the framebuffer");
    }
    private static int contrast(int a, int b) {
        return Math.max(Math.abs((a >>> 16 & 255) - (b >>> 16 & 255)),
                Math.max(Math.abs((a >>> 8 & 255) - (b >>> 8 & 255)), Math.abs((a & 255) - (b & 255))));
    }
    private static double difference(BufferedImage a, BufferedImage b, int[] box) {
        long total = 0;
        for (int y = box[1]; y < box[3]; y++) for (int x = box[0]; x < box[2]; x++) total += contrast(a.getRGB(x, y), b.getRGB(x, y));
        return (double) total / ((box[2] - box[0]) * (box[3] - box[1]));
    }
    private static double[] meanColor(BufferedImage image, int[] box) {
        double[] sum = new double[3];
        for (int y = box[1]; y < box[3]; y++) for (int x = box[0]; x < box[2]; x++) {
            int rgb = image.getRGB(x, y); sum[0] += rgb >>> 16 & 255; sum[1] += rgb >>> 8 & 255; sum[2] += rgb & 255;
        }
        int count = (box[2] - box[0]) * (box[3] - box[1]);
        for (int i = 0; i < 3; i++) sum[i] /= count;
        return sum;
    }
    private static double luminance(double r, double g, double b) { return .2126 * r + .7152 * g + .0722 * b; }
    private static boolean stoneColor(int r, int g, int b, double[] reference) {
        double max = Math.max(r, Math.max(g, b)), refMax = Math.max(reference[0], Math.max(reference[1], reference[2]));
        return max > 0 && Math.abs(r / max - reference[0] / refMax) <= .25
                && Math.abs(g / max - reference[1] / refMax) <= .25
                && Math.abs(b / max - reference[2] / refMax) <= .25;
    }
    static final class Result {
        final int changed, lit, samples;
        final double luminance, controlDrift, referenceLight, minimumLight;
        final boolean visible;
        Result(int changed, int lit, double luminance, double controlDrift, double referenceLight,
                double minimumLight, boolean visible, int samples) {
            this.changed = changed; this.lit = lit; this.luminance = luminance;
            this.referenceLight = referenceLight; this.minimumLight = minimumLight;
            this.controlDrift = controlDrift; this.visible = visible; this.samples = samples;
        }
        String describe() { return "changedPixels=" + changed + " litPixels=" + lit + " luminance=" + luminance
                + " referenceLuminance=" + referenceLight + " minimumLuminance=" + minimumLight
                + " controlDrift=" + controlDrift + " visible=" + visible + " visibleFrames=" + samples; }
    }
}

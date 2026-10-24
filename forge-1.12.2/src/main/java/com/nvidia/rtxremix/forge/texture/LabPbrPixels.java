/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import java.nio.ByteBuffer;

/** LabPBR 1.3 normal, smoothness, metal classification and emission conversion. */
final class LabPbrPixels {
    static final int NORMAL = 0, ROUGHNESS = 1, METALLIC = 2, EMISSIVE = 3;
    static final int[] STRIDES = {2, 1, 1, 4};
    private static final byte[] EMISSION_COLORS = emissionColors();
    private LabPbrPixels() { }

    static int roughness(int argb) {
        double smoothness = ((argb >>> 16) & 255) / 255.0;
        return (int) ((1 - smoothness) * (1 - smoothness) * 255);
    }
    static int metallic(int argb) { return ((argb >>> 8) & 255) >= 230 ? 255 : 0; }
    static int emission(int argb) {
        int alpha = argb >>> 24;
        return alpha == 255 ? 0 : alpha * 255 / 254;
    }
    static int blend(int first, int second, float mix) {
        return Math.max(0, Math.min(255, (int) (first * (1 - mix) + second * mix)));
    }

    static void write(ByteBuffer target, int offset, int channel, int first, int second,
            float mix, int albedo) {
        if (channel == NORMAL) {
            int red = blend((first >>> 16) & 255, (second >>> 16) & 255, mix);
            int green = blend((first >>> 8) & 255, (second >>> 8) & 255, mix);
            double nx = red / 127.5 - 1, ny = green / 127.5 - 1;
            double nz = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
            // The pinned API 0.16 runtime's NORMAL_OTH takes ordinary octahedral
            // XY/L1, as checked against NORMAL_DX by GlInteropSmokeTest. LabPBR
            // reconstructs positive Z, so no lower-face fold is needed here.
            // Preserve the DirectX tangent convention (+Y down the image).
            double length = Math.abs(nx) + Math.abs(ny) + nz;
            target.put(offset, (byte) Math.round(127.5 * (nx / length + 1)))
                    .put(offset + 1, (byte) Math.round(127.5 * (ny / length + 1)));
        } else if (channel == ROUGHNESS) {
            target.put(offset, (byte) blend(roughness(first), roughness(second), mix));
        } else if (channel == METALLIC) {
            target.put(offset, (byte) blend(metallic(first), metallic(second), mix));
        } else {
            int intensity = blend(emission(first), emission(second), mix);
            target.put(offset, EMISSION_COLORS[((albedo >>> 16) & 255) * 256 + intensity])
                    .put(offset + 1, EMISSION_COLORS[((albedo >>> 8) & 255) * 256 + intensity])
                    .put(offset + 2, EMISSION_COLORS[(albedo & 255) * 256 + intensity])
                    .put(offset + 3, (byte) 255);
        }
    }

    private static byte[] emissionColors() {
        byte[] table = new byte[256 * 256];
        for (int color = 0; color < 256; color++) {
            double srgb = color / 255.0;
            double linear = srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
            for (int intensity = 0; intensity < 256; intensity++) {
                double emitted = linear * intensity / 255.0;
                double encoded = emitted <= 0.0031308 ? emitted * 12.92 : 1.055 * Math.pow(emitted, 1 / 2.4) - 0.055;
                table[color * 256 + intensity] = (byte) Math.round(encoded * 255);
            }
        }
        return table;
    }

    static void defaults(ByteBuffer target, int channel) {
        target.position(0);
        while (target.hasRemaining()) {
            if (channel == NORMAL) target.put((byte) 128).put((byte) 128);
            else if (channel == EMISSIVE) target.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 255);
            else target.put(channel == ROUGHNESS ? (byte) 255 : (byte) 0);
        }
        target.flip();
    }
}

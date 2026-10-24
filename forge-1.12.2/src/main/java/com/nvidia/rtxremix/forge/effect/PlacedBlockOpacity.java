/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.forge.texture.SpriteAnimationContract;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

/** Scope-local alpha proof for placement draws, with one shared per-frame scan budget. */
final class PlacedBlockOpacity {
    static final int UNKNOWN = 0, CUTOUT = 1, OPAQUE = 2;
    static final int MAX_PIXELS = 1024 * 1024;
    private final TextureAtlasSprite[] checked = new TextureAtlasSprite[32];
    private final int[] coverage = new int[checked.length];
    private int count, remaining = MAX_PIXELS;

    void beginFrame() { clear(); remaining = MAX_PIXELS; }
    void clear() {
        for (int i = 0; i < count; i++) checked[i] = null;
        count = 0;
    }

    int quad(BakedQuad quad) {
        TextureAtlasSprite sprite = quad.getSprite();
        VertexFormat format = quad.getFormat();
        if (sprite == null || !format.hasUvOffset(0)) return UNKNOWN;
        boolean uv = false;
        for (int i = 0; i < format.getElementCount(); i++) {
            VertexFormatElement element = format.getElement(i);
            if (element.getUsage() == VertexFormatElement.EnumUsage.UV && element.getIndex() == 0)
                uv = element.getType() == VertexFormatElement.EnumType.FLOAT && element.getElementCount() == 2;
        }
        float minU = sprite.getMinU(), minV = sprite.getMinV(), maxU = sprite.getMaxU(), maxV = sprite.getMaxV();
        if (!uv || !Float.isFinite(minU) || !Float.isFinite(minV) || !Float.isFinite(maxU)
                || !Float.isFinite(maxV) || minU >= maxU || minV >= maxV) return UNKNOWN;
        int stride = format.getIntegerSize(), offset = format.getUvOffsetById(0) / 4;
        int[] data = quad.getVertexData();
        if (format.getUvOffsetById(0) % 4 != 0 || offset < 0 || offset + 2 > stride || data.length != stride * 4)
            return UNKNOWN;
        for (int v = 0; v < 4; v++) {
            float u = Float.intBitsToFloat(data[v * stride + offset]);
            float t = Float.intBitsToFloat(data[v * stride + offset + 1]);
            if (!Float.isFinite(u) || !Float.isFinite(t) || u < minU || u > maxU || t < minV || t > maxV)
                return UNKNOWN;
        }
        for (int i = 0; i < count; i++) if (checked[i] == sprite) return coverage[i];
        if (count == checked.length) return UNKNOWN;
        int result = sprite(sprite);
        checked[count] = sprite; coverage[count++] = result;
        return result;
    }

    private int sprite(TextureAtlasSprite sprite) {
        if (SpriteAnimationContract.unsupportedPixels(sprite) != null) return UNKNOWN;
        int width = sprite.getIconWidth(), height = sprite.getIconHeight(), frames = sprite.getFrameCount();
        if (width <= 0 || height <= 0 || (long) width * height > remaining || frames <= 0 || frames > 256)
            return UNKNOWN;
        int result = OPAQUE;
        // Authored level-zero fractional alpha always keeps blending. Binary
        // sprites use the existing alpha test as cutouts, including filtered
        // mip edges. No source proof survives this placement draw.
        for (int frame = 0; frame < frames; frame++) {
            int[][] levels = sprite.getFrameTextureData(frame);
            if (levels == null || levels.length == 0 || levels.length > 31 || levels[0] == null) return UNKNOWN;
            int frameCoverage = OPAQUE;
            for (int mip = 0; mip < levels.length; mip++) {
                int[] pixels = levels[mip];
                if (pixels == null) continue;
                long expected = (long) Math.max(1, width >> mip) * Math.max(1, height >> mip);
                if (pixels.length != expected || pixels.length > remaining) return UNKNOWN;
                remaining -= pixels.length;
                if (mip == 0) {
                    for (int pixel : pixels) {
                        int alpha = pixel >>> 24;
                        if (alpha == 0) frameCoverage = CUTOUT;
                        else if (alpha != 255) return UNKNOWN;
                    }
                } else if (frameCoverage == OPAQUE) {
                    for (int pixel : pixels) if ((pixel >>> 24) != 255) return UNKNOWN;
                }
            }
            result = Math.min(result, frameCoverage);
        }
        return result;
    }
}

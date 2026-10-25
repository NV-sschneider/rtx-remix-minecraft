/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.forge.texture.SpriteOpacity;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Source-sprite proof for the physical cube draw inside an observed FBP digging scope. */
final class DebrisCapture {
    private final SpriteOpacity opacity = new SpriteOpacity();
    private final float[] bounds = new float[4];
    private TextureAtlasSprite sprite;
    private int depth;
    private boolean prepared, faded;

    void beginFrame() { clear(); opacity.beginFrame(); }
    void clear() { depth = 0; sprite = null; prepared = faded = false; opacity.clear(); }
    void begin(Object value) {
        sprite = depth++ == 0 && value instanceof TextureAtlasSprite ? (TextureAtlasSprite) value : null;
        prepared = faded = false;
    }
    void end() {
        if (depth > 0) depth--;
        // Nested/custom calls cannot lend their proof back to the enclosing draw.
        sprite = null; prepared = faded = false;
    }

    int prepare(BufferBuilder buffer) {
        prepared = faded = false;
        if (depth != 1 || sprite == null || !FinishedEffectBuffer.unpaddedCube(buffer)) return SpriteOpacity.UNKNOWN;
        bounds[0] = sprite.getMinU(); bounds[1] = sprite.getMinV();
        bounds[2] = sprite.getMaxU(); bounds[3] = sprite.getMaxV();
        for (float bound : bounds) if (!Float.isFinite(bound)) return SpriteOpacity.UNKNOWN;
        if (bounds[0] < 0 || bounds[1] < 0 || bounds[2] > 1 || bounds[3] > 1
                || bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) return SpriteOpacity.UNKNOWN;
        ByteBuffer source = buffer.getByteBuffer();
        if (!source.isDirect() || source.order() != ByteOrder.nativeOrder() || source.position() != 0)
            return SpriteOpacity.UNKNOWN;
        int limit = source.limit(), readable = FinishedEffectBuffer.readableLimit(buffer);
        if (readable < 24 * 31) return SpriteOpacity.UNKNOWN;
        if (readable != limit) source.limit(readable);
        try {
            for (int v = 0; v < 24; v++) {
                int at = v * 31;
                float u = source.getFloat(at + 12), w = source.getFloat(at + 16);
                if (!Float.isFinite(u) || !Float.isFinite(w) || u < bounds[0] || u > bounds[2]
                        || w < bounds[1] || w > bounds[3]) return SpriteOpacity.UNKNOWN;
                faded |= (source.get(at + 23) & 255) != 255;
            }
            prepared = true;
            return faded ? SpriteOpacity.UNKNOWN : opacity.sprite(sprite);
        } finally { if (readable != limit) source.limit(limit); }
    }

    float[] bounds() { return prepared ? bounds : null; }
    boolean faded() { return prepared && faded; }
}

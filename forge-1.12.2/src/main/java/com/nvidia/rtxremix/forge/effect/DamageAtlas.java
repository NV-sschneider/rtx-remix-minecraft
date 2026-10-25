/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.HardcodedVertex;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Compact, immutable darkening coverage for vanilla's ten multiplicative crack sprites. */
final class DamageAtlas {
    static final int STAGES = 10, MAX_SIDE = 512;
    private static final Logger LOGGER = LogManager.getLogger("RLCraftRemixEffects");
    private static final Field ROTATED = ReflectionHelper.findField(TextureAtlasSprite.class,
            new String[] {"rotated", "field_130222_e"});
    final ByteBuffer pixels;
    final int width, height;
    private final Region[] regions = new Region[STAGES];
    private ByteBuffer vertices;
    private boolean warned;

    static DamageAtlas load() {
        TextureMap map = Minecraft.getMinecraft().getTextureMapBlocks();
        Source[] sources = new Source[STAGES];
        for (int stage = 0; stage < STAGES; stage++) {
            String name = "minecraft:blocks/destroy_stage_" + stage;
            TextureAtlasSprite sprite = map.getAtlasSprite(name);
            try {
                if (!name.equals(sprite.getIconName()) || sprite.hasAnimationMetadata() || sprite.getFrameCount() != 1
                        || ROTATED.getBoolean(sprite)) {
                    LOGGER.warn("Omitting damage sprite {}: missing, animated, or rotated source", name);
                    continue;
                }
                int[][] mipmaps = sprite.getFrameTextureData(0);
                sources[stage] = new Source(sprite.getMinU(), sprite.getMinV(), sprite.getMaxU(), sprite.getMaxV(),
                        sprite.getIconWidth(), sprite.getIconHeight(), mipmaps == null || mipmaps.length == 0 ? null : mipmaps[0]);
            } catch (IllegalAccessException error) { throw new IllegalStateException("Cannot inspect damage sprite orientation", error); }
        }
        return new DamageAtlas(sources);
    }

    DamageAtlas(Source[] sources) {
        if (sources.length != STAGES) throw new IllegalArgumentException("Damage atlas requires ten stage slots");
        int cellWidth = 1, cellHeight = 1;
        for (int i = 0; i < STAGES; i++) {
            Source source = sources[i];
            if (source == null) continue;
            if (!source.valid()) {
                LOGGER.warn("Omitting damage sprite stage {}: malformed, colored, or larger than {} pixels", i, MAX_SIDE);
                continue;
            }
            regions[i] = new Region(source);
            cellWidth = Math.max(cellWidth, source.width); cellHeight = Math.max(cellHeight, source.height);
        }
        cellWidth += 2; cellHeight += 2;
        width = cellWidth * STAGES; height = cellHeight;
        pixels = CapturedEffectBatch.direct(width * height * 4);
        for (int i = 0; i < STAGES; i++) {
            Region region = regions[i];
            if (region == null) continue;
            Source source = sources[i]; region.x = i * cellWidth + 1;
            for (int y = -1; y <= source.height; y++) for (int x = -1; x <= source.width; x++) {
                int pixel = source.argb[Math.max(0, Math.min(source.height - 1, y)) * source.width
                        + Math.max(0, Math.min(source.width - 1, x))];
                int offset = ((y + 1) * width + region.x + x) * 4;
                pixels.put(offset, (byte) 0); pixels.put(offset + 1, (byte) 0); pixels.put(offset + 2, (byte) 0);
                pixels.put(offset + 3, (byte) coverage(pixel));
            }
            region.boundsMin = HardcodedVertex.packHalf2x16((float) region.x / width, 1f / height);
            region.boundsMax = HardcodedVertex.packHalf2x16((float) (region.x + region.width) / width,
                    (float) (1 + region.height) / height);
        }
    }

    /** The darkening half of vanilla's display-space multiply, expressed as linear decal coverage. */
    static int coverage(int argb) {
        // Pinned RenderGlobal rejects source alpha <= 0.1 before the multiply.
        // Its DST_COLOR/SRC_COLOR factors do not otherwise weight RGB by alpha.
        if ((argb >>> 24) <= 25) return 0;
        double transmission = Math.min(1, 2 * (argb & 255) / 255.0);
        // Remix blends decal albedo in linear space and uses a 2.2 gamma transfer.
        // Keeping the encoded transmission as linear opacity washes out dark cracks.
        // Brightening texels still cannot be represented by a black coverage decal.
        return (int) Math.round(255 * (1 - Math.pow(transmission, 2.2)));
    }

    ByteBuffer remap(CapturedEffectBatch batch) {
        if (!batch.damage || !batch.blockAtlas || batch.triangles || batch.quads <= 0
                || batch.quads > CapturedEffectBatch.MAX_QUADS) return unsupported();
        int bytes = batch.quads * 4 * HardcodedVertex.STRIDE;
        if (batch.vertices.limit() < bytes) return unsupported();
        if (vertices == null || vertices.capacity() < bytes) vertices = CapturedEffectBatch.direct(
                Math.min(CapturedEffectBatch.MAX_QUADS * 4 * HardcodedVertex.STRIDE,
                        Math.max(bytes, vertices == null ? 256 : vertices.capacity() * 2)));
        vertices.clear();
        // Copy into scene-owned scratch; captured buffers and their atlas UVs remain reusable.
        for (int offset = 0; offset < bytes; offset += 4) vertices.putInt(batch.vertices.getInt(offset));
        vertices.flip();
        for (int quad = 0; quad < batch.quads; quad++) {
            int offset = quad * 4 * HardcodedVertex.STRIDE;
            Region region = region(batch.vertices, offset);
            if (region == null) return unsupported();
            for (int v = 0; v < 4; v++) {
                int vertex = offset + v * HardcodedVertex.STRIDE;
                // Vanilla noColor writes white. A tinted custom multiply needs a distinct contract.
                if (batch.vertices.getInt(vertex + 32) != -1) return unsupported();
                float u = batch.vertices.getFloat(vertex + 24), w = batch.vertices.getFloat(vertex + 28);
                vertices.putFloat(vertex + 24, (region.x + (u - region.minU) / (region.maxU - region.minU) * region.width) / width);
                vertices.putFloat(vertex + 28, (1 + (w - region.minV) / (region.maxV - region.minV) * region.height) / height);
                vertices.putInt(vertex + 36, region.boundsMin); vertices.putInt(vertex + 40, region.boundsMax);
            }
        }
        return vertices;
    }

    private Region region(ByteBuffer source, int offset) {
        for (Region region : regions) {
            if (region == null) continue;
            boolean inside = true;
            for (int v = 0; v < 4; v++) {
                float u = source.getFloat(offset + v * HardcodedVertex.STRIDE + 24);
                float w = source.getFloat(offset + v * HardcodedVertex.STRIDE + 28);
                if (!Float.isFinite(u) || !Float.isFinite(w) || u < region.minU || u > region.maxU
                        || w < region.minV || w > region.maxV) { inside = false; break; }
            }
            if (inside) return region;
        }
        return null;
    }
    private ByteBuffer unsupported() {
        if (!warned) {
            LOGGER.warn("Omitting unsupported block damage geometry: expected white quads within a supported destroy sprite");
            warned = true;
        }
        return null;
    }
    long retainedBytes() { return pixels.capacity() + (vertices == null ? 0L : vertices.capacity()); }

    static final class Source {
        final float minU, minV, maxU, maxV;
        final int width, height;
        final int[] argb;
        Source(float minU, float minV, float maxU, float maxV, int width, int height, int[] argb) {
            this.minU = minU; this.minV = minV; this.maxU = maxU; this.maxV = maxV;
            this.width = width; this.height = height; this.argb = argb;
        }
        boolean valid() {
            if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || argb == null
                    || argb.length != width * height || !Float.isFinite(minU) || !Float.isFinite(minV)
                    || !Float.isFinite(maxU) || !Float.isFinite(maxV) || minU < 0 || minV < 0
                    || maxU > 1 || maxV > 1 || maxU <= minU || maxV <= minV) return false;
            for (int pixel : argb) if ((pixel >>> 24) > 25
                    && ((pixel >> 16 & 255) != (pixel & 255) || (pixel >> 8 & 255) != (pixel & 255))) return false;
            return true;
        }
    }
    private static final class Region {
        final float minU, minV, maxU, maxV;
        final int width, height;
        int x, boundsMin, boundsMax;
        Region(Source source) {
            minU = source.minU; minV = source.minV; maxU = source.maxU; maxV = source.maxV;
            width = source.width; height = source.height;
        }
    }
}

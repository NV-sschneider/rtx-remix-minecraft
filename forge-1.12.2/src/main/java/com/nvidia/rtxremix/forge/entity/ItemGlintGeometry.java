/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.util.vector.Matrix4f;

/** Preserves the generated item's alpha silhouette when its glint stands off the base surface. */
final class ItemGlintGeometry {
    static final int MAX_SPRITES = 64, MAX_PIXELS = 128 * 128, MAX_FRAMES = 64, MAX_RECTS = 256;
    private static final int MAX_MASK_BUILDS_PER_FRAME = 8;
    private static final int STRIDE = 28, MAX_QUADS = EntityTriangles.MAX_GROUP_VERTICES / 6;
    private final IdentityHashMap<TextureAtlasSprite, Mask> masks = new IdentityHashMap<>();
    private final boolean[] opaque = new boolean[MAX_PIXELS];
    private final int[] rectangles = new int[MAX_RECTS * 4], corners = new int[4];
    private ByteBuffer clipped;
    private int maskBuilds;
    private long useSequence;

    void beginFrame() { maskBuilds = 0; }
    void clear() { masks.clear(); clipped = null; maskBuilds = 0; useSequence = 0; }

    /** The supplied sprites describe the exact quads already emitted by Forge, in buffer order. */
    boolean append(EntityTriangles destination, ByteBuffer source, int vertexCount, int stride,
            int uvOffset, int colorOffset, int normalOffset, int argb, Matrix4f texture,
            TextureAtlasSprite[] sprites, int quadCount, int alphaReference) {
        if (vertexCount <= 0 || vertexCount % 4 != 0 || quadCount != vertexCount / 4
                || quadCount > sprites.length || stride < 12 || uvOffset < 12 || uvOffset + 8 > stride
                || colorOffset != -1 && (colorOffset < 12 || colorOffset + 4 > stride)
                || normalOffset < 12 || normalOffset + 3 > stride || (long) vertexCount * stride > source.limit()) return false;
        if (clipped == null) clipped = EntityMesh.direct(MAX_QUADS * 4 * STRIDE);
        clipped.clear();
        for (int quad = 0; quad < quadCount; quad++) {
            int first = quad * 4 * stride;
            if (!cardFace(source, first, stride)) {
                if (!space(1)) return false;
                for (int v = 0; v < 4; v++) copy(source, first + v * stride, uvOffset, colorOffset, normalOffset, argb);
                continue;
            }
            TextureAtlasSprite sprite = sprites[quad];
            if (sprite == null || !uvCorners(source, first, stride, uvOffset, sprite)) return false;
            Mask mask = mask(sprite, alphaReference);
            if (mask == null || mask.rectangles == null || !space(mask.rectangles.length / 4)) return false;
            float width = sprite.getIconWidth(), height = sprite.getIconHeight();
            for (int r = 0; r < mask.rectangles.length; r += 4) {
                float left = mask.rectangles[r] / width, top = mask.rectangles[r + 1] / height;
                float right = mask.rectangles[r + 2] / width, bottom = mask.rectangles[r + 3] / height;
                // Keep the source winding, including mirrored card faces.
                for (int vertex = 0; vertex < 4; vertex++) {
                    int corner = 0; while (corners[corner] != first + vertex * stride) corner++;
                    float u = (corner & 1) == 0 ? left : right, v = (corner & 2) == 0 ? top : bottom;
                    interpolate(source, uvOffset, colorOffset, normalOffset, argb, u, v);
                }
            }
        }
        clipped.flip();
        if (clipped.remaining() == 0) return true;
        return destination.appendPrimitives(clipped, clipped.remaining() / STRIDE, 4, STRIDE, 16, 12, 24,
                argb, texture, EntitySurface.ITEM_GLINT_SHELL_OFFSET);
    }

    private boolean space(int quads) { return quads <= (clipped.capacity() - clipped.position()) / (4 * STRIDE); }

    /** ItemModelGenerator's front and back cards span the unit square at these two exact planes. */
    static boolean cardFace(ByteBuffer source, int first, int stride) {
        float plane = source.getFloat(first + 8);
        if (plane != 7.5f / 16 && plane != 8.5f / 16) return false;
        int visited = 0;
        for (int v = 0; v < 4; v++) {
            int offset = first + v * stride;
            float x = source.getFloat(offset), y = source.getFloat(offset + 4);
            if (x != 0 && x != 1 || y != 0 && y != 1 || source.getFloat(offset + 8) != plane) return false;
            visited |= 1 << ((x == 1 ? 1 : 0) | (y == 1 ? 2 : 0));
        }
        return visited == 15;
    }

    private boolean uvCorners(ByteBuffer source, int first, int stride, int uvOffset, TextureAtlasSprite sprite) {
        float minU = sprite.getMinU(), maxU = sprite.getMaxU(), minV = sprite.getMinV(), maxV = sprite.getMaxV();
        if (!(maxU > minU) || !(maxV > minV)) return false;
        int visited = 0;
        for (int v = 0; v < 4; v++) {
            int offset = first + v * stride;
            float u = (source.getFloat(offset + uvOffset) - minU) / (maxU - minU);
            float t = (source.getFloat(offset + uvOffset + 4) - minV) / (maxV - minV);
            int x = endpoint(u), y = endpoint(t);
            if (x < 0 || y < 0) return false;
            int corner = x | (y << 1); corners[corner] = offset; visited |= 1 << corner;
        }
        return visited == 15;
    }
    private static int endpoint(float value) {
        return Math.abs(value) <= 1.0e-4f ? 0 : Math.abs(value - 1) <= 1.0e-4f ? 1 : -1;
    }

    private Mask mask(TextureAtlasSprite sprite, int threshold) {
        Mask found = masks.get(sprite);
        if (found != null && found.threshold == threshold) { found.used = ++useSequence; return found; }
        if (maskBuilds >= MAX_MASK_BUILDS_PER_FRAME) return null;
        maskBuilds++;
        int width = sprite.getIconWidth(), height = sprite.getIconHeight(), frames = sprite.getFrameCount();
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS || frames <= 0 || frames > MAX_FRAMES) {
            return remember(sprite, threshold, null);
        }
        int pixels = width * height;
        Arrays.fill(opaque, 0, pixels, false);
        boolean observed = false;
        // Vanilla generated side geometry, and the upstream capture, use the union of frame silhouettes.
        for (int frame = 0; frame < frames; frame++) {
            int[][] levels = sprite.getFrameTextureData(frame);
            if (levels == null) continue; // Explicit animation indices can leave sparse frame slots.
            if (levels.length == 0 || levels[0] == null || levels[0].length < pixels) {
                return remember(sprite, threshold, null);
            }
            observed = true;
            for (int pixel = 0; pixel < pixels; pixel++) opaque[pixel] |= (levels[0][pixel] >>> 24) > threshold;
        }
        if (!observed) return remember(sprite, threshold, null);
        int count = rectangles(opaque, width, height, rectangles);
        return remember(sprite, threshold, count < 0 ? null : Arrays.copyOf(rectangles, count * 4));
    }
    private Mask remember(TextureAtlasSprite sprite, int threshold, int[] rectangles) {
        if (masks.size() == MAX_SPRITES && !masks.containsKey(sprite)) {
            TextureAtlasSprite oldest = null; long used = Long.MAX_VALUE;
            for (Map.Entry<TextureAtlasSprite, Mask> entry : masks.entrySet()) {
                if (entry.getValue().used < used) { oldest = entry.getKey(); used = entry.getValue().used; }
            }
            masks.remove(oldest);
        }
        Mask mask = new Mask(threshold, rectangles); mask.used = ++useSequence; masks.put(sprite, mask); return mask;
    }

    /** Merge identical horizontal runs on adjacent rows; holes and separated runs remain holes. */
    static int rectangles(boolean[] pixels, int width, int height, int[] output) {
        int count = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width;) {
            if (!pixels[y * width + x]) { x++; continue; }
            int left = x++;
            while (x < width && pixels[y * width + x]) x++;
            int match = 0;
            while (match < count && !(output[match * 4] == left && output[match * 4 + 2] == x && output[match * 4 + 3] == y)) match++;
            if (match == count) {
                if (count == output.length / 4) return -1;
                output[count * 4] = left; output[count * 4 + 1] = y; output[count * 4 + 2] = x; count++;
            }
            output[match * 4 + 3] = y + 1;
        }
        return count;
    }

    private void copy(ByteBuffer source, int offset, int uv, int color, int normal, int argb) {
        for (int axis = 0; axis < 3; axis++) clipped.putFloat(source.getFloat(offset + axis * 4));
        if (color >= 0) for (int channel = 0; channel < 4; channel++) clipped.put(source.get(offset + color + channel));
        else clipped.put((byte) (argb >>> 16)).put((byte) (argb >>> 8)).put((byte) argb).put((byte) (argb >>> 24));
        clipped.putFloat(source.getFloat(offset + uv)).putFloat(source.getFloat(offset + uv + 4));
        for (int axis = 0; axis < 3; axis++) clipped.put(source.get(offset + normal + axis));
        clipped.put((byte) 0);
    }
    private void interpolate(ByteBuffer source, int uv, int color, int normal, int argb, float u, float v) {
        for (int axis = 0; axis < 3; axis++) clipped.putFloat(interpolateFloat(source, axis * 4, u, v));
        for (int channel = 0; channel < 4; channel++) {
            int value = color >= 0 ? Math.round(interpolateByte(source, color + channel, u, v, true))
                    : argb >>> (channel == 0 ? 16 : channel == 1 ? 8 : channel == 2 ? 0 : 24) & 255;
            clipped.put((byte) value);
        }
        clipped.putFloat(interpolateFloat(source, uv, u, v)).putFloat(interpolateFloat(source, uv + 4, u, v));
        for (int axis = 0; axis < 3; axis++) clipped.put((byte) Math.round(interpolateByte(source, normal + axis, u, v, false)));
        clipped.put((byte) 0);
    }
    private float interpolateFloat(ByteBuffer source, int offset, float u, float v) {
        return bilinear(source.getFloat(corners[0] + offset), source.getFloat(corners[1] + offset),
                source.getFloat(corners[2] + offset), source.getFloat(corners[3] + offset), u, v);
    }
    private float interpolateByte(ByteBuffer source, int offset, float u, float v, boolean unsigned) {
        int mask = unsigned ? 255 : -1;
        return bilinear(source.get(corners[0] + offset) & mask, source.get(corners[1] + offset) & mask,
                source.get(corners[2] + offset) & mask, source.get(corners[3] + offset) & mask, u, v);
    }
    private static float bilinear(float a, float b, float c, float d, float u, float v) {
        return (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v;
    }
    private static final class Mask {
        final int threshold;
        final int[] rectangles;
        long used;
        Mask(int threshold, int[] rectangles) { this.threshold = threshold; this.rectangles = rectangles; }
    }
}

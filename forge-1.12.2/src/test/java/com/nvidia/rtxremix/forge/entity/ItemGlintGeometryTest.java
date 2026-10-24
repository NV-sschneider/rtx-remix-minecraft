/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class ItemGlintGeometryTest {
    @Test public void bothCardFacesFollowOpaquePixelsWithTheirOriginalOrientationTintAndAnimatedUvs() {
        Sprite sprite = new Sprite(3, 3, new int[] {0, -1, 0, -1, -1, -1, 0, -1, 0});
        ByteBuffer source = cards(sprite, 2); source.position(3).mark().position(7);
        EntityTriangles output = new EntityTriangles(); ItemGlintGeometry geometry = new ItemGlintGeometry();
        Matrix4f texture = new Matrix4f(); texture.m00 = texture.m11 = 8; texture.m30 = 0.125f; texture.m31 = -0.25f;
        assertTrue(append(geometry, output, source, new TextureAtlasSprite[] {sprite, sprite}, texture));
        assertEquals(36, output.count());
        ByteBuffer vertices = output.info(0, output.count(), 1, 2).surfaces[0].vertexBuffer;
        double area = 0;
        for (int vertex = 0; vertex < output.count(); vertex++) {
            int offset = vertex * 64; float x = vertices.getFloat(offset), y = vertices.getFloat(offset + 4);
            boolean back = vertex >= 18;
            assertEquals((back ? 8.5f : 7.5f) / 16 + (back ? 1 : -1) * EntitySurface.ITEM_GLINT_SHELL_OFFSET,
                    vertices.getFloat(offset + 8), 1.0e-6f);
            assertEquals(back ? 1 : -1, vertices.getFloat(offset + 20), 0);
            float u = sprite.getMinU() + (sprite.getMaxU() - sprite.getMinU()) * (back ? 1 - x : x);
            float v = sprite.getMinV() + (sprite.getMaxV() - sprite.getMinV()) * (1 - y);
            assertEquals(u * 8 + 0.125f, vertices.getFloat(offset + 24), 1.0e-6f);
            assertEquals(v * 8 - 0.25f, vertices.getFloat(offset + 28), 1.0e-6f);
            assertEquals(0xff8040c0, vertices.getInt(offset + 32));
            if (vertex % 3 == 0) {
                float bx = vertices.getFloat(offset + 64), by = vertices.getFloat(offset + 68);
                float cx = vertices.getFloat(offset + 128), cy = vertices.getFloat(offset + 132);
                area += Math.abs((bx - x) * (cy - y) - (by - y) * (cx - x)) / 2;
                float middleX = (x + bx + cx) / 3, middleY = (y + by + cy) / 3;
                int pixelX = Math.min(2, (int) ((back ? 1 - middleX : middleX) * 3));
                int pixelY = Math.min(2, (int) ((1 - middleY) * 3));
                assertTrue("No native triangle may fill a transparent texel", pixelX == 1 || pixelY == 1);
            }
        }
        assertEquals(10.0 / 9, area, 1.0e-6);
        assertEquals(7, source.position()); source.reset(); assertEquals(3, source.position());
        assertEquals(7.5f / 16, source.getFloat(8), 0);
    }

    @Test public void animationSilhouettesUseTheFrameUnionAndRespectTheObservedAlphaThreshold() {
        Sprite sprite = new Sprite(2, 2, new int[] {-1, 0x0a000000, 0, 0});
        sprite.setFramesTextureData(Arrays.asList(new int[][] {{-1, 0x0a000000, 0, 0}}, new int[][] {{0, 0, 0, -1}}));
        EntityTriangles output = new EntityTriangles(); ItemGlintGeometry geometry = new ItemGlintGeometry();
        assertTrue(append(geometry, output, cards(sprite, 1), new TextureAtlasSprite[] {sprite}, new Matrix4f()));
        assertEquals(12, output.count());
        ByteBuffer vertices = output.info(0, 12, 1, 2).surfaces[0].vertexBuffer;
        for (int first = 0; first < 12; first += 3) {
            float x = 0, y = 0;
            for (int v = 0; v < 3; v++) { x += vertices.getFloat((first + v) * 64); y += vertices.getFloat((first + v) * 64 + 4); }
            assertEquals(x / 3 < 0.5f, y / 3 > 0.5f);
        }
    }

    @Test public void nonCardGeometryRetainsItsWholeFaceAndMissingMasksNeverPublishPartialItems() {
        Sprite sprite = new Sprite(2, 2, new int[] {-1, 0, 0, 0});
        ByteBuffer source = cards(sprite, 2);
        for (int v = 0; v < 4; v++) source.putFloat(v * 28 + 8, 0.25f);
        EntityTriangles output = new EntityTriangles(); ItemGlintGeometry geometry = new ItemGlintGeometry();
        assertFalse(append(geometry, output, source, new TextureAtlasSprite[] {null, null}, new Matrix4f()));
        assertEquals(0, output.count());
        assertTrue(append(geometry, output, source, new TextureAtlasSprite[] {null, sprite}, new Matrix4f()));
        assertEquals(12, output.count());
        ByteBuffer vertices = output.info(0, 12, 1, 2).surfaces[0].vertexBuffer;
        assertEquals(1, vertices.getFloat(64), 0);
        assertEquals(0.25f - EntitySurface.ITEM_GLINT_SHELL_OFFSET, vertices.getFloat(8), 0);
        source.putFloat(4 * 28 + 16, sprite.getMinU() + 0.1f);
        assertFalse(append(geometry, output, source, new TextureAtlasSprite[] {null, sprite}, new Matrix4f()));
        assertEquals(12, output.count());
    }

    @Test public void boundsOmitOnlyTheOverBudgetPassAndReloadReleasesCachedMasks() {
        ItemGlintGeometry geometry = new ItemGlintGeometry(); EntityTriangles output = new EntityTriangles();
        for (int i = 0; i < ItemGlintGeometry.MAX_SPRITES; i++) {
            geometry.beginFrame(); Sprite sprite = new Sprite(1, 1, new int[] {-1});
            assertTrue(append(geometry, output, cards(sprite, 1), new TextureAtlasSprite[] {sprite}, new Matrix4f()));
        }
        int before = output.count(); Sprite next = new Sprite(1, 1, new int[] {-1});
        geometry.beginFrame(); assertTrue(append(geometry, output, cards(next, 1), new TextureAtlasSprite[] {next}, new Matrix4f()));
        assertEquals(before + 6, output.count());
        next.setFramesTextureData(Collections.singletonList(new int[][] {{0}}));
        geometry.clear(); before = output.count();
        assertTrue(append(geometry, output, cards(next, 1), new TextureAtlasSprite[] {next}, new Matrix4f()));
        assertEquals(before, output.count());
        int[] checkerboard = new int[128 * 128];
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) if (((x + y) & 1) == 0) checkerboard[y * 128 + x] = -1;
        Sprite excessive = new Sprite(128, 128, checkerboard);
        before = output.count();
        assertFalse(append(geometry, output, cards(excessive, 1), new TextureAtlasSprite[] {excessive}, new Matrix4f()));
        assertEquals(before, output.count());
        Sprite transparent = new Sprite(1, 1, new int[] {0});
        assertTrue(append(geometry, output, cards(transparent, 1), new TextureAtlasSprite[] {transparent}, new Matrix4f()));
        assertEquals(before, output.count());
    }

    @Test public void aBurstOfNewMasksDefersWorkUntilTheNextFrameWithoutChangingPreviousGeometry() {
        ItemGlintGeometry geometry = new ItemGlintGeometry(); EntityTriangles output = new EntityTriangles();
        for (int i = 0; i < 8; i++) {
            Sprite sprite = new Sprite(1, 1, new int[] {-1});
            assertTrue(append(geometry, output, cards(sprite, 1), new TextureAtlasSprite[] {sprite}, new Matrix4f()));
        }
        Sprite deferred = new Sprite(1, 1, new int[] {-1}); int before = output.count();
        assertFalse(append(geometry, output, cards(deferred, 1), new TextureAtlasSprite[] {deferred}, new Matrix4f()));
        assertEquals(before, output.count()); geometry.beginFrame();
        assertTrue(append(geometry, output, cards(deferred, 1), new TextureAtlasSprite[] {deferred}, new Matrix4f()));
        assertEquals(before + 6, output.count());
    }

    @Test public void mergedRectanglesCoverEachOpaquePixelExactlyOnceWithoutFillingHoles() {
        int width = 9, height = 7; boolean[] mask = new boolean[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) mask[y * width + x] = (x * 13 + y * 7) % 5 < 3;
        int[] rectangles = new int[256]; int count = ItemGlintGeometry.rectangles(mask, width, height, rectangles);
        assertTrue(count > 0); int[] coverage = new int[mask.length];
        for (int rectangle = 0; rectangle < count; rectangle++) {
            int i = rectangle * 4;
            for (int y = rectangles[i + 1]; y < rectangles[i + 3]; y++) {
                for (int x = rectangles[i]; x < rectangles[i + 2]; x++) coverage[y * width + x]++;
            }
        }
        for (int pixel = 0; pixel < mask.length; pixel++) assertEquals(mask[pixel] ? 1 : 0, coverage[pixel]);
        assertEquals(-1, ItemGlintGeometry.rectangles(mask, width, height, new int[4]));
    }

    private static boolean append(ItemGlintGeometry geometry, EntityTriangles output, ByteBuffer source,
            TextureAtlasSprite[] sprites, Matrix4f texture) {
        return geometry.append(output, source, source.limit() / 28, 28, 16, 12, 24, -1, texture, sprites, sprites.length, 25);
    }
    private static ByteBuffer cards(Sprite sprite, int count) {
        ByteBuffer source = EntityMesh.direct(count * 4 * 28);
        for (int face = 0; face < count; face++) for (int v = 0; v < 4; v++) {
            float x = v == 1 || v == 2 ? 1 : 0, y = v >= 2 ? 1 : 0;
            boolean back = face != 0;
            source.putFloat(x).putFloat(y).putFloat((back ? 8.5f : 7.5f) / 16);
            source.put((byte) 0x80).put((byte) 0x40).put((byte) 0xc0).put((byte) 0xff);
            source.putFloat(sprite.getMinU() + (sprite.getMaxU() - sprite.getMinU()) * (back ? 1 - x : x));
            source.putFloat(sprite.getMinV() + (sprite.getMaxV() - sprite.getMinV()) * (1 - y));
            source.put((byte) 0).put((byte) 0).put((byte) (back ? 127 : -127)).put((byte) 0);
        }
        source.flip(); return source;
    }
    private static final class Sprite extends TextureAtlasSprite {
        Sprite(int width, int height, int[] pixels) {
            super("glint-fixture"); setIconWidth(width); setIconHeight(height);
            setFramesTextureData(Collections.singletonList(new int[][] {pixels}));
        }
        @Override public float getMinU() { return 0.25f; }
        @Override public float getMaxU() { return 0.5f; }
        @Override public float getMinV() { return 0.5f; }
        @Override public float getMaxV() { return 0.75f; }
    }
}

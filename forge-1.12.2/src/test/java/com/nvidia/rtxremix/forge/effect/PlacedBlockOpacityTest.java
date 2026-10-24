/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import java.util.Arrays;
import java.util.Collections;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlacedBlockOpacityTest {
    @Test public void sourceFramesDistinguishOpaqueCutoutAndFractionalAlphaAcrossNewDraws() {
        PlacedBlockOpacity proof = new PlacedBlockOpacity();
        TextureAtlasSprite sprite = sprite(2, 2);
        int[][] first = {{-1, -1, -1, -1}, {-1}}, second = {{-1, -1, -1, -1}, {-1}};
        sprite.setFramesTextureData(Arrays.asList(first, second));
        assertEquals(PlacedBlockOpacity.OPAQUE, proof.quad(quad(sprite)));
        proof.clear(); second[1][0] = 0x80ffffff;
        assertEquals(PlacedBlockOpacity.UNKNOWN, proof.quad(quad(sprite)));
        proof.clear(); second[1][0] = -1; second[0][3] = 0;
        assertEquals(PlacedBlockOpacity.CUTOUT, proof.quad(quad(sprite)));
        proof.clear(); second[1][0] = 0x80ffffff;
        assertEquals(PlacedBlockOpacity.CUTOUT, proof.quad(quad(sprite)));
        proof.clear(); second[0][3] = 0xfeffffff;
        assertEquals(PlacedBlockOpacity.UNKNOWN, proof.quad(quad(sprite)));
        proof.clear(); second[0][3] = -1; second[1][0] = -1;
        assertEquals(PlacedBlockOpacity.OPAQUE, proof.quad(quad(sprite)));
        proof.clear(); assertEquals(PlacedBlockOpacity.OPAQUE, proof.quad(quad(sprite(1, 1))));
    }

    @Test public void spriteProofCannotCoverUnknownPixelsOrMalformedCpuFrames() {
        PlacedBlockOpacity proof = new PlacedBlockOpacity();
        TextureAtlasSprite sprite = sprite(2, 2);
        BakedQuad escaped = quad(sprite);
        escaped.getVertexData()[4] = Float.floatToIntBits(sprite.getMinU() - .01f);
        assertEquals(PlacedBlockOpacity.UNKNOWN, proof.quad(escaped));
        escaped.getVertexData()[4] = Float.floatToIntBits(Float.NaN);
        assertEquals(PlacedBlockOpacity.UNKNOWN, proof.quad(escaped));
        sprite.setFramesTextureData(Collections.singletonList(new int[][] {{-1}}));
        assertEquals(PlacedBlockOpacity.UNKNOWN, proof.quad(quad(sprite))); proof.clear();
        sprite.setFramesTextureData(Collections.singletonList(new int[][] {null}));
        assertEquals(PlacedBlockOpacity.UNKNOWN, proof.quad(quad(sprite))); proof.clear();
        sprite.setFramesTextureData(Collections.<int[][]>emptyList());
        assertEquals(PlacedBlockOpacity.UNKNOWN, proof.quad(quad(sprite))); proof.clear();
        assertEquals(PlacedBlockOpacity.UNKNOWN, proof.quad(quad(new UnknownSprite())));
    }

    @Test public void repeatedFacesShareOneScanButTheFrameBudgetSurvivesNewDraws() {
        PlacedBlockOpacity proof = new PlacedBlockOpacity();
        BakedQuad large = quad(sprite(1024, 1024)), small = quad(sprite(1, 1));
        assertEquals(PlacedBlockOpacity.OPAQUE, proof.quad(large));
        for (int face = 0; face < 6; face++) assertEquals(PlacedBlockOpacity.OPAQUE, proof.quad(large));
        assertEquals(PlacedBlockOpacity.UNKNOWN, proof.quad(small));
        proof.clear(); assertEquals(PlacedBlockOpacity.UNKNOWN, proof.quad(small));
        proof.beginFrame(); assertEquals(PlacedBlockOpacity.OPAQUE, proof.quad(small));
        proof.beginFrame();
        for (int i = 0; i < 32; i++) assertEquals(PlacedBlockOpacity.OPAQUE, proof.quad(quad(sprite(1, 1))));
        assertEquals(PlacedBlockOpacity.UNKNOWN, proof.quad(quad(sprite(1, 1))));
    }

    static TextureAtlasSprite sprite(int width, int height) {
        TextureAtlasSprite sprite = UnknownSprite.vanilla();
        sprite.setIconWidth(width); sprite.setIconHeight(height);
        sprite.initSprite(width, height, 0, 0, false);
        int[] pixels = new int[width * height]; Arrays.fill(pixels, -1);
        sprite.setFramesTextureData(Collections.singletonList(new int[][] {pixels}));
        return sprite;
    }

    static BakedQuad quad(TextureAtlasSprite sprite) {
        int[] data = new int[28];
        for (int v = 0; v < 4; v++) {
            data[v * 7 + 3] = -1;
            data[v * 7 + 4] = Float.floatToIntBits(v % 2 == 0 ? sprite.getMinU() : sprite.getMaxU());
            data[v * 7 + 5] = Float.floatToIntBits(v / 2 == 0 ? sprite.getMinV() : sprite.getMaxV());
        }
        return new BakedQuad(data, -1, EnumFacing.UP, sprite, true, DefaultVertexFormats.BLOCK);
    }

    private static final class UnknownSprite extends TextureAtlasSprite {
        UnknownSprite() {
            super("test:unknown"); setIconWidth(1); setIconHeight(1); initSprite(1, 1, 0, 0, false);
            setFramesTextureData(Collections.singletonList(new int[][] {{-1}}));
        }
        static TextureAtlasSprite vanilla() { return makeAtlasSprite(new ResourceLocation("test:placement")); }
    }
}

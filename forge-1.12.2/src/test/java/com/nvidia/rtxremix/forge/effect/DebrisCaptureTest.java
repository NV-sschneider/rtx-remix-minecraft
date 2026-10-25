/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.forge.texture.SpriteOpacity;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class DebrisCaptureTest {
    private static final long BLEND = EffectBlend.key(true, 770, 771, 32774, 770, 771, 32774, true, 516, 1);

    @Test public void opaqueCubeUsesOrdinaryLightingWithoutChangingItsDarkTintNormalsOrSource() {
        TextureAtlasSprite sprite = PlacedBlockOpacityTest.sprite(4, 4);
        BufferBuilder source = cube(sprite, 255);
        int originalLimit = source.getByteBuffer().limit();
        DebrisCapture proof = new DebrisCapture(); proof.beginFrame(); proof.begin(sprite);
        int coverage = proof.prepare(source);
        assertEquals(SpriteOpacity.OPAQUE, coverage);
        InstanceInfoBlendEXT state = EffectBlend.extension(EffectBlend.opaqueGeometry(BLEND, coverage));
        assertEquals(0, state.alphaBlendEnabled); assertEquals(1, state.alphaTestReferenceValue);
        assertEquals(4, state.alphaTestCompareOp); assertFalse(proof.faded());
        CapturedEffectBatch target = new CapturedEffectBatch();
        assertEquals(6, FinishedEffectBuffer.append(new EffectVertexDecoder(), source, null, -1,
                new Matrix4f(), 6, target, proof.bounds()));
        assertEquals(originalLimit, source.getByteBuffer().limit()); assertEquals(0, source.getByteBuffer().position());
        for (int v = 0; v < 24; v++) {
            int at = v * HardcodedVertex.STRIDE;
            assertEquals(0xff183020, target.vertices.getInt(at + 32));
            assertEquals(1, target.vertices.getFloat(at + 20), 0);
            assertEquals(HardcodedVertex.packHalf2x16(sprite.getMinU(), sprite.getMinV()), target.vertices.getInt(at + 36));
            assertEquals(HardcodedVertex.packHalf2x16(sprite.getMaxU(), sprite.getMaxV()), target.vertices.getInt(at + 40));
        }
        assertEquals(0, EffectScene.material(42, false).emissiveTexture);
    }

    @Test public void actualFadeTransitionRetainsInterpolatedAlphaAndBlendEvenWithOpaqueSprite() {
        TextureAtlasSprite sprite = PlacedBlockOpacityTest.sprite(4, 4);
        DebrisCapture proof = new DebrisCapture(); proof.beginFrame();
        for (int alpha : new int[] {255, 254, 192, 128, 1, 0}) {
            proof.begin(sprite); BufferBuilder source = cube(sprite, alpha);
            int coverage = proof.prepare(source);
            assertEquals(alpha == 255 ? SpriteOpacity.OPAQUE : SpriteOpacity.UNKNOWN, coverage);
            assertEquals(alpha != 255, proof.faded());
            assertEquals(alpha == 255 ? 0 : 1,
                    EffectBlend.extension(EffectBlend.opaqueGeometry(BLEND, coverage)).alphaBlendEnabled);
            CapturedEffectBatch target = new CapturedEffectBatch();
            FinishedEffectBuffer.append(new EffectVertexDecoder(), source, null, -1, new Matrix4f(), 6, target, proof.bounds());
            for (int v = 0; v < 24; v++) assertEquals(alpha, target.vertices.getInt(v * HardcodedVertex.STRIDE + 32) >>> 24);
            proof.end();
        }
    }

    @Test public void sourceFractionalAlphaAndFadedSingleVertexNeverBorrowAnOpaqueNeighborsProof() {
        TextureAtlasSprite sprite = PlacedBlockOpacityTest.sprite(2, 2);
        int[][] opaque = {{-1, -1, -1, -1}}, fractional = {{-1, -1, -1, 0x80ffffff}};
        sprite.setFramesTextureData(Arrays.asList(opaque, fractional));
        DebrisCapture proof = new DebrisCapture(); proof.beginFrame(); proof.begin(sprite);
        assertEquals(SpriteOpacity.UNKNOWN, proof.prepare(cube(sprite, 255)));
        assertFalse(proof.faded()); assertNotNull(proof.bounds());
        proof.end(); fractional[0][3] = 0; proof.beginFrame(); proof.begin(sprite);
        assertEquals(SpriteOpacity.CUTOUT, proof.prepare(cube(sprite, 255)));
        assertEquals(0, EffectBlend.extension(EffectBlend.opaqueGeometry(BLEND, SpriteOpacity.CUTOUT)).alphaBlendEnabled);
        long noTest = EffectBlend.key(true, 770, 771, 32774, 770, 771, 32774, false, 516, 1);
        assertEquals(noTest, EffectBlend.opaqueGeometry(noTest, SpriteOpacity.CUTOUT));
        BufferBuilder mixed = cube(sprite, 255);
        mixed.getByteBuffer().put(23, (byte) 0);
        assertEquals(SpriteOpacity.UNKNOWN, proof.prepare(mixed)); assertTrue(proof.faded());
    }

    @Test public void scopeAndExactDrawContractExcludeBillboardsNestedDrawsAndStaleParticles() {
        TextureAtlasSprite sprite = PlacedBlockOpacityTest.sprite(1, 1);
        DebrisCapture proof = new DebrisCapture(); BufferBuilder source = cube(sprite, 255);
        assertEquals(SpriteOpacity.UNKNOWN, proof.prepare(source)); assertNull(proof.bounds());
        proof.begin(sprite); assertEquals(SpriteOpacity.OPAQUE, proof.prepare(source));
        proof.begin(sprite); assertEquals(SpriteOpacity.UNKNOWN, proof.prepare(source));
        proof.end(); assertEquals(SpriteOpacity.UNKNOWN, proof.prepare(source));
        proof.end(); proof.begin(sprite);
        BufferBuilder billboard = new BufferBuilder(128); billboard.begin(7, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        for (int v = 0; v < 24; v++) billboard.pos(v & 1, v >> 1 & 1, 0).tex(.5, .5).color(0, 0, 0, 255).lightmap(0, 0).endVertex();
        billboard.finishDrawing();
        assertEquals(SpriteOpacity.UNKNOWN, proof.prepare(billboard)); assertNull(proof.bounds());
        proof.clear(); assertEquals(SpriteOpacity.UNKNOWN, proof.prepare(source));
    }

    @Test public void boundsAndFinishedBufferExtentMustCoverTheWholeDrawIncludingLastVertex() {
        TextureAtlasSprite sprite = PlacedBlockOpacityTest.sprite(4, 4);
        DebrisCapture proof = new DebrisCapture(); proof.begin(sprite);
        BufferBuilder source = cube(sprite, 255); ByteBuffer bytes = source.getByteBuffer();
        int limit = bytes.limit();
        bytes.limit(bytes.capacity()); bytes.putFloat(23 * 31 + 12, sprite.getMaxU() + .01f); bytes.limit(limit);
        assertEquals(SpriteOpacity.UNKNOWN, proof.prepare(source)); assertNull(proof.bounds()); assertEquals(limit, bytes.limit());
        bytes.limit(bytes.capacity()); bytes.putFloat(23 * 31 + 12, .5f); bytes.limit(limit - 1);
        assertEquals(SpriteOpacity.UNKNOWN, proof.prepare(source)); assertNull(proof.bounds()); assertEquals(limit - 1, bytes.limit());
        bytes.limit(limit).position(1);
        assertEquals(SpriteOpacity.UNKNOWN, proof.prepare(source)); assertEquals(1, bytes.position());
        bytes.position(0); assertEquals(SpriteOpacity.OPAQUE, proof.prepare(source));
        assertEquals(limit, bytes.limit());
    }

    @Test public void mixedSpriteDrawsKeepPerVertexBoundsAndNewFramesRevokeChangedSourceProof() {
        TextureAtlasSprite first = PlacedBlockOpacityTest.sprite(4, 4), second = PlacedBlockOpacityTest.sprite(4, 4);
        first.initSprite(32, 32, 0, 0, false); second.initSprite(32, 32, 16, 16, false);
        DebrisCapture proof = new DebrisCapture(); proof.beginFrame();
        CapturedEffectBatch mixed = new CapturedEffectBatch();
        for (TextureAtlasSprite sprite : new TextureAtlasSprite[] {first, second}) {
            proof.begin(sprite); BufferBuilder source = cube(sprite, 255);
            assertEquals(SpriteOpacity.OPAQUE, proof.prepare(source));
            FinishedEffectBuffer.append(new EffectVertexDecoder(), source, null, -1, new Matrix4f(), 6, mixed, proof.bounds());
            proof.end(); assertNull(proof.bounds());
        }
        assertEquals(12, mixed.quads);
        assertEquals(HardcodedVertex.packHalf2x16(first.getMinU(), first.getMinV()), mixed.vertices.getInt(36));
        assertEquals(HardcodedVertex.packHalf2x16(second.getMinU(), second.getMinV()),
                mixed.vertices.getInt(24 * HardcodedVertex.STRIDE + 36));
        second.getFrameTextureData(0)[0][0] = 0x80ffffff;
        proof.beginFrame(); proof.begin(second);
        assertEquals(SpriteOpacity.UNKNOWN, proof.prepare(cube(second, 255)));
    }

    static BufferBuilder cube(TextureAtlasSprite sprite, int alpha) {
        BufferBuilder result = new BufferBuilder(1024);
        result.begin(7, new VertexFormat().addElement(DefaultVertexFormats.POSITION_3F)
                .addElement(DefaultVertexFormats.TEX_2F).addElement(DefaultVertexFormats.COLOR_4UB)
                .addElement(DefaultVertexFormats.TEX_2S).addElement(DefaultVertexFormats.NORMAL_3B));
        float[] xy = {0, 0, 1, 0, 1, 1, 0, 1};
        for (int face = 0; face < 6; face++) for (int v = 0; v < 4; v++)
            result.pos(xy[v * 2], xy[v * 2 + 1], face).tex(
                    sprite.getMinU() + (.25 + xy[v * 2] * .25) * (sprite.getMaxU() - sprite.getMinU()),
                    sprite.getMinV() + (.25 + xy[v * 2 + 1] * .25) * (sprite.getMaxV() - sprite.getMinV()))
                    .color(24, 48, 32, alpha).lightmap(0, 0).normal(0, 0, 1).endVertex();
        result.finishDrawing(); return result;
    }
}

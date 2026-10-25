/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.forge.texture.SpriteOpacity;

import java.util.Collections;
import java.util.List;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.model.pipeline.IVertexConsumer;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlacedBlockColorsTest {
    @Test public void opaquePlacementRequiresMatchingDrawAndEveryOriginalVertexAlpha() {
        PlacedBlockColors observer = new PlacedBlockColors();
        BakedQuad quad = PlacedBlockOpacityTest.quad(PlacedBlockOpacityTest.sprite(2, 2));
        BufferBuilder draw = draw(4);
        observer.begin(new Model(Collections.singletonList(quad)), index -> 0xffffff);
        observer.getQuads(null, null, 0); observer.finish(draw, true);
        assertEquals(SpriteOpacity.UNKNOWN, observer.coverage(draw(4)));
        assertEquals(SpriteOpacity.OPAQUE, observer.coverage(draw));
        assertNotNull(observer.consume(draw)); assertEquals(SpriteOpacity.UNKNOWN, observer.coverage(draw));
        quad.getVertexData()[3] = 0x80ffffff;
        observer.begin(new Model(Collections.singletonList(quad)), index -> 0xffffff);
        observer.getQuads(null, null, 0); observer.finish(draw, true);
        assertEquals(SpriteOpacity.UNKNOWN, observer.coverage(draw));
        assertEquals(0x80ffffff, observer.consume(draw)[0]);
        quad.getVertexData()[3] = 0x00ffffff;
        observer.begin(new Model(Collections.singletonList(quad)), index -> 0xffffff);
        observer.getQuads(null, null, 0); observer.finish(draw, true);
        assertEquals("Mixed binary vertex alpha interpolates fractional coverage", SpriteOpacity.UNKNOWN, observer.coverage(draw));
    }

    @Test public void grassStyleOpaqueFacesAndBinaryOverlaysShareCutoutCoverageWithoutLosingColors() {
        PlacedBlockColors observer = new PlacedBlockColors();
        TextureAtlasSprite opaque = PlacedBlockOpacityTest.sprite(2, 2), cutout = PlacedBlockOpacityTest.sprite(2, 2);
        cutout.setFramesTextureData(Collections.singletonList(new int[][] {{-1, -1, 0, 0}, {0x80ffffff}}));
        List<BakedQuad> quads = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) quads.add(PlacedBlockOpacityTest.quad(opaque));
        for (int i = 0; i < 4; i++) quads.add(PlacedBlockOpacityTest.quad(cutout));
        BufferBuilder draw = draw(40);
        observer.begin(new Model(quads), index -> 0xffffff);
        observer.getQuads(null, null, 0); observer.finish(draw, true);
        assertEquals(SpriteOpacity.CUTOUT, observer.coverage(draw));
        int[] colors = observer.consume(draw);
        for (int i = 0; i < 40; i++) assertEquals(-1, colors[i]);
        cutout.getFrameTextureData(0)[0][2] = 0x01ffffff;
        observer.begin(new Model(quads), index -> 0xffffff);
        observer.getQuads(null, null, 0); observer.finish(draw, true);
        assertEquals(SpriteOpacity.UNKNOWN, observer.coverage(draw));
        assertNotNull(observer.consume(draw));
    }

    @Test public void delegatesExactlyOnceAndRetainsOriginalColorAlphaAndBiomeTint() {
        BakedQuad quad = quad();
        Model model = new Model(Collections.singletonList(quad));
        PlacedBlockColors observer = new PlacedBlockColors();
        observer.begin(model, index -> { assertEquals(2, index); return 0x8040ff; });
        assertSame(model.quads, observer.getQuads(null, EnumFacing.UP, 432));
        assertEquals(1, model.calls); assertEquals(432, model.seed);
        BufferBuilder draw = draw(4);
        observer.finish(draw, true);
        int[] colors = observer.consume(draw);
        assertNotNull(colors);
        // Model RGBA=(128,64,32,120), biome tint=(128,64,255).
        for (int v = 0; v < 4; v++) assertEquals(0x78401020, colors[v]);
        assertNull(observer.consume(draw));
        assertEquals(0x78204080, quad.getVertexData()[3]);
    }

    @Test public void failuresWrongBufferAndChangedVertexCountsCannotReusePreviousColors() {
        for (int reason = 0; reason < 3; reason++) {
            PlacedBlockColors observer = new PlacedBlockColors();
            observer.begin(new Model(Collections.singletonList(quad())), index -> 0xffffff);
            observer.getQuads(null, null, 0);
            BufferBuilder draw = draw(reason == 1 ? 8 : 4);
            observer.finish(draw, reason != 0);
            assertNull(observer.consume(reason == 2 ? draw(4) : draw));
            assertNull(observer.consume(draw));
        }
    }

    @Test public void customPipelinesAndCapacityExhaustionFailClosed() {
        BakedQuad custom = new BakedQuad(quad().getVertexData(), 2, EnumFacing.UP, null, true, DefaultVertexFormats.BLOCK) {
            @Override public void pipe(IVertexConsumer consumer) { }
        };
        for (List<BakedQuad> quads : java.util.Arrays.asList(Collections.singletonList(custom),
                Collections.nCopies(CapturedEffectBatch.MAX_QUADS + 1, quad()))) {
            PlacedBlockColors observer = new PlacedBlockColors();
            observer.begin(new Model(quads), index -> 0xffffff);
            observer.getQuads(null, null, 0);
            BufferBuilder draw = draw(quads.size() * 4);
            observer.finish(draw, true);
            assertNull(observer.consume(draw));
        }
    }

    private static BakedQuad quad() {
        int[] data = new int[28];
        for (int v = 0; v < 4; v++) data[v * 7 + 3] = 0x78204080;
        return new BakedQuad(data, 2, EnumFacing.UP, null, true, DefaultVertexFormats.BLOCK);
    }
    private static BufferBuilder draw(final int vertices) {
        return new BufferBuilder(1) {
            @Override public int getDrawMode() { return 7; }
            @Override public int getVertexCount() { return vertices; }
        };
    }
    private static final class Model implements IBakedModel {
        final List<BakedQuad> quads;
        int calls; long seed;
        Model(List<BakedQuad> quads) { this.quads = quads; }
        @Override public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long seed) { calls++; this.seed = seed; return quads; }
        @Override public boolean isAmbientOcclusion() { return true; }
        @Override public boolean isGui3d() { return true; }
        @Override public boolean isBuiltInRenderer() { return false; }
        @Override public TextureAtlasSprite getParticleTexture() { return null; }
        @Override public ItemOverrideList getOverrides() { return ItemOverrideList.NONE; }
    }
}

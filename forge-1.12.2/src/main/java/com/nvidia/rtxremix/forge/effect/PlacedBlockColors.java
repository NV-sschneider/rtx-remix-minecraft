/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.forge.texture.SpriteOpacity;

import java.util.List;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.model.pipeline.IVertexConsumer;

/** Records original model albedo while the existing placement renderer evaluates its model once. */
final class PlacedBlockColors implements IBakedModel {
    interface Tint { int color(int index); }
    private static final ClassValue<Boolean> STANDARD_PIPE = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try { return type.getMethod("pipe", IVertexConsumer.class).getDeclaringClass() == BakedQuad.class; }
            catch (ReflectiveOperationException error) { return false; }
        }
    };
    private final int[] colors = new int[CapturedEffectBatch.MAX_QUADS * 4];
    private final SpriteOpacity opacity = new SpriteOpacity();
    private IBakedModel delegate;
    private Tint tint;
    private BufferBuilder pending;
    private int count;
    private boolean valid;
    private int coverage;

    void beginFrame() { clear(); opacity.beginFrame(); }

    void begin(IBakedModel model, Tint tint) {
        clear(); delegate = model; this.tint = tint; valid = true; coverage = SpriteOpacity.OPAQUE;
    }

    void finish(BufferBuilder buffer, boolean success) {
        // Both pinned renderers consume every returned quad when checkSides=false.
        // A custom pipe, truncation, changed format, or reordered renderer cannot
        // turn an unrelated draw into an unlit placement payload.
        valid &= success && buffer.getDrawMode() == 7 && buffer.getVertexCount() == count;
        pending = valid ? buffer : null;
        delegate = null; tint = null;
    }

    int[] consume(BufferBuilder buffer) {
        int[] result = pending == buffer && valid && buffer.getVertexCount() == count ? colors : null;
        clear();
        return result;
    }

    int coverage(BufferBuilder buffer) {
        return pending == buffer && valid && count != 0 && buffer.getVertexCount() == count
                ? coverage : SpriteOpacity.UNKNOWN;
    }

    void clear() {
        delegate = null; tint = null; pending = null; count = 0; valid = false; coverage = SpriteOpacity.UNKNOWN;
        opacity.clear();
    }

    @Override public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long seed) {
        List<BakedQuad> result = delegate.getQuads(state, side, seed);
        for (int q = 0; valid && q < result.size(); q++) {
            BakedQuad quad = result.get(q);
            try {
                int color = quad.hasTintIndex() ? tint.color(quad.getTintIndex()) : 0xffffff;
                append(quad, color);
            } catch (RuntimeException unsupported) { valid = false; }
        }
        return result;
    }

    void append(BakedQuad quad, int tint) {
        VertexFormat format = quad.getFormat();
        int[] data = quad.getVertexData();
        int stride = format.getSize();
        if (!valid || !STANDARD_PIPE.get(quad.getClass()) || stride < 20 || stride > 64 || (stride & 3) != 0
                || data.length != stride || count > colors.length - 4) { valid = false; return; }
        if (format.hasColor()) {
            for (int i = 0; i < format.getElementCount(); i++) {
                VertexFormatElement element = format.getElement(i);
                if (element.getUsage() == VertexFormatElement.EnumUsage.COLOR
                        && (element.getType() != VertexFormatElement.EnumType.UBYTE || element.getElementCount() != 4)) {
                    valid = false; return;
                }
            }
        }
        if (coverage != SpriteOpacity.UNKNOWN) coverage = Math.min(coverage, opacity.quad(quad));
        for (int v = 0; v < 4; v++) {
            int at = v * stride + format.getColorOffset();
            colors[count++] = !format.hasColor() ? 0xff000000 | tint & 0xffffff
                    : channel(data, at + 3) << 24 | channel(data, at) * (tint >> 16 & 255) / 255 << 16
                    | channel(data, at + 1) * (tint >> 8 & 255) / 255 << 8 | channel(data, at + 2) * (tint & 255) / 255;
            if (colors[count - 1] >>> 24 != 255) coverage = SpriteOpacity.UNKNOWN;
        }
    }

    private static int channel(int[] data, int at) { return data[at / 4] >>> ((at & 3) * 8) & 255; }
    @Override public boolean isAmbientOcclusion() { return delegate.isAmbientOcclusion(); }
    @Override public boolean isAmbientOcclusion(IBlockState state) { return delegate.isAmbientOcclusion(state); }
    @Override public boolean isGui3d() { return delegate.isGui3d(); }
    @Override public boolean isBuiltInRenderer() { return delegate.isBuiltInRenderer(); }
    @Override public TextureAtlasSprite getParticleTexture() { return delegate.getParticleTexture(); }
    @Override public ItemCameraTransforms getItemCameraTransforms() { return delegate.getItemCameraTransforms(); }
    @Override public ItemOverrideList getOverrides() { return delegate.getOverrides(); }
    @Override public org.apache.commons.lang3.tuple.Pair<? extends IBakedModel, javax.vecmath.Matrix4f> handlePerspective(
            ItemCameraTransforms.TransformType type) { return delegate.handlePerspective(type); }
}

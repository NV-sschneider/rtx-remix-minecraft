/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/** Fixed-capacity callback buffer; neither model reservations nor emitted vertices can grow it. */
final class BlockCaptureBuffer extends BufferBuilder implements BlockQuadSink {
    private final float[] metadata = new float[BlockMeshDecoder.MAX_VERTICES / 4 * BlockMeshDecoder.METADATA_STRIDE];
    private final BlockMeshDecoder decoder = new BlockMeshDecoder();
    private float minU, minV, maxU, maxV, nx, ny, nz;
    private int foliageVertices;
    private boolean foliage;
    private int originX, originY, originZ;
    private long hash;
    private boolean building;

    BlockCaptureBuffer() { super((BlockMeshDecoder.MAX_VERTICES + 8) * 7); }

    void beginBlock(int x, int y, int z) {
        if (DefaultVertexFormats.BLOCK.getSize() != 28 || DefaultVertexFormats.BLOCK.getColorOffset() != 12
                || DefaultVertexFormats.BLOCK.getUvOffsetById(0) != 16)
            throw new IllegalStateException("Better Foliage capture requires the standard 28-byte BLOCK format");
        originX = x; originY = y; originZ = z; foliageVertices = 0;
        hash = 0xcbf29ce484222325L;
        minU = minV = maxU = maxV = Float.NaN; nx = Float.NaN; foliage = false;
        setTranslation(-x, -y, -z); begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK); building = true;
    }

    void reserveQuads(int quads) {
        // The mod reserves one spare quad. The eight spare vertices also cover BufferBuilder's next-vertex check.
        if (quads < 0 || (long) getVertexCount() + (long) quads * 4 > BlockMeshDecoder.MAX_VERTICES + 4)
            throw new IllegalStateException("Better Foliage model exceeds the bounded per-block capture buffer");
    }

    void sprite(TextureAtlasSprite sprite) {
        minU = sprite.getMinU(); minV = sprite.getMinV(); maxU = sprite.getMaxU(); maxV = sprite.getMaxV();
        nx = Float.NaN; foliage = true;
    }

    @Override public void endVertex() {
        int vertex = getVertexCount();
        if (vertex >= BlockMeshDecoder.MAX_VERTICES)
            throw new IllegalStateException("Better Foliage output exceeds the bounded per-block capture buffer");
        if ((vertex & 3) == 0) {
            int meta = vertex / 4 * BlockMeshDecoder.METADATA_STRIDE;
            metadata[meta] = minU; metadata[meta + 1] = minV; metadata[meta + 2] = maxU; metadata[meta + 3] = maxV;
            metadata[meta + 4] = nx; metadata[meta + 5] = ny; metadata[meta + 6] = nz;
        }
        super.endVertex(); if (foliage) foliageVertices++;
        // Deliberately omit the lightmap: the packed scene contains unlit material colors.
        for (int word = 0; word < 6; word++) hash = (hash ^ getByteBuffer().getInt(vertex * 28 + word * 4)) * 0x100000001b3L;
    }

    @Override public void quad(float[] positions, float[] uv, int[] colors, float nx, float ny, float nz,
                               float minU, float minV, float maxU, float maxV) {
        reserveQuads(1); this.minU = minU; this.minV = minV; this.maxU = maxU; this.maxV = maxV;
        this.nx = nx; this.ny = ny; this.nz = nz; foliage = false;
        for (int v = 0; v < 4; v++) {
            int color = colors[v];
            pos(positions[v * 3] + (double) originX, positions[v * 3 + 1] + (double) originY,
                    positions[v * 3 + 2] + (double) originZ)
                    .color(color >>> 16 & 255, color >>> 8 & 255, color & 255, color >>> 24)
                    .tex(uv[v * 2], uv[v * 2 + 1]).lightmap(240, 240).endVertex();
        }
    }

    int append(MeshPacking target) {
        if (getDrawMode() != GL11.GL_QUADS || !getVertexFormat().equals(DefaultVertexFormats.BLOCK))
            throw new IllegalStateException("Better Foliage changed its captured vertex format");
        decoder.append(getByteBuffer(), getVertexCount(), metadata, target); return foliageVertices;
    }

    @Override public void finishDrawing() { super.finishDrawing(); building = false; }
    void releaseBlock() {
        if (building) finishDrawing();
        reset(); setTranslation(0, 0, 0); foliageVertices = 0;
    }
    long geometryHash() { return hash; }
    boolean complete() { return decoder.complete(); }
}

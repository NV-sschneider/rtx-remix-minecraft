/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.util.List;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;

/** The single baked-model emitter; lighting is supplied by Remix, vertex and biome colors by the model. */
final class BakedBlockCapture {
    private static final EnumFacing[] FACES = EnumFacing.values();
    private final Minecraft mc;
    private final float[] positions = new float[12], uvs = new float[8];
    private final int[] colors = new int[4];

    BakedBlockCapture(Minecraft mc) { this.mc = mc; }

    void emit(IBlockState raw, IBlockAccess world, BlockPos pos,
              int originX, int originY, int originZ, BlockQuadSink target) {
        IBlockState actual = raw.getActualState(world, pos);
        IBlockState extended = actual.getBlock().getExtendedState(actual, world, pos);
        Vec3d offset = actual.getOffset(world, pos);
        long seed = MathHelper.getPositionRandom(pos);
        IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(actual);
        float x = pos.getX() - originX + (float) offset.x;
        float y = pos.getY() - originY + (float) offset.y;
        float z = pos.getZ() - originZ + (float) offset.z;
        for (EnumFacing side : FACES) if (actual.shouldSideBeRendered(world, pos, side))
            emitQuads(target, model.getQuads(extended, side, seed), actual, world, pos, x, y, z);
        emitQuads(target, model.getQuads(extended, null, seed), actual, world, pos, x, y, z);
    }
    private void emitQuads(BlockQuadSink target, List<BakedQuad> quads, IBlockState state, IBlockAccess world,
                           BlockPos pos, float x, float y, float z) {
        for (int q = 0; q < quads.size(); q++) {
            BakedQuad quad = quads.get(q);
            VertexFormat format = quad.getFormat();
            if (!format.hasUvOffset(0)) continue;
            int[] data = quad.getVertexData();
            int stride = format.getIntegerSize();
            int uv = format.getUvOffsetById(0) / 4;
            if (stride < 3 || data.length < stride * 4 || uv + 1 >= stride) continue;
            int tint = quad.hasTintIndex() ? mc.getBlockColors().colorMultiplier(state, world, pos, quad.getTintIndex()) : 0xFFFFFF;
            for (int v = 0; v < 4; v++) {
                int base = v * stride;
                positions[v * 3] = Float.intBitsToFloat(data[base]) + x;
                positions[v * 3 + 1] = Float.intBitsToFloat(data[base + 1]) + y;
                positions[v * 3 + 2] = Float.intBitsToFloat(data[base + 2]) + z;
                uvs[v * 2] = Float.intBitsToFloat(data[base + uv]);
                uvs[v * 2 + 1] = Float.intBitsToFloat(data[base + uv + 1]);
                if (format.hasColor()) {
                    int colorByte = base * 4 + format.getColorOffset();
                    colors[v] = MeshPacking.tintRgba(byteAt(data, colorByte), byteAt(data, colorByte + 1),
                            byteAt(data, colorByte + 2), byteAt(data, colorByte + 3), tint);
                } else {
                    colors[v] = 0xFF000000 | (tint & 0xFFFFFF);
                }
            }
            float ax = positions[3] - positions[0], ay = positions[4] - positions[1], az = positions[5] - positions[2];
            float bx = positions[6] - positions[0], by = positions[7] - positions[1], bz = positions[8] - positions[2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 0.00001f) {
                nx /= length; ny /= length; nz /= length;
            } else if (quad.getFace() != null) {
                nx = quad.getFace().getXOffset(); ny = quad.getFace().getYOffset(); nz = quad.getFace().getZOffset();
            } else continue;
            TextureAtlasSprite sprite = quad.getSprite();
            target.quad(positions, uvs, colors, nx, ny, nz,
                    sprite == null ? 0 : sprite.getMinU(), sprite == null ? 0 : sprite.getMinV(),
                    sprite == null ? 1 : sprite.getMaxU(), sprite == null ? 1 : sprite.getMaxV());
        }
    }

    private static int byteAt(int[] data, int offset) {
        return (data[offset / 4] >>> ((offset & 3) * 8)) & 255;
    }

}

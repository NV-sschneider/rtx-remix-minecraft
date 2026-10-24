/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import net.minecraft.client.renderer.VboRenderList;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;

/** Replaces only the pure GL draw call; VboRenderList retains all traversal and GL-state work. */
public final class VanillaTerrainDraws {
    private static final TerrainDrawGate GATE = new TerrainDrawGate();
    private static final boolean SUPPORTED = contractPresent();

    private VanillaTerrainDraws() { }

    public static void beginFrame(boolean eligible, TerrainDrawGate.Replacement replacement) {
        GATE.beginFrame(SUPPORTED && eligible, replacement);
    }
    public static void endFrame() { GATE.endFrame(); }
    public static void discard() { GATE.discard(); }
    public static boolean supported() { return SUPPORTED; }
    public static int skippedDrawCount() { return GATE.skippedDrawCount(); }
    public static int vanillaDrawCount() { return GATE.vanillaDrawCount(); }
    public static RuntimeException failure() { return GATE.failure(); }

    /** Called at the original VertexBuffer.drawArrays invocation, after its binding and pointer setup. */
    public static void draw(Object source, int mode, Object section, Object layer) {
        VertexBuffer buffer = (VertexBuffer) source;
        boolean known = buffer.getClass() == VertexBuffer.class && section != null && section.getClass() == RenderChunk.class
                && mode == 7 && layer == BlockRenderLayer.SOLID;
        if (known) {
            BlockPos position = ((RenderChunk) section).getPosition();
            if (GATE.skip(true, position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4)) return;
        } else GATE.skip(false, 0, 0, 0);
        buffer.drawArrays(mode);
    }

    private static boolean contractPresent() {
        try { return VboRenderList.class.getField("rtxremixVboDrawContract").getInt(null) == 1; }
        catch (ReflectiveOperationException failure) { return false; }
    }
}

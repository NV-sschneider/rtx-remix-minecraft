/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.nio.FloatBuffer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Matrix4f;

/** Observes Forge's actual fast-TESR batch on the render thread without rerunning a renderer. */
public final class FastTileCaptureHooks {
    private static final FastTileBatch BATCH = new FastTileBatch();
    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    private static final Matrix4f VIEW = new Matrix4f(), INVERSE = new Matrix4f(), FINAL_VIEW = new Matrix4f();
    private static Thread renderThread;
    private static boolean armed, nested;
    private static int depth, firstVertex;
    private static BufferBuilder buffer;
    private static TileEntity pending;
    private static double originX, originY, originZ;
    private static Throwable failure;
    private static long unsupportedBatches;

    private FastTileCaptureHooks() { }

    public static void beginFrame(boolean capture) {
        discard(); renderThread = Thread.currentThread(); armed = capture;
    }
    public static void discard() {
        armed = false; depth = 0; pending = null; buffer = null; failure = null; BATCH.reset();
    }
    public static List<CapturedEntityPart> finishFrame() {
        armed = false; pending = null; buffer = null; depth = 0;
        List<CapturedEntityPart> result = BATCH.finish();
        if (failure != null) throw new IllegalStateException("Fast tile capture failed", failure);
        return result;
    }
    public static int partCount() { return BATCH.count(); }
    public static long capturedRangeCount() { return BATCH.capturedRanges(); }
    public static long rejectedRangeCount() { return BATCH.rejectedRanges(); }
    public static long unsupportedBatchCount() { return unsupportedBatches; }

    public static void beginBatch(Object target) {
        if (!active()) return;
        try {
            BATCH.begin(); buffer = (BufferBuilder) target;
            MATRIX.clear(); GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX); VIEW.load(MATRIX);
            if (Matrix4f.invert(VIEW, INVERSE) == null) rejectBatch();
        } catch (RuntimeException | LinkageError error) { fail(error); }
    }

    public static void enterDispatch() {
        if (!active()) return;
        if (++depth > 1) nested = true;
    }

    /** Called with the receiver buffer still on Forge's operand stack, immediately before dispatch. */
    public static void beginRange(Object target, Object rendered, double x, double y, double z, int destroyStage) {
        if (!active() || depth != 1 || !BATCH.collecting()) return;
        try {
            nested = false; pending = null;
            if (target != buffer || !(rendered instanceof TileEntity) || destroyStage >= 0) return;
            TileEntity tile = (TileEntity) rendered;
            Minecraft mc = Minecraft.getMinecraft(); BlockPos pos = tile.getPos();
            if (mc.world == null || tile.isInvalid() || tile.getWorld() != mc.world
                    || !mc.world.isBlockLoaded(pos) || mc.world.getTileEntity(pos) != tile) return;
            if (buffer.getDrawMode() != GL11.GL_QUADS || !DefaultVertexFormats.BLOCK.equals(buffer.getVertexFormat())) {
                BATCH.reject(tile); return;
            }
            pending = tile; firstVertex = buffer.getVertexCount();
            originX = pos.getX() - x; originY = pos.getY() - y; originZ = pos.getZ() - z;
        } catch (RuntimeException | LinkageError error) { fail(error); }
    }

    /** Only successful calls reach this hook, before another tile can reuse the same batch. */
    public static void completeRange() {
        if (!active() || depth != 1 || pending == null) return;
        try {
            if (nested || !DefaultVertexFormats.BLOCK.equals(buffer.getVertexFormat())
                    || buffer.getDrawMode() != GL11.GL_QUADS) BATCH.reject(pending);
            else BATCH.append(pending, buffer.getByteBuffer(), firstVertex, buffer.getVertexCount() - firstVertex,
                    buffer.getVertexFormat().getSize(), buffer.getVertexFormat().getUvOffsetById(0),
                    buffer.getVertexFormat().getColorOffset(), originX, originY, originZ);
            pending = null;
        } catch (RuntimeException | LinkageError error) { fail(error); }
    }

    public static void leaveDispatch() {
        if (!active() || depth == 0) return;
        if (--depth == 0 && pending != null) { BATCH.reject(pending); pending = null; }
    }

    /** The batch's draw state is read after Forge has bound its atlas and configured blending. */
    public static void prepareBatch() {
        if (!active() || !BATCH.collecting()) return;
        try {
            if (GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0 || !GL11.glIsEnabled(GL11.GL_BLEND)
                    || GL11.glGetInteger(GL11.GL_BLEND_SRC) != GL11.GL_SRC_ALPHA
                    || GL11.glGetInteger(GL11.GL_BLEND_DST) != GL11.GL_ONE_MINUS_SRC_ALPHA
                    || GL11.glGetInteger(GL14.GL_BLEND_EQUATION) != GL14.GL_FUNC_ADD) { rejectBatch(); return; }
            TextureMap atlas = Minecraft.getMinecraft().getTextureMapBlocks();
            int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            try {
                OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
                if (!GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
                        || GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != atlas.getGlTextureId()) { rejectBatch(); return; }
                MATRIX.clear(); GL11.glGetFloat(GL11.GL_TEXTURE_MATRIX, MATRIX);
                for (int i = 0; i < 16; i++) if (MATRIX.get(i) != (i % 5 == 0 ? 1 : 0)) { rejectBatch(); return; }
            } finally { OpenGlHelper.setActiveTexture(activeTexture); }
            MATRIX.clear(); GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX); FINAL_VIEW.load(MATRIX);
            BATCH.prepare(atlas, atlas.getGlTextureId(), GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
                    ? GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC) - GL11.GL_NEVER : 7,
                    (int) (GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF) * 255), INVERSE, FINAL_VIEW);
        } catch (RuntimeException | LinkageError error) { fail(error); }
    }
    public static void completeBatch() { if (active()) { BATCH.complete(); buffer = null; } }
    public static void endBatch() { if (active()) { BATCH.abort(); buffer = null; pending = null; } }

    private static boolean active() { return armed && failure == null && Thread.currentThread() == renderThread; }
    private static void rejectBatch() { unsupportedBatches++; BATCH.abort(); buffer = null; pending = null; }
    private static void fail(Throwable error) { failure = error; BATCH.abort(); buffer = null; pending = null; }
}

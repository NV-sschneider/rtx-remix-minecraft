/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.cloud;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Matrix4f;

/** Observes existing cloud/custom-sky CPU draws without replacing traversal or changing its GL state. */
public final class CloudCaptureHooks {
    public static final int MAX_BATCHES = 16;
    public static final int MAX_VERTICES = 32766;
    private static final List<CapturedCloudBatch> BATCHES = new ArrayList<CapturedCloudBatch>(MAX_BATCHES);
    private static final List<CapturedCloudBatch> VIEW = Collections.unmodifiableList(BATCHES);
    private static final CapturedCloudBatch[] POOL = new CapturedCloudBatch[MAX_BATCHES];
    private static final CloudGeometry GEOMETRY = new CloudGeometry();
    private static final FloatBuffer FLOATS = BufferUtils.createFloatBuffer(16);
    private static final ByteBuffer MASK = BufferUtils.createByteBuffer(16);
    private static final Matrix4f BASE = new Matrix4f(), INVERSE = new Matrix4f();
    private static final Matrix4f CURRENT = new Matrix4f(), RELATIVE = new Matrix4f(), TEXTURE = new Matrix4f();
    private static boolean armed, valid, sky, customSky, lastCustomSky;
    private static Thread renderThread;
    private static Object world;
    private static ITextureObject texture;
    private static int depth, vertices;
    private static double originX, originY, originZ;
    private static Throwable failure;
    private static long skipped, lastSkipped;

    private CloudCaptureHooks() {}

    public static void beginFrame(boolean enabled) {
        discard(); renderThread = Thread.currentThread(); armed = enabled;
        world = enabled ? Minecraft.getMinecraft().world : null;
    }

    /** Payloads remain valid only until the next beginFrame/discard and belong to this render thread. */
    public static List<CapturedCloudBatch> finishFrame() {
        armed = false; depth = 0; texture = null;
        if (world != Minecraft.getMinecraft().world) { discard(); return VIEW; }
        if (failure != null) throw new IllegalStateException("Cloud draw observation failed", failure);
        lastSkipped = skipped; lastCustomSky = customSky; return VIEW;
    }

    public static void discard() {
        armed = valid = false; depth = vertices = 0; world = null; texture = null; failure = null;
        skipped = lastSkipped = 0; customSky = lastCustomSky = false;
        for (CapturedCloudBatch batch : POOL) if (batch != null) batch.clear();
        BATCHES.clear();
    }

    public static long skippedDrawCount() { return lastSkipped; }
    public static boolean customSkyHandlerActive() { return lastCustomSky; }

    public static void beginClouds(float partialTicks, double x, double y, double z) {
        begin(false, x, y, z);
    }

    public static void beginSky(float partialTicks) {
        if (!observing()) return;
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) { begin(true, Double.NaN, Double.NaN, Double.NaN); return; }
        begin(true, view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks,
                view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks,
                view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks);
    }

    private static void begin(boolean isSky, double x, double y, double z) {
        if (!observing() || ++depth != 1) return;
        valid = false; texture = null; sky = isSky;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (world == null || world != mc.world || isSky && mc.world.provider.getSkyRenderer() == null
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
            customSky |= isSky;
            readMatrix(GL11.GL_MODELVIEW_MATRIX, BASE);
            if (Matrix4f.invert(BASE, INVERSE) == null) return;
            originX = x; originY = y; originZ = z; valid = true;
        } catch (Throwable error) { failure = error; }
    }

    public static void endPass() {
        if (!armed || Thread.currentThread() != renderThread || depth == 0) return;
        if (--depth == 0) { valid = false; texture = null; }
    }

    public static void boundTexture(Object location) {
        if (!observing() || depth != 1 || !valid) return;
        try { texture = Minecraft.getMinecraft().getTextureManager().getTexture((ResourceLocation) location); }
        catch (Throwable error) { failure = error; }
    }

    public static void drawBuffer(Object buffer) {
        if (!observing() || depth != 1 || !valid) return;
        try { capture((BufferBuilder) buffer); }
        catch (Throwable error) { failure = error; }
    }

    private static void capture(BufferBuilder buffer) {
        int count = buffer.getVertexCount();
        if (count == 0) return;
        // Fancy clouds render their complete geometry twice, first with color writes disabled.
        MASK.clear(); GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, MASK);
        if (MASK.get(0) == 0 && MASK.get(1) == 0 && MASK.get(2) == 0) return;
        VertexFormat format = buffer.getVertexFormat();
        if (world != Minecraft.getMinecraft().world || CloudGeometry.triangles(buffer.getDrawMode(), count) < 0
                || !supported(format) || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0) { skipped++; return; }
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int textureId;
        boolean textured;
        try {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            textured = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            textureId = textured ? GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) : 0;
            if (textured && (texture == null || texture instanceof DynamicTexture || textureId <= 0
                    || texture.getGlTextureId() != textureId || !format.hasUvOffset(0))) { skipped++; return; }
            readMatrix(GL11.GL_TEXTURE_MATRIX, TEXTURE);
        } finally { GlStateManager.setActiveTexture(active); }
        long blend = CloudBlend.key(GL11.glIsEnabled(GL11.GL_BLEND), GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_DST_RGB), GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
                GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA), GL11.glIsEnabled(GL11.GL_ALPHA_TEST),
                GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC), Math.round(GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF) * 255), textured);
        if (blend < 0 || vertices >= MAX_VERTICES) { skipped++; return; }
        Object identity = textured ? texture : null;
        CapturedCloudBatch batch = null;
        // Preserve custom sky layer order: only consecutive draws can share a batch.
        if (!BATCHES.isEmpty()) {
            CapturedCloudBatch candidate = BATCHES.get(BATCHES.size() - 1);
            if (candidate.matches(identity, textureId, sky, blend, originX, originY, originZ)) batch = candidate;
        }
        if (batch == null) {
            int index = BATCHES.size();
            if (index == MAX_BATCHES) { skipped++; return; }
            if (POOL[index] == null) POOL[index] = new CapturedCloudBatch();
            batch = POOL[index];
            batch.initialize(identity, textureId, sky, blend, originX, originY, originZ); BATCHES.add(batch);
        }
        readMatrix(GL11.GL_MODELVIEW_MATRIX, CURRENT); Matrix4f.mul(INVERSE, CURRENT, RELATIVE);
        int color = 0xffffffff;
        if (!format.hasColor()) {
            FLOATS.clear(); GL11.glGetFloat(GL11.GL_CURRENT_COLOR, FLOATS);
            color = channel(FLOATS.get(3)) << 24 | channel(FLOATS.get(0)) << 16 | channel(FLOATS.get(1)) << 8 | channel(FLOATS.get(2));
        }
        int accepted = GEOMETRY.append(buffer.getByteBuffer(), count, buffer.getDrawMode(), format.getSize(),
                textured ? format.getUvOffsetById(0) : -1, format.hasColor() ? format.getColorOffset() : -1,
                color, RELATIVE, TEXTURE, MAX_VERTICES - vertices, batch);
        vertices += accepted;
        if (accepted / 3 != CloudGeometry.triangles(buffer.getDrawMode(), count)) skipped++;
    }

    private static boolean supported(VertexFormat format) {
        if (format.getSize() < 12 || format.getSize() > 64) return false;
        boolean position = false;
        for (int i = 0; i < format.getElementCount(); i++) {
            VertexFormatElement element = format.getElement(i);
            if (element.getUsage() == VertexFormatElement.EnumUsage.POSITION) {
                position = format.getOffset(i) == 0 && element.getType() == VertexFormatElement.EnumType.FLOAT && element.getElementCount() == 3;
            } else if (element.getUsage() == VertexFormatElement.EnumUsage.UV && element.getIndex() == 0
                    && (element.getType() != VertexFormatElement.EnumType.FLOAT || element.getElementCount() != 2)) return false;
            else if (element.getUsage() == VertexFormatElement.EnumUsage.COLOR
                    && (element.getType() != VertexFormatElement.EnumType.UBYTE || element.getElementCount() != 4)) return false;
        }
        return position;
    }

    private static void readMatrix(int kind, Matrix4f destination) { FLOATS.clear(); GL11.glGetFloat(kind, FLOATS); destination.load(FLOATS); }
    private static boolean observing() { return armed && failure == null && Thread.currentThread() == renderThread; }
    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value * 255))); }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
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

/** Copies only existing world effect draws; never advances particles or changes vanilla GL draws. */
public final class EffectCaptureHooks {
    public static final int MAX_BATCHES = 32;
    public static final int MAX_QUADS = 8192;
    private static final List<CapturedEffectBatch> BATCHES = new ArrayList<CapturedEffectBatch>(MAX_BATCHES);
    private static final List<CapturedEffectBatch> VIEW = Collections.unmodifiableList(BATCHES);
    private static final CapturedEffectBatch[] POOL = new CapturedEffectBatch[MAX_BATCHES];
    private static final EffectVertexDecoder DECODER = new EffectVertexDecoder();
    private static final FloatBuffer FLOATS = BufferUtils.createFloatBuffer(16);
    private static final Matrix4f BASE = new Matrix4f(), INVERSE = new Matrix4f();
    private static final Matrix4f CURRENT = new Matrix4f(), RELATIVE = new Matrix4f();
    private static boolean armed, weather, damage, scopeValid;
    private static Thread renderThread;
    private static Object world;
    private static ITextureObject texture;
    private static int depth, quads;
    private static double weatherX, weatherY, weatherZ;
    private static Throwable failure;
    private static long skippedBatches, skippedQuads, lastSkippedBatches, lastSkippedQuads;

    private EffectCaptureHooks() {}

    public static void beginFrame(boolean enabled) {
        discard();
        renderThread = Thread.currentThread();
        armed = enabled;
        world = enabled ? Minecraft.getMinecraft().world : null;
    }

    /** Payload is consumed synchronously before the next beginFrame/discard. */
    public static List<CapturedEffectBatch> finishFrame() {
        armed = false; depth = 0; texture = null;
        if (world != Minecraft.getMinecraft().world) { discard(); return VIEW; }
        if (failure != null) throw new IllegalStateException("Effect draw observation failed", failure);
        lastSkippedBatches = skippedBatches; lastSkippedQuads = skippedQuads;
        return VIEW;
    }

    public static void discard() {
        armed = false; depth = 0; world = null; texture = null; failure = null;
        for (int i = 0; i < POOL.length; i++) if (POOL[i] != null) POOL[i].clear();
        BATCHES.clear(); quads = 0; skippedBatches = skippedQuads = 0;
        lastSkippedBatches = lastSkippedQuads = 0;
    }
    public static long skippedBatchCount() { return lastSkippedBatches; }
    public static long skippedQuadCount() { return lastSkippedQuads; }

    public static void beginParticles(Object entity, float partialTicks) { begin(false, entity, partialTicks); }
    public static void beginDamage(Object entity, float partialTicks) {
        begin(false, entity, partialTicks);
        if (observing() && depth == 1) damage = true;
    }
    public static void beginWeather(float partialTicks) {
        if (!observing()) return;
        begin(true, Minecraft.getMinecraft().getRenderViewEntity(), partialTicks);
    }
    private static void begin(boolean isWeather, Object entity, float partialTicks) {
        if (!observing() || ++depth != 1) return;
        scopeValid = false; texture = null; weather = isWeather; damage = false;
        try {
            if (!(entity instanceof Entity) || world == null || world != Minecraft.getMinecraft().world) return;
            readMatrix(GL11.GL_MODELVIEW_MATRIX, BASE);
            if (Matrix4f.invert(BASE, INVERSE) == null) return;
            Entity view = (Entity) entity;
            weatherX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
            weatherY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
            weatherZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;
            scopeValid = true;
        } catch (Throwable error) { failure = error; }
    }
    public static void endPass() {
        if (!armed || Thread.currentThread() != renderThread || depth == 0) return;
        if (--depth == 0) { texture = null; scopeValid = false; }
    }

    public static void boundTexture(Object location) {
        if (!observing() || depth != 1) return;
        try {
            texture = Minecraft.getMinecraft().getTextureManager().getTexture((ResourceLocation) location);
        } catch (Throwable error) { failure = error; }
    }

    /** Called before WorldVertexBufferUploader consumes an already finished buffer. */
    public static void drawBuffer(Object value) {
        if (!observing() || depth != 1) return;
        try { capture((BufferBuilder) value); }
        catch (Throwable error) { failure = error; }
    }

    private static void capture(BufferBuilder buffer) {
        int vertices = buffer.getVertexCount();
        if (vertices == 0) return;
        int mode = buffer.getDrawMode();
        int attempted = EffectVertexDecoder.primitiveCount(mode, vertices);
        VertexFormat format = buffer.getVertexFormat();
        if (!scopeValid || world != Minecraft.getMinecraft().world || attempted < 0
                || !supported(format) || texture == null || GL11.glGetInteger(GL11.GL_LIST_INDEX) != 0
                || !GL11.glIsEnabled(GL11.GL_DEPTH_TEST) || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0) {
            omit(Math.max(0, attempted)); return;
        }
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        long blendKey = EffectBlend.key(damage, blend, GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_DST_RGB), GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
                GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA), alpha, GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC),
                Math.round(GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF) * 255));
        if (blendKey < 0) { omit(attempted); return; }
        int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int textureId;
        try {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            textureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if (!GL11.glIsEnabled(GL11.GL_TEXTURE_2D) || textureId <= 0 || texture.getGlTextureId() != textureId
                    || !identityTextureMatrix()) { omit(attempted); return; }
        } finally { GlStateManager.setActiveTexture(oldActive); }
        boolean atlas = texture == Minecraft.getMinecraft().getTextureMapBlocks();
        // Lit particles run before renderParticles updates these fields. Read the origin
        // that the actual particle renderer subtracted, not a newly inferred camera origin.
        double x = weather || damage ? weatherX : Particle.interpPosX;
        double y = weather || damage ? weatherY : Particle.interpPosY;
        double z = weather || damage ? weatherZ : Particle.interpPosZ;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) { omit(attempted); return; }
        CapturedEffectBatch batch = null;
        for (int i = 0; i < BATCHES.size(); i++) {
            CapturedEffectBatch candidate = BATCHES.get(i);
            if (candidate.damage == damage && candidate.triangles == (mode != GL11.GL_QUADS)
                    && candidate.matches(texture, textureId, weather, atlas, blendKey, x, y, z)
                    && candidate.quads < CapturedEffectBatch.MAX_QUADS) { batch = candidate; break; }
        }
        if (quads == MAX_QUADS || batch == null && BATCHES.size() == MAX_BATCHES) { omit(attempted); return; }
        if (batch == null) {
            int index = BATCHES.size();
            if (POOL[index] == null) POOL[index] = new CapturedEffectBatch();
            batch = POOL[index];
            batch.initialize(texture, textureId, weather, atlas, blendKey, x, y, z);
            batch.damage = damage;
            batch.triangles = mode != GL11.GL_QUADS;
            BATCHES.add(batch);
        }
        readMatrix(GL11.GL_MODELVIEW_MATRIX, CURRENT);
        Matrix4f.mul(INVERSE, CURRENT, RELATIVE);
        int color = 0xffffffff;
        if (!format.hasColor()) {
            FLOATS.clear(); GL11.glGetFloat(GL11.GL_CURRENT_COLOR, FLOATS);
            color = channel(FLOATS.get(3)) << 24 | channel(FLOATS.get(0)) << 16
                    | channel(FLOATS.get(1)) << 8 | channel(FLOATS.get(2));
        }
        int accepted;
        try {
            accepted = DECODER.append(buffer.getByteBuffer(), vertices, mode, format.getSize(), format.getUvOffsetById(0),
                    format.hasColor() ? format.getColorOffset() : -1, color, RELATIVE, MAX_QUADS - quads, batch);
        } catch (IllegalArgumentException unsupported) { omit(attempted); return; }
        quads += accepted;
        if (accepted != attempted) { skippedBatches++; skippedQuads += attempted - accepted; }
    }

    private static boolean supported(VertexFormat format) {
        if (format.getSize() > 64 || !format.hasUvOffset(0)) return false;
        boolean position = false, uv = false;
        for (int i = 0; i < format.getElementCount(); i++) {
            VertexFormatElement element = format.getElement(i);
            if (element.getUsage() == VertexFormatElement.EnumUsage.POSITION) {
                position = format.getOffset(i) == 0 && element.getType() == VertexFormatElement.EnumType.FLOAT
                        && element.getElementCount() == 3;
            } else if (element.getUsage() == VertexFormatElement.EnumUsage.UV && element.getIndex() == 0) {
                uv = element.getType() == VertexFormatElement.EnumType.FLOAT && element.getElementCount() == 2;
            } else if (element.getUsage() == VertexFormatElement.EnumUsage.COLOR
                    && (element.getType() != VertexFormatElement.EnumType.UBYTE || element.getElementCount() != 4)) return false;
        }
        return position && uv;
    }
    private static boolean identityTextureMatrix() {
        FLOATS.clear(); GL11.glGetFloat(GL11.GL_TEXTURE_MATRIX, FLOATS);
        for (int i = 0; i < 16; i++) if (Math.abs(FLOATS.get(i) - (i % 5 == 0 ? 1 : 0)) > 0.000001f) return false;
        return true;
    }
    private static void readMatrix(int kind, Matrix4f target) {
        FLOATS.clear(); GL11.glGetFloat(kind, FLOATS); target.load(FLOATS);
    }
    private static boolean observing() { return armed && failure == null && Thread.currentThread() == renderThread; }
    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value * 255))); }
    private static void omit(int count) { skippedBatches++; skippedQuads += count; }
}

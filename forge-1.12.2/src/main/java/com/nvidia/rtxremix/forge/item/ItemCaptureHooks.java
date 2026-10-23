/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.item;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.util.vector.Matrix4f;

/** Observes world-owned dropped items, never GUI, held-item, or enchantment-glint draws. */
public final class ItemCaptureHooks {
    static final int MAX_PARTS = 256;
    static final int MAX_OWNER_PARTS = 16;
    private static final int MAX_MODEL_VERTICES = 16384;
    private static final Field ORIGIN_X = ReflectionHelper.findField(RenderManager.class, new String[] {"renderPosX", "field_78725_b"});
    private static final Field ORIGIN_Y = ReflectionHelper.findField(RenderManager.class, new String[] {"renderPosY", "field_78726_c"});
    private static final Field ORIGIN_Z = ReflectionHelper.findField(RenderManager.class, new String[] {"renderPosZ", "field_78723_d"});
    private static final List<CapturedItem> PARTS = new ArrayList<>();
    private static final List<CapturedItem> POOL = new ArrayList<>();
    private static final List<CapturedItem> FRAME = Collections.unmodifiableList(PARTS);
    private static final ItemGeometry GEOMETRY = new ItemGeometry();
    private static final FloatBuffer BUFFER = BufferUtils.createFloatBuffer(16);
    private static final Matrix4f VIEW = new Matrix4f(), INVERSE = new Matrix4f(), MODEL = new Matrix4f(), WORLD = new Matrix4f();
    private static boolean armed;
    private static Throwable failure;
    private static EntityItem owner;
    private static CapturedItem active;
    private static int entityDepth, modelDepth, occurrence, quadStart;
    private static double originX, originY, originZ;
    private static TextureAtlasSprite sprite;
    private static boolean rejected;

    private ItemCaptureHooks() { }
    public static void beginFrame(boolean capture) { discard(); armed = capture; }
    public static List<CapturedItem> finishFrame() {
        armed = false;
        clearScope();
        if (failure != null) throw new IllegalStateException("Dropped-item capture failed", failure);
        return FRAME;
    }
    public static void discard() {
        armed = false;
        clearScope();
        failure = null;
        for (int i = 0; i < POOL.size(); i++) POOL.get(i).clear();
        PARTS.clear();
        GEOMETRY.truncate(0);
    }
    private static void clearScope() { owner = null; active = null; sprite = null; entityDepth = modelDepth = 0; }

    public static void beginEntity(Object rendered) {
        if (!armed || failure != null || ++entityDepth != 1) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (!(rendered instanceof EntityItem) || ((EntityItem) rendered).world != mc.world
                    || ((EntityItem) rendered).isDead) return;
            owner = (EntityItem) rendered;
            occurrence = 0;
            readView(VIEW);
            if (Matrix4f.invert(VIEW, INVERSE) == null) throw new IllegalStateException("Item base view is singular");
            RenderManager manager = mc.getRenderManager();
            originX = ORIGIN_X.getDouble(manager); originY = ORIGIN_Y.getDouble(manager); originZ = ORIGIN_Z.getDouble(manager);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) { fail(ex); }
    }
    public static void endEntity() {
        if (!armed || failure != null) return;
        if (entityDepth > 0 && --entityDepth == 0) { owner = null; active = null; sprite = null; modelDepth = 0; }
    }

    public static void beginModel(Object model, Object renderedStack) {
        if (!armed || failure != null || ++modelDepth != 1) return;
        if (entityDepth != 1 || owner == null || PARTS.size() == MAX_PARTS || occurrence == MAX_OWNER_PARTS) return;
        try {
            ItemStack stack = (ItemStack) renderedStack;
            if (stack.isEmpty()) return;
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            if (blend && (GL11.glGetInteger(GL11.GL_BLEND_SRC) != GL11.GL_SRC_ALPHA
                    || GL11.glGetInteger(GL11.GL_BLEND_DST) != GL11.GL_ONE_MINUS_SRC_ALPHA
                    || GL11.glGetInteger(GL14.GL_BLEND_EQUATION) != GL14.GL_FUNC_ADD)) return;
            int textureUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            try {
                OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
                if (!GL11.glIsEnabled(GL11.GL_TEXTURE_2D) || GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
                        != Minecraft.getMinecraft().getTextureMapBlocks().getGlTextureId()) return;
            } finally { OpenGlHelper.setActiveTexture(textureUnit); }
            if (PARTS.size() == POOL.size()) POOL.add(new CapturedItem());
            active = POOL.get(PARTS.size());
            active.owner = owner; active.model = model; active.stack = stack; active.geometry = GEOMETRY;
            active.occurrence = occurrence++;
            active.firstVertex = GEOMETRY.count();
            active.alphaBlend = blend;
            active.alphaCompare = GL11.glIsEnabled(GL11.GL_ALPHA_TEST) ? GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC) - GL11.GL_NEVER : 7;
            active.alphaReference = channel(GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF));
            readView(MODEL);
            ItemTransform.world(INVERSE, MODEL, WORLD, originX, originY, originZ, active.transform);
            rejected = false;
        } catch (RuntimeException | LinkageError ex) { fail(ex); }
    }
    public static void endModel() {
        if (!armed || failure != null || modelDepth == 0 || --modelDepth != 0 || active == null) return;
        active.vertexCount = GEOMETRY.count() - active.firstVertex;
        if (rejected || active.vertexCount == 0) {
            GEOMETRY.truncate(active.firstVertex);
            active.clear();
        } else {
            active.fingerprint = GEOMETRY.fingerprint(active.firstVertex, active.vertexCount);
            PARTS.add(active);
        }
        active = null;
        sprite = null;
    }

    /** The tint operand is observed after ItemColors; the emitted buffer contains its final result. */
    public static void beforeQuad(Object quad, int tint, Object buffer) {
        if (!capturing()) return;
        try {
            quadStart = ((BufferBuilder) buffer).getVertexCount();
            sprite = ((BakedQuad) quad).getSprite();
            if (sprite == null) rejected = true;
        } catch (RuntimeException | LinkageError ex) { fail(ex); }
    }
    public static void afterQuad(Object drawnBuffer) {
        if (!capturing() || sprite == null) return;
        try {
            BufferBuilder buffer = (BufferBuilder) drawnBuffer;
            VertexFormat format = buffer.getVertexFormat();
            if (buffer.getVertexCount() != quadStart + 4 || !DefaultVertexFormats.ITEM.equals(format)
                    || GEOMETRY.count() - active.firstVertex >= MAX_MODEL_VERTICES
                    || !GEOMETRY.append(buffer.getByteBuffer(), quadStart, format.getSize(), format.getUvOffsetById(0),
                    format.getColorOffset(), format.getNormalOffset(), sprite.getMinU(), sprite.getMinV(), sprite.getMaxU(), sprite.getMaxV())) {
                rejected = true;
            }
            sprite = null;
        } catch (RuntimeException | LinkageError ex) { fail(ex); }
    }
    private static boolean capturing() { return armed && failure == null && entityDepth == 1 && modelDepth == 1 && active != null && !rejected; }
    private static void fail(Throwable error) { failure = error; clearScope(); }
    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value * 255))); }
    private static void readView(Matrix4f destination) { BUFFER.clear(); GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, BUFFER); destination.load(BUFFER); }

}

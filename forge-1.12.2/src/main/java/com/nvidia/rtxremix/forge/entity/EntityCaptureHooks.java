/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.nio.FloatBuffer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.util.vector.Matrix4f;

/** Observes existing model draws. All entry points run on the client render thread. */
public final class EntityCaptureHooks {
    private static final int MAX_PARTS = 1024;
    private static final int MAX_OWNER_PARTS = 256;
    private static final Field ORIGIN_X = ReflectionHelper.findField(RenderManager.class, new String[] {"renderPosX", "field_78725_b"});
    private static final Field ORIGIN_Y = ReflectionHelper.findField(RenderManager.class, new String[] {"renderPosY", "field_78726_c"});
    private static final Field ORIGIN_Z = ReflectionHelper.findField(RenderManager.class, new String[] {"renderPosZ", "field_78723_d"});
    private static final List<CapturedEntityPart> PARTS = new ArrayList<>();
    private static final List<CapturedEntityPart> POOL = new ArrayList<>();
    private static final List<CapturedEntityPart> FRAME_VIEW = Collections.unmodifiableList(PARTS);
    private static final FloatBuffer BUFFER = BufferUtils.createFloatBuffer(16);
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();
    private static final Matrix4f PART_VIEW = new Matrix4f();
    private static final Matrix4f WORLD = new Matrix4f();
    private static boolean armed;
    private static int depth;
    private static EntityLivingBase owner;
    private static int ownerStart;
    private static int occurrence;
    private static double originX, originY, originZ;
    private static ITextureObject texture;
    private static Throwable failure;

    private EntityCaptureHooks() { }

    public static void beginFrame(boolean capture) {
        discard();
        armed = capture;
    }

    /** Stops observing before first-person rendering and GUI previews. */
    public static List<CapturedEntityPart> finishFrame() {
        armed = false;
        owner = null;
        texture = null;
        depth = 0;
        if (failure != null) {
            throw new IllegalStateException("Living-entity capture failed", failure);
        }
        return FRAME_VIEW;
    }

    public static void discard() {
        armed = false;
        owner = null;
        texture = null;
        depth = 0;
        failure = null;
        for (int i = 0; i < POOL.size(); i++) { POOL.get(i).clear(); }
        PARTS.clear();
    }

    // Object descriptors remain valid in both MCP development and SRG production classes.
    public static void beginLiving(Object rendered) {
        if (!armed || failure != null) { return; }
        if (++depth != 1) { return; }
        try {
            owner = (EntityLivingBase) rendered;
            ownerStart = PARTS.size();
            occurrence = 0;
            texture = null;
            readView(VIEW);
            if (Matrix4f.invert(VIEW, INVERSE) == null) {
                throw new IllegalStateException("Living-entity base view is singular");
            }
            // Using the render manager's origin preserves offsets applied by subclasses
            // before entering the living renderer, such as the sneaking player offset.
            RenderManager manager = Minecraft.getMinecraft().getRenderManager();
            originX = ORIGIN_X.getDouble(manager);
            originY = ORIGIN_Y.getDouble(manager);
            originZ = ORIGIN_Z.getDouble(manager);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) { fail(ex); }
    }

    public static void endLiving() {
        if (!armed || failure != null || depth == 0) { return; }
        if (--depth == 0) {
            owner = null;
            texture = null;
        }
    }

    public static void boundTexture(Object location) {
        if (!armed || depth != 1 || owner == null || failure != null) { return; }
        try {
            texture = Minecraft.getMinecraft().getTextureManager().getTexture((ResourceLocation) location);
        } catch (RuntimeException | LinkageError ex) { fail(ex); }
    }

    public static void capturePart(Object renderedPart, float scale) {
        if (!armed || depth != 1 || owner == null || texture == null || failure != null) { return; }
        try {
            ModelRenderer model = (ModelRenderer) renderedPart;
            if (model.cubeList.isEmpty()) { return; }
            // Player skins enable ordinary alpha blending even for their solid body.
            // Additive eyes, glint and faded invisible models need separate semantics.
            boolean alphaBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            if (alphaBlend && (GL11.glGetInteger(GL11.GL_BLEND_SRC) != GL11.GL_SRC_ALPHA
                    || GL11.glGetInteger(GL11.GL_BLEND_DST) != GL11.GL_ONE_MINUS_SRC_ALPHA
                    || GL11.glGetInteger(GL14.GL_BLEND_EQUATION) != GL14.GL_FUNC_ADD)) { return; }
            BUFFER.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, BUFFER);
            if (BUFFER.get(3) < 0.999f) { return; }
            int argb = channel(BUFFER.get(3)) << 24 | channel(BUFFER.get(0)) << 16
                    | channel(BUFFER.get(1)) << 8 | channel(BUFFER.get(2));
            if (PARTS.size() == MAX_PARTS || occurrence == MAX_OWNER_PARTS) {
                for (int i = PARTS.size() - 1; i >= ownerStart; i--) { PARTS.remove(i).clear(); }
                owner = null;
                return;
            }
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int textureId;
            try {
                OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
                if (!GL11.glIsEnabled(GL11.GL_TEXTURE_2D)) { return; }
                textureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            } finally { OpenGlHelper.setActiveTexture(active); }
            if (textureId == 0 || texture.getGlTextureId() != textureId) { return; }
            if (PARTS.size() == POOL.size()) { POOL.add(new CapturedEntityPart()); }
            CapturedEntityPart part = POOL.get(PARTS.size());
            part.owner = owner;
            part.modelPart = model;
            part.occurrence = occurrence++;
            part.scale = scale;
            part.textureId = textureId;
            part.textureIdentity = texture;
            part.argb = argb;
            part.alphaBlend = alphaBlend;
            part.alphaCompare = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
                    ? GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC) - GL11.GL_NEVER : 7;
            part.alphaReference = (int) (GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF) * 255);
            readView(PART_VIEW);
            EntityTransform.world(INVERSE, PART_VIEW, WORLD, originX, originY, originZ, part.transform);
            PARTS.add(part);
        } catch (RuntimeException | LinkageError ex) { fail(ex); }
    }

    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value * 255))); }

    private static void readView(Matrix4f destination) {
        BUFFER.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, BUFFER);
        destination.load(BUFFER);
    }

    private static void fail(Throwable error) {
        failure = error;
        owner = null;
        texture = null;
    }
}

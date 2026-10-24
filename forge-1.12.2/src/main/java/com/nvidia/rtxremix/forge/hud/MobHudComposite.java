/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.hud;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

/** Composites already lit UI without changing Minecraft's cached fixed-function state. */
final class MobHudComposite {
    private final FloatBuffer color = BufferUtils.createFloatBuffer(16);
    private final ByteBuffer mask = BufferUtils.createByteBuffer(16);

    void draw(int texture) {
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING), fog = GL11.glIsEnabled(GL11.GL_FOG), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        color.clear(); GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        mask.clear(); GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, mask);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        boolean lightmap = GL11.glIsEnabled(GL11.GL_TEXTURE_2D); GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        boolean textured = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity();
        try {
            GL20.glUseProgram(0); GlStateManager.disableDepth(); GlStateManager.depthMask(false);
            GlStateManager.disableAlpha(); GlStateManager.disableLighting(); GlStateManager.disableFog(); GlStateManager.disableCull();
            GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glDisable(GL11.GL_STENCIL_TEST); GL11.glColorMask(true, true, true, true);
            GlStateManager.enableTexture2D(); GlStateManager.bindTexture(texture); GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GlStateManager.color(1, 1, 1, 1);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0, 0); GL11.glVertex2f(-1, -1);
            GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, -1);
            GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
            GL11.glTexCoord2f(0, 1); GL11.glVertex2f(-1, 1);
            GL11.glEnd();
        } finally {
            GlStateManager.color(color.get(0), color.get(1), color.get(2), color.get(3));
            GlStateManager.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            if (!blend) GlStateManager.disableBlend();
            GlStateManager.bindTexture(binding); if (!textured) GlStateManager.disableTexture2D();
            GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glPopMatrix();
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit); if (lightmap) GlStateManager.enableTexture2D();
            GlStateManager.setActiveTexture(activeTexture);
            if (depth) GlStateManager.enableDepth(); GlStateManager.depthMask(depthMask);
            if (alpha) GlStateManager.enableAlpha(); if (lighting) GlStateManager.enableLighting();
            if (fog) GlStateManager.enableFog(); if (cull) GlStateManager.enableCull();
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); if (stencil) GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
            GL20.glUseProgram(program);
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix(); GL11.glMatrixMode(matrixMode);
        }
    }
}

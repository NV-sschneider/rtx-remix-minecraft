/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.nio.FloatBuffer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

/** Isolates supplementary model evaluation from the displayed framebuffer and GL caches. */
final class EntityRenderState {
    private final PrimitiveStateSnapshot cache = new PrimitiveStateSnapshot(GlStateManager.class);
    private final FloatBuffer modelMatrix = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projectionMatrix = BufferUtils.createFloatBuffer(16);
    private FloatBuffer[] textureMatrices;
    private int[] textureDepths;
    private Framebuffer target;
    private int mode, activeTexture, clientTexture, arrayBuffer, elementBuffer, program, framebuffer, textureUnits;
    private float brightnessX, brightnessY;
    private int modelDepth, projectionDepth, attributeDepth, clientAttributeDepth;
    private boolean open;

    void begin() {
        if (open) throw new IllegalStateException("Offscreen entity state is already open");
        cache.capture();
        mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        clientTexture = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        elementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        framebuffer = GL11.glGetInteger(36006); // GL_FRAMEBUFFER_BINDING on core and EXT framebuffer paths.
        brightnessX = OpenGlHelper.lastBrightnessX;
        brightnessY = OpenGlHelper.lastBrightnessY;
        textureUnits = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
        if (textureMatrices == null) {
            textureMatrices = new FloatBuffer[textureUnits];
            textureDepths = new int[textureUnits];
            for (int i = 0; i < textureUnits; i++) textureMatrices[i] = BufferUtils.createFloatBuffer(16);
        }
        attributeDepth = GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH);
        clientAttributeDepth = GL11.glGetInteger(GL11.GL_CLIENT_ATTRIB_STACK_DEPTH);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT | GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        modelDepth = saveMatrix(GL11.GL_MODELVIEW_MATRIX, GL11.GL_MODELVIEW_STACK_DEPTH, modelMatrix);
        projectionDepth = saveMatrix(GL11.GL_PROJECTION_MATRIX, GL11.GL_PROJECTION_STACK_DEPTH, projectionMatrix);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix();
        for (int i = 0; i < textureUnits; i++) {
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit + i);
            textureDepths[i] = saveMatrix(GL11.GL_TEXTURE_MATRIX, GL11.GL_TEXTURE_STACK_DEPTH, textureMatrices[i]);
            GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glPushMatrix();
        }
        OpenGlHelper.setActiveTexture(activeTexture);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        open = true;
        try {
            if (target == null) target = new Framebuffer(1, 1, true);
            target.bindFramebuffer(false);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(0, 0, 0, 0);
        } catch (RuntimeException | LinkageError error) {
            try { end(); } catch (RuntimeException | LinkageError cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    void end() {
        if (!open) return;
        try {
            for (int i = textureUnits - 1; i >= 0; i--) {
                OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit + i);
                restoreMatrix(GL11.GL_TEXTURE, GL11.GL_TEXTURE_STACK_DEPTH, textureDepths[i], textureMatrices[i]);
            }
            restoreMatrix(GL11.GL_MODELVIEW, GL11.GL_MODELVIEW_STACK_DEPTH, modelDepth, modelMatrix);
            restoreMatrix(GL11.GL_PROJECTION, GL11.GL_PROJECTION_STACK_DEPTH, projectionDepth, projectionMatrix);
            GL11.glMatrixMode(mode);
            while (GL11.glGetInteger(GL11.GL_CLIENT_ATTRIB_STACK_DEPTH) > clientAttributeDepth) GL11.glPopClientAttrib();
            while (GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH) > attributeDepth) GL11.glPopAttrib();
            OpenGlHelper.setActiveTexture(activeTexture);
            OpenGlHelper.setClientActiveTexture(clientTexture);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
            GL20.glUseProgram(program);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, framebuffer);
            OpenGlHelper.lastBrightnessX = brightnessX;
            OpenGlHelper.lastBrightnessY = brightnessY;
        } finally { open = false; cache.restore(); }
    }

    void release() {
        if (open) throw new IllegalStateException("Cannot release an active entity render target");
        if (target != null) { target.deleteFramebuffer(); target = null; }
    }

    private static int saveMatrix(int matrix, int depth, FloatBuffer target) {
        target.clear(); GL11.glGetFloat(matrix, target);
        return GL11.glGetInteger(depth);
    }

    private static void restoreMatrix(int mode, int depthName, int targetDepth, FloatBuffer matrix) {
        GL11.glMatrixMode(mode);
        int depth = GL11.glGetInteger(depthName);
        while (depth-- > targetDepth) GL11.glPopMatrix();
        depth = GL11.glGetInteger(depthName);
        while (depth++ < targetDepth) GL11.glPushMatrix();
        matrix.position(0); GL11.glLoadMatrix(matrix);
    }
}

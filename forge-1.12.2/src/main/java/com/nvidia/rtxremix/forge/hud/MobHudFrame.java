/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.hud;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/** Preserves a world HUD as premultiplied RGBA until the native world color has been copied. */
public final class MobHudFrame implements AutoCloseable {
    interface Gl {
        int drawFramebuffer();
        void bindDrawFramebuffer(int framebuffer);
        void allocate(int width, int height);
        int framebuffer();
        void clear();
        void blend(int source, int destination, boolean captureAlpha);
        void composite();
        void close();
    }
    private final Thread thread = Thread.currentThread();
    private final Gl gl;
    private int width, height, previousFramebuffer, sourceBlend, destinationBlend;
    private boolean active, ready, changedBlend;
    private long captures, composites;

    public MobHudFrame() { this(new NativeGl()); }
    MobHudFrame(Gl gl) { this.gl = gl; }

    public void begin(int width, int height) {
        checkThread(); ready = false;
        if (active) throw new IllegalStateException("Nested mob HUD capture");
        if (width <= 0 || height <= 0) return;
        if (this.width != width || this.height != height) {
            gl.close(); this.width = this.height = 0;
            gl.allocate(width, height); this.width = width; this.height = height;
        }
        previousFramebuffer = gl.drawFramebuffer();
        try {
            gl.bindDrawFramebuffer(gl.framebuffer()); gl.clear(); active = true; changedBlend = false;
        } finally {
            if (!active) gl.bindDrawFramebuffer(previousFramebuffer);
        }
    }

    /** RGB retains the mod's blend; coverage alpha must not be multiplied by itself. */
    public void blend(int source, int destination) {
        checkThread(); gl.blend(source, destination, active);
        if (active) { sourceBlend = source; destinationBlend = destination; changedBlend = true; }
    }

    public void end(boolean success) {
        checkThread();
        if (!active) return;
        try {
            gl.bindDrawFramebuffer(previousFramebuffer);
            if (changedBlend) gl.blend(sourceBlend, destinationBlend, false);
            ready = success;
            if (success) captures++;
        } finally { active = false; changedBlend = false; }
    }

    public void composite(int width, int height) {
        checkThread();
        boolean draw = ready && !active && this.width == width && this.height == height;
        ready = false;
        if (draw) { gl.composite(); composites++; }
    }

    public void discard() { checkThread(); end(false); ready = false; }
    public boolean active() { return active; }
    public long captures() { return captures; }
    public long composites() { return composites; }
    public int resourceCount() { return width == 0 ? 0 : 2; }
    @Override public void close() { checkThread(); discard(); gl.close(); width = height = 0; }
    private void checkThread() { if (Thread.currentThread() != thread) throw new IllegalStateException("Mob HUD requires its render thread"); }

    private static final class NativeGl implements Gl {
        // LWJGL 2's generic glGet bindings require sixteen slots even for four-component state.
        private final FloatBuffer clearColor = BufferUtils.createFloatBuffer(16);
        private final ByteBuffer colorMask = BufferUtils.createByteBuffer(16);
        private final MobHudComposite composite = new MobHudComposite();
        private int framebuffer, texture;
        public int drawFramebuffer() { return GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING); }
        public void bindDrawFramebuffer(int value) { GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, value); }
        public int framebuffer() { return framebuffer; }
        public void allocate(int width, int height) {
            int previousDraw = drawFramebuffer(), previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                texture = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                framebuffer = GL30.glGenFramebuffers(); bindDrawFramebuffer(framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Cannot allocate mob HUD framebuffer");
                }
            } catch (RuntimeException | LinkageError failure) { close(); throw failure; }
            finally {
                bindDrawFramebuffer(previousDraw); GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
            }
        }
        public void clear() {
            clearColor.clear(); GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
            colorMask.clear(); GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, colorMask);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            try {
                GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glColorMask(true, true, true, true);
                GL11.glClearColor(0, 0, 0, 0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            } finally {
                GL11.glClearColor(clearColor.get(0), clearColor.get(1), clearColor.get(2), clearColor.get(3));
                GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
                if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
        }
        public void blend(int source, int destination, boolean captureAlpha) {
            if (captureAlpha) GlStateManager.tryBlendFuncSeparate(source, destination, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            else GlStateManager.tryBlendFuncSeparate(source, destination, source, destination);
        }
        public void composite() { composite.draw(texture); }
        public void close() {
            if (framebuffer != 0) { GL30.glDeleteFramebuffers(framebuffer); framebuffer = 0; }
            if (texture != 0) { GL11.glDeleteTextures(texture); texture = 0; }
        }
    }
}

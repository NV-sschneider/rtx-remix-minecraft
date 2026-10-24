/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.overlay;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/**
 * Preserves the native final color while Minecraft draws its ordinary UI, then restores it at
 * framebuffer presentation. The snapshot is GL-owned: it never accesses the shared image after signaling
 * the native client-done semaphore, and is valid only for the captured frame and menu session.
 */
public final class OverlayFrame implements AutoCloseable {
    interface Gl {
        int integer(int key);
        boolean enabled(int capability);
        void enabled(int capability, boolean value);
        void bindFramebuffer(int target, int value);
        void bindTexture(int value);
        void bindUnpackBuffer(int value);
        int genTexture();
        int genFramebuffer();
        void allocateTexture(int width, int height);
        void attachTexture(int texture);
        boolean complete();
        void blit(int width, int height);
        void deleteTexture(int value);
        void deleteFramebuffer(int value);
    }
    private final Thread thread = Thread.currentThread();
    private final Gl gl;
    private final OverlayFrameStamp stamp = new OverlayFrameStamp();
    private int framebuffer, texture, width, height;

    public OverlayFrame() { this(new NativeGl()); }
    OverlayFrame(Gl gl) { this.gl = gl; }

    /** Call immediately after the Remix-to-Minecraft framebuffer copy, before Minecraft UI draws. */
    public void capture(int sourceFramebuffer, int width, int height) {
        capture(sourceFramebuffer, width, height, OverlayHooks.capturing(), OverlayHooks.frameId(), OverlayHooks.sessionId());
    }

    void capture(int sourceFramebuffer, int width, int height, boolean capturing, long frame, long session) {
        checkThread(); stamp.clear();
        if (!capturing || width <= 0 || height <= 0) return;
        ensureSize(width, height);
        blit(sourceFramebuffer, framebuffer, width, height);
        stamp.capture(frame, session, width, height);
    }

    /** Call immediately before Minecraft copies its framebuffer to the window. */
    public void restore(int targetFramebuffer, int width, int height) {
        restore(targetFramebuffer, width, height, OverlayHooks.capturing(), OverlayHooks.frameId(), OverlayHooks.sessionId());
    }

    void restore(int targetFramebuffer, int width, int height, boolean capturing, long frame, long session) {
        checkThread();
        try { if (stamp.matches(capturing, frame, session, width, height)) blit(framebuffer, targetFramebuffer, width, height); }
        finally { stamp.clear(); }
    }

    private void ensureSize(int newWidth, int newHeight) {
        if (framebuffer != 0 && width == newWidth && height == newHeight) return;
        close();
        int read = gl.integer(GL30.GL_READ_FRAMEBUFFER_BINDING), draw = gl.integer(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int binding = gl.integer(GL11.GL_TEXTURE_BINDING_2D);
        int unpackBuffer = gl.integer(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        try {
            gl.bindUnpackBuffer(0);
            texture = gl.genTexture(); gl.bindTexture(texture); gl.allocateTexture(newWidth, newHeight);
            framebuffer = gl.genFramebuffer(); gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer); gl.attachTexture(texture);
            if (!gl.complete()) throw new IllegalStateException("Cannot allocate Remix menu framebuffer");
            width = newWidth; height = newHeight;
        } catch (RuntimeException | LinkageError error) {
            close(); throw error;
        } finally {
            gl.bindUnpackBuffer(unpackBuffer); gl.bindTexture(binding);
            gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read); gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        }
    }

    private void blit(int source, int target, int width, int height) {
        int read = gl.integer(GL30.GL_READ_FRAMEBUFFER_BINDING), draw = gl.integer(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = gl.enabled(GL11.GL_SCISSOR_TEST), srgb = gl.enabled(GL30.GL_FRAMEBUFFER_SRGB);
        try {
            gl.enabled(GL11.GL_SCISSOR_TEST, false); gl.enabled(GL30.GL_FRAMEBUFFER_SRGB, false);
            gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source); gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target); gl.blit(width, height);
        } finally {
            gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read); gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            gl.enabled(GL11.GL_SCISSOR_TEST, scissor); gl.enabled(GL30.GL_FRAMEBUFFER_SRGB, srgb);
        }
    }

    @Override public void close() {
        checkThread(); stamp.clear();
        if (framebuffer != 0) { gl.deleteFramebuffer(framebuffer); framebuffer = 0; }
        if (texture != 0) { gl.deleteTexture(texture); texture = 0; }
        width = height = 0;
    }
    public int resourceCount() { return (framebuffer == 0 ? 0 : 1) + (texture == 0 ? 0 : 1); }
    private void checkThread() { if (Thread.currentThread() != thread) throw new IllegalStateException("Menu framebuffer requires its render thread"); }

    private static final class NativeGl implements Gl {
        public int integer(int key) { return GL11.glGetInteger(key); }
        public boolean enabled(int capability) { return GL11.glIsEnabled(capability); }
        public void enabled(int capability, boolean value) { if (value) GL11.glEnable(capability); else GL11.glDisable(capability); }
        public void bindFramebuffer(int target, int value) { GL30.glBindFramebuffer(target, value); }
        public void bindTexture(int value) { GL11.glBindTexture(GL11.GL_TEXTURE_2D, value); }
        public void bindUnpackBuffer(int value) { GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, value); }
        public int genTexture() { return GL11.glGenTextures(); }
        public int genFramebuffer() { return GL30.glGenFramebuffers(); }
        public void allocateTexture(int width, int height) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
        public void attachTexture(int texture) { GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0); }
        public boolean complete() { return GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE; }
        public void blit(int width, int height) { GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST); }
        public void deleteTexture(int value) { GL11.glDeleteTextures(value); }
        public void deleteFramebuffer(int value) { GL30.glDeleteFramebuffers(value); }
    }
}

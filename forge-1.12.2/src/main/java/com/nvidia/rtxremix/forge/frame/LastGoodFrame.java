/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.frame;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/**
 * A bounded, GL-owned world-color fallback. Ping-pong snapshots preserve the previous complete
 * image when a new copy fails. No HUD, CPU pixels or shared native image ownership is retained.
 */
public final class LastGoodFrame implements AutoCloseable {
    static final long MAX_BYTES = 128L * 1024 * 1024;
    interface Gl {
        Object context();
        int integer(int key);
        boolean enabled(int capability);
        void enabled(int capability, boolean value);
        void bindFramebuffer(int target, int value);
        void readBuffer(int value);
        void drawBuffer(int value);
        void drawBuffers(int[] values);
        void bindTexture(int value);
        void bindUnpackBuffer(int value);
        int genTexture();
        int genFramebuffer();
        void allocateTexture(int width, int height);
        void attachTexture(int texture);
        boolean complete();
        void blit(int width, int height);
        int error();
        void deleteTexture(int value);
        void deleteFramebuffer(int value);
    }
    private final Thread thread = Thread.currentThread();
    private final Gl gl;
    private final int[] textures = new int[2], framebuffers = new int[2];
    private int[] drawBuffers;
    private Object context, world;
    private int width, height, front;
    private long restoredFrame = Long.MIN_VALUE, captures, restores;
    private boolean valid;

    public LastGoodFrame() { this(new NativeGl()); }
    LastGoodFrame(Gl gl) { this.gl = gl; }

    /** Check before arming any omitted vanilla draws. A mismatched session can never revive an old image. */
    public boolean available(Object world, int width, int height) {
        checkThread();
        if (!valid) return false;
        if (world == null || world != this.world || context != gl.context() || width != this.width || height != this.height) {
            invalidate(); return false;
        }
        return true;
    }

    /** Call only after a successful native world copy and before Minecraft's HUD. */
    public boolean capture(Object world, int sourceFramebuffer, int width, int height) {
        checkThread();
        if (world == null || sourceFramebuffer < 0 || width <= 0 || height <= 0 || (long) width * height > MAX_BYTES / 8) {
            invalidate(); return false;
        }
        if (this.world != world) invalidate();
        ensureSize(width, height);
        int target = valid ? 1 - front : front;
        blit(sourceFramebuffer, framebuffers[target], width, height);
        front = target; this.world = world; valid = true; captures++;
        return true;
    }

    /** Restore at the independent pre-HUD hook; repeated callbacks in one frame do nothing. */
    public boolean restore(Object world, int targetFramebuffer, int width, int height, long frameId) {
        checkThread();
        if (restoredFrame == frameId || !available(world, width, height)) return false;
        restoredFrame = frameId;
        blit(framebuffers[front], targetFramebuffer, width, height); restores++;
        return true;
    }

    public void invalidate() { checkThread(); valid = false; world = null; }
    public int resourceCount() { checkThread(); int count = 0; for (int value : textures) if (value != 0) count++; for (int value : framebuffers) if (value != 0) count++; return count; }
    public long byteCount() { checkThread(); int count = 0; for (int value : textures) if (value != 0) count++; return (long) width * height * 4 * count; }
    public long captureCount() { checkThread(); return captures; }
    public long restoreCount() { checkThread(); return restores; }

    private void ensureSize(int w, int h) {
        Object current = gl.context();
        if (resourceCount() != 0 && context != current) {
            invalidate(); throw new IllegalStateException("World fallback must close before its OpenGL context is destroyed");
        }
        if (framebuffers[0] != 0 && framebuffers[1] != 0 && width == w && height == h) return;
        close();
        int maximum = gl.integer(GL11.GL_MAX_TEXTURE_SIZE);
        if (w > maximum || h > maximum) throw new IllegalArgumentException("World fallback exceeds the supported texture size");
        context = current;
        width = w; height = h;
        int read = gl.integer(GL30.GL_READ_FRAMEBUFFER_BINDING), draw = gl.integer(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int binding = gl.integer(GL11.GL_TEXTURE_BINDING_2D), unpack = gl.integer(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        drainErrors();
        try {
            gl.bindUnpackBuffer(0);
            for (int i = 0; i < textures.length; i++) {
                textures[i] = gl.genTexture(); gl.bindTexture(textures[i]); gl.allocateTexture(w, h);
                framebuffers[i] = gl.genFramebuffer(); gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffers[i]); gl.attachTexture(textures[i]);
                if (!gl.complete()) throw new IllegalStateException("World fallback framebuffer is incomplete");
            }
            checkError("allocating world fallback");
        } catch (RuntimeException | LinkageError failure) {
            try { close(); } catch (RuntimeException | LinkageError cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        } finally {
            gl.bindUnpackBuffer(unpack); gl.bindTexture(binding);
            gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read); gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        }
    }

    private void blit(int source, int target, int w, int h) {
        int read = gl.integer(GL30.GL_READ_FRAMEBUFFER_BINDING), draw = gl.integer(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = gl.enabled(GL11.GL_SCISSOR_TEST), srgb = gl.enabled(GL30.GL_FRAMEBUFFER_SRGB);
        if (drawBuffers == null) drawBuffers = new int[gl.integer(GL20.GL_MAX_DRAW_BUFFERS)];
        drainErrors();
        int readBuffer = 0;
        boolean selectionsSaved = false;
        try {
            gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source); gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target);
            readBuffer = gl.integer(GL11.GL_READ_BUFFER);
            for (int i = 0; i < drawBuffers.length; i++) drawBuffers[i] = gl.integer(GL20.GL_DRAW_BUFFER0 + i);
            selectionsSaved = true;
            gl.readBuffer(source == 0 ? GL11.GL_BACK : GL30.GL_COLOR_ATTACHMENT0);
            gl.drawBuffer(target == 0 ? GL11.GL_BACK : GL30.GL_COLOR_ATTACHMENT0);
            gl.enabled(GL11.GL_SCISSOR_TEST, false); gl.enabled(GL30.GL_FRAMEBUFFER_SRGB, false);
            gl.blit(w, h); checkError("copying world fallback");
        } finally {
            // glDrawBuffer changes every MRT slot; restoring only DRAW_BUFFER0 would lose the rest.
            if (selectionsSaved) { gl.readBuffer(readBuffer); gl.drawBuffers(drawBuffers); }
            gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read); gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            gl.enabled(GL11.GL_SCISSOR_TEST, scissor); gl.enabled(GL30.GL_FRAMEBUFFER_SRGB, srgb);
        }
    }
    private void drainErrors() { while (gl.error() != GL11.GL_NO_ERROR) { } }
    private void checkError(String operation) { int error = gl.error(); if (error != GL11.GL_NO_ERROR) throw new IllegalStateException(operation + ": GL error " + error); }

    @Override public void close() {
        checkThread(); invalidate();
        if (resourceCount() != 0 && context != gl.context()) throw new IllegalStateException("World fallback requires its original OpenGL context");
        for (int i = 0; i < framebuffers.length; i++) {
            if (framebuffers[i] != 0) { gl.deleteFramebuffer(framebuffers[i]); framebuffers[i] = 0; }
            if (textures[i] != 0) { gl.deleteTexture(textures[i]); textures[i] = 0; }
        }
        width = height = front = 0; context = null; drawBuffers = null; restoredFrame = Long.MIN_VALUE;
    }
    private void checkThread() { if (Thread.currentThread() != thread) throw new IllegalStateException("World fallback requires its render thread"); }

    private static final class NativeGl implements Gl {
        private IntBuffer drawBufferRestore;
        public Object context() { return GLContext.getCapabilities(); }
        public int integer(int key) { return GL11.glGetInteger(key); }
        public boolean enabled(int capability) { return GL11.glIsEnabled(capability); }
        public void enabled(int capability, boolean value) { if (value) GL11.glEnable(capability); else GL11.glDisable(capability); }
        public void bindFramebuffer(int target, int value) { GL30.glBindFramebuffer(target, value); }
        public void readBuffer(int value) { GL11.glReadBuffer(value); }
        public void drawBuffer(int value) { GL11.glDrawBuffer(value); }
        public void drawBuffers(int[] values) {
            int count = values.length;
            while (count > 1 && values[count - 1] == GL11.GL_NONE) count--;
            if (count == 1) { GL11.glDrawBuffer(values[0]); return; }
            if (drawBufferRestore == null || drawBufferRestore.capacity() < count) drawBufferRestore = BufferUtils.createIntBuffer(values.length);
            drawBufferRestore.clear(); drawBufferRestore.put(values, 0, count).flip(); GL20.glDrawBuffers(drawBufferRestore);
        }
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
        public int error() { return GL11.glGetError(); }
        public void deleteTexture(int value) { GL11.glDeleteTextures(value); }
        public void deleteFramebuffer(int value) { GL30.glDeleteFramebuffers(value); }
    }
}

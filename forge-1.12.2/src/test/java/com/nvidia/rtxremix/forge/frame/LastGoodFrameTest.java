/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.frame;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import static org.junit.Assert.*;

public class LastGoodFrameTest {
    @Test public void retainsLastCompleteWorldColorAcrossFailedCaptureAndRestoresOnlyOncePerFrame() {
        Fake gl = new Fake(); LastGoodFrame cache = new LastGoodFrame(gl); Object world = new Object();
        gl.colors.put(4, 10); assertTrue(cache.capture(world, 4, 800, 600)); gl.assertRestored();
        gl.colors.put(4, 20); gl.failBlit = true;
        assertThrows(IllegalStateException.class, () -> cache.capture(world, 4, 800, 600)); gl.assertRestored();
        gl.failBlit = false; assertTrue(cache.available(world, 800, 600));
        assertTrue(cache.restore(world, 4, 800, 600, 1)); assertEquals(Integer.valueOf(10), gl.colors.get(4)); gl.assertRestored();
        assertFalse(cache.restore(world, 4, 800, 600, 1));
        gl.colors.put(4, 30); cache.capture(world, 4, 800, 600); gl.colors.put(4, 99);
        assertTrue(cache.restore(world, 4, 800, 600, 2)); assertEquals(Integer.valueOf(30), gl.colors.get(4));
        assertEquals(2, cache.captureCount()); assertEquals(2, cache.restoreCount()); assertEquals(4, cache.resourceCount());
        assertEquals(800L * 600 * 8, cache.byteCount()); cache.close(); assertTrue(gl.live.isEmpty());
    }

    @Test public void wrongWorldSizeOrContextInvalidatesWithoutRevivingOldImages() {
        Fake gl = new Fake(); LastGoodFrame cache = new LastGoodFrame(gl); Object world = new Object(), other = new Object();
        cache.capture(world, 4, 800, 600); assertFalse(cache.available(other, 800, 600)); assertFalse(cache.available(world, 800, 600));
        cache.capture(world, 4, 800, 600); assertFalse(cache.restore(world, 4, 900, 600, 1)); assertFalse(cache.available(world, 800, 600));
        cache.capture(world, 4, 800, 600); Object original = gl.context; gl.context = new Object();
        assertFalse(cache.available(world, 800, 600)); assertThrows(IllegalStateException.class, cache::close); assertEquals(4, cache.resourceCount());
        gl.context = original; assertFalse(cache.available(world, 800, 600)); cache.close(); assertTrue(gl.live.isEmpty());
    }

    @Test public void resizeAndBudgetLimitsKeepAtMostTwoImagesAndAllocationFailureIsRetryable() {
        Fake gl = new Fake(); LastGoodFrame cache = new LastGoodFrame(gl); Object world = new Object();
        cache.capture(world, 4, 800, 600); Set<Integer> old = new HashSet<Integer>(gl.live);
        cache.capture(world, 4, 1024, 768); assertEquals(4, gl.live.size()); for (Integer id : old) assertFalse(gl.live.contains(id));
        assertFalse(cache.capture(world, 4, 8192, 8192)); assertFalse(cache.available(world, 1024, 768));
        gl.failAllocation = true;
        assertThrows(IllegalStateException.class, () -> cache.capture(world, 4, 800, 600)); gl.assertRestored(); assertTrue(gl.live.isEmpty());
        gl.failAllocation = false; cache.capture(world, 4, 800, 600); assertTrue(cache.available(world, 800, 600)); cache.close();
    }

    @Test public void glErrorsDoNotPublishIncompleteCopiesAndCleanupCanRetry() {
        Fake gl = new Fake(); LastGoodFrame cache = new LastGoodFrame(gl); Object world = new Object();
        gl.errorOnBlit = true;
        assertThrows(IllegalStateException.class, () -> cache.capture(world, 4, 800, 600)); assertFalse(cache.available(world, 800, 600)); gl.assertRestored();
        gl.errorOnBlit = false; cache.capture(world, 4, 800, 600); gl.failBlit = true;
        assertThrows(IllegalStateException.class, () -> cache.restore(world, 4, 800, 600, 2)); gl.failBlit = false;
        assertFalse(cache.restore(world, 4, 800, 600, 2)); assertTrue(cache.restore(world, 4, 800, 600, 3));
        gl.failDelete = true; assertThrows(IllegalStateException.class, cache::close); assertEquals(4, cache.resourceCount());
        gl.failDelete = false; cache.close(); cache.close(); assertTrue(gl.live.isEmpty()); assertEquals(0, cache.byteCount());
    }

    @Test public void wrongThreadCannotTouchTheGlOwner() throws Exception {
        Fake gl = new Fake(); LastGoodFrame cache = new LastGoodFrame(gl); boolean[] rejected = {false};
        Thread worker = new Thread(() -> { try { cache.capture(new Object(), 4, 800, 600); } catch (IllegalStateException expected) { rejected[0] = true; } });
        worker.start(); worker.join(); assertTrue(rejected[0]); assertTrue(gl.live.isEmpty());
    }

    @Test public void restoresEveryMrtSelectionAndBindingsWhenSetupThrows() {
        Fake gl = new Fake(); LastGoodFrame cache = new LastGoodFrame(gl); Object world = new Object();
        int[] selections = {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_NONE, GL30.GL_COLOR_ATTACHMENT3};
        gl.draws.put(4, selections.clone()); cache.capture(world, 4, 800, 600);
        assertTrue(cache.restore(world, 4, 800, 600, 1)); assertArrayEquals(selections, gl.draws.get(4));
        gl.failBind = 4; assertThrows(IllegalStateException.class, () -> cache.capture(world, 4, 800, 600));
        assertEquals(31, gl.read); assertEquals(32, gl.draw); assertTrue(gl.scissor); assertTrue(gl.srgb);
        assertTrue(cache.available(world, 800, 600)); cache.close();
    }

    private static final class Fake implements LastGoodFrame.Gl {
        Object context = new Object();
        int read = 31, draw = 32, texture = 33, unpack = 34, next = 100, error, failBind = -1;
        boolean scissor = true, srgb = true, failAllocation, failBlit, errorOnBlit, failDelete;
        final Set<Integer> live = new HashSet<Integer>();
        final Map<Integer, Integer> colors = new HashMap<Integer, Integer>(), reads = new HashMap<Integer, Integer>();
        final Map<Integer, int[]> draws = new HashMap<Integer, int[]>();
        public Object context() { return context; }
        public int integer(int key) {
            if (key == GL30.GL_READ_FRAMEBUFFER_BINDING) return read;
            if (key == GL30.GL_DRAW_FRAMEBUFFER_BINDING) return draw;
            if (key == GL11.GL_TEXTURE_BINDING_2D) return texture;
            if (key == GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) return unpack;
            if (key == GL11.GL_MAX_TEXTURE_SIZE) return 16384;
            if (key == GL20.GL_MAX_DRAW_BUFFERS) return 4;
            if (key == GL11.GL_READ_BUFFER) return reads.getOrDefault(read, 41);
            if (key == GL11.GL_DRAW_BUFFER) return selections()[0];
            if (key >= GL20.GL_DRAW_BUFFER0 && key < GL20.GL_DRAW_BUFFER0 + 4) return selections()[key - GL20.GL_DRAW_BUFFER0];
            throw new AssertionError("Unexpected state query " + key);
        }
        public boolean enabled(int key) { if (key == GL11.GL_SCISSOR_TEST) return scissor; assertEquals(GL30.GL_FRAMEBUFFER_SRGB, key); return srgb; }
        public void enabled(int key, boolean value) { if (key == GL11.GL_SCISSOR_TEST) scissor = value; else { assertEquals(GL30.GL_FRAMEBUFFER_SRGB, key); srgb = value; } }
        public void bindFramebuffer(int target, int value) {
            if (target == GL30.GL_READ_FRAMEBUFFER) read = value; else { assertEquals(GL30.GL_DRAW_FRAMEBUFFER, target); draw = value; }
            if (value == failBind) { failBind = -1; throw new IllegalStateException("bind"); }
        }
        public void readBuffer(int value) { reads.put(read, value); }
        public void drawBuffer(int value) { draws.put(draw, new int[] {value, GL11.GL_NONE, GL11.GL_NONE, GL11.GL_NONE}); }
        public void drawBuffers(int[] values) { draws.put(draw, values.clone()); }
        private int[] selections() { return draws.getOrDefault(draw, new int[] {42, GL11.GL_NONE, GL11.GL_NONE, GL11.GL_NONE}); }
        public void bindTexture(int value) { texture = value; }
        public void bindUnpackBuffer(int value) { unpack = value; }
        public int genTexture() { int value = next++; assertTrue(live.add(value)); return value; }
        public int genFramebuffer() { return genTexture(); }
        public void allocateTexture(int width, int height) { assertEquals(0, unpack); if (failAllocation) throw new IllegalStateException("allocation"); }
        public void attachTexture(int value) { assertTrue(live.contains(value)); assertTrue(live.contains(draw)); }
        public boolean complete() { return true; }
        public void blit(int width, int height) {
            assertFalse(scissor); assertFalse(srgb); assertEquals(GL30.GL_COLOR_ATTACHMENT0, integer(GL11.GL_READ_BUFFER)); assertEquals(GL30.GL_COLOR_ATTACHMENT0, integer(GL11.GL_DRAW_BUFFER));
            colors.put(draw, -1);
            if (failBlit) throw new IllegalStateException("blit");
            if (errorOnBlit) error = GL30.GL_INVALID_FRAMEBUFFER_OPERATION;
            else colors.put(draw, colors.getOrDefault(read, 0));
        }
        public int error() { int value = error; error = 0; return value; }
        public void deleteTexture(int value) { if (failDelete) throw new IllegalStateException("delete"); assertTrue(live.remove(value)); }
        public void deleteFramebuffer(int value) { deleteTexture(value); }
        void assertRestored() {
            assertEquals(31, read); assertEquals(32, draw); assertEquals(33, texture); assertEquals(34, unpack); assertTrue(scissor); assertTrue(srgb);
            for (Integer value : reads.values()) assertEquals(Integer.valueOf(41), value);
            for (int[] values : draws.values()) assertArrayEquals(new int[] {42, GL11.GL_NONE, GL11.GL_NONE, GL11.GL_NONE}, values);
        }
    }
}

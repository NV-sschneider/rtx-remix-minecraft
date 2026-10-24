/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.overlay;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import static org.junit.Assert.*;

public class OverlayFrameTest {
    @Test public void compositorPreservesDistinctBindingsScissorSrgbAndUnpackBuffer() {
        FakeGl gl = new FakeGl(); OverlayFrame frame = new OverlayFrame(gl);
        frame.capture(4, 800, 600, true, 10, 5); gl.assertRestored();
        assertEquals(2, frame.resourceCount()); assertEquals(1, gl.blits); assertEquals(4, gl.lastSource);
        int snapshot = gl.lastTarget;
        frame.restore(4, 800, 600, true, 10, 5); gl.assertRestored();
        assertEquals(2, gl.blits); assertEquals(snapshot, gl.lastSource); assertEquals(4, gl.lastTarget);
        frame.restore(4, 800, 600, true, 10, 5); assertEquals(2, gl.blits);
        frame.close(); assertTrue(gl.live.isEmpty()); assertEquals(0, frame.resourceCount());
    }

    @Test public void inactiveNextFrameNewSessionAndResizeNeverRestoreAnOldSnapshot() {
        FakeGl gl = new FakeGl(); OverlayFrame frame = new OverlayFrame(gl);
        frame.capture(4, 800, 600, false, 1, 1); assertEquals(0, gl.blits); assertEquals(0, frame.resourceCount());
        frame.capture(4, 800, 600, true, 1, 1); frame.restore(4, 800, 600, false, 1, 1); assertEquals(1, gl.blits);
        frame.capture(4, 800, 600, true, 1, 1); frame.restore(4, 800, 600, true, 2, 1); assertEquals(2, gl.blits);
        frame.capture(4, 800, 600, true, 2, 1); frame.restore(4, 800, 600, true, 2, 2); assertEquals(3, gl.blits);
        frame.capture(4, 800, 600, true, 3, 2); frame.restore(4, 900, 600, true, 3, 2); assertEquals(4, gl.blits);
        Set<Integer> old = new HashSet<Integer>(gl.live);
        frame.capture(4, 900, 600, true, 4, 2);
        assertEquals(2, gl.live.size()); for (Integer id : old) assertFalse(gl.live.contains(id));
        frame.close(); frame.close(); assertTrue(gl.live.isEmpty());
    }

    @Test public void failedAllocationAndBlitRestoreStateAndNeverPublishIncompleteImage() {
        FakeGl gl = new FakeGl(); OverlayFrame frame = new OverlayFrame(gl);
        gl.failAllocation = true;
        assertThrows(IllegalStateException.class, () -> frame.capture(4, 800, 600, true, 1, 1));
        gl.assertRestored(); assertTrue(gl.live.isEmpty()); assertEquals(0, frame.resourceCount());
        gl.failAllocation = false; gl.failBlit = true;
        assertThrows(IllegalStateException.class, () -> frame.capture(4, 800, 600, true, 1, 1));
        gl.assertRestored(); gl.failBlit = false;
        frame.restore(4, 800, 600, true, 1, 1); assertEquals(0, gl.blits);
        frame.capture(4, 800, 600, true, 2, 1); gl.failBlit = true;
        assertThrows(IllegalStateException.class, () -> frame.restore(4, 800, 600, true, 2, 1)); gl.assertRestored();
        gl.failBlit = false; frame.restore(4, 800, 600, true, 2, 1); assertEquals(1, gl.blits);
        frame.close(); assertTrue(gl.live.isEmpty());
    }

    @Test public void cleanupCanRetryAndCannotTouchGlFromAnotherThread() throws Exception {
        FakeGl gl = new FakeGl(); OverlayFrame frame = new OverlayFrame(gl);
        frame.capture(4, 800, 600, true, 1, 1); gl.failDelete = true;
        assertThrows(IllegalStateException.class, frame::close); assertEquals(2, frame.resourceCount());
        gl.failDelete = false; frame.close(); assertTrue(gl.live.isEmpty());
        final boolean[] rejected = {false}; Thread worker = new Thread(() -> {
            try { frame.capture(4, 800, 600, true, 2, 1); } catch (IllegalStateException expected) { rejected[0] = true; }
        }); worker.start(); worker.join(); assertTrue(rejected[0]); assertEquals(0, frame.resourceCount());
    }

    private static final class FakeGl implements OverlayFrame.Gl {
        int read = 31, draw = 32, texture = 33, unpack = 34, next = 100, blits, lastSource, lastTarget;
        boolean scissor = true, srgb = true, failAllocation, failBlit, failDelete;
        final Set<Integer> live = new HashSet<Integer>();
        public int integer(int key) {
            if (key == GL30.GL_READ_FRAMEBUFFER_BINDING) return read;
            if (key == GL30.GL_DRAW_FRAMEBUFFER_BINDING) return draw;
            if (key == GL11.GL_TEXTURE_BINDING_2D) return texture;
            if (key == GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) return unpack;
            throw new AssertionError("Unexpected state query " + key);
        }
        public boolean enabled(int key) { if (key == GL11.GL_SCISSOR_TEST) return scissor; assertEquals(GL30.GL_FRAMEBUFFER_SRGB, key); return srgb; }
        public void enabled(int key, boolean value) { if (key == GL11.GL_SCISSOR_TEST) scissor = value; else { assertEquals(GL30.GL_FRAMEBUFFER_SRGB, key); srgb = value; } }
        public void bindFramebuffer(int target, int value) { if (target == GL30.GL_READ_FRAMEBUFFER) read = value; else { assertEquals(GL30.GL_DRAW_FRAMEBUFFER, target); draw = value; } }
        public void bindTexture(int value) { texture = value; }
        public void bindUnpackBuffer(int value) { unpack = value; }
        public int genTexture() { int id = next++; live.add(id); return id; }
        public int genFramebuffer() { return genTexture(); }
        public void allocateTexture(int width, int height) { assertEquals(0, unpack); if (failAllocation) throw new IllegalStateException("allocation"); }
        public void attachTexture(int value) { assertTrue(live.contains(value)); assertTrue(live.contains(draw)); }
        public boolean complete() { return true; }
        public void blit(int width, int height) {
            assertFalse(scissor); assertFalse(srgb);
            if (failBlit) throw new IllegalStateException("blit");
            blits++; lastSource = read; lastTarget = draw;
        }
        public void deleteTexture(int id) { if (failDelete) throw new IllegalStateException("delete"); assertTrue(live.remove(id)); }
        public void deleteFramebuffer(int id) { deleteTexture(id); }
        void assertRestored() { assertEquals(31, read); assertEquals(32, draw); assertEquals(33, texture); assertEquals(34, unpack); assertTrue(scissor); assertTrue(srgb); }
    }
}

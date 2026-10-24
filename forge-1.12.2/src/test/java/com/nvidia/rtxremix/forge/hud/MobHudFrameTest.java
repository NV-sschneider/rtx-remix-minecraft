/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.hud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.lwjgl.opengl.GL11;
import static org.junit.Assert.*;

public class MobHudFrameTest {
    @Test public void capturesOnceAndRestoresTheModsFinalBlendBeforeCompositingOnce() {
        FakeGl gl = new FakeGl();
        try (MobHudFrame frame = new MobHudFrame(gl)) {
            frame.begin(800, 600); assertTrue(frame.active()); assertEquals(gl.framebuffer, gl.draw);
            frame.blend(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            frame.blend(GL11.GL_ONE, GL11.GL_ZERO);
            frame.end(true); frame.end(true);
            assertFalse(frame.active()); assertEquals(31, gl.draw); assertEquals(1, frame.captures());
            assertEquals(Arrays.asList("770/771/true", "1/0/true", "1/0/false"), gl.blends);
            assertEquals(0, gl.composites);
            frame.composite(800, 600); frame.composite(800, 600);
            assertEquals(1, gl.composites); assertEquals(1, frame.composites());
            assertEquals(1, gl.clears); assertEquals(2, frame.resourceCount());
        }
        assertEquals(0, gl.framebuffer);
    }

    @Test public void failedOrDiscardedCaptureNeverPublishesItsColor() {
        FakeGl gl = new FakeGl();
        try (MobHudFrame frame = new MobHudFrame(gl)) {
            frame.begin(800, 600); frame.blend(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            frame.end(false); frame.composite(800, 600);
            assertEquals(31, gl.draw); assertFalse(frame.active()); assertEquals(0, frame.captures());
            assertEquals("770/771/false", gl.blends.get(1));
            frame.begin(800, 600); frame.discard(); frame.end(true); frame.composite(800, 600);
            assertEquals(31, gl.draw); assertEquals(0, frame.captures());
            frame.begin(800, 600); frame.end(true); frame.discard(); frame.composite(800, 600);
            assertEquals(1, frame.captures()); assertEquals(0, frame.composites()); assertEquals(0, gl.composites);
        }
    }

    @Test public void resizeAndInvalidDimensionsConsumeStaleFramesAndReuseOnlyMatchingStorage() {
        FakeGl gl = new FakeGl();
        try (MobHudFrame frame = new MobHudFrame(gl)) {
            frame.begin(800, 600); frame.end(true); int first = gl.framebuffer;
            frame.composite(801, 600); frame.composite(800, 600); assertEquals(0, gl.composites);
            frame.begin(800, 600); frame.end(true); assertEquals(first, gl.framebuffer);
            frame.begin(0, 600); frame.composite(800, 600); assertEquals(0, gl.composites);
            frame.begin(800, -1); assertFalse(frame.active()); assertEquals(1, gl.allocations);
            frame.begin(801, 600); assertNotEquals(first, gl.framebuffer); assertEquals(2, gl.allocations);
            assertEquals(801, gl.width); assertEquals(600, gl.height);
            frame.end(true); frame.composite(801, 600); assertEquals(1, gl.composites);
        }
    }

    @Test public void nestedBeginCannotReplaceTheActiveTargetAndCompositeWaitsForEnd() {
        FakeGl gl = new FakeGl();
        try (MobHudFrame frame = new MobHudFrame(gl)) {
            frame.begin(800, 600); int target = gl.framebuffer;
            assertThrows(IllegalStateException.class, () -> frame.begin(1024, 768));
            assertTrue(frame.active()); assertEquals(target, gl.draw); assertEquals(1, gl.allocations);
            frame.composite(800, 600); assertEquals(0, gl.composites);
            frame.end(true); frame.composite(800, 600); assertEquals(1, gl.composites);
        }
    }

    @Test public void allocationClearAndCompositeFailuresCannotPublishOrReplayIncompleteFrames() {
        FakeGl gl = new FakeGl();
        try (MobHudFrame frame = new MobHudFrame(gl)) {
            gl.failAllocate = true;
            assertThrows(IllegalStateException.class, () -> frame.begin(800, 600));
            assertEquals(0, frame.resourceCount()); assertEquals(31, gl.draw); assertFalse(frame.active());
            gl.failAllocate = false; gl.failClear = true;
            assertThrows(IllegalStateException.class, () -> frame.begin(800, 600));
            assertEquals(31, gl.draw); assertFalse(frame.active());
            frame.composite(800, 600); assertEquals(0, gl.composites);
            gl.failClear = false; frame.begin(800, 600); frame.end(true); gl.failComposite = true;
            assertThrows(IllegalStateException.class, () -> frame.composite(800, 600));
            assertEquals(31, gl.draw); assertEquals(0, frame.composites());
            gl.failComposite = false; frame.composite(800, 600); assertEquals(0, gl.composites);
            frame.begin(800, 600); frame.end(true); frame.composite(800, 600); assertEquals(1, gl.composites);
        }
    }

    @Test public void blendRestorationFailureDoesNotPublishTheCaptureOrLeaveItActive() {
        FakeGl gl = new FakeGl();
        try (MobHudFrame frame = new MobHudFrame(gl)) {
            frame.begin(800, 600); frame.blend(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            gl.failRestoreBlend = true;
            assertThrows(IllegalStateException.class, () -> frame.end(true));
            assertEquals(31, gl.draw); assertFalse(frame.active()); assertEquals(0, frame.captures());
            gl.failRestoreBlend = false; frame.composite(800, 600); assertEquals(0, gl.composites);
            frame.begin(800, 600); frame.end(true); frame.composite(800, 600); assertEquals(1, gl.composites);
        }
    }

    @Test public void closeDiscardsAnActiveFrameAndCanRetryResourceRelease() {
        FakeGl gl = new FakeGl(); MobHudFrame frame = new MobHudFrame(gl);
        frame.begin(800, 600); frame.blend(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        gl.failClose = true; assertThrows(IllegalStateException.class, frame::close);
        assertEquals(31, gl.draw); assertFalse(frame.active()); assertEquals(2, frame.resourceCount());
        gl.failClose = false; frame.close(); frame.close();
        assertEquals(0, frame.resourceCount()); assertEquals(0, gl.framebuffer);
        frame.composite(800, 600); assertEquals(0, gl.composites); assertEquals(0, frame.captures());
    }

    @Test public void everyMutatingEntryPointRejectsAnotherThreadBeforeTouchingGl() throws Exception {
        FakeGl gl = new FakeGl();
        try (MobHudFrame frame = new MobHudFrame(gl)) {
            frame.begin(800, 600); int calls = gl.calls;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    assertThrows(IllegalStateException.class, () -> frame.begin(800, 600));
                    assertThrows(IllegalStateException.class, () -> frame.blend(GL11.GL_ONE, GL11.GL_ZERO));
                    assertThrows(IllegalStateException.class, () -> frame.end(true));
                    assertThrows(IllegalStateException.class, () -> frame.composite(800, 600));
                    assertThrows(IllegalStateException.class, frame::discard);
                    assertThrows(IllegalStateException.class, frame::close);
                } catch (Throwable error) { failure.set(error); }
            });
            worker.start(); worker.join();
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(calls, gl.calls); assertTrue(frame.active()); assertEquals(gl.framebuffer, gl.draw);
        }
    }

    private static final class FakeGl implements MobHudFrame.Gl {
        int draw = 31, framebuffer, next = 100, width, height, calls, allocations, clears, composites;
        boolean failAllocate, failClear, failComposite, failRestoreBlend, failClose;
        final List<String> blends = new ArrayList<>();
        public int drawFramebuffer() { calls++; return draw; }
        public void bindDrawFramebuffer(int value) { calls++; draw = value; }
        public int framebuffer() { calls++; return framebuffer; }
        public void allocate(int width, int height) {
            calls++; assertEquals(0, framebuffer);
            if (failAllocate) throw new IllegalStateException("allocate");
            framebuffer = next++; this.width = width; this.height = height; allocations++;
        }
        public void clear() {
            calls++; assertNotEquals(0, framebuffer); assertEquals(framebuffer, draw);
            if (failClear) throw new IllegalStateException("clear"); clears++;
        }
        public void blend(int source, int destination, boolean captureAlpha) {
            calls++;
            if (!captureAlpha && failRestoreBlend) throw new IllegalStateException("blend");
            blends.add(source + "/" + destination + "/" + captureAlpha);
        }
        public void composite() {
            calls++; assertNotEquals(0, framebuffer); assertEquals(31, draw);
            if (failComposite) throw new IllegalStateException("composite"); composites++;
        }
        public void close() {
            calls++; if (failClose) throw new IllegalStateException("close"); framebuffer = 0;
        }
    }
}

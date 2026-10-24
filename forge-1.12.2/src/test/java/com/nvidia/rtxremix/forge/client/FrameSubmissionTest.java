/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class FrameSubmissionTest {
    @Test public void handCompletionAndWorldTailCannotSubmitTwice() {
        FrameSubmission frame = new FrameSubmission(); Object world = new Object();
        frame.prepare(world, 0.25f);
        assertTrue(frame.consume(world)); assertFalse(frame.consume(world));
        assertEquals(0.25f, frame.partialTicks(), 0);
    }
    @Test public void newFrameAndWorldSwapDiscardOldPayloads() {
        FrameSubmission frame = new FrameSubmission(); Object world = new Object();
        frame.prepare(world, 0.5f); frame.clear(); assertFalse(frame.consume(world));
        frame.prepare(world, 0.5f); assertFalse(frame.consume(new Object()));
        assertFalse(frame.consume(world));
        frame.prepare(world, 0.75f); assertFalse(frame.consume(null));
    }
    @Test public void hiddenHandStillSubmitsAtTheWorldTail() {
        FrameSubmission frame = new FrameSubmission(); Object world = new Object();
        assertFalse(frame.consume(world)); frame.prepare(world, 0.8f);
        assertTrue(frame.consume(world)); frame.prepare(world, 0.9f);
        assertTrue(frame.consume(world)); assertEquals(0.9f, frame.partialTicks(), 0);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PresentationDiagnosticsTest {
    @Test public void focusRoundTripPreservesTimelineAndDistinguishesWorldlessFrames() {
        List<String> messages = new ArrayList<String>();
        PresentationDiagnostics trace = new PresentationDiagnostics(true, messages::add);
        trace.window(7, true, true, 1280, 720, 0, 1);
        trace.submitted(true, 41, 9, 1280, 720, true, 854, 480);
        trace.releasing(31); trace.presented();
        trace.window(7, false, true, 1280, 720, 1, 2);
        String lost = messages.get(1);
        assertTrue(lost.contains("active=false visible=true"));
        assertTrue(lost.contains("image=1280x720 depthPresent=true depth=854x480"));
        assertTrue(lost.contains("worldSubmitted=true worldFrames=1 submits=1 presents=1 runtimeWait=41 clientDone=31"));
        trace.submitted(false, 42, 9, 1280, 720, false, 0, 0);
        trace.releasing(32); trace.presented();
        trace.window(7, true, true, 1280, 720, 1, 3);
        assertTrue(messages.get(2).contains("worldSubmitted=false worldFrames=1 submits=2 presents=2 runtimeWait=42 clientDone=32"));
        assertTrue(messages.get(2).contains("depthPresent=false"));
    }

    @Test public void repeatedTimelineIsReportedOnceUntilItAdvances() {
        List<String> messages = new ArrayList<String>();
        PresentationDiagnostics trace = new PresentationDiagnostics(true, messages::add);
        trace.submitted(true, 99, 9, 320, 240, true, 213, 160);
        for (int i = 0; i < 100; i++) trace.submitted(true, 99, 9, 320, 240, true, 213, 160);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("event=timeline-not-advancing nextRuntime=99"));
        trace.submitted(true, 100, 9, 320, 240, true, 213, 160);
        assertEquals(2, messages.size());
        assertTrue(messages.get(1).contains("event=timeline-resumed nextRuntime=100"));
    }

    @Test public void longPresentRecordsItsPhaseAndBoundsRepeatedSlowReports() {
        List<String> messages = new ArrayList<String>();
        PresentationDiagnostics trace = new PresentationDiagnostics(true, messages::add);
        trace.begin(PresentationDiagnostics.PRESENT, 1);
        trace.end(2_000_000_001L);
        assertTrue(messages.get(0).contains("event=slow phase=present elapsedMs=2000"));
        trace.begin(PresentationDiagnostics.ACQUIRE, 2_000_000_002L);
        trace.end(3_000_000_002L);
        assertEquals(1, messages.size());
        trace.begin(PresentationDiagnostics.SUBMIT, 12_000_000_001L);
        trace.end(13_000_000_001L);
        assertEquals(2, messages.size());
        assertTrue(messages.get(1).contains("phase=submit"));
    }

    @Test public void quietFramesEmitOnlyBoundedProgressAndDisabledTracingEmitsNothing() {
        List<String> messages = new ArrayList<String>();
        PresentationDiagnostics trace = new PresentationDiagnostics(true, messages::add);
        for (int i = 0; i <= 2000; i++) trace.window(7, true, true, 320, 240, i, i * 10_000_000L);
        assertEquals(3, messages.size());
        assertTrue(messages.get(1).contains("event=progress"));
        PresentationDiagnostics disabled = new PresentationDiagnostics(false, value -> fail(value));
        disabled.window(7, true, true, 320, 240, 0, 1);
        disabled.begin(PresentationDiagnostics.PRESENT, 1); disabled.end(Long.MAX_VALUE);
        disabled.submitted(true, 1, 9, 320, 240, true, 213, 160);
        disabled.submitted(true, 1, 9, 320, 240, true, 213, 160);
        disabled.releasing(1); disabled.presented();
    }
}

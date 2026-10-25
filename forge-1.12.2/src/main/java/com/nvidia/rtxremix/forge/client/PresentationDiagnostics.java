/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.function.Consumer;

/** Opt-in render-thread evidence; observes presentation without resetting or retaining scene state. */
final class PresentationDiagnostics {
    static final int SUBMIT = 1, ACQUIRE = 2, COPY = 3, PRESENT = 4;
    private static final long PERIOD = 10_000_000_000L, SLOW = 500_000_000L;
    private static final String[] PHASES = {"none", "submit", "acquire", "copy", "present"};
    private final boolean enabled;
    private final Consumer<String> output;
    private int phase, width, height, imageWidth, imageHeight, depthWidth, depthHeight;
    private long phaseStarted, lastProgress, lastSlow, hwnd, submits, presents, worldFrames;
    private long runtimeValue, clientValue, imageHandle;
    private boolean haveWindow, haveFrame, active, visible, worldSubmitted, depthPresent, stalled;

    PresentationDiagnostics(boolean enabled, Consumer<String> output) {
        this.enabled = enabled; this.output = output;
    }
    void window(long window, boolean focused, boolean shown, int w, int h, long renderedFrames) {
        if (enabled) window(window, focused, shown, w, h, renderedFrames, System.nanoTime());
    }
    void window(long window, boolean focused, boolean shown, int w, int h, long renderedFrames, long now) {
        if (!enabled) return;
        boolean changed = !haveWindow || hwnd != window || active != focused || visible != shown || width != w || height != h;
        hwnd = window; active = focused; visible = shown; width = w; height = h; worldFrames = renderedFrames;
        if (changed || now - lastProgress >= PERIOD) {
            emit(changed ? "window" : "progress", ""); lastProgress = now;
        }
        haveWindow = true;
    }
    void begin(int nextPhase) { if (enabled) begin(nextPhase, System.nanoTime()); }
    void begin(int nextPhase, long now) {
        if (!enabled) return;
        phase = nextPhase; phaseStarted = now;
    }
    void end() { if (enabled) end(System.nanoTime()); }
    void end(long now) {
        if (!enabled || phase == 0) return;
        long duration = now - phaseStarted;
        if (duration >= SLOW && (lastSlow == 0 || now - lastSlow >= PERIOD)) {
            emit("slow", " phase=" + PHASES[phase] + " elapsedMs=" + duration / 1_000_000L);
            lastSlow = now;
        }
        phase = 0;
    }
    void submitted(boolean world, long waitingValue, long colorHandle, int w, int h, boolean hasDepth, int dw, int dh) {
        if (!enabled) return;
        submits++; worldSubmitted = world; imageHandle = colorHandle; imageWidth = w; imageHeight = h; depthPresent = hasDepth;
        depthWidth = dw; depthHeight = dh;
        if (haveFrame && waitingValue <= runtimeValue) {
            if (!stalled) emit("timeline-not-advancing", " nextRuntime=" + waitingValue);
            stalled = true;
        } else if (stalled) {
            stalled = false; emit("timeline-resumed", " nextRuntime=" + waitingValue);
        }
        runtimeValue = waitingValue; haveFrame = true;
    }
    void releasing(long value) { if (enabled) clientValue = value; }
    void presented() { if (enabled) presents++; }
    private void emit(String event, String detail) {
        output.accept("RTX_PRESENT event=" + event + detail + " hwnd=" + Long.toHexString(hwnd)
                + " active=" + active + " visible=" + visible + " size=" + width + "x" + height
                + " worldSubmitted=" + worldSubmitted + " worldFrames=" + worldFrames
                + " submits=" + submits + " presents=" + presents + " runtimeWait=" + runtimeValue
                + " clientDone=" + clientValue + " color=" + Long.toHexString(imageHandle)
                + " image=" + imageWidth + "x" + imageHeight + " depthPresent=" + depthPresent
                + " depth=" + depthWidth + "x" + depthHeight);
    }
}

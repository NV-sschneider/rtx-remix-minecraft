/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.interop;

import com.nvidia.rtxremix.InteropInfo;

/**
 * Imports a Remix color image into the current Windows OpenGL context.
 * All methods must run on the creating render thread with that context current.
 * The bridge DLL must already be loaded through RemixAPI before use.
 */
public final class LegacyGlInterop implements AutoCloseable {
    private final long runtimeDoneHandle;
    private final long clientDoneHandle;
    private long nativeState;

    /** Returns the HWND owning the current WGL context, or zero if no window is current. */
    public static native long currentWindowHandle();

    public LegacyGlInterop(InteropInfo initial) {
        if (initial == null || initial.dxvkDoneSemaphore_Handle == 0 || initial.clientDoneSemaphore_Handle == 0) {
            throw new IllegalArgumentException("Remix must supply both interop semaphore handles");
        }
        runtimeDoneHandle = initial.dxvkDoneSemaphore_Handle;
        clientDoneHandle = initial.clientDoneSemaphore_Handle;
        nativeState = create(runtimeDoneHandle, clientDoneHandle);
        if (nativeState == 0) throw new IllegalStateException("Native OpenGL interop initialization failed");
    }

    /**
     * Waits for Remix, copies its color into the supplied GL framebuffer, and
     * signals that GL has finished reading the shared image. Returns the timeline
     * value to pass to PresentInfo.clientDoneSemaphore_ValueToWait. Call after
     * RemixAPI.submit, then present with skipVulkanSwapchain=1 for app-owned swap.
     * Existing framebuffer bindings, texture binding, sRGB and scissor state are restored.
     */
    public long copyToFramebuffer(InteropInfo frame, int targetFbo, int width, int height, boolean flipY) {
        if (nativeState == 0) throw new IllegalStateException("OpenGL interop is closed");
        if (frame == null || frame.dxvkDoneSemaphore_Handle != runtimeDoneHandle
                || frame.clientDoneSemaphore_Handle != clientDoneHandle) {
            throw new IllegalArgumentException("Interop semaphore handles changed; recreate the interop session");
        }
        if (frame.imageMemory_Handle == 0 || frame.allocationSize <= 0 || frame.width <= 0
                || frame.height <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Submit must allocate a nonempty interop color image");
        }
        return copy(nativeState, frame.imageMemory_Handle, frame.allocationSize,
                frame.width, frame.height, frame.interopImageFormat,
                frame.dxvkDoneSemaphore_ValueToWait, targetFbo, width, height, flipY);
    }

    /** Releases GL imports before the Remix runtime shuts down. */
    @Override
    public void close() {
        if (nativeState != 0) {
            destroy(nativeState);
            nativeState = 0;
        }
    }

    private static native long create(long runtimeDoneHandle, long clientDoneHandle);
    private static native long copy(long state, long imageHandle, long allocationSize,
                                    int imageWidth, int imageHeight, int imageFormat,
                                    long runtimeDoneValue, int targetFbo,
                                    int targetWidth, int targetHeight, boolean flipY);
    private static native void destroy(long state);
}

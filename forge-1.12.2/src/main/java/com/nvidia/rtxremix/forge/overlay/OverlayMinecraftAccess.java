/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.overlay;

/** Injected access to the keyboard combination state whose key-up events are gated. */
public interface OverlayMinecraftAccess {
    void rtxremix$resetOverlayDebugState();
}

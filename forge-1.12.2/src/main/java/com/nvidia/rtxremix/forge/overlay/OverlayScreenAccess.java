/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.overlay;

/** Finalizes an active screen interaction at its actual cursor position without replacing the screen. */
public interface OverlayScreenAccess {
    void rtxremix$releaseOverlayMouse(int x, int y);
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.frame;

/** Independent of capture arming: a failed/reset capture still reaches the pre-HUD recovery callback. */
public final class WorldFallbackHooks {
    private static Runnable beforeHud;
    private WorldFallbackHooks() { }
    public static void setBeforeHudCallback(Runnable callback) { beforeHud = callback; }
    public static void beforeHud() { if (beforeHud != null) beforeHud.run(); }
}

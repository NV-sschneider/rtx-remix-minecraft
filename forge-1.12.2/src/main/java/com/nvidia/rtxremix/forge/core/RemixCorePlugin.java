/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/** Registers lifecycle and in-place model capture hooks before Minecraft loads. */
@IFMLLoadingPlugin.Name("RLCraftRemixLifecycle")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({"com.nvidia.rtxremix.forge.core."})
public final class RemixCorePlugin implements IFMLLoadingPlugin {
    @Override
    public String[] getASMTransformerClass() {
        return new String[] {
            "com.nvidia.rtxremix.forge.core.DisplayLifecycleTransformer",
            "com.nvidia.rtxremix.forge.core.EntityCaptureTransformer",
            "com.nvidia.rtxremix.forge.core.AtlasAnimationTransformer",
            "com.nvidia.rtxremix.forge.core.ItemCaptureTransformer",
            "com.nvidia.rtxremix.forge.core.EffectCaptureTransformer",
            "com.nvidia.rtxremix.forge.core.CloudCaptureTransformer",
            "com.nvidia.rtxremix.forge.core.FirstPersonCaptureTransformer",
            "com.nvidia.rtxremix.forge.core.OverlayInputTransformer",
            "com.nvidia.rtxremix.forge.core.FastTileCaptureTransformer",
            "com.nvidia.rtxremix.forge.core.HeldLightCaptureTransformer",
            "com.nvidia.rtxremix.forge.core.EntityGlintTransformer",
            "com.nvidia.rtxremix.forge.core.TextureRevisionTransformer",
            "com.nvidia.rtxremix.forge.core.BetterFoliageCaptureTransformer",
            "com.nvidia.rtxremix.forge.core.TerrainDrawTransformer",
            "com.nvidia.rtxremix.forge.core.WorldFallbackTransformer",
            "com.nvidia.rtxremix.forge.core.MobHudTransformer",
            "com.nvidia.rtxremix.forge.core.FogCaptureTransformer"
        };
    }

    @Override
    public String getModContainerClass() { return null; }

    @Override
    public String getSetupClass() { return null; }

    @Override
    public void injectData(Map<String, Object> data) { }

    @Override
    public String getAccessTransformerClass() { return null; }
}

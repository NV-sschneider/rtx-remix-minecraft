/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/** Registers the narrow window-destruction hook before Minecraft loads. */
@IFMLLoadingPlugin.Name("RLCraftRemixLifecycle")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.TransformerExclusions({"com.nvidia.rtxremix.forge.core."})
public final class RemixCorePlugin implements IFMLLoadingPlugin {
    @Override
    public String[] getASMTransformerClass() {
        return new String[] { "com.nvidia.rtxremix.forge.core.DisplayLifecycleTransformer" };
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

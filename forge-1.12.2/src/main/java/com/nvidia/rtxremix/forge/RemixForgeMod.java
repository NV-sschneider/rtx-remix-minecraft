/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.forge.client.ClientEvents;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = RemixForgeMod.ID, name = "RTX Remix Forge Prototype", version = "0.1.0-prototype",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*")
public final class RemixForgeMod {
    public static final String ID = "rlcraftremix";
    public static final Logger LOG = LogManager.getLogger(ID);

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        ClientEvents client = new ClientEvents();
        MinecraftForge.EVENT_BUS.register(client);
        client.register();
        LOG.info("RTX Remix Forge prototype loaded. Use /mrtx on in a test world to enable Remix.");
    }
}

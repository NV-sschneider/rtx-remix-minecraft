/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

final class RemixCommand extends CommandBase {
    private final ClientEvents client;
    RemixCommand(ClientEvents client) { this.client = client; }
    @Override public String getName() { return "mrtx"; }
    @Override public String getUsage(ICommandSender sender) { return "/mrtx <on|off|status|reload|settings>"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        String action = args.length == 0 ? "status" : args[0];
        switch (action) {
            case "on": client.setEnabled(true); break;
            case "off": client.setEnabled(false); break;
            case "reload": client.reload(); client.message("Texture refresh queued."); break;
            case "settings": client.openSettings(); break;
            case "status": client.message(client.status()); break;
            default: client.message(getUsage(sender));
        }
    }
    @Override public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "on", "off", "status", "reload", "settings") : Collections.emptyList();
    }
}

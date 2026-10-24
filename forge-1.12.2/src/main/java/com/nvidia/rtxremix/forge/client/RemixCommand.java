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
import java.io.IOException;
import javax.annotation.Nullable;

final class RemixCommand extends CommandBase {
    private final ClientEvents client;
    RemixCommand(ClientEvents client) { this.client = client; }
    @Override public String getName() { return "mrtx"; }
    @Override public String getUsage(ICommandSender sender) { return "/mrtx <on|off|status|reload|settings|shadows <0-5|status|reset>>"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        String action = args.length == 0 ? "status" : args[0];
        switch (action) {
            case "on": client.setEnabled(true); break;
            case "off": client.setEnabled(false); break;
            case "reload": client.reload(); client.message("Texture refresh queued."); break;
            case "settings": client.openSettings(); break;
            case "shadows": shadows(args); break;
            case "status": client.message(client.status()); break;
            default: client.message(getUsage(sender));
        }
    }
    private void shadows(String[] args) {
        if (args.length == 1 || args.length == 2 && "status".equals(args[1])) {
            client.message(client.shadowVisibilityStatus()); return;
        }
        if (args.length != 2) { client.message("Usage: /mrtx shadows <0-5|status|reset>"); return; }
        try {
            if ("reset".equals(args[1])) client.resetShadowVisibility();
            else client.setShadowVisibility(VisibilitySettings.parseShadows(args[1]));
            client.message(client.shadowVisibilityStatus());
        } catch (IllegalArgumentException error) { client.message(error.getMessage()); }
        catch (IOException error) { client.message("Could not save shadow visibility: " + error.getMessage()); }
    }
    @Override public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "on", "off", "status", "reload", "settings", "shadows");
        if (args.length == 2 && "shadows".equals(args[0])) return getListOfStringsMatchingLastWord(args, "status", "reset", "2", "2.5", "3");
        return Collections.emptyList();
    }
}

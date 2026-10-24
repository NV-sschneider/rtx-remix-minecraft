/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.net.InetAddress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetHandlerLoginClient;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.CPacketLoginStart;
import net.minecraft.util.text.TextComponentString;

/** Fixture-owned connector; login, encryption and Forge negotiation remain vanilla. */
final class FixtureConnection extends GuiScreen {
    private final Minecraft client;
    private final int port;
    private final boolean nativeTransport;
    private final GameProfile profile;
    private NetworkManager manager;
    private Thread worker;
    private boolean cancelled, completed;
    private Exception failure;

    FixtureConnection(Minecraft client, int port) {
        this.client = client; this.port = port;
        nativeTransport = client.gameSettings.isUsingNativeTransport();
        profile = client.getSession().getProfile();
    }

    synchronized void connect() {
        if (worker != null || cancelled) throw new IllegalStateException("Connection attempt already started or cancelled");
        worker = new Thread(this::connectWorker, "Remix smoke server connector");
        worker.setDaemon(true);
        worker.start();
    }

    private void connectWorker() {
        NetworkManager opened = null;
        try {
            opened = NetworkManager.createNetworkManagerAndConnect(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
                    port, nativeTransport);
            synchronized (this) {
                if (cancelled) {
                    opened.closeChannel(new TextComponentString("Smoke fixture cancelled"));
                    return;
                }
                manager = opened;
                manager.setNetHandler(new NetHandlerLoginClient(manager, client, new GuiMainMenu()));
                // The fourth parameter enables the Forge marker used by pinned GuiConnecting.
                manager.sendPacket(new C00Handshake("127.0.0.1", port, EnumConnectionState.LOGIN, true));
                manager.sendPacket(new CPacketLoginStart(profile));
            }
        } catch (Exception problem) {
            if (opened != null) opened.closeChannel(new TextComponentString("Smoke connection failed"));
            synchronized (this) { failure = problem; }
        } finally {
            synchronized (this) { completed = true; }
        }
    }

    @Override public void updateScreen() {
        NetworkManager current;
        Exception problem;
        synchronized (this) { if (cancelled) return; current = manager; problem = failure; }
        if (problem != null) {
            // Do not copy arbitrary authentication or connection exceptions into fixture logs.
            client.displayGuiScreen(new GuiDisconnected(new GuiMainMenu(), "connect.failed",
                    new TextComponentString("The owned localhost test server could not be reached ("
                            + problem.getClass().getSimpleName() + ").")));
        } else if (current != null) {
            if (current.isChannelOpen()) current.processReceivedPackets();
            else current.handleDisconnection();
        }
    }

    synchronized void cancelConnection() {
        cancelled = true;
        if (manager != null) manager.closeChannel(new TextComponentString("Smoke fixture cancelled"));
        if (client.currentScreen == this) client.displayGuiScreen(new GuiMainMenu());
    }

    synchronized boolean finished() {
        return (worker == null || completed && !worker.isAlive()) && (manager == null || !manager.isChannelOpen());
    }

    @Override protected void keyTyped(char character, int keyCode) throws IOException {
        if (keyCode == 1) cancelConnection();
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, "Connecting to the authenticated localhost test server...", width / 2, height / 2, 0xffffff);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}

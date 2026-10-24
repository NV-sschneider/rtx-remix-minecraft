/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.init.Blocks;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.math.BlockPos;

/** Two real authenticated TCP sessions against the helper's owned loopback server. */
final class FixtureMultiplayer implements AutoCloseable {
    private enum Phase { CONNECT, CAPTURE, RELEASE, DONE }
    private static final BlockPos PLATFORM = new BlockPos(0, 64, 0);
    private static final BlockPos TORCH = new BlockPos(3, 65, 3);
    private final Minecraft mc;
    private final ClientEvents events;
    private final FixtureTerrain.Output output;
    private final int port, originalRadius;
    private final String identifier;
    private final UUID cowId;
    private final boolean originalHud;
    private final int originalView;
    private Phase phase = Phase.CONNECT;
    private FixtureConnection connecting;
    private ServerData ownedServer;
    private WorldClient world, previousWorld;
    private NetworkManager network, previousNetwork;
    private long since = System.nanoTime(), progress = since, baseline, readyFrame = -1, readySince;
    private int session, releaseTicks, peakResources;
    private boolean closed;

    FixtureMultiplayer(Minecraft mc, ClientEvents events, int port, String identifier, FixtureTerrain.Output output) {
        if (!Boolean.getBoolean("rlcraftremix.smoke") || !Boolean.getBoolean("rlcraftremix.smoke.multiplayer"))
            throw new IllegalStateException("Multiplayer fixture requires explicit smoke flags");
        requireIdentifier(identifier);
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid loopback fixture port");
        if (mc.world != null || mc.getConnection() != null || mc.getIntegratedServer() != null)
            throw new IllegalStateException("Multiplayer fixture must start from the disconnected menu");
        if (!events.isEnabled() || events.failureMessage() != null)
            throw new IllegalStateException("Multiplayer fixture requires an enabled, healthy renderer");
        this.mc = mc; this.events = events; this.port = port; this.identifier = identifier; this.output = output;
        cowId = cowId(identifier);
        originalRadius = mc.gameSettings.renderDistanceChunks;
        originalHud = mc.gameSettings.hideGUI; originalView = mc.gameSettings.thirdPersonView;
    }

    static void requireIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[a-zA-Z0-9][a-zA-Z0-9-]{0,47}"))
            throw new IllegalArgumentException("Invalid multiplayer fixture identifier");
    }

    static UUID cowId(String identifier) {
        requireIdentifier(identifier);
        return UUID.nameUUIDFromBytes(("mrtx-multiplayer-" + identifier).getBytes(StandardCharsets.US_ASCII));
    }

    boolean tick() throws Exception {
        if (closed) throw new IllegalStateException("Multiplayer fixture is closed");
        if (events.failureMessage() != null || !events.isEnabled())
            throw new IllegalStateException("Multiplayer renderer failed: " + events.failureMessage());
        if (mc.getIntegratedServer() != null) throw new IllegalStateException("Unexpected integrated server during TCP fixture");
        if (session == 0) {
            FixtureTerrain.setRadius(mc, 4); mc.gameSettings.hideGUI = true; mc.gameSettings.thirdPersonView = 0;
            connect();
        }
        long now = System.nanoTime();
        if (now - since > TimeUnit.SECONDS.toNanos(180)) throw new IllegalStateException("Multiplayer phase timed out: " + status());
        if (now - progress > TimeUnit.SECONDS.toNanos(10)) { log("PROGRESS"); progress = now; }
        switch (phase) {
            case CONNECT:
                if (mc.currentScreen instanceof GuiDisconnected)
                    throw new IllegalStateException("Authenticated multiplayer connection rejected; inspect the client disconnect message");
                if (mc.world == null || mc.player == null || mc.getConnection() == null) break;
                world = mc.world; network = mc.getConnection().getNetworkManager();
                requireConnection();
                if (world == previousWorld || network == previousNetwork)
                    throw new IllegalStateException("Reconnect reused its previous client world or network manager");
                transition(Phase.CAPTURE); log("CONNECTED");
                break;
            case CAPTURE:
                requireConnection();
                // The server console owns position and game mode. Only the local camera is held.
                mc.player.rotationYaw = mc.player.prevRotationYaw = 0;
                mc.player.rotationPitch = mc.player.prevRotationPitch = 8;
                int resources = resources();
                peakResources = Math.max(peakResources, resources);
                if (resources > 65536 || events.terrainCapturedBytes() > 512L * 1024 * 1024)
                    throw new IllegalStateException("Multiplayer scene exceeded the bounded fixture envelope");
                if (!ready()) { readyFrame = -1; break; }
                if (readyFrame < 0) { readyFrame = events.renderedFrames(); readySince = now; }
                if (events.renderedFrames() < readyFrame + 60 || now - readySince < TimeUnit.SECONDS.toNanos(2)) break;
                output.capture("multiplayer-session-" + session, output.image());
                log("CAPTURE_PASS");
                previousWorld = world; previousNetwork = network;
                disconnect(); baseline = events.renderedFrames(); releaseTicks = 0;
                transition(Phase.RELEASE);
                break;
            case RELEASE:
                if (mc.world != null || mc.getConnection() != null)
                    throw new IllegalStateException("A client world survived the normal multiplayer disconnect");
                if (previousNetwork.isChannelOpen() || !connecting.finished()) break;
                if (events.renderedFrames() != baseline)
                    throw new IllegalStateException("Native world frames advanced after multiplayer disconnect");
                if (resources() == 0 && events.capturedEntityParts() == 0 && events.capturedTileParts() == 0
                        && events.terrainCapturedBytes() == 0 && events.terrainPendingSectionCount() == 0
                        && events.terrainRetirementCount() == 0) releaseTicks++;
                else releaseTicks = 0;
                if (releaseTicks < 20) break;
                log("RELEASE_PASS");
                if (session < 2) connect();
                else { restoreSettings(); transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private void connect() {
        if (mc.world != null || mc.getConnection() != null || resources() != 0)
            throw new IllegalStateException("Previous scene was not released before multiplayer connect");
        session++;
        world = null; network = null; baseline = events.renderedFrames();
        // false is essential: the pinned LAN login path tolerates authentication errors.
        ownedServer = new ServerData("RTX Remix smoke " + identifier, "127.0.0.1:" + port, false);
        ownedServer.setResourceMode(ServerData.ServerResourceMode.DISABLED);
        mc.setServerData(ownedServer);
        connecting = new FixtureConnection(mc, port);
        mc.displayGuiScreen(connecting);
        connecting.connect();
        transition(Phase.CONNECT); log("CONNECT");
    }

    private void requireConnection() {
        if (mc.world != world || mc.player == null || mc.getConnection() == null
                || mc.getConnection().getNetworkManager() != network || !network.isChannelOpen()
                || network.isLocalChannel() || !network.isEncrypted())
            throw new IllegalStateException("Multiplayer fixture lost its encrypted TCP session");
        if (!(network.getRemoteAddress() instanceof InetSocketAddress))
            throw new IllegalStateException("Unexpected multiplayer transport address");
        InetSocketAddress address = (InetSocketAddress) network.getRemoteAddress();
        if (address.getAddress() == null || !"127.0.0.1".equals(address.getAddress().getHostAddress())
                || address.getPort() != port || mc.getCurrentServerData() == null || mc.getCurrentServerData().isOnLAN())
            throw new IllegalStateException("Multiplayer fixture is not connected to the owned authenticated endpoint");
    }

    private boolean ready() {
        if (mc.currentScreen != null || mc.player.getDistanceSq(0.5, 65, -8.5) > 1
                || world.provider.getDimension() != 0 || !mc.player.capabilities.isCreativeMode
                || world.getBlockState(PLATFORM).getBlock() != Blocks.STONEBRICK
                || world.getBlockState(TORCH).getBlock() != Blocks.TORCH) return false;
        Entity cow = null;
        for (Entity entity : world.loadedEntityList) if (cowId.equals(entity.getUniqueID())) { cow = entity; break; }
        return cow instanceof EntityCow && ("mrtx-smoke-" + identifier).equals(cow.getCustomNameTag())
                && events.entityCaptured(cowId) && events.entityResourceCount() > 0
                && events.terrainSectionReady(0, 4, 0) && events.terrainColumnCaptured(-1, -1)
                && events.terrainResourceCount() > 0 && events.skyResourceCount() > 0
                && events.blockLightCaptured(TORCH) && events.renderedFrames() > baseline;
    }

    private int resources() {
        return events.terrainResourceCount() + events.entityResourceCount() + events.itemResourceCount()
                + events.effectResourceCount() + events.cloudResourceCount() + events.skyResourceCount()
                + events.blockLightResourceCount() + events.heldLightCount() + events.cameraMediumResourceCount()
                + events.terrainFallbackResources() + events.pbrTextureCount();
    }

    private void disconnect() {
        requireConnection();
        detachOwnedWorld();
    }

    private void detachOwnedWorld() {
        // Exactly the dedicated-server branch of pinned GuiIngameMenu, on the client thread.
        world.sendQuittingDisconnectingPacket();
        mc.loadWorld((WorldClient) null);
        mc.displayGuiScreen(new GuiMainMenu());
    }

    private void transition(Phase next) { phase = next; since = System.nanoTime(); readyFrame = -1; }
    String status() {
        return "phase=" + phase + " session=" + session + " frames=" + events.renderedFrames()
                + " resources=" + resources() + " peakResources=" + peakResources
                + " capturedCow=" + events.entityCaptured(cowId) + " releaseTicks=" + releaseTicks;
    }
    private void log(String event) { output.log("MULTIPLAYER_" + event, "fixture=" + identifier + " " + status()); }
    private void restoreSettings() {
        FixtureTerrain.setRadius(mc, originalRadius);
        mc.gameSettings.hideGUI = originalHud; mc.gameSettings.thirdPersonView = originalView;
    }
    boolean cleanupComplete() {
        return (connecting == null || connecting.finished())
                && mc.world == null && mc.getConnection() == null && mc.getIntegratedServer() == null
                && resources() == 0 && events.capturedEntityParts() == 0 && events.capturedTileParts() == 0
                && events.terrainCapturedBytes() == 0 && events.terrainPendingSectionCount() == 0
                && events.terrainRetirementCount() == 0;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            // A connection can finish between the controller's failure check and our next tick.
            // The ServerData object is owned by this request, independent of endpoint equality.
            if (mc.getIntegratedServer() == null && mc.getCurrentServerData() == ownedServer && ownedServer != null) {
                if (mc.world != null && mc.getConnection() != null) {
                    world = mc.world; network = mc.getConnection().getNetworkManager();
                    detachOwnedWorld();
                }
            }
        } finally {
            try { if (connecting != null) connecting.cancelConnection(); }
            finally { restoreSettings(); }
        }
    }

}

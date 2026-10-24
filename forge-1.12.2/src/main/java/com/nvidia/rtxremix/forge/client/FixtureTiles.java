/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.entity.FastTileCaptureHooks;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.client.model.animation.FastTESR;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.opengl.GL11;

/** Original client-only test renderers exercise normal CPU draws and two owners of a shared fast batch. */
final class FixtureTiles implements AutoCloseable {
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(240), PROGRESS = TimeUnit.SECONDS.toNanos(10);
    private enum Phase { ARRIVE, INSTALL, READY, ANIMATE, RELOAD, RELOADED, REMOVE, REMOVED, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String saveName;
    private final UUID playerId;
    private final Layout layout;
    private final FixtureTerrain.Output output;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private final TileEntity[] originals = new TileEntity[3], owned = new TileEntity[3];
    private final long[] geometry = new long[3];
    private final NormalRenderer normalRenderer = new NormalRenderer();
    private final FastRenderer fastRenderer = new FastRenderer();
    private TileEntitySpecialRenderer<?> originalNormalRenderer, originalFastRenderer;
    private boolean normalPresent, fastPresent, installed, closed;
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private long since = System.nanoTime(), lastProgress = since, readyFrame = -1, reloadBaseline, fastBaseline;

    FixtureTiles(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String saveName, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        if (!saveName.startsWith("rlcraft-remix-smoke-") || !saveName.equals(server.getFolderName())
                || mc.getIntegratedServer() != server || mc.world == null)
            throw new IllegalArgumentException("Tile fixture requires its dedicated smoke world");
        this.mc = mc; this.events = events; this.server = server; this.world = world;
        clientWorld = mc.world; this.saveName = saveName; this.layout = layout; this.output = output;
        playerId = mc.player.getUniqueID(); returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        action = server.callFromMainThread(() -> {
            requireWorld(); FixtureEnvironment.setSky(world, 9000L, false);
            player().connection.setPlayerLocation(cameraX(), cameraY(), cameraZ(), 180f, 10f); return null;
        });
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (closed || mc.world != clientWorld || mc.getIntegratedServer() != server
                || !events.isEnabled() || events.failureMessage() != null)
            throw new IllegalStateException("Tile fixture renderer/world unavailable");
        if (System.nanoTime() - since >= TIMEOUT) {
            capture("tiles-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Tile fixture timed out: " + status());
        }
        if (System.nanoTime() - lastProgress >= PROGRESS) { log("PROGRESS"); lastProgress = System.nanoTime(); }
        if (phase != Phase.RETURN && phase != Phase.DONE) holdPose(cameraX(), cameraY(), cameraZ(), 180f, 10f);
        switch (phase) {
            case ARRIVE:
                if (complete()) { FixtureEnvironment.setSky(clientWorld, 9000L, false); transition(Phase.INSTALL); }
                break;
            case INSTALL:
                if (blocksReady()) { install(); fastBaseline = FastTileCaptureHooks.capturedRangeCount(); transition(Phase.READY); }
                break;
            case READY:
                if (settled(captured() && FastTileCaptureHooks.capturedRangeCount() >= fastBaseline + 2)) {
                    capture("tiles-normal-and-two-fast-owners"); log("OWNERS_PASS");
                    for (int i = 0; i < geometry.length; i++) geometry[i] = events.tileGeometryUpdates(layout.tiles[i]);
                    transition(Phase.ANIMATE);
                }
                break;
            case ANIMATE:
                if (settled(animated())) {
                    capture("tiles-animated"); log("ANIMATION_PASS"); reloadBaseline = events.textureReloadCount();
                    events.reload(); transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(events.textureReloadCount() > reloadBaseline && captured())) {
                    capture("tiles-reloaded"); log("RELOAD_PASS"); transition(Phase.RELOADED);
                }
                break;
            case RELOADED:
                action = server.callFromMainThread(() -> { requireWorld(); for (BlockPos pos : layout.tiles) world.setBlockToAir(pos); return null; });
                transition(Phase.REMOVE); break;
            case REMOVE:
                if (complete()) transition(Phase.REMOVED);
                break;
            case REMOVED:
                if (settled(removed())) {
                    capture("tiles-removed"); log("REMOVAL_PASS"); restoreRenderersAndTiles();
                    action = server.callFromMainThread(() -> { requireWorld(); restorePlayer(); return null; }); transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) { holdPose(returnX, returnY, returnZ, returnYaw, returnPitch); transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private void install() {
        TileEntityRendererDispatcher dispatcher = TileEntityRendererDispatcher.instance;
        normalPresent = dispatcher.renderers.containsKey(NormalTile.class); fastPresent = dispatcher.renderers.containsKey(FastTile.class);
        originalNormalRenderer = dispatcher.renderers.get(NormalTile.class); originalFastRenderer = dispatcher.renderers.get(FastTile.class);
        installed = true;
        ClientRegistry.bindTileEntitySpecialRenderer(NormalTile.class, normalRenderer);
        ClientRegistry.bindTileEntitySpecialRenderer(FastTile.class, fastRenderer);
        for (int i = 0; i < owned.length; i++) {
            originals[i] = clientWorld.getTileEntity(layout.tiles[i]);
            TileEntity tile = i == 0 ? new NormalTile() : new FastTile();
            owned[i] = tile; clientWorld.removeTileEntity(layout.tiles[i]);
            clientWorld.setTileEntity(layout.tiles[i], tile); clientWorld.markBlockRangeForRenderUpdate(layout.tiles[i], layout.tiles[i]);
        }
    }
    private void restoreRenderersAndTiles() {
        if (!installed) return;
        for (int i = 0; i < owned.length; i++) {
            if (clientWorld.getTileEntity(layout.tiles[i]) != owned[i]) continue;
            clientWorld.removeTileEntity(layout.tiles[i]);
            if (originals[i] != null && clientWorld.getBlockState(layout.tiles[i]).getBlock() == Blocks.CHEST) {
                originals[i].validate(); clientWorld.setTileEntity(layout.tiles[i], originals[i]);
            }
            clientWorld.markBlockRangeForRenderUpdate(layout.tiles[i], layout.tiles[i]);
        }
        TileEntityRendererDispatcher dispatcher = TileEntityRendererDispatcher.instance;
        if (normalPresent) dispatcher.renderers.put(NormalTile.class, originalNormalRenderer); else dispatcher.renderers.remove(NormalTile.class);
        if (fastPresent) dispatcher.renderers.put(FastTile.class, originalFastRenderer); else dispatcher.renderers.remove(FastTile.class);
        installed = false;
    }
    private boolean blocksReady() {
        for (BlockPos pos : layout.tiles) if (clientWorld.getBlockState(pos).getBlock() != Blocks.CHEST
                || !(clientWorld.getTileEntity(pos) instanceof TileEntityChest)
                || !events.terrainSectionReady(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4)) return false;
        return true;
    }
    private boolean captured() {
        for (int i = 0; i < owned.length; i++) if (clientWorld.getTileEntity(layout.tiles[i]) != owned[i]
                || !events.tileCaptured(layout.tiles[i]) || events.tileTrianglePartCount(layout.tiles[i]) != 1) return false;
        return true;
    }
    private boolean animated() {
        if (!captured()) return false;
        for (int i = 0; i < geometry.length; i++) if (events.tileGeometryUpdates(layout.tiles[i]) <= geometry[i]) return false;
        return true;
    }
    private boolean removed() {
        for (BlockPos pos : layout.tiles) if (!clientWorld.isAirBlock(pos) || events.tileCaptured(pos)
                || events.tileTrianglePartCount(pos) != 0) return false;
        return true;
    }
    private boolean settled(boolean ready) {
        if (!ready) { readyFrame = -1; return false; }
        if (readyFrame < 0) readyFrame = events.renderedFrames();
        return events.renderedFrames() - readyFrame >= 30;
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private double cameraX() { return layout.x + 0.5; }
    private double cameraY() { return layout.y + 4.0; }
    private double cameraZ() { return layout.z + 18.5; }
    private void holdPose(double x, double y, double z, float yaw, float pitch) {
        mc.player.setPositionAndRotation(x, y, z, yaw, pitch);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z;
        mc.player.prevRotationYaw = mc.player.rotationYawHead = mc.player.prevRotationYawHead = yaw;
        mc.player.prevRotationPitch = pitch; mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
        mc.player.capabilities.isFlying = true;
    }
    private void requireWorld() {
        if (!server.isCallingFromMinecraftThread() || !saveName.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Tile fixture no longer owns the server world");
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Tile fixture player absent"); return player;
    }
    private void restorePlayer() { player().connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); }
    private void transition(Phase next) { phase = next; since = lastProgress = System.nanoTime(); readyFrame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.log("TILES_CAPTURE", status() + " file=" + output.capture(name, output.image())); }
    private void log(String event) { output.log("TILES_" + event, status()); }
    String status() {
        return "tilePhase=" + phase + " normalParts=" + events.tileTrianglePartCount(layout.tiles[0])
                + " fastLeftParts=" + events.tileTrianglePartCount(layout.tiles[1]) + " fastRightParts=" + events.tileTrianglePartCount(layout.tiles[2])
                + " fastRanges=" + FastTileCaptureHooks.capturedRangeCount() + " rejectedRanges=" + FastTileCaptureHooks.rejectedRangeCount()
                + " unsupportedBatches=" + FastTileCaptureHooks.unsupportedBatchCount() + " reloads=" + events.textureReloadCount();
    }
    @Override public void close() {
        if (closed) return; restoreRenderersAndTiles();
        if (phase != Phase.DONE && mc.world == clientWorld && mc.getIntegratedServer() == server && mc.player != null
                && playerId.equals(mc.player.getUniqueID())) {
            holdPose(returnX, returnY, returnZ, returnYaw, returnPitch);
            server.callFromMainThread(() -> { requireWorld(); restorePlayer(); return null; });
        }
        closed = true;
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        if (!world.getMinecraftServer().isCallingFromMinecraftThread()
                || !world.getMinecraftServer().getFolderName().startsWith("rlcraft-remix-smoke-"))
            throw new IllegalStateException("Tile construction requires its owned server thread and smoke save");
        Layout layout = new Layout(x, y, z);
        for (int dx = -9; dx <= 9; dx++) for (int dz = 2; dz <= 21; dz++) {
            world.setBlockState(new BlockPos(x + dx, y, z + dz), ((dx + dz) & 1) == 0
                    ? Blocks.QUARTZ_BLOCK.getDefaultState() : Blocks.COAL_BLOCK.getDefaultState(), 3);
            for (int dy = 1; dy <= 8; dy++) world.setBlockToAir(new BlockPos(x + dx, y + dy, z + dz));
        }
        for (BlockPos pos : layout.tiles) world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        return layout;
    }
    static final class Layout {
        final int x, y, z; final BlockPos[] tiles;
        Layout(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
            tiles = new BlockPos[] {new BlockPos(x - 4, y + 1, z + 8), new BlockPos(x, y + 1, z + 8), new BlockPos(x + 4, y + 1, z + 8)};
        }
    }

    public static class NormalTile extends TileEntityChest {
        @Override public void update() { }
        @Override public AxisAlignedBB getRenderBoundingBox() { return new AxisAlignedBB(getPos()).grow(1, 3, 1); }
    }
    public static final class FastTile extends NormalTile {
        @Override public boolean hasFastRenderer() { return true; }
    }
    private static final class NormalRenderer extends TileEntitySpecialRenderer<NormalTile> {
        @Override public void render(NormalTile tile, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
            bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            BufferBuilder target = Tessellator.getInstance().getBuffer(); target.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            emit(target, tile, x, y, z, partialTicks, 220, 55, 45); Tessellator.getInstance().draw();
        }
    }
    private static final class FastRenderer extends FastTESR<FastTile> {
        @Override public void renderTileEntityFast(FastTile tile, double x, double y, double z, float partialTicks,
                                                  int destroyStage, float alpha, BufferBuilder target) {
            emit(target, tile, x, y, z, partialTicks, 45, 155, 235);
        }
    }
    private static void emit(BufferBuilder target, TileEntity tile, double x, double y, double z,
                             float partialTicks, int red, int green, int blue) {
        TextureAtlasSprite sprite = Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite("minecraft:blocks/quartz_block_side");
        double top = 2 + Math.sin((tile.getWorld().getTotalWorldTime() + partialTicks) * 0.12 + tile.getPos().getX()) * 0.35;
        target.pos(x - 0.25, y, z + 0.5).color(red, green, blue, 255).tex(sprite.getMinU(), sprite.getMaxV()).lightmap(240, 240).endVertex();
        target.pos(x + 1.25, y, z + 0.5).color(red, green, blue, 255).tex(sprite.getMaxU(), sprite.getMaxV()).lightmap(240, 240).endVertex();
        target.pos(x + 1.25, y + top, z + 0.5).color(red, green, blue, 255).tex(sprite.getMaxU(), sprite.getMinV()).lightmap(240, 240).endVertex();
        target.pos(x - 0.25, y + top, z + 0.5).color(red, green, blue, 255).tex(sprite.getMinU(), sprite.getMinV()).lightmap(240, 240).endVertex();
    }
}

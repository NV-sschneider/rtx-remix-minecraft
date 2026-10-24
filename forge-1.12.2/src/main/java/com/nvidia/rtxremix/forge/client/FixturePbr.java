/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.block.BlockStainedGlassPane;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/** Pack switching and actual animated material publication in an owned test world. */
final class FixturePbr implements AutoCloseable {
    private static final Field DEFAULT_PACKS = ReflectionHelper.findField(Minecraft.class,
            new String[] {"defaultResourcePacks", "field_110449_ao"});
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(300);
    private static final long PROGRESS = TimeUnit.SECONDS.toNanos(10);
    private static final int SETTLE_FRAMES = 30;
    private enum Phase { ARRIVE, BASELINE, INSTALL, ANIMATE, RELOAD, REANIMATE, CHANGE,
        CHANGED, REMOVE_PACK, PACK_REMOVED, REMOVE_PANES, PANES_REMOVED, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String saveName;
    private final UUID playerId;
    private final Layout layout;
    private final FixtureTerrain.Output output;
    private final FixturePackScope packs;
    private final FixturePbrPack firstPack, secondPack;
    private final List<ResourcePackRepository.Entry> selectedPacks;
    private final List<String> selectedNames, incompatibleNames;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private final long[] removalRevisions;
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private long since = System.nanoTime(), lastProgress = since, readyFrame = -1;
    private long reloadBaseline, animationBaseline, mapBaseline, animationHash;
    private long clearBaseline, stainedBaseline;
    private int originalSprites, originalTextures, originalSkipped;
    private int animationTicks;
    private boolean closed;

    FixturePbr(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String saveName, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) throws IOException {
        if (!saveName.startsWith("rlcraft-remix-smoke-") || !saveName.equals(server.getFolderName())
                || mc.getIntegratedServer() != server || mc.world == null) {
            throw new IllegalArgumentException("PBR fixture requires its dedicated smoke world");
        }
        this.mc = mc; this.events = events; this.server = server; this.world = world;
        clientWorld = mc.world; this.saveName = saveName; this.layout = layout; this.output = output;
        playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        selectedPacks = new ArrayList<ResourcePackRepository.Entry>(mc.getResourcePackRepository().getRepositoryEntries());
        selectedNames = new ArrayList<String>(mc.gameSettings.resourcePacks);
        incompatibleNames = new ArrayList<String>(mc.gameSettings.incompatibleResourcePacks);
        packs = new FixturePackScope(activePacks(mc), next -> {
            ((IReloadableResourceManager) mc.getResourceManager()).reloadResources(next);
            mc.renderGlobal.loadRenderers();
        });
        firstPack = new FixturePbrPack(1); secondPack = new FixturePbrPack(2);
        removalRevisions = new long[layout.paneSections.length];
        action = server.callFromMainThread(() -> {
            requireWorld(); FixtureEnvironment.setSky(world, 9000L, false);
            player().connection.setPlayerLocation(cameraX(), cameraY(), cameraZ(), 180f, 10f);
            return null;
        });
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (closed || mc.world != clientWorld || mc.getIntegratedServer() != server
                || !events.isEnabled() || events.failureMessage() != null) {
            throw new IllegalStateException("PBR fixture renderer/world unavailable");
        }
        if (!selectionUnchanged()) throw new IllegalStateException("Resource pack selection changed during PBR fixture");
        if (System.nanoTime() - since >= TIMEOUT) {
            log("TIMEOUT"); capture("pbr-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("PBR fixture timed out: " + status());
        }
        if (System.nanoTime() - lastProgress >= PROGRESS) { log("PROGRESS"); lastProgress = System.nanoTime(); }
        if (phase != Phase.RETURN && phase != Phase.DONE) holdPose(cameraX(), cameraY(), cameraZ(), 180f, 10f);
        switch (phase) {
            case ARRIVE:
                if (complete()) { FixtureEnvironment.setSky(clientWorld, 9000L, false); transition(Phase.BASELINE); }
                break;
            case BASELINE:
                if (settled(sceneReady(true))) {
                    originalSprites = events.pbrSpriteCount(); originalTextures = events.pbrTextureCount();
                    originalSkipped = events.pbrSkippedCount();
                    capture("pbr-before-pack");
                    reloadBaseline = events.textureReloadCount(); packs.install(firstPack); transition(Phase.INSTALL);
                }
                break;
            case INSTALL:
                if (settled(mapped(firstPack))) { beginAnimation(Phase.ANIMATE); log("PACK_INSTALL_PASS"); }
                break;
            case ANIMATE:
                if (animated()) {
                    capture("pbr-maps-and-panes"); log("ANIMATION_PASS");
                    reloadBaseline = events.textureReloadCount(); packs.install(firstPack); transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(mapped(firstPack))) beginAnimation(Phase.REANIMATE);
                break;
            case REANIMATE:
                if (animated()) {
                    capture("pbr-reloaded"); log("RELOAD_PASS");
                    reloadBaseline = events.textureReloadCount(); packs.install(secondPack); transition(Phase.CHANGE);
                }
                break;
            case CHANGE:
                if (settled(mapped(secondPack))) beginAnimation(Phase.CHANGED);
                break;
            case CHANGED:
                if (animated()) {
                    capture("pbr-changed-maps"); log("PACK_CHANGE_PASS");
                    reloadBaseline = events.textureReloadCount(); packs.close(); transition(Phase.REMOVE_PACK);
                }
                break;
            case REMOVE_PACK:
                if (settled(events.textureReloadCount() > reloadBaseline && sceneReady(true)
                        && events.pbrSpriteCount() == originalSprites && events.pbrTextureCount() == originalTextures
                        && events.pbrSkippedCount() == originalSkipped && !fixturePackPresent())) {
                    capture("pbr-pack-removed"); log("PACK_REMOVAL_PASS"); transition(Phase.PACK_REMOVED);
                }
                break;
            case PACK_REMOVED:
                clearBaseline = events.terrainClearGlassVertexCount(); stainedBaseline = events.terrainStainedGlassVertexCount();
                for (int i = 0; i < removalRevisions.length; i++) removalRevisions[i] = revision(layout.paneSections[i]);
                action = server.callFromMainThread(() -> {
                    requireWorld(); for (BlockPos pane : layout.panes) world.setBlockToAir(pane); return null;
                });
                transition(Phase.REMOVE_PANES);
                break;
            case REMOVE_PANES:
                if (complete()) transition(Phase.PANES_REMOVED);
                break;
            case PANES_REMOVED:
                if (settled(panesRemoved() && events.terrainClearGlassVertexCount() < clearBaseline
                        && events.terrainStainedGlassVertexCount() < stainedBaseline)) {
                    capture("pbr-panes-removed"); log("PANE_REMOVAL_PASS");
                    action = server.callFromMainThread(() -> { requireWorld(); restorePlayer(); return null; });
                    transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) { holdPose(returnX, returnY, returnZ, returnYaw, returnPitch); transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private boolean mapped(FixturePbrPack pack) throws IOException {
        try (IResource resource = mc.getResourceManager().getResource(FixturePbrPack.REVISION)) {
            return pack.getPackName().equals(resource.getResourcePackName()) && events.textureReloadCount() > reloadBaseline
                    && events.pbrSpriteCount() >= FixturePbrPack.SPRITES.length && events.pbrTextureCount() == 4
                    && sceneReady(true);
        }
    }
    private boolean fixturePackPresent() throws IOException {
        try (IResource resource = mc.getResourceManager().getResource(FixturePbrPack.REVISION)) {
            return firstPack.getPackName().equals(resource.getResourcePackName()) || secondPack.getPackName().equals(resource.getResourcePackName());
        } catch (FileNotFoundException absent) { return false; }
    }
    private void beginAnimation(Phase next) {
        animationBaseline = events.atlasSpriteUpdates(FixturePbrPack.ANIMATED_SPRITE);
        animationHash = events.atlasSpriteHash(FixturePbrPack.ANIMATED_SPRITE);
        mapBaseline = events.pbrUpdateCount(); animationTicks = 0; transition(next);
    }
    private boolean animated() {
        return ++animationTicks >= 20 && events.pbrUpdateCount() >= mapBaseline + 4
                && events.atlasSpriteUpdates(FixturePbrPack.ANIMATED_SPRITE) >= animationBaseline + 4
                && events.atlasSpriteHash(FixturePbrPack.ANIMATED_SPRITE) != animationHash && sceneReady(true);
    }
    private boolean sceneReady(boolean panes) {
        for (int i = 0; i < layout.panels.length; i++) {
            if (clientWorld.getBlockState(layout.panels[i]).getBlock() != panelBlock(i / 9)) return false;
        }
        if (panes) {
            for (int i = 0; i < layout.panes.length; i++) {
                if (!clientWorld.getBlockState(layout.panes[i]).equals(paneState(i / 9))) return false;
            }
            if (events.terrainClearGlassVertexCount() == 0 || events.terrainStainedGlassVertexCount() == 0) return false;
        }
        for (BlockPos section : layout.sections) if (!sectionReady(section)) return false;
        return true;
    }
    private boolean panesRemoved() {
        for (BlockPos pane : layout.panes) if (!clientWorld.isAirBlock(pane)) return false;
        for (int i = 0; i < layout.paneSections.length; i++) {
            if (!sectionReady(layout.paneSections[i]) || revision(layout.paneSections[i]) <= removalRevisions[i]) return false;
        }
        return true;
    }
    private boolean sectionReady(BlockPos section) { return events.terrainSectionReady(section.getX(), section.getY(), section.getZ()); }
    private long revision(BlockPos section) { return events.terrainSectionRevision(section.getX(), section.getY(), section.getZ()); }
    private boolean settled(boolean ready) {
        if (!ready) { readyFrame = -1; return false; }
        if (readyFrame < 0) readyFrame = events.renderedFrames();
        return events.renderedFrames() - readyFrame >= SETTLE_FRAMES;
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private double cameraX() { return layout.x + 0.5; }
    private double cameraY() { return layout.y + 4.5; }
    private double cameraZ() { return layout.z + 27.5; }
    private void restorePlayer() { player().connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); }
    private void holdPose(double x, double y, double z, float yaw, float pitch) {
        mc.player.setPositionAndRotation(x, y, z, yaw, pitch);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z;
        mc.player.prevRotationYaw = mc.player.rotationYawHead = mc.player.prevRotationYawHead = yaw;
        mc.player.prevRotationPitch = pitch;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("PBR fixture player is absent");
        return player;
    }
    private void requireWorld() {
        if (!server.isCallingFromMinecraftThread() || !saveName.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("PBR fixture no longer owns the server world");
    }
    private boolean selectionUnchanged() {
        return selectedPacks.equals(mc.getResourcePackRepository().getRepositoryEntries())
                && selectedNames.equals(mc.gameSettings.resourcePacks) && incompatibleNames.equals(mc.gameSettings.incompatibleResourcePacks);
    }
    @SuppressWarnings("unchecked") private static List<IResourcePack> activePacks(Minecraft mc) {
        try {
            List<IResourcePack> packs = new ArrayList<IResourcePack>((List<IResourcePack>) DEFAULT_PACKS.get(mc));
            for (ResourcePackRepository.Entry entry : mc.getResourcePackRepository().getRepositoryEntries()) packs.add(entry.getResourcePack());
            IResourcePack server = mc.getResourcePackRepository().getServerResourcePack();
            if (server != null) packs.add(server);
            return packs;
        } catch (IllegalAccessException error) { throw new IllegalStateException("Cannot snapshot active resource packs", error); }
    }
    private void transition(Phase next) { phase = next; since = lastProgress = System.nanoTime(); readyFrame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.log("PBR_CAPTURE", status() + " file=" + output.capture(name, output.image())); }
    private void log(String event) { output.log("PBR_" + event, status()); }
    String status() {
        return "pbrPhase=" + phase + " mappedSprites=" + events.pbrSpriteCount() + " mapTextures=" + events.pbrTextureCount()
                + " mapUpdates=" + events.pbrUpdateCount() + " skipped=" + events.pbrSkippedCount()
                + " animatedUpdates=" + events.atlasSpriteUpdates(FixturePbrPack.ANIMATED_SPRITE)
                + " animatedHash=" + events.atlasSpriteHash(FixturePbrPack.ANIMATED_SPRITE)
                + " clearGlass=" + events.terrainClearGlassVertexCount() + " stainedGlass=" + events.terrainStainedGlassVertexCount()
                + " selectionPreserved=" + selectionUnchanged() + " packInstalled=" + packs.installed()
                + " reloads=" + events.textureReloadCount() + " pending=" + events.terrainPendingSectionCount();
    }

    @Override public void close() {
        if (closed) return;
        packs.close();
        if (phase != Phase.DONE && mc.world == clientWorld && mc.getIntegratedServer() == server && mc.player != null
                && playerId.equals(mc.player.getUniqueID())) {
            holdPose(returnX, returnY, returnZ, returnYaw, returnPitch);
            server.callFromMainThread(() -> { requireWorld(); restorePlayer(); return null; });
        }
        closed = true;
    }

    /** Called from the parent fixture's server-owned setup task. */
    static Layout build(WorldServer world, int x, int y, int z) {
        if (!world.getMinecraftServer().isCallingFromMinecraftThread()
                || !world.getMinecraftServer().getFolderName().startsWith("rlcraft-remix-smoke-")) {
            throw new IllegalStateException("PBR construction requires its owned server thread and smoke save");
        }
        Layout layout = new Layout(x, y, z);
        for (int dx = -13; dx <= 13; dx++) for (int dz = 2; dz <= 24; dz++) {
            world.setBlockState(new BlockPos(x + dx, y, z + dz), ((dx + dz) & 1) == 0
                    ? Blocks.QUARTZ_BLOCK.getDefaultState() : Blocks.COAL_BLOCK.getDefaultState(), 3);
            for (int dy = 1; dy <= 12; dy++) world.setBlockToAir(new BlockPos(x + dx, y + dy, z + dz));
        }
        for (int i = 0; i < layout.panels.length; i++) world.setBlockState(layout.panels[i], panelBlock(i / 9).getDefaultState(), 3);
        for (int i = 0; i < layout.panes.length; i++) {
            world.setBlockState(layout.panes[i], paneState(i / 9), 3);
            world.setBlockState(layout.panes[i].north(2), (i % 2 == 0 ? Blocks.QUARTZ_BLOCK : Blocks.LAPIS_BLOCK).getDefaultState(), 3);
        }
        return layout;
    }
    private static Block panelBlock(int panel) {
        switch (panel) {
            case 0: return Blocks.BRICK_BLOCK;
            case 1: return Blocks.IRON_BLOCK;
            case 2: return Blocks.GOLD_BLOCK;
            case 3: return Blocks.REDSTONE_BLOCK;
            case 4: return Blocks.DIAMOND_BLOCK;
            default: throw new IllegalArgumentException("Unknown PBR panel");
        }
    }
    private static IBlockState paneState(int pane) {
        return pane == 0 ? Blocks.GLASS_PANE.getDefaultState() : Blocks.STAINED_GLASS_PANE.getDefaultState()
                .withProperty(BlockStainedGlassPane.COLOR, EnumDyeColor.BLUE);
    }
    static final class Layout {
        final int x, y, z;
        final BlockPos[] panels = new BlockPos[45], panes = new BlockPos[18], sections, paneSections;
        Layout(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
            Set<BlockPos> touched = new LinkedHashSet<BlockPos>();
            Set<BlockPos> paneTouched = new LinkedHashSet<BlockPos>();
            int index = 0;
            for (int panel = 0; panel < 5; panel++) for (int dx = -1; dx <= 1; dx++) for (int dy = 1; dy <= 3; dy++) {
                BlockPos pos = new BlockPos(x + (panel - 2) * 5 + dx, y + dy, z + 8);
                panels[index++] = pos; touched.add(section(pos));
            }
            index = 0;
            for (int pane = 0; pane < 2; pane++) for (int dx = -1; dx <= 1; dx++) for (int dy = 1; dy <= 3; dy++) {
                BlockPos pos = new BlockPos(x + (pane == 0 ? -5 : 5) + dx, y + dy, z + 16);
                panes[index++] = pos; touched.add(section(pos)); paneTouched.add(section(pos));
            }
            sections = touched.toArray(new BlockPos[0]);
            paneSections = paneTouched.toArray(new BlockPos[0]);
        }
        private static BlockPos section(BlockPos pos) { return new BlockPos(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4); }
    }
}

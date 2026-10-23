/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Native fluid, animated-atlas and block-light evidence in an owned fixture scene. */
final class FixtureMaterials {
    private static final String WATER = "minecraft:blocks/water_still";
    private static final String LAVA = "minecraft:blocks/lava_still";
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(300);
    private static final long PROGRESS_INTERVAL = TimeUnit.SECONDS.toNanos(10);
    private static final int SETTLE_FRAMES = 20;
    private enum Phase { NIGHT, READY, ANIMATION, REMOVE, REMOVED, RELOAD,
        ENTER_WATER, UNDERWATER, UNDERWATER_RELOAD, EXIT_WATER, AIR, RESTORE, DONE }

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
    private Phase phase = Phase.NIGHT;
    private Future<?> action;
    private long phaseSince = System.nanoTime();
    private long lastProgress = phaseSince;
    private long readyFrame = -1;
    private long waterUpdates, lavaUpdates, waterHash, lavaHash;
    private long reloadRevision;
    private int animationTicks;
    private boolean afterReload;
    private long mediumReloadCount;

    FixtureMaterials(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String saveName, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        this.mc = mc; this.events = events; this.server = server; this.world = world;
        this.clientWorld = mc.world; this.saveName = saveName; this.layout = layout; this.output = output;
        this.playerId = mc.player.getUniqueID();
        this.returnX = x; this.returnY = y; this.returnZ = z; this.returnYaw = yaw; this.returnPitch = pitch;
        action = server.callFromMainThread(() -> {
            requireWorld();
            FixtureEnvironment.setSky(world, 18000L, false);
            player().connection.setPlayerLocation(layout.x + 0.5, layout.y + 8.0, layout.z + 22.5, 180f, 30f);
            return null;
        });
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (mc.world != clientWorld || mc.getIntegratedServer() != server) {
            throw new IllegalStateException("Material fixture no longer owns its world");
        }
        if (!events.isEnabled() || events.failureMessage() != null) {
            throw new IllegalStateException("Material fixture renderer is unavailable");
        }
        if (System.nanoTime() - phaseSince > TIMEOUT) {
            log("TIMEOUT");
            output.capture("materials-timeout-" + phase.name().toLowerCase(Locale.ROOT), output.image());
            throw new IllegalStateException("Material fixture timed out");
        }
        if (System.nanoTime() - lastProgress >= PROGRESS_INTERVAL) {
            log("PROGRESS");
            lastProgress = System.nanoTime();
        }
        if (phase != Phase.RESTORE) holdPose();
        switch (phase) {
            case NIGHT:
                if (action.isDone()) {
                    action.get();
                    FixtureEnvironment.setSky(clientWorld, 18000L, false);
                    transition(Phase.READY);
                }
                break;
            case READY:
                if (settled(sceneReady() && events.blockLightCaptured(layout.torch))) beginAnimation();
                break;
            case ANIMATION:
                if (++animationTicks >= 20 && animationChanged() && sceneReady()) {
                    capture(afterReload ? "materials-reloaded" : "materials-night-lit");
                    log(afterReload ? "RELOAD_PASS" : "ANIMATION_PASS");
                    if (afterReload) {
                        action = server.callFromMainThread(() -> {
                            requireWorld();
                            player().connection.setPlayerLocation(layout.water.getX() + 0.5, layout.y + 1.1,
                                    layout.water.getZ() + 0.5, 0f, 0f);
                            return null;
                        });
                        transition(Phase.ENTER_WATER);
                    } else {
                        action = server.callFromMainThread(() -> {
                            requireWorld();
                            world.setBlockToAir(layout.torch);
                            return null;
                        });
                        transition(Phase.REMOVE);
                    }
                }
                break;
            case REMOVE:
                if (action.isDone()) { action.get(); transition(Phase.REMOVED); }
                break;
            case REMOVED:
                if (settled(clientWorld.isAirBlock(layout.torch) && !events.blockLightCaptured(layout.torch)
                        && sceneReady())) {
                    capture("materials-night-torch-removed");
                    log("LIGHT_REMOVAL_PASS");
                    reloadRevision = sectionRevision(layout.water);
                    events.reload();
                    transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(sceneReady() && sectionRevision(layout.water) > reloadRevision
                        && !events.blockLightCaptured(layout.torch))) {
                    afterReload = true;
                    beginAnimation();
                }
                break;
            case ENTER_WATER:
                if (action.isDone()) { action.get(); transition(Phase.UNDERWATER); }
                break;
            case UNDERWATER:
                if (settled(waterMediumReady())) {
                    capture("materials-underwater");
                    log("UNDERWATER_PASS");
                    mediumReloadCount = events.textureReloadCount();
                    events.reload();
                    transition(Phase.UNDERWATER_RELOAD);
                }
                break;
            case UNDERWATER_RELOAD:
                // Native handles may legally be reused after reload; completed reload plus
                // a freshly submitted water medium is the relevant runtime evidence.
                if (settled(waterMediumReady() && events.textureReloadCount() > mediumReloadCount)) {
                    capture("materials-underwater-reloaded");
                    log("UNDERWATER_RELOAD_PASS");
                    action = server.callFromMainThread(() -> {
                        requireWorld();
                        player().connection.setPlayerLocation(layout.x + 0.5, layout.y + 8.0,
                                layout.z + 22.5, 180f, 30f);
                        return null;
                    });
                    transition(Phase.EXIT_WATER);
                }
                break;
            case EXIT_WATER:
                if (action.isDone()) { action.get(); transition(Phase.AIR); }
                break;
            case AIR:
                if (settled(!events.cameraMediumUnderwater() && events.cameraMediumHandle() == 0
                        && !actualCameraWater() && sceneReady())) {
                    capture("materials-water-exit");
                    log("UNDERWATER_EXIT_PASS");
                    action = server.callFromMainThread(() -> {
                        requireWorld();
                        FixtureEnvironment.setSky(world, 6000L, false);
                        player().connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch);
                        return null;
                    });
                    transition(Phase.RESTORE);
                }
                break;
            case RESTORE:
                if (action.isDone()) {
                    action.get();
                    FixtureEnvironment.setSky(clientWorld, 6000L, false);
                    transition(Phase.DONE);
                    log("PASS");
                }
                break;
            default:
                break;
        }
        return phase == Phase.DONE;
    }

    String status() {
        return "materialPhase=" + phase.name() + " waterVertices=" + events.terrainWaterVertexCount()
                + " lavaVertices=" + events.terrainLavaVertexCount() + " atlasUpdates=" + events.atlasTextureUpdates()
                + " waterUpdates=" + events.atlasSpriteUpdates(WATER) + " lavaUpdates=" + events.atlasSpriteUpdates(LAVA)
                + " waterHash=" + events.atlasSpriteHash(WATER) + " lavaHash=" + events.atlasSpriteHash(LAVA)
                + " waterChanged=" + waterChanged() + " lavaChanged=" + lavaChanged()
                + " flowingWater=" + flowing(layout.waterFlow, Material.WATER)
                + " flowingLava=" + flowing(layout.lavaFlow, Material.LAVA)
                + " lightCount=" + events.blockLightCount() + " lightResources=" + events.blockLightResourceCount()
                + " torchCaptured=" + events.blockLightCaptured(layout.torch)
                + " lampCaptured=" + events.blockLightCaptured(layout.lamp)
                + " cameraWater=" + actualCameraWater() + " nativeWater=" + events.cameraMediumUnderwater()
                + " medium=" + events.cameraMediumHandle() + " mediumResources=" + events.cameraMediumResourceCount()
                + " camera=" + events.cameraMediumX() + "," + events.cameraMediumY() + "," + events.cameraMediumZ();
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        Layout layout = new Layout(x, y, z);
        for (int dx = -11; dx <= 11; dx++) {
            for (int dz = 0; dz <= 17; dz++) {
                world.setBlockState(new BlockPos(x + dx, y, z + dz),
                        ((dx + dz) & 1) == 0 ? Blocks.QUARTZ_BLOCK.getDefaultState() : Blocks.STONE.getDefaultState(), 3);
                for (int dy = 1; dy <= 12; dy++) world.setBlockToAir(new BlockPos(x + dx, y + dy, z + dz));
            }
        }
        basin(world, x - 8, y, z + 9, Blocks.WATER, false);
        basin(world, x + 4, y, z + 9, Blocks.LAVA, false);
        basin(world, x - 8, y, z + 1, Blocks.FLOWING_WATER, true);
        basin(world, x + 4, y, z + 1, Blocks.FLOWING_LAVA, true);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                world.setBlockState(new BlockPos(x + dx, y + dy, z + 7), Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
            }
        }
        world.setBlockState(layout.torch, Blocks.TORCH.getDefaultState(), 3);
        world.setBlockState(layout.lamp.down(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
        world.setBlockState(layout.lamp, Blocks.LIT_REDSTONE_LAMP.getDefaultState(), 3);
        return layout;
    }

    private static void basin(WorldServer world, int x, int y, int z, Block fluid, boolean flowing) {
        int length = flowing ? 7 : 5;
        int depth = !flowing && fluid == Blocks.WATER ? 3 : 1;
        int wallHeight = flowing ? 4 : depth;
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < length; dz++) {
                if (dx == 0 || dx == 4 || dz == 0 || dz == length - 1) {
                    int height = flowing && dz == length - 1 ? 1 : wallHeight;
                    for (int dy = 1; dy <= height; dy++) {
                        world.setBlockState(new BlockPos(x + dx, y + dy, z + dz), Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
                    }
                } else if (!flowing) {
                    for (int dy = 1; dy <= depth; dy++)
                        world.setBlockState(new BlockPos(x + dx, y + dy, z + dz), fluid.getDefaultState(), 3);
                }
            }
        }
        if (flowing) {
            world.setBlockState(new BlockPos(x + 2, y + 1, z + 1), Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
            world.setBlockState(new BlockPos(x + 2, y + 2, z + 1), Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
            world.setBlockState(new BlockPos(x + 2, y + 3, z + 1), fluid.getDefaultState(), 3);
        }
    }

    private boolean sceneReady() {
        return events.terrainWaterVertexCount() > 0 && events.terrainLavaVertexCount() > 0
                && sectionReady(layout.water) && sectionReady(layout.lava)
                && flowing(layout.waterFlow, Material.WATER) && flowing(layout.lavaFlow, Material.LAVA)
                && events.blockLightCaptured(layout.lamp);
    }

    private boolean actualCameraWater() {
        BlockPos point = new BlockPos(events.cameraMediumX(), events.cameraMediumY(), events.cameraMediumZ());
        return clientWorld.getBlockState(point).getMaterial() == Material.WATER;
    }

    private boolean waterMediumReady() {
        return actualCameraWater() && events.cameraMediumUnderwater() && events.cameraMediumHandle() != 0
                && events.cameraMediumResourceCount() == 1 && sceneReady();
    }

    private boolean flowing(BlockPos block, Material material) {
        IBlockState state = clientWorld.getBlockState(block);
        return state.getMaterial() == material && state.getBlock() instanceof BlockLiquid
                && state.getValue(BlockLiquid.LEVEL) > 0;
    }

    private boolean sectionReady(BlockPos block) {
        return events.terrainSectionReady(block.getX() >> 4, block.getY() >> 4, block.getZ() >> 4);
    }
    private long sectionRevision(BlockPos block) {
        return events.terrainSectionRevision(block.getX() >> 4, block.getY() >> 4, block.getZ() >> 4);
    }
    private boolean waterChanged() {
        return events.atlasSpriteUpdates(WATER) > waterUpdates && events.atlasSpriteHash(WATER) != waterHash;
    }
    private boolean lavaChanged() {
        return events.atlasSpriteUpdates(LAVA) > lavaUpdates && events.atlasSpriteHash(LAVA) != lavaHash;
    }
    private boolean animationChanged() { return waterChanged() && lavaChanged(); }
    private void beginAnimation() {
        waterUpdates = events.atlasSpriteUpdates(WATER); lavaUpdates = events.atlasSpriteUpdates(LAVA);
        waterHash = events.atlasSpriteHash(WATER); lavaHash = events.atlasSpriteHash(LAVA);
        animationTicks = 0;
        transition(Phase.ANIMATION);
    }
    private boolean settled(boolean ready) {
        if (!ready) { readyFrame = -1; return false; }
        long frame = events.renderedFrames();
        if (readyFrame < 0) readyFrame = frame;
        return frame - readyFrame >= SETTLE_FRAMES;
    }
    private void holdPose() {
        boolean submerged = phase == Phase.ENTER_WATER || phase == Phase.UNDERWATER || phase == Phase.UNDERWATER_RELOAD;
        double x = submerged ? layout.water.getX() + 0.5 : layout.x + 0.5;
        double y = submerged ? layout.y + 1.1 : layout.y + 8.0;
        double z = submerged ? layout.water.getZ() + 0.5 : layout.z + 22.5;
        float yaw = submerged ? 0f : 180f, pitch = submerged ? 0f : 30f;
        mc.player.setPositionAndRotation(x, y, z, yaw, pitch);
        mc.player.prevPosX = mc.player.lastTickPosX = x;
        mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z;
        mc.player.prevRotationYaw = mc.player.rotationYawHead = mc.player.prevRotationYawHead = yaw;
        mc.player.prevRotationPitch = pitch;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
        mc.player.capabilities.isFlying = true;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Material fixture player is absent");
        return player;
    }
    private void requireWorld() {
        if (!server.isCallingFromMinecraftThread() || !saveName.equals(server.getFolderName())
                || server.getWorld(0) != world) throw new IllegalStateException("Unexpected material fixture server");
    }
    private void capture(String name) throws Exception {
        BufferedImage image = output.image();
        String file = output.capture(name, image);
        output.log("MATERIAL_CAPTURE", status() + " file=" + file);
    }
    private void transition(Phase next) {
        phase = next;
        phaseSince = lastProgress = System.nanoTime();
        readyFrame = -1;
        log("PHASE");
    }
    private void log(String event) { output.log("MATERIAL_" + event, status()); }

    static final class Layout {
        final int x, y, z;
        final BlockPos water, lava, waterFlow, lavaFlow, torch, lamp;
        Layout(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
            water = new BlockPos(x - 6, y + 1, z + 11);
            lava = new BlockPos(x + 6, y + 1, z + 11);
            waterFlow = new BlockPos(x - 6, y + 1, z + 4);
            lavaFlow = new BlockPos(x + 6, y + 1, z + 4);
            torch = new BlockPos(x - 2, y + 1, z + 13);
            lamp = new BlockPos(x + 2, y + 1, z + 13);
        }
    }
}

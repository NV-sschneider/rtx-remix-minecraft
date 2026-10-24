/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.entity.EntityCaptureHooks;
import com.nvidia.rtxremix.forge.tree.FallingTreeObservation;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Real Dynamic Trees generation, deferred player chopping, native animation and owner retirement. */
final class FixtureFallingTrees implements AutoCloseable {
    private static final String TREE = "com.ferreusveritas.dynamictrees.entities.EntityFallingTree";
    private enum Phase { SETUP, ARRIVE, STANDING, CHOP, SPAWN, FALLING, RETIRED, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String save;
    private final Layout layout;
    private final FixtureTerrain.Output output;
    private final UUID playerId;
    private final int originalView;
    private final boolean originalHud, originalFlying;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private Class<?> treeClass;
    private Method clientBuilt, shouldRender, destroyData, branchCount;
    private final Set<UUID> priorTrees = new HashSet<>();
    private Phase phase = Phase.SETUP;
    private Future<?> action;
    private FallingTreeObservation observation;
    private Spawn spawned;
    private Entity ownedTree;
    private BufferedImage firstPoseImage, animatedPoseImage, latePoseImage;
    private ItemStack heldItem;
    private long since = System.nanoTime(), progress = since, frame = -1;
    private int lastClientBranches, lastNativeParts;
    private boolean lastClientAlive, lastRenderable;
    private boolean cleanupQueued, serverFlying, serverConfigured, clientConfigured, serverSetupQueued;

    FixtureFallingTrees(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        this.mc = mc; this.events = events; this.server = server; this.world = world; this.save = save;
        this.layout = layout; this.output = output; clientWorld = mc.world; playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        originalView = mc.gameSettings.thirdPersonView; originalHud = mc.gameSettings.hideGUI;
        originalFlying = mc.player.capabilities.isFlying;
    }

    private void setup() throws ReflectiveOperationException {
        treeClass = Class.forName(TREE);
        clientBuilt = treeClass.getMethod("isClientBuilt"); shouldRender = treeClass.getMethod("shouldRender");
        destroyData = treeClass.getMethod("getDestroyData");
        branchCount = destroyData.getReturnType().getMethod("getNumBranches");
        // The controller owns this fixture before any settings or server work can change.
        clientConfigured = true;
        mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = true;
        serverSetupQueued = true;
        action = server.callFromMainThread(() -> {
            requireWorld(); EntityPlayerMP player = player();
            serverFlying = player.capabilities.isFlying; heldItem = player.getHeldItemMainhand(); serverConfigured = true;
            player.capabilities.isFlying = true; player.sendPlayerAbilities();
            player.connection.setPlayerLocation(cameraX(), cameraY(), cameraZ(), 180, 0);
            return null;
        });
        log("STARTED");
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        FixtureCompatibility.requireSmokeSave(world.getMinecraftServer().getFolderName());
        if (!world.getMinecraftServer().isCallingFromMinecraftThread() || world.provider.getDimension() != 0)
            throw new IllegalStateException("Falling-tree layout requires the owned overworld server thread");
        for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 18; dz++) {
            world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.GRASS.getDefaultState(), 3);
            for (int dy = 1; dy <= 18; dy++) world.setBlockToAir(new BlockPos(x + dx, y + dy, z + dz));
        }
        int result = world.getMinecraftServer().getCommandManager().executeCommand(world.getMinecraftServer(),
                "dt settree " + x + " " + y + " " + z + " dynamictrees:oak JP");
        Layout layout = new Layout(x, y, z);
        if (result != 1 || !isBranch(world.getBlockState(layout.cut)))
            throw new IllegalStateException("Installed Dynamic Trees did not generate the fixture's oak trunk");
        return layout;
    }

    boolean tick() throws Exception {
        if (mc.getIntegratedServer() != server || mc.world != clientWorld || mc.player == null
                || !events.isEnabled() || events.failureMessage() != null)
            throw new IllegalStateException("Falling-tree fixture renderer/world unavailable");
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(90)) {
            capture("falling-tree-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Falling-tree fixture timed out: " + status());
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(5)) { log("PROGRESS"); progress = System.nanoTime(); }
        if (phase != Phase.SETUP && phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        if (spawned != null) observe();
        switch (phase) {
            case SETUP:
                setup(); transition(Phase.ARRIVE);
                break;
            case ARRIVE:
                if (complete()) transition(Phase.STANDING);
                break;
            case STANDING:
                if (settled(isBranch(clientWorld.getBlockState(layout.cut))
                        && events.terrainColumnCaptured(layout.x >> 4, layout.z >> 4)
                        && events.terrainPendingSectionCount() == 0 && events.terrainRetirementCount() == 0, 30)) {
                    capture("falling-tree-standing"); log("STANDING_PASS");
                    action = server.callFromMainThread(() -> {
                        requireWorld();
                        for (Entity entity : world.loadedEntityList) if (treeClass.isInstance(entity)) priorTrees.add(entity.getUniqueID());
                        EntityPlayerMP player = player(); player.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
                        // FutureBreak raytraces this server player within five blocks to choose the fall direction.
                        // Keep the real chop in reach while the client observes from outside the canopy.
                        player.connection.setPlayerLocation(cameraX(), cameraY(), layout.z + 4, 180, 17);
                        IBlockState state = world.getBlockState(layout.cut);
                        if (!isBranch(state)) throw new IllegalStateException("Fixture oak disappeared before chopping");
                        // The mod queues FutureBreak here; world ticks own the actual destruction and spawning.
                        state.getBlock().removedByPlayer(state, world, layout.cut, player, true);
                        return null;
                    });
                    transition(Phase.CHOP);
                }
                break;
            case CHOP:
                if (complete()) { action = findSpawned(); transition(Phase.SPAWN); }
                break;
            case SPAWN:
                if (action.isDone()) {
                    spawned = (Spawn) action.get();
                    if (spawned == null) action = findSpawned();
                    else {
                        if (spawned.branches <= 0) throw new IllegalStateException("Server falling tree has no branch data");
                        observation = new FallingTreeObservation(spawned.id); transition(Phase.FALLING); observe();
                        log("SPAWN_PASS");
                    }
                }
                break;
            case FALLING:
                if (observation.passed()) {
                    writePoseImages(); capture("falling-tree-retired"); log("ANIMATION_PASS"); transition(Phase.RETIRED);
                }
                else if (observation.clientFrames() > 0 && !lastClientAlive && lastNativeParts == 0) {
                    writePoseImages();
                    capture("falling-tree-missing-animation");
                    throw new IllegalStateException("Falling tree retired without native animation: " + status());
                }
                break;
            case RETIRED:
                if (settled(!events.entityCaptured(spawned.id) && clientTree() == null, 20)) {
                    log("RETIREMENT_PASS"); restoreClient(); action = restoreServer(); cleanupQueued = true; transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) { transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private Future<Spawn> findSpawned() {
        return server.callFromMainThread(() -> {
            requireWorld();
            for (Entity entity : world.loadedEntityList) {
                if (entity.isDead || !treeClass.isInstance(entity) || priorTrees.contains(entity.getUniqueID())
                        || entity.getDistanceSq(layout.cut) > 64) continue;
                ownedTree = entity;
                return new Spawn(entity.getUniqueID(), entity.getEntityId(), branches(entity));
            }
            return null;
        });
    }

    private void observe() throws Exception {
        Entity entity = clientTree();
        lastClientAlive = entity != null;
        lastClientBranches = entity == null ? 0 : branches(entity);
        lastRenderable = entity != null && (Boolean) clientBuilt.invoke(entity) && (Boolean) shouldRender.invoke(entity);
        lastNativeParts = events.trianglePartCount(spawned.id);
        observation.sample(spawned.id, events.renderedFrames(), lastClientAlive, lastRenderable,
                lastClientBranches, lastNativeParts, events.entityTransformUpdates(spawned.id));
        if (firstPoseImage == null && lastNativeParts > 0 && lastRenderable) {
            firstPoseImage = output.image(); log("CAPTURE_PASS");
        }
        if (animatedPoseImage == null && lastClientAlive && lastNativeParts > 0 && lastRenderable && observation.animated()) {
            animatedPoseImage = output.image(); log("POSE_PASS");
        }
        if (latePoseImage == null && lastClientAlive && lastNativeParts > 0 && lastRenderable
                && observation.renderableFrames() >= 10) {
            latePoseImage = output.image();
        }
    }

    private void writePoseImages() throws Exception {
        // PNG encoding can outlast the entire falling animation; only write after retirement.
        if (firstPoseImage != null) output.capture("falling-tree-captured", firstPoseImage);
        if (animatedPoseImage != null) output.capture("falling-tree-animated", animatedPoseImage);
        if (latePoseImage != null) output.capture("falling-tree-late-pose", latePoseImage);
        firstPoseImage = animatedPoseImage = latePoseImage = null;
    }

    private int branches(Entity entity) throws ReflectiveOperationException {
        Object data = destroyData.invoke(entity); return data == null ? 0 : (Integer) branchCount.invoke(data);
    }
    private Entity clientTree() {
        if (spawned == null) return null;
        Entity entity = clientWorld.getEntityByID(spawned.entityId);
        if (entity == null || entity.isDead) return null;
        if (!treeClass.isInstance(entity) || !spawned.id.equals(entity.getUniqueID()))
            throw new IllegalStateException("Falling-tree client identity differs from its server owner");
        return entity;
    }
    private static boolean isBranch(IBlockState state) {
        for (Class<?> type = state.getBlock().getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("com.ferreusveritas.dynamictrees.blocks.BlockBranch")) return true;
        return false;
    }
    private double cameraX() { return layout.x + 0.5; }
    private double cameraY() { return layout.y + 1.1; }
    private double cameraZ() { return layout.z + 9; }
    private void holdPose() {
        mc.player.setPositionAndRotation(cameraX(), cameraY(), cameraZ(), 180, 0);
        mc.player.prevPosX = mc.player.lastTickPosX = cameraX(); mc.player.prevPosY = mc.player.lastTickPosY = cameraY();
        mc.player.prevPosZ = mc.player.lastTickPosZ = cameraZ(); mc.player.prevRotationYaw = 180; mc.player.prevRotationPitch = 0;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Falling-tree fixture player unavailable"); return player;
    }
    private void requireWorld() {
        FixtureCompatibility.requireSmokeSave(save);
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Falling-tree fixture no longer owns server world");
    }
    private Future<?> restoreServer() {
        return server.callFromMainThread(() -> {
            requireWorld();
            if (ownedTree != null && ownedTree.world == world) ownedTree.setDead();
            if (serverConfigured) {
                EntityPlayerMP player = player(); player.setHeldItem(EnumHand.MAIN_HAND, heldItem);
                player.capabilities.isFlying = serverFlying; player.sendPlayerAbilities();
                player.connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); serverConfigured = false;
            }
            return null;
        });
    }
    private void restoreClient() {
        if (!clientConfigured) return;
        mc.gameSettings.thirdPersonView = originalView; mc.gameSettings.hideGUI = originalHud;
        if (mc.world == clientWorld && mc.getIntegratedServer() == server
                && mc.player != null && playerId.equals(mc.player.getUniqueID())) mc.player.capabilities.isFlying = originalFlying;
        clientConfigured = false;
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready, int frames) {
        if (!ready) { frame = -1; return false; }
        if (frame < 0) frame = events.renderedFrames(); return events.renderedFrames() - frame >= frames;
    }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); frame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.capture(name, output.image()); }
    private void log(String name) { output.log("FALLING_TREE_" + name, status()); }
    String status() {
        return "phase=" + phase + " spawned=" + (spawned != null) + " serverBranches=" + (spawned == null ? 0 : spawned.branches)
                + " clientAlive=" + lastClientAlive + " clientBranches=" + lastClientBranches + " renderable=" + lastRenderable
                + " nativeParts=" + lastNativeParts + " normallyRendered=" + (spawned != null && EntityCaptureHooks.normallyRendered(spawned.id))
                + " capturedFrames=" + (observation == null ? 0 : observation.capturedFrames())
                + " renderableFrames=" + (observation == null ? 0 : observation.renderableFrames())
                + " transformUpdates=" + (observation == null ? 0 : observation.transformUpdates())
                + " rejected=" + EntityCaptureHooks.rejectedTriangleDraws() + " unsupported=" + EntityCaptureHooks.unsupportedTriangleDraws()
                + " dirtyTextures=" + events.dirtyEntityTextures() + " pendingTerrain=" + events.terrainPendingSectionCount();
    }
    @Override public void close() {
        firstPoseImage = animatedPoseImage = latePoseImage = null;
        restoreClient();
        if (serverSetupQueued && !cleanupQueued) { restoreServer(); cleanupQueued = true; }
    }

    static final class Layout {
        final int x, y, z;
        final BlockPos cut;
        Layout(int x, int y, int z) { this.x = x; this.y = y; this.z = z; cut = new BlockPos(x, y + 1, z); }
    }
    private static final class Spawn {
        final UUID id;
        final int entityId, branches;
        Spawn(UUID id, int entityId, int branches) { this.id = id; this.entityId = entityId; this.branches = branches; }
    }
}

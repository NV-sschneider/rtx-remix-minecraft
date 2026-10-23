/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.lwjgl.opengl.Display;

/** Owned-world integration checks for items, model tiles, menus and window resizing. */
final class FixtureObjects implements AutoCloseable {
    private enum Phase { ARRIVE, READY, OPEN, OPENED, CLOSE, CLOSED, MOVE, MOVED,
        INVENTORY, PAUSE, RESIZE, RESTORE_SIZE, RELOAD, REMOVE, REMOVED, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final String save;
    private final Layout layout;
    private final UUID playerId;
    private final FixtureTerrain.Output output;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private long since = System.nanoTime(), lastProgress = since;
    private long frame = -1, lidBaseline, itemBaseline;
    private long reloadBaseline;
    private GuiScreen ownedScreen;
    private int originalWidth, originalHeight;
    private boolean changedMode;
    private volatile ContainerChest ownedContainer;
    private boolean containerCleanupQueued;

    FixtureObjects(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        this.mc = mc; this.events = events; this.server = server; this.world = world;
        this.save = save; this.layout = layout; this.output = output; this.playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        action = server.callFromMainThread(() -> {
            requireWorld();
            player().connection.setPlayerLocation(layout.x + 0.5, layout.y + 6.0, layout.z + 18.5, 180f, 24f);
            return null;
        });
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (mc.getIntegratedServer() != server || mc.world == null || !events.isEnabled()
                || events.failureMessage() != null) throw new IllegalStateException("Object fixture renderer/world unavailable");
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(180)) {
            capture("objects-timeout-" + phase.name().toLowerCase(java.util.Locale.ROOT));
            throw new IllegalStateException("Object fixture timed out: " + phase);
        }
        if (System.nanoTime() - lastProgress > TimeUnit.SECONDS.toNanos(10)) {
            log("PROGRESS"); lastProgress = System.nanoTime();
        }
        if (phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        switch (phase) {
            case ARRIVE:
                if (complete()) transition(Phase.READY);
                break;
            case READY:
                if (settled(objectsReady(), 30)) {
                    capture("objects-closed");
                    lidBaseline = events.tileTransformUpdates(layout.chest);
                    action = server.callFromMainThread(() -> {
                        requireWorld();
                        EntityPlayerMP player = player();
                        if (player.openContainer != player.inventoryContainer)
                            throw new IllegalStateException("Fixture player already has an open container");
                        player.connection.setPlayerLocation(layout.x - 2.5, layout.y + 2, layout.z + 11.5, 180f, 30f);
                        ownedContainer = new ContainerChest(player.inventory, chest(), player);
                        player.openContainer = ownedContainer;
                        return null;
                    });
                    transition(Phase.OPEN);
                }
                break;
            case OPEN:
                if (complete()) transition(Phase.OPENED);
                break;
            case OPENED:
                if (settled(events.tileCaptured(layout.chest) && clientLid() > 0.7f
                        && events.tileTransformUpdates(layout.chest) > lidBaseline, 20)) {
                    capture("objects-chest-open"); log("LID_PASS");
                    action = server.callFromMainThread(() -> {
                        requireWorld(); closeContainer();
                        player().connection.setPlayerLocation(layout.x + 0.5, layout.y + 6, layout.z + 18.5, 180f, 24f);
                        return null;
                    });
                    transition(Phase.CLOSE);
                }
                break;
            case CLOSE:
                if (complete()) transition(Phase.CLOSED);
                break;
            case CLOSED:
                if (settled(clientLid() < 0.05f && objectsReady(), 20)) {
                    itemBaseline = events.itemTransformUpdates(layout.diamond);
                    action = server.callFromMainThread(() -> {
                        requireWorld();
                        Entity item = world.getEntityFromUuid(layout.diamond);
                        if (item == null) throw new IllegalStateException("Fixture item missing");
                        item.setPosition(layout.x + 1.5, layout.y + 1.8, layout.z + 11.5);
                        item.motionX = item.motionY = item.motionZ = 0;
                        return null;
                    });
                    transition(Phase.MOVE);
                }
                break;
            case MOVE:
                if (complete()) transition(Phase.MOVED);
                break;
            case MOVED:
                if (settled(objectsReady() && events.itemTransformUpdates(layout.diamond) > itemBaseline
                        && clientItemMoved(), 30)) {
                    capture("objects-item-moved"); log("ITEM_MOTION_PASS");
                    ownedScreen = new GuiInventory(mc.player);
                    mc.displayGuiScreen(ownedScreen);
                    // Creative inventory may replace GuiInventory from initGui.
                    ownedScreen = mc.currentScreen;
                    transition(Phase.INVENTORY);
                }
                break;
            case INVENTORY:
                if (settled(mc.currentScreen == ownedScreen && objectsReady(), 40)) {
                    capture("objects-inventory"); log("INVENTORY_PASS");
                    ownedScreen = new GuiIngameMenu(); mc.displayGuiScreen(ownedScreen);
                    transition(Phase.PAUSE);
                }
                break;
            case PAUSE:
                if (settled(mc.currentScreen == ownedScreen && objectsReady(), 40)) {
                    capture("objects-pause"); log("PAUSE_PASS");
                    mc.displayGuiScreen(null); ownedScreen = null;
                    if (Display.isFullscreen()) throw new IllegalStateException("Window fixture requires windowed mode");
                    originalWidth = Display.getWidth(); originalHeight = Display.getHeight();
                    changedMode = true;
                    resizeWindow(originalWidth == 960 ? 1024 : 960, 540);
                    transition(Phase.RESIZE);
                }
                break;
            case RESIZE:
                if (settled(mc.displayHeight == 540 && objectsReady(), 40)) {
                    capture("objects-resized"); log("RESIZE_PASS");
                    resizeWindow(originalWidth, originalHeight);
                    transition(Phase.RESTORE_SIZE);
                }
                break;
            case RESTORE_SIZE:
                if (settled(mc.displayWidth == originalWidth && mc.displayHeight == originalHeight
                        && objectsReady(), 40)) {
                    changedMode = false;
                    log("SIZE_RESTORE_PASS");
                    reloadBaseline = events.textureReloadCount();
                    events.reload();
                    transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(events.textureReloadCount() > reloadBaseline && objectsReady(), 40)) {
                    capture("objects-reloaded"); log("RELOAD_PASS");
                    action = server.callFromMainThread(() -> {
                        requireWorld();
                        world.setBlockToAir(layout.chest);
                        world.setBlockToAir(layout.bedFoot); world.setBlockToAir(layout.bedHead);
                        for (UUID id : new UUID[] {layout.diamond, layout.glass, layout.zombie, layout.maka}) {
                            if (id == null) continue;
                            Entity entity = world.getEntityFromUuid(id); if (entity != null) entity.setDead();
                        }
                        return null;
                    });
                    transition(Phase.REMOVE);
                }
                break;
            case REMOVE:
                if (complete()) transition(Phase.REMOVED);
                break;
            case REMOVED:
                if (settled(!events.tileCaptured(layout.chest) && !events.tileCaptured(layout.bedFoot)
                        && !events.tileCaptured(layout.bedHead) && !events.itemCaptured(layout.diamond)
                        && !events.itemCaptured(layout.glass) && !events.entityCaptured(layout.zombie)
                        && (layout.maka == null || !events.entityCaptured(layout.maka)), 30)) {
                    capture("objects-removed"); log("REMOVAL_PASS");
                    action = server.callFromMainThread(() -> {
                        requireWorld(); player().connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch);
                        return null;
                    });
                    transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) { transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        Layout result = new Layout(x, y, z);
        for (int dx = -9; dx <= 9; dx++) for (int dz = 0; dz <= 16; dz++) {
            world.setBlockState(new BlockPos(x + dx, y, z + dz),
                    ((dx + dz) & 1) == 0 ? Blocks.QUARTZ_BLOCK.getDefaultState() : Blocks.STONE.getDefaultState(), 3);
            for (int dy = 1; dy <= 12; dy++) world.setBlockToAir(new BlockPos(x + dx, y + dy, z + dz));
        }
        world.setBlockState(result.chest, Blocks.CHEST.getDefaultState().withProperty(BlockChest.FACING, EnumFacing.SOUTH), 3);
        world.setBlockState(result.bedFoot, Blocks.BED.getDefaultState().withProperty(BlockHorizontal.FACING, EnumFacing.NORTH)
                .withProperty(BlockBed.PART, BlockBed.EnumPartType.FOOT), 2);
        world.setBlockState(result.bedHead, Blocks.BED.getDefaultState().withProperty(BlockHorizontal.FACING, EnumFacing.NORTH)
                .withProperty(BlockBed.PART, BlockBed.EnumPartType.HEAD), 2);
        EntityItem diamond = item(world, x - 1.5, y + 1.8, z + 11.5, new ItemStack(Items.DIAMOND));
        EntityItem glass = item(world, x - 4.5, y + 1.8, z + 11.5, new ItemStack(Blocks.STAINED_GLASS, 3, 14));
        EntityZombie zombie = new EntityZombie(world);
        zombie.setPosition(x + 6.5, y + 1, z + 10.5); zombie.rotationYaw = 180; zombie.renderYawOffset = 180;
        zombie.setNoAI(true); zombie.setEntityInvulnerable(true);
        zombie.setItemStackToSlot(EntityEquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        if (!world.spawnEntity(zombie)) throw new IllegalStateException("Could not spawn fixture zombie");
        result.diamond = diamond.getUniqueID(); result.glass = glass.getUniqueID(); result.zombie = zombie.getUniqueID();
        result.diamondEntityId = diamond.getEntityId();
        // An ordinary Maka may replace itself with an Alpha on its first tick,
        // invalidating the fixture UUID. Spawn the stable type directly.
        ResourceLocation makaType = new ResourceLocation("lycanitesmobs", "makaalpha");
        if (EntityList.getClass(makaType) != null) {
            Entity candidate = EntityList.createEntityByIDFromName(makaType, world);
            if (!(candidate instanceof EntityLiving)) throw new IllegalStateException("Unexpected fixture creature type");
            EntityLiving maka = (EntityLiving) candidate;
            maka.setPosition(x - 6.5, y + 1, z + 5.5); maka.rotationYaw = 180; maka.renderYawOffset = 180;
            maka.setNoAI(true); maka.setEntityInvulnerable(true); maka.enablePersistence();
            if (!world.spawnEntity(maka)) throw new IllegalStateException("Could not spawn fixture creature");
            result.maka = maka.getUniqueID();
            result.makaEntityId = maka.getEntityId();
        }
        return result;
    }
    private static EntityItem item(WorldServer world, double x, double y, double z, ItemStack stack) {
        EntityItem item = new EntityItem(world, x, y, z, stack);
        item.setNoGravity(true); item.motionX = item.motionY = item.motionZ = 0;
        item.setInfinitePickupDelay(); item.setNoDespawn();
        if (!world.spawnEntity(item)) throw new IllegalStateException("Could not spawn fixture item");
        return item;
    }
    private boolean objectsReady() {
        return events.tileCaptured(layout.chest) && events.tileCaptured(layout.bedFoot)
                && events.tileCaptured(layout.bedHead) && events.itemCaptured(layout.diamond)
                && events.itemCaptured(layout.glass) && events.entityCaptured(layout.zombie)
                && (layout.maka == null || events.trianglePartCount(layout.maka) > 0);
    }
    private boolean clientItemMoved() {
        Entity entity = mc.world.getEntityByID(layout.diamondEntityId);
        return entity != null && Math.abs(entity.posX - layout.x - 1.5) < 0.2;
    }
    private float clientLid() {
        return mc.world.getTileEntity(layout.chest) instanceof TileEntityChest
                ? ((TileEntityChest) mc.world.getTileEntity(layout.chest)).lidAngle : -1;
    }
    private TileEntityChest chest() { return (TileEntityChest) world.getTileEntity(layout.chest); }
    private EntityPlayerMP player() { return server.getPlayerList().getPlayerByUUID(playerId); }
    private void closeContainer() {
        EntityPlayerMP player = player();
        if (ownedContainer != null && player != null && player.openContainer == ownedContainer) player.closeContainer();
        ownedContainer = null;
    }
    private void requireWorld() {
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Object fixture no longer owns the server world");
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready, int frames) {
        if (!ready) { frame = -1; return false; }
        if (frame < 0) frame = events.renderedFrames();
        return events.renderedFrames() - frame >= frames;
    }
    private void holdPose() {
        double x = layout.x + 0.5, y = layout.y + 6.0, z = layout.z + 18.5;
        float pitch = 24;
        if (phase == Phase.OPEN || phase == Phase.OPENED) {
            x = layout.x - 2.5; y = layout.y + 2; z = layout.z + 11.5; pitch = 30;
        }
        mc.player.setPositionAndRotation(x, y, z, 180, pitch);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z;
        mc.player.prevRotationYaw = 180; mc.player.prevRotationPitch = pitch;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private void capture(String name) throws Exception { output.capture(name, output.image()); }
    private void transition(Phase next) { phase = next; since = lastProgress = System.nanoTime(); frame = -1; log("PHASE"); }
    private void log(String event) {
        output.log("OBJECT_" + event, "phase=" + phase + " chest=" + events.tileCaptured(layout.chest)
                + " bedFoot=" + events.tileCaptured(layout.bedFoot) + " bedHead=" + events.tileCaptured(layout.bedHead)
                + " diamond=" + events.itemCaptured(layout.diamond) + " glass=" + events.itemCaptured(layout.glass)
                + " zombie=" + events.entityCaptured(layout.zombie) + " tileParts=" + events.capturedTileParts()
                + " maka=" + (layout.maka == null ? "unavailable" : events.trianglePartCount(layout.maka))
                + " makaClient=" + clientCreatureState()
                + " vboDraws=" + events.vboDrawCount() + " vboSnapshots=" + events.vboUploadSnapshots()
                + " vboReadbacks=" + events.vboReadbackCount() + " vboDeferred=" + events.deferredVboDrawCount()
                + " vboBytes=" + events.vboCacheBytes()
                + " itemCount=" + events.capturedItemCount() + " lid=" + clientLid()
                + " tileUpdates=" + events.tileTransformUpdates(layout.chest)
                + " width=" + mc.displayWidth + " height=" + mc.displayHeight);
    }
    private String clientCreatureState() {
        if (layout.maka == null) return "unavailable";
        Entity entity = mc.world == null ? null : mc.world.getEntityByID(layout.makaEntityId);
        return entity == null ? "absent" : entity.getClass().getSimpleName() + ",uuidMatch="
                + layout.maka.equals(entity.getUniqueID()) + ",alive=" + entity.isEntityAlive()
                + ",position=" + entity.posX + "," + entity.posY + "," + entity.posZ;
    }
    @Override public void close() {
        if (ownedScreen != null && mc.currentScreen == ownedScreen) mc.displayGuiScreen(null);
        ownedScreen = null;
        if (!containerCleanupQueued && ownedContainer != null) {
            containerCleanupQueued = true;
            server.callFromMainThread(() -> { requireWorld(); closeContainer(); return null; });
        }
        if (changedMode) {
            resizeWindow(originalWidth, originalHeight); changedMode = false;
        }
    }
    private static void resizeWindow(int width, int height) {
        // Display.setDisplayMode recreates LWJGL 2's HWND. Use its existing-window
        // reshape path to exercise the same WM_SIZE handling as a user dragging it.
        if (Display.isFullscreen()) throw new IllegalStateException("Window fixture requires windowed mode");
        try {
            java.lang.reflect.Field field = Display.class.getDeclaredField("display_impl");
            field.setAccessible(true);
            Object implementation = field.get(null);
            java.lang.reflect.Method reshape = implementation.getClass().getDeclaredMethod("reshape",
                    int.class, int.class, int.class, int.class);
            reshape.setAccessible(true);
            reshape.invoke(implementation, Display.getX(), Display.getY(), width, height);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not resize the fixture's existing window", error);
        }
    }
    static final class Layout {
        final int x, y, z;
        final BlockPos chest, bedFoot, bedHead;
        UUID diamond, glass, zombie, maka;
        int makaEntityId;
        int diamondEntityId;
        Layout(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
            chest = new BlockPos(x - 3, y + 1, z + 7);
            bedFoot = new BlockPos(x + 3, y + 1, z + 8); bedHead = bedFoot.north();
        }
    }
}

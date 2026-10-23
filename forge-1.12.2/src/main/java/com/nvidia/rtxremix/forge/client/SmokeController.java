/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.RemixForgeMod;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.BlockColored;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/**
 * Opt-in, in-process smoke fixture for a normally authenticated Minecraft launch.
 * It neither starts a launcher nor changes account/session authentication.
 * Enable with -Drlcraftremix.smoke=true; shutdown additionally requires
 * -Drlcraftremix.smoke.exit=true. The unique fixture save and screenshots remain
 * on disk for inspection. Completion checks frame capture and renderer lifecycle,
 * not visual correctness.
 */
final class SmokeController {
    private static final long SEED = 8675309L;
    private static final int REQUIRED_NATIVE_FRAMES = 180;
    private static final int REENABLE_NATIVE_FRAMES = 60;
    private static final int SETTLE_TICKS = 40;
    private static final int VANILLA_TICKS = 60;
    private static final int UNLOAD_TICKS = 20;
    private static final long STARTUP_TIMEOUT = TimeUnit.SECONDS.toNanos(180);
    private static final long NATIVE_TIMEOUT = TimeUnit.SECONDS.toNanos(300);
    private static final long SETTLE_TIMEOUT = TimeUnit.SECONDS.toNanos(90);

    private enum State {
        INACTIVE, WAIT_MENU, WAIT_WORLD, BUILD_SCENE, SETTLE, REMIX, VANILLA,
        REENABLE, UNLOAD, EXIT, DONE
    }

    private final Minecraft mc;
    private final ClientEvents events;
    private final boolean exitAfter;
    private final String id;
    private State state;
    private long stateSince;
    private long frameBaseline;
    private long lastFrames;
    private long capturedFrames;
    private long reenabledFrames;
    private int ticks;
    private String saveName;
    private String remixScreenshot;
    private String vanillaScreenshot;
    private Future<Pose> setup;
    private Pose pose;
    private boolean settingsSaved;
    private boolean previousPauseOnLostFocus;
    private int previousThirdPerson;
    private GuiScreen initializedMainMenu;

    SmokeController(Minecraft mc, ClientEvents events) {
        this.mc = mc;
        this.events = events;
        boolean requested = Boolean.getBoolean("rlcraftremix.smoke");
        this.exitAfter = requested && Boolean.getBoolean("rlcraftremix.smoke.exit");
        this.id = requested
                ? DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
                        + "-" + UUID.randomUUID().toString().substring(0, 8)
                : "disabled";
        this.state = requested ? State.WAIT_MENU : State.INACTIVE;
        this.stateSince = System.nanoTime();
        if (requested) log("START", "exit=" + exitAfter + " seed=" + SEED + " validation=capture_and_lifecycle");
    }

    /** Observe the main-menu compatibility marker without requiring its actual screen type. */
    void screenInitialized(GuiScreen eventScreen) {
        if (state != State.WAIT_MENU && state != State.UNLOAD) return;
        // The initialization event may name a compatibility marker while currentScreen
        // holds a replacement. Remember only that initialized screen, never any menu.
        initializedMainMenu = eventScreen instanceof GuiMainMenu && mc.world == null
                ? mc.currentScreen : null;
    }

    private boolean isMainMenuReady() {
        return initializedMainMenu != null && initializedMainMenu == mc.currentScreen
                && mc.world == null && mc.getIntegratedServer() == null && mc.getConnection() == null;
    }

    /** Invoke once at ClientTickEvent END, after ClientEvents processes renderer reset/close. */
    void tick() {
        if (state == State.INACTIVE || state == State.DONE) return;
        try {
            if (state == State.EXIT) {
                if (++ticks >= 20) {
                    state = State.DONE;
                    log("EXIT", "requested=true");
                    mc.shutdown();
                }
                return;
            }
            if (System.nanoTime() - stateSince > timeoutForState()) {
                fail("timeout_" + state.name().toLowerCase(java.util.Locale.ROOT));
                return;
            }
            switch (state) {
                case WAIT_MENU:
                    if (mc.world != null) {
                        fail("existing_world_open");
                    } else if (isMainMenuReady()) {
                        launchFixture();
                    }
                    break;
                case WAIT_WORLD:
                    if (fixtureReady() && ++ticks >= 20) scheduleScene();
                    break;
                case BUILD_SCENE:
                    if (!fixtureReady()) break;
                    if (setup.isDone()) {
                        pose = setup.get();
                        mc.displayGuiScreen(null);
                        holdPose();
                        transition(State.SETTLE);
                        log("SCENE_READY", "world=" + saveName);
                    }
                    break;
                case SETTLE:
                    if (!fixtureReady()) break;
                    holdPose();
                    if (canRender() && ++ticks >= SETTLE_TICKS) {
                        frameBaseline = events.renderedFrames();
                        lastFrames = frameBaseline;
                        events.setEnabled(true);
                        transition(State.REMIX);
                        log("REMIX_ENABLED", "targetFrames=" + REQUIRED_NATIVE_FRAMES);
                    }
                    break;
                case REMIX:
                    if (!fixtureReady()) break;
                    holdPose();
                    if (events.failureMessage() != null) {
                        fail("renderer_failure");
                        break;
                    }
                    if (!events.isEnabled()) {
                        fail("renderer_disabled");
                        break;
                    }
                    // Count only frames the renderer has actually submitted in this world.
                    // Menu ticks and unloaded-world ticks never contribute to this count.
                    if (!canRender()) break;
                    long frames = events.renderedFrames();
                    if (frames < lastFrames) {
                        fail("renderer_frame_counter_reset");
                        break;
                    }
                    lastFrames = frames;
                    if (frames - frameBaseline >= REQUIRED_NATIVE_FRAMES) {
                        capturedFrames = frames - frameBaseline;
                        remixScreenshot = capture("remix");
                        log("REMIX_CAPTURED", "frames=" + capturedFrames + " file=" + remixScreenshot);
                        events.setEnabled(false);
                        transition(State.VANILLA);
                    }
                    break;
                case VANILLA:
                    if (!fixtureReady()) break;
                    holdPose();
                    if (events.isEnabled()) {
                        fail("vanilla_phase_renderer_enabled");
                        break;
                    }
                    // Leave several complete vanilla frames after terrain release at this pose.
                    if (canRender() && ++ticks >= VANILLA_TICKS) {
                        vanillaScreenshot = capture("vanilla");
                        log("VANILLA_CAPTURED", "file=" + vanillaScreenshot);
                        frameBaseline = events.renderedFrames();
                        lastFrames = frameBaseline;
                        events.setEnabled(true);
                        transition(State.REENABLE);
                        log("REENABLE_STARTED", "baseline=" + frameBaseline
                                + " targetFrames=" + REENABLE_NATIVE_FRAMES);
                    }
                    break;
                case REENABLE:
                    if (events.failureMessage() != null) {
                        fail("reenable_renderer_failure");
                        break;
                    }
                    if (!events.isEnabled()) {
                        fail("reenable_renderer_disabled");
                        break;
                    }
                    if (!fixtureReady()) break;
                    holdPose();
                    if (!canRender()) break;
                    long reenableFrames = events.renderedFrames();
                    if (reenableFrames < lastFrames) {
                        fail("reenable_frame_counter_reset");
                        break;
                    }
                    lastFrames = reenableFrames;
                    if (reenableFrames - frameBaseline >= REENABLE_NATIVE_FRAMES) {
                        reenabledFrames = reenableFrames - frameBaseline;
                        log("REENABLE_PASS", "frames=" + reenabledFrames
                                + " baseline=" + frameBaseline + " totalFrames=" + reenableFrames);
                        // fixtureReady above verifies ownership before unloading. Keep Remix
                        // enabled so WorldEvent.Unload and the next client tick release terrain.
                        transition(State.UNLOAD);
                        log("WORLD_UNLOAD", "world=" + saveName + " rendererEnabled=true");
                        mc.loadWorld(null);
                        mc.displayGuiScreen(new GuiMainMenu());
                    }
                    break;
                case UNLOAD:
                    if (events.failureMessage() != null) {
                        fail("unload_renderer_failure");
                        break;
                    }
                    if (!events.isEnabled()) {
                        fail("unload_renderer_disabled");
                        break;
                    }
                    if (mc.world != null) {
                        fail("world_present_after_unload");
                        break;
                    }
                    if (events.renderedFrames() != lastFrames) {
                        fail("renderer_frame_counter_changed_in_menu");
                        break;
                    }
                    if (isMainMenuReady() && ++ticks >= UNLOAD_TICKS) {
                        log("WORLD_UNLOAD_PASS", "menuTicks=" + ticks + " rendererEnabled=true");
                        finish(true, "frames=" + capturedFrames + " world=" + saveName
                                + " remix=" + remixScreenshot + " vanilla=" + vanillaScreenshot
                                + " reenableFrames=" + reenabledFrames + " unloadTicks=" + ticks
                                + " validation=capture_and_lifecycle");
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception | LinkageError exception) {
            // Deliberately do not log session objects, account fields or arbitrary exception messages.
            fail("exception_" + exception.getClass().getSimpleName());
        }
    }

    private void launchFixture() throws Exception {
        if (events.isEnabled()) events.setEnabled(false);
        previousPauseOnLostFocus = mc.gameSettings.pauseOnLostFocus;
        previousThirdPerson = mc.gameSettings.thirdPersonView;
        settingsSaved = true;
        mc.gameSettings.pauseOnLostFocus = false;
        mc.gameSettings.thirdPersonView = 0;

        saveName = "rlcraft-remix-smoke-" + id;
        Path saves = mc.gameDir.toPath().resolve("saves");
        Files.createDirectories(saves);
        // Atomic reservation: never reuse, overwrite, or delete an existing world directory.
        Files.createDirectory(saves.resolve(saveName));
        WorldSettings settings = new WorldSettings(SEED, GameType.CREATIVE, false, false, WorldType.FLAT)
                .setGeneratorOptions("3;minecraft:bedrock,2*minecraft:dirt,minecraft:grass;1;")
                .enableCommands();
        transition(State.WAIT_WORLD);
        log("WORLD_CREATE", "world=" + saveName);
        mc.launchIntegratedServer(saveName, "RTX Remix smoke " + id, settings);
    }

    private boolean fixtureReady() {
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null || mc.world == null || mc.player == null) return false;
        if (!saveName.equals(server.getFolderName())) {
            fail("unexpected_world");
            return false;
        }
        if (mc.world.provider.getDimension() != 0) {
            fail("unexpected_dimension");
            return false;
        }
        return true;
    }

    private void scheduleScene() {
        final IntegratedServer server = mc.getIntegratedServer();
        final UUID playerId = mc.player.getUniqueID();
        // All world and authoritative player mutations occur on the integrated server thread.
        setup = server.callFromMainThread(() -> buildScene(server, playerId));
        transition(State.BUILD_SCENE);
        log("SCENE_SCHEDULED", "world=" + saveName);
    }

    private Pose buildScene(IntegratedServer server, UUID playerId) {
        if (!saveName.equals(server.getFolderName())) throw new IllegalStateException("Unexpected fixture world");
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Fixture player is not ready");
        WorldServer world = player.getServerWorld();
        if (world.provider.getDimension() != 0) throw new IllegalStateException("Fixture is not in the overworld");
        BlockPos spawn = world.getSpawnPoint();
        int cx = spawn.getX();
        int cz = spawn.getZ();
        int floorY = world.getTopSolidOrLiquidBlock(spawn).getY() - 1;
        world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
        world.getGameRules().setOrCreateGameRule("doWeatherCycle", "false");
        world.getGameRules().setOrCreateGameRule("randomTickSpeed", "0");
        world.setWorldTime(6000L);
        world.getWorldInfo().setRaining(false);
        world.getWorldInfo().setThundering(false);

        for (int x = -10; x <= 10; x++) {
            for (int z = -5; z <= 13; z++) {
                world.setBlockState(new BlockPos(cx + x, floorY, cz + z),
                        ((x + z) & 1) == 0 ? Blocks.QUARTZ_BLOCK.getDefaultState() : Blocks.STONE.getDefaultState(), 3);
            }
        }
        // Distinct colors, opaque geometry, and a transparent front panel in one small view.
        for (int i = 0; i < 16; i++) {
            for (int y = 1; y <= 2 + i % 3; y++) {
                world.setBlockState(new BlockPos(cx - 8 + i, floorY + y, cz + 2),
                        Blocks.WOOL.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.byMetadata(i)), 3);
            }
        }
        for (int x = -3; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                world.setBlockState(new BlockPos(cx + x, floorY + y, cz + 5), Blocks.GLASS.getDefaultState(), 3);
            }
        }
        for (int y = 1; y <= 3; y++) {
            world.setBlockState(new BlockPos(cx - 7, floorY + y, cz + 8), Blocks.STONE.getDefaultState(), 3);
            world.setBlockState(new BlockPos(cx + 7, floorY + y, cz + 8), Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
        }
        Pose result = new Pose(cx + 0.5, floorY + 4.0, cz + 17.5, 180f, 16f);
        world.setSpawnPoint(new BlockPos(cx, floorY + 1, cz + 12));
        player.setGameType(GameType.CREATIVE);
        player.capabilities.allowFlying = true;
        player.capabilities.isFlying = true;
        player.capabilities.disableDamage = true;
        player.sendPlayerAbilities();
        player.connection.setPlayerLocation(result.x, result.y, result.z, result.yaw, result.pitch);
        return result;
    }

    private void holdPose() {
        if (pose == null || mc.player == null) return;
        mc.player.setPositionAndRotation(pose.x, pose.y, pose.z, pose.yaw, pose.pitch);
        mc.player.prevPosX = mc.player.lastTickPosX = pose.x;
        mc.player.prevPosY = mc.player.lastTickPosY = pose.y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = pose.z;
        mc.player.prevRotationYaw = mc.player.rotationYawHead = mc.player.prevRotationYawHead = pose.yaw;
        mc.player.prevRotationPitch = pose.pitch;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
        mc.player.capabilities.isFlying = true;
    }

    private boolean canRender() {
        return mc.world != null && mc.player != null && mc.currentScreen == null
                && !mc.isGamePaused() && mc.displayWidth > 0 && mc.displayHeight > 0;
    }

    private String capture(String renderer) throws Exception {
        String name = "rlcraft-remix-smoke-" + id + "-" + renderer + ".png";
        File file = new File(new File(mc.gameDir, "screenshots"), name);
        if (file.exists()) throw new IllegalStateException("Screenshot filename already exists");
        ScreenShotHelper.saveScreenshot(mc.gameDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        if (!file.isFile() || file.length() == 0) throw new IllegalStateException("Screenshot was not written");
        return name;
    }

    private long timeoutForState() {
        if (state == State.REMIX || state == State.REENABLE) return NATIVE_TIMEOUT;
        if (state == State.SETTLE || state == State.VANILLA || state == State.UNLOAD) return SETTLE_TIMEOUT;
        return STARTUP_TIMEOUT;
    }

    private void transition(State next) {
        state = next;
        stateSince = System.nanoTime();
        ticks = 0;
        initializedMainMenu = null;
    }

    private void fail(String reason) {
        if (state == State.DONE || state == State.EXIT || state == State.INACTIVE) return;
        finish(false, "reason=" + reason + " phase=" + state.name()
                + " frames=" + Math.max(0L, lastFrames - frameBaseline));
    }

    private void finish(boolean success, String fields) {
        if (setup != null && !setup.isDone()) setup.cancel(false);
        events.setEnabled(false);
        if (settingsSaved) {
            mc.gameSettings.pauseOnLostFocus = previousPauseOnLostFocus;
            mc.gameSettings.thirdPersonView = previousThirdPerson;
            settingsSaved = false;
        }
        log(success ? "PASS" : "FAIL", fields);
        transition(exitAfter ? State.EXIT : State.DONE);
    }

    private void log(String event, String fields) {
        RemixForgeMod.LOG.info("RLC_SMOKE event={} id={} {}", event, id, fields);
    }

    private static final class Pose {
        final double x, y, z;
        final float yaw, pitch;
        Pose(double x, double y, double z, float yaw, float pitch) {
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }
    }
}

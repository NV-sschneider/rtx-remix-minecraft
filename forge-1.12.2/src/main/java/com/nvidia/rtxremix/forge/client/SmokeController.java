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
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
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
import org.lwjgl.opengl.GL11;

/**
 * Opt-in, in-process smoke fixture for a normally authenticated Minecraft launch.
 * It neither starts a launcher nor changes account/session authentication.
 * Enable with -Drlcraftremix.smoke=true; shutdown additionally requires
 * -Drlcraftremix.smoke.exit=true. Add -Drlcraftremix.smoke.entities=true for
 * a stationary pig and a cow making a bounded round trip before each capture.
 * The unique fixture save and screenshots remain
 * on disk for inspection. Completion checks frame capture, nonblank fixture output,
 * and renderer lifecycle. Visual correctness still requires image inspection.
 */
final class SmokeController {
    private static final long SEED = 8675309L;
    private static final int REQUIRED_NATIVE_FRAMES = 180;
    private static final int REENABLE_NATIVE_FRAMES = 60;
    private static final int SETTLE_TICKS = 40;
    private static final int VANILLA_TICKS = 60;
    private static final int UNLOAD_TICKS = 20;
    private static final int ENTITY_SETTLE_TICKS = 10;
    private static final long STARTUP_TIMEOUT = TimeUnit.SECONDS.toNanos(180);
    private static final long NATIVE_TIMEOUT = TimeUnit.SECONDS.toNanos(300);
    private static final long SETTLE_TIMEOUT = TimeUnit.SECONDS.toNanos(90);
    private static final long IMAGE_PROBE_INTERVAL = TimeUnit.MILLISECONDS.toNanos(250);

    private enum State {
        INACTIVE, WAIT_MENU, WAIT_WORLD, BUILD_SCENE, SETTLE, REMIX, VANILLA,
        REENABLE, RESTORE_RULES, UNLOAD, EXIT, DONE
    }

    private final Minecraft mc;
    private final ClientEvents events;
    private final boolean exitAfter;
    private final boolean includeEntities;
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
    private Future<Boolean> entityStep;
    private Future<?> ruleRestoration;
    private IntegratedServer fixtureServer;
    private WorldClient fixtureClientWorld;
    private WorldServer fixtureServerWorld;
    private FixtureGameRules clientRules;
    private volatile FixtureGameRules serverRules;
    private boolean rulesRestored;
    private boolean restoreForSuccess;
    private String pendingFailure;
    private boolean entityMotionStarted;
    private boolean entityMotionComplete;
    private boolean entityReferenceHeld;
    private boolean entityCaptureValidated;
    private long cowTransformBaseline;
    private long cowMotionTransformUpdates;
    private double lowestCowX;
    private double highestCowX;
    private int entitySettleTicks;
    private Pose pose;
    private boolean settingsSaved;
    private boolean previousPauseOnLostFocus;
    private int previousThirdPerson;
    private GuiScreen initializedMainMenu;
    private long lastImageProbeNanos;
    private long lastImageProbeFrame;
    private FixtureImageReadiness.Result imageReadiness;

    SmokeController(Minecraft mc, ClientEvents events) {
        this.mc = mc;
        this.events = events;
        boolean requested = Boolean.getBoolean("rlcraftremix.smoke");
        this.exitAfter = requested && Boolean.getBoolean("rlcraftremix.smoke.exit");
        this.includeEntities = requested && Boolean.getBoolean("rlcraftremix.smoke.entities");
        this.id = requested
                ? DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
                        + "-" + UUID.randomUUID().toString().substring(0, 8)
                : "disabled";
        this.state = requested ? State.WAIT_MENU : State.INACTIVE;
        this.stateSince = System.nanoTime();
        if (requested) log("START", "exit=" + exitAfter + " seed=" + SEED + " entities=" + includeEntities
                + " validation=capture_and_lifecycle pixelValidation=nonblank_fixture");
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
                        startEntityMotion();
                        log("REMIX_ENABLED", "targetFrames=" + REQUIRED_NATIVE_FRAMES);
                    }
                    break;
                case REMIX:
                    if (!fixtureReady()) break;
                    holdPose();
                    advanceEntityMotion();
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
                    if (frames - frameBaseline >= REQUIRED_NATIVE_FRAMES
                            && entityFixtureReady() && fixtureImageReady(frames)) {
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
                        if (includeEntities && events.capturedEntityParts() != 0) {
                            fail("entity_parts_present_after_disable");
                            break;
                        }
                        vanillaScreenshot = capture("vanilla");
                        log("VANILLA_CAPTURED", "file=" + vanillaScreenshot);
                        frameBaseline = events.renderedFrames();
                        lastFrames = frameBaseline;
                        events.setEnabled(true);
                        transition(State.REENABLE);
                        startEntityMotion();
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
                    advanceEntityMotion();
                    if (!canRender()) break;
                    long reenableFrames = events.renderedFrames();
                    if (reenableFrames < lastFrames) {
                        fail("reenable_frame_counter_reset");
                        break;
                    }
                    lastFrames = reenableFrames;
                    if (reenableFrames - frameBaseline >= REENABLE_NATIVE_FRAMES
                            && entityFixtureReady() && fixtureImageReady(reenableFrames)) {
                        reenabledFrames = reenableFrames - frameBaseline;
                        log("REENABLE_PASS", "frames=" + reenabledFrames
                                + " baseline=" + frameBaseline + " totalFrames=" + reenableFrames);
                        beginRuleRestoration(true, null);
                    }
                    break;
                case RESTORE_RULES:
                    if (ruleRestoration.isDone()) {
                        ruleRestoration.get();
                        requireOwnedClientWorld();
                        clientRules.restore(fixtureClientWorld.getGameRules());
                        rulesRestored = true;
                        log("RULES_RESTORED", "world=" + saveName + " sides=server,client");
                        if (restoreForSuccess && (events.failureMessage() != null || !events.isEnabled())) {
                            restoreForSuccess = false;
                            pendingFailure = "reason=renderer_failed_during_rule_restoration phase=RESTORE_RULES";
                        }
                        unloadFixture();
                        if (!restoreForSuccess) finish(false, pendingFailure);
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
                        if (includeEntities && events.capturedEntityParts() != 0) {
                            fail("entity_parts_present_after_unload");
                            break;
                        }
                        if (includeEntities) log("ENTITY_RELEASE_PASS", "liveParts=0");
                        log("WORLD_UNLOAD_PASS", "menuTicks=" + ticks + " rendererEnabled=true");
                        finish(true, "frames=" + capturedFrames + " world=" + saveName
                                + " remix=" + remixScreenshot + " vanilla=" + vanillaScreenshot
                                + " reenableFrames=" + reenabledFrames + " unloadTicks=" + ticks
                                + " entities=" + includeEntities
                                + " validation=capture_and_lifecycle pixelValidation=nonblank_fixture");
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
        fixtureServer = server;
        fixtureClientWorld = mc.world;
        clientRules = FixtureGameRules.capture(fixtureClientWorld.getGameRules());
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
        fixtureServerWorld = world;
        // Publish before the first mutation, independently of Pose: scene setup can
        // throw after changing rules, and GlobalGameRules saves both sides on unload.
        serverRules = FixtureGameRules.capture(world.getGameRules());
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
        EntitySmokeFixture creatures = includeEntities
                ? new EntitySmokeFixture(saveName, world, cx, floorY, cz) : null;
        Pose result = new Pose(cx + 0.5, floorY + 4.0, cz + 17.5, 180f, 16f, creatures);
        world.setSpawnPoint(new BlockPos(cx, floorY + 1, cz + 12));
        player.setGameType(GameType.CREATIVE);
        player.capabilities.allowFlying = true;
        player.capabilities.isFlying = true;
        player.capabilities.disableDamage = true;
        player.sendPlayerAbilities();
        player.connection.setPlayerLocation(result.x, result.y, result.z, result.yaw, result.pitch);
        return result;
    }

    private void beginRuleRestoration(boolean success, String failureFields) {
        restoreForSuccess = success;
        pendingFailure = failureFields;
        transition(State.RESTORE_RULES);
        if (!success) events.setEnabled(false);
        final IntegratedServer server = fixtureServer;
        // This task follows setup and entity work in the server's FIFO queue. A
        // cancelled Future alone cannot prove that its running body has stopped.
        ruleRestoration = server.callFromMainThread(() -> {
            if (!saveName.equals(server.getFolderName())) {
                throw new IllegalStateException("Unexpected fixture server during rule restoration");
            }
            FixtureGameRules original = serverRules;
            if (original != null) {
                if (server.getWorld(0) != fixtureServerWorld) {
                    throw new IllegalStateException("Unexpected fixture world during rule restoration");
                }
                original.restore(fixtureServerWorld.getGameRules());
            }
            return null;
        });
        log("RULES_RESTORE_STARTED", "world=" + saveName + " continuation=" + (success ? "unload_check" : "failure"));
    }

    private void requireOwnedClientWorld() {
        if (mc.world != fixtureClientWorld || mc.getIntegratedServer() != fixtureServer
                || !saveName.equals(fixtureServer.getFolderName())) {
            throw new IllegalStateException("Unexpected fixture client world during cleanup");
        }
    }

    private void unloadFixture() {
        requireOwnedClientWorld();
        // Keep Remix enabled on the success path so WorldEvent.Unload and the next
        // client tick exercise resource release. Restoration may have rendered frames.
        lastFrames = events.renderedFrames();
        transition(State.UNLOAD);
        log("WORLD_UNLOAD", "world=" + saveName + " rendererEnabled=" + events.isEnabled());
        // Match the menu's disconnect order: stop incoming packets before loadWorld
        // clears the handler and waits for integrated-server shutdown.
        fixtureClientWorld.sendQuittingDisconnectingPacket();
        mc.loadWorld(null);
        mc.displayGuiScreen(new GuiMainMenu());
    }

    private void startEntityMotion() {
        entityMotionStarted = false;
        entityMotionComplete = !includeEntities;
        entityCaptureValidated = false;
        cowTransformBaseline = 0;
        cowMotionTransformUpdates = 0;
        lowestCowX = Double.POSITIVE_INFINITY;
        highestCowX = Double.NEGATIVE_INFINITY;
        entitySettleTicks = 0;
        if (!includeEntities) return;
        log("ENTITY_CAPTURE_WAIT", "phase=" + state.name() + " creatures=2");
    }

    private void advanceEntityMotion() throws Exception {
        if (!includeEntities) return;
        if (!entityMotionStarted) {
            if (!events.entityCaptured(pose.creatures.cowUuid())
                    || !events.entityCaptured(pose.creatures.pigUuid())) return;
            // The first retained frame is the baseline, so initial mesh publication
            // cannot count as evidence that moving instances were updated.
            cowTransformBaseline = events.entityTransformUpdates(pose.creatures.cowUuid());
            entityMotionStarted = true;
            entityReferenceHeld = false;
            observeCowMotion();
            scheduleEntityStep(true);
            log("ENTITY_MOTION_STARTED", "phase=" + state.name()
                    + " cowCaptured=true pigCaptured=true transformBaseline=" + cowTransformBaseline);
            return;
        }
        observeCowMotion();
        if (entityMotionComplete || !entityStep.isDone()) return;
        entityMotionComplete = entityStep.get();
        entityStep = null;
        if (entityMotionComplete) {
            // Freeze the motion evidence before the explicit screenshot pose can
            // cause additional instance updates of its own.
            cowMotionTransformUpdates = events.entityTransformUpdates(pose.creatures.cowUuid())
                    - cowTransformBaseline;
            log("ENTITY_MOTION_COMPLETE", "phase=" + state.name() + " cowDistance=4.0 creatures=2");
        } else {
            scheduleEntityStep(false);
        }
    }

    private void observeCowMotion() {
        if (!events.entityCaptured(pose.creatures.cowUuid())) return;
        double x = pose.creatures.clientCowX(mc.world);
        if (Double.isNaN(x)) return;
        lowestCowX = Math.min(lowestCowX, x);
        highestCowX = Math.max(highestCowX, x);
    }

    private void scheduleEntityStep(boolean restart) {
        final IntegratedServer server = mc.getIntegratedServer();
        entityStep = server.callFromMainThread(() -> pose.creatures.advance(server, restart));
    }

    private boolean entityFixtureReady() {
        if (!includeEntities) return true;
        if (!entityMotionComplete || !entityReferenceHeld || !pose.creatures.isAtReferencePose(mc.world)) {
            entitySettleTicks = 0;
            return false;
        }
        if (++entitySettleTicks < ENTITY_SETTLE_TICKS) return false;
        if (entityCaptureValidated) return true;
        if (!events.entityCaptured(pose.creatures.cowUuid())
                || !events.entityCaptured(pose.creatures.pigUuid())) {
            fail("fixture_entity_not_captured");
            return false;
        }
        if (cowMotionTransformUpdates <= 0) {
            fail("entity_transform_updates_missing");
            return false;
        }
        // Seeing at least a full block of the two-block track distinguishes moving
        // capture from the idle model's small head and limb interpolation changes.
        if (highestCowX - lowestCowX < 1.0) {
            fail("entity_motion_not_observed");
            return false;
        }
        entityCaptureValidated = true;
        log("ENTITY_CAPTURE_PASS", "phase=" + state.name() + " " + entityCaptureSummary());
        return true;
    }

    private String entityCaptureSummary() {
        return "cowCaptured=" + events.entityCaptured(pose.creatures.cowUuid())
                + " pigCaptured=" + events.entityCaptured(pose.creatures.pigUuid())
                + " cowTransformUpdates="
                + (events.entityTransformUpdates(pose.creatures.cowUuid()) - cowTransformBaseline)
                + " cowMotionTransformUpdates=" + cowMotionTransformUpdates
                + " observedCowSpan=" + (highestCowX - lowestCowX)
                + " liveParts=" + events.capturedEntityParts();
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
        if (includeEntities && (entityReferenceHeld
                || entityMotionComplete && pose.creatures.isAtReferencePosition(mc.world))) {
            pose.creatures.holdReferencePose(mc.getIntegratedServer(), mc.world);
            entityReferenceHeld = true;
        }
    }

    private boolean canRender() {
        return mc.world != null && mc.player != null && mc.currentScreen == null
                && !mc.isGamePaused() && mc.displayWidth > 0 && mc.displayHeight > 0;
    }

    private boolean fixtureImageReady(long frames) {
        long now = System.nanoTime();
        // Bound full-frame GPU readback cost; readiness never extends the state's deadline.
        if (imageReadiness != null && (frames == lastImageProbeFrame
                || now - lastImageProbeNanos < IMAGE_PROBE_INTERVAL)) return false;
        boolean firstProbe = imageReadiness == null;
        lastImageProbeNanos = now;
        lastImageProbeFrame = frames;
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int unpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        try {
            imageReadiness = FixtureImageReadiness.evaluate(ScreenShotHelper.createScreenshot(
                    mc.displayWidth, mc.displayHeight, mc.getFramebuffer()));
        } finally {
            // The screenshot helper changes all three; keep Minecraft's texture cache in sync.
            GlStateManager.bindTexture(texture);
            GlStateManager.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment);
            GlStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment);
        }
        if (firstProbe || imageReadiness.isReady()) {
            log(imageReadiness.isReady() ? "IMAGE_READY" : "IMAGE_WAIT",
                    "phase=" + state.name() + " frames=" + (frames - frameBaseline)
                            + " " + imageReadiness.describe());
        }
        return imageReadiness.isReady();
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
        if (state == State.SETTLE || state == State.VANILLA || state == State.RESTORE_RULES
                || state == State.UNLOAD) return SETTLE_TIMEOUT;
        return STARTUP_TIMEOUT;
    }

    private void transition(State next) {
        state = next;
        stateSince = System.nanoTime();
        ticks = 0;
        initializedMainMenu = null;
        imageReadiness = null;
    }

    private void fail(String reason) {
        if (state == State.DONE || state == State.EXIT || state == State.INACTIVE) return;
        if (state == State.RESTORE_RULES) {
            // Leaving the fixture open avoids persisting its temporary rules when
            // cleanup could not complete. Never report success or close another world.
            finishNow(false, (pendingFailure == null ? "reason=rule_restoration_failed" : pendingFailure)
                    + " cleanup=" + reason + " rulesRestored=" + rulesRestored + " exitSuppressed=true", false);
            return;
        }
        finish(false, "reason=" + reason + " phase=" + state.name()
                + " frames=" + Math.max(0L, lastFrames - frameBaseline)
                + (includeEntities && pose != null ? " " + entityCaptureSummary() : "")
                + (imageReadiness == null ? "" : " " + imageReadiness.describe()));
    }

    private void finish(boolean success, String fields) {
        if (clientRules != null && !rulesRestored) {
            beginRuleRestoration(false, fields);
            return;
        }
        finishNow(success, fields, mc.world == null);
    }

    private void finishNow(boolean success, String fields, boolean permitExit) {
        events.setEnabled(false);
        if (settingsSaved) {
            mc.gameSettings.pauseOnLostFocus = previousPauseOnLostFocus;
            mc.gameSettings.thirdPersonView = previousThirdPerson;
            settingsSaved = false;
        }
        log(success ? "PASS" : "FAIL", fields);
        if (rulesRestored) {
            setup = null;
            entityStep = null;
            ruleRestoration = null;
            clientRules = null;
            serverRules = null;
            fixtureClientWorld = null;
            fixtureServerWorld = null;
            fixtureServer = null;
        }
        transition(exitAfter && permitExit ? State.EXIT : State.DONE);
    }

    private void log(String event, String fields) {
        RemixForgeMod.LOG.info("RLC_SMOKE event={} id={} {}", event, id, fields);
    }

    private static final class Pose {
        final double x, y, z;
        final float yaw, pitch;
        final EntitySmokeFixture creatures;
        Pose(double x, double y, double z, float yaw, float pitch, EntitySmokeFixture creatures) {
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
            this.creatures = creatures;
        }
    }
}

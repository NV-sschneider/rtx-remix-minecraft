/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.RemixForgeMod;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
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
 * Add -Drlcraftremix.smoke.sky=true for clear noon, sunset, midnight, and rain.
 * Add -Drlcraftremix.smoke.terrain=true for terrain travel, mutation, and radius checks;
 * -Drlcraftremix.smoke.terrainNatural=true selects a generated landscape for those checks.
 * -Drlcraftremix.smoke.terrainRadius=N selects 4 through 32 chunks (default 4).
 * Add -Drlcraftremix.smoke.materials=true for fluids, animated textures and block lights.
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
    private static final int SKY_SETTLE_TICKS = 30;
    private static final long STARTUP_TIMEOUT = TimeUnit.SECONDS.toNanos(180);
    private static final long NATIVE_TIMEOUT = TimeUnit.SECONDS.toNanos(300);
    private static final long SETTLE_TIMEOUT = TimeUnit.SECONDS.toNanos(90);
    private static final long IMAGE_PROBE_INTERVAL = TimeUnit.MILLISECONDS.toNanos(250);

    private enum State {
        INACTIVE, WAIT_MENU, WAIT_WORLD, BUILD_SCENE, SETTLE, REMIX, VANILLA,
        REENABLE, MATERIALS, GLASS, OBJECTS, EFFECTS, COMBAT, CLOUDS, PBR, FIRSTPERSON, HELD_LIGHTS, GLINT, TILES, FOLIAGE, WINDOW, TERRAIN,
        COMPATIBILITY, SURVIVAL, DIMENSIONS, TERRAIN_OPTIMIZATION, SOAK, MULTIPLAYER, RESTORE_MULTIPLAYER,
        SKY_APPLY, SKY_CAPTURE, RESTORE_SURVIVAL, RESTORE_DIMENSIONS, RESTORE_RULES, DISCONNECT, UNLOAD, EXIT, DONE
    }

    private enum SkyPhase {
        DAY(6000L, false), SUNSET(12500L, false), NIGHT(18000L, false), RAIN(6000L, true);
        final long time;
        final boolean rain;
        SkyPhase(long time, boolean rain) { this.time = time; this.rain = rain; }
    }

    private final Minecraft mc;
    private final ClientEvents events;
    private final boolean exitAfter;
    private final boolean includeEntities;
    private final boolean includeSky;
    private final boolean includeTerrain;
    private final boolean naturalTerrain;
    private final boolean includeMaterials;
    private final boolean includeObjects;
    private final boolean includeEffects;
    private final boolean includeCombat, includeClouds, includePbr, includeFirstPerson, includeTiles, includeWindow;
    private final boolean includeHeldLights, includeFoliage, includeGlint;
    private final boolean includeCompatibility, includeDimensions;
    private final boolean includeSurvival;
    private final boolean includeTerrainOptimization;
    private final boolean includeSoak;
    private final boolean includeMultiplayer;
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
    private Future<?> skyStep;
    private Future<?> serverShutdown;
    private IntegratedServer fixtureServer;
    private WorldClient fixtureClientWorld;
    private WorldServer fixtureServerWorld;
    private FixtureGameRules clientRules;
    private volatile FixtureGameRules serverRules;
    private FixtureEnvironment clientEnvironment;
    private volatile FixtureEnvironment serverEnvironment;
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
    private int previousRenderDistance;
    private FixtureTerrain terrainFixture;
    private FixtureMaterials materialFixture;
    private volatile FixtureMaterials.Layout materialLayout;
    private FixtureGlass glassFixture;
    private volatile FixtureGlass.Layout glassLayout;
    private FixtureObjects objectFixture;
    private volatile FixtureObjects.Layout objectLayout;
    private FixtureEffects effectFixture;
    private volatile FixtureEffects.Layout effectLayout;
    private FixtureCombat combatFixture;
    private volatile FixtureCombat.Layout combatLayout;
    private FixtureClouds cloudFixture;
    private FixturePbr pbrFixture;
    private volatile FixturePbr.Layout pbrLayout;
    private FixtureFirstPerson firstPersonFixture;
    private volatile FixtureFirstPerson.Layout firstPersonLayout;
    private FixtureTiles tileFixture;
    private volatile FixtureTiles.Layout tileLayout;
    private FixtureWindowOverlay windowFixture;
    private FixtureHeldLights heldLightsFixture;
    private volatile FixtureHeldLights.Layout heldLightsLayout;
    private FixtureGlint glintFixture;
    private volatile FixtureGlint.Layout glintLayout;
    private FixtureFoliage foliageFixture;
    private volatile FixtureFoliage.Layout foliageLayout;
    private FixtureCompatibility compatibilityFixture;
    private volatile FixtureCompatibility.Layout compatibilityLayout;
    private FixtureSurvival survivalFixture;
    private volatile FixtureSurvival.Layout survivalLayout;
    private FixtureDimensions dimensionsFixture;
    private FixtureTerrainOptimization optimizationFixture;
    private FixtureSoak soakFixture;
    private FixtureMultiplayer multiplayerFixture;
    private GuiScreen initializedMainMenu;
    private long lastImageProbeNanos;
    private long lastImageProbeFrame;
    private FixtureImageReadiness.Result imageReadiness;
    private SkyPhase skyPhase;
    private FixtureSkyImage.Result daySkyImage;
    private long skyUpdateBaseline;

    SmokeController(Minecraft mc, ClientEvents events) {
        this.mc = mc;
        this.events = events;
        boolean requested = Boolean.getBoolean("rlcraftremix.smoke");
        this.exitAfter = requested && Boolean.getBoolean("rlcraftremix.smoke.exit");
        this.includeEntities = requested && Boolean.getBoolean("rlcraftremix.smoke.entities");
        this.includeSky = requested && Boolean.getBoolean("rlcraftremix.smoke.sky");
        this.includeTerrain = requested && Boolean.getBoolean("rlcraftremix.smoke.terrain");
        this.naturalTerrain = includeTerrain && Boolean.getBoolean("rlcraftremix.smoke.terrainNatural");
        this.includeMaterials = requested && Boolean.getBoolean("rlcraftremix.smoke.materials");
        this.includeObjects = requested && Boolean.getBoolean("rlcraftremix.smoke.objects");
        this.includeEffects = requested && Boolean.getBoolean("rlcraftremix.smoke.effects");
        this.includeCombat = requested && Boolean.getBoolean("rlcraftremix.smoke.combat");
        this.includeClouds = requested && Boolean.getBoolean("rlcraftremix.smoke.clouds");
        this.includePbr = requested && Boolean.getBoolean("rlcraftremix.smoke.pbr");
        this.includeFirstPerson = requested && Boolean.getBoolean("rlcraftremix.smoke.firstperson");
        this.includeTiles = requested && Boolean.getBoolean("rlcraftremix.smoke.tiles");
        this.includeWindow = requested && Boolean.getBoolean("rlcraftremix.smoke.window");
        this.includeHeldLights = requested && Boolean.getBoolean("rlcraftremix.smoke.heldlights");
        this.includeGlint = requested && Boolean.getBoolean("rlcraftremix.smoke.glint");
        this.includeFoliage = requested && Boolean.getBoolean("rlcraftremix.smoke.foliage");
        this.includeCompatibility = requested && Boolean.getBoolean("rlcraftremix.smoke.compatibility");
        this.includeDimensions = requested && Boolean.getBoolean("rlcraftremix.smoke.dimensions");
        this.includeSurvival = requested && Boolean.getBoolean("rlcraftremix.smoke.survival");
        this.includeTerrainOptimization = requested && Boolean.getBoolean("rlcraftremix.smoke.terrainOptimization");
        this.includeSoak = requested && Boolean.getBoolean("rlcraftremix.smoke.soak");
        this.includeMultiplayer = requested && Boolean.getBoolean("rlcraftremix.smoke.multiplayer");
        if (includeMultiplayer && (includeEntities || includeSky || includeTerrain || includeMaterials || includeObjects
                || includeEffects || includeCombat || includeClouds || includePbr || includeFirstPerson || includeTiles
                || includeWindow || includeHeldLights || includeGlint || includeFoliage || includeCompatibility
                || includeDimensions || includeTerrainOptimization || includeSoak || includeSurvival))
            throw new IllegalArgumentException("Multiplayer smoke runs separately from integrated-world fixtures");
        this.id = requested
                ? DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
                        + "-" + UUID.randomUUID().toString().substring(0, 8)
                : "disabled";
        this.state = requested ? State.WAIT_MENU : State.INACTIVE;
        this.stateSince = System.nanoTime();
        if (requested) log("START", "exit=" + exitAfter + " seed=" + SEED + " entities=" + includeEntities
                + " sky=" + includeSky + " terrain=" + includeTerrain + " naturalTerrain=" + naturalTerrain
                + " materials=" + includeMaterials + " objects=" + includeObjects + " effects=" + includeEffects
                + " combat=" + includeCombat + " clouds=" + includeClouds + " pbr=" + includePbr
                + " firstperson=" + includeFirstPerson + " tiles=" + includeTiles + " window=" + includeWindow
                + " heldlights=" + includeHeldLights + " foliage=" + includeFoliage + " glint=" + includeGlint
                + " compatibility=" + includeCompatibility + " dimensions=" + includeDimensions
                + " survival=" + includeSurvival
                + " terrainOptimization=" + includeTerrainOptimization
                + " soak=" + includeSoak
                + " multiplayer=" + includeMultiplayer
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
                        if (includeMultiplayer) {
                            events.setEnabled(true);
                            multiplayerFixture = new FixtureMultiplayer(mc, events,
                                    Integer.getInteger("rlcraftremix.smoke.multiplayerPort", 25565),
                                    System.getProperty("rlcraftremix.smoke.multiplayerFixtureId", ""), fixtureOutput());
                            transition(State.MULTIPLAYER);
                        } else launchFixture();
                    }
                    break;
                case MULTIPLAYER:
                    if (multiplayerFixture.tick())
                        finishNow(true, "multiplayer=true " + multiplayerFixture.status(), mc.world == null);
                    break;
                case RESTORE_MULTIPLAYER:
                    if (multiplayerFixture.cleanupComplete())
                        finishNow(false, pendingFailure + " multiplayerCleanup=true", mc.world == null);
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
                    if (events.failureMessage() != null) {
                        fail("disable_renderer_failure");
                        break;
                    }
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
                        if (includeSky && events.skyResourceCount() != 0) {
                            fail("sky_resources_present_after_disable");
                            break;
                        }
                        if (includeTerrain && events.terrainResourceCount() != 0) {
                            fail("terrain_resources_present_after_disable");
                            break;
                        }
                        if (includeTerrain) log("TERRAIN_DISABLE_PASS", "liveResources=0");
                        if (includeMaterials && !materialsReleased()) {
                            fail("material_resources_present_after_disable");
                            break;
                        }
                        if (includeMaterials) log("MATERIAL_DISABLE_PASS", "terrainResources=0 lightResources=0 fluidVertices=0");
                        if (!objectsReleased()) { fail("object_resources_present_after_disable"); break; }
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
                        if (includeMaterials) beginMaterialFixture();
                        else beginAfterMaterials();
                    }
                    break;
                case MATERIALS:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (materialFixture.tick()) beginAfterMaterials();
                    break;
                case GLASS:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (glassFixture.tick()) beginAfterGlass();
                    break;
                case OBJECTS:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (objectFixture.tick()) beginAfterObjects();
                    break;
                case EFFECTS:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (effectFixture.tick()) beginAfterEffects();
                    break;
                case COMBAT:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (combatFixture.tick()) beginAfterCombat();
                    break;
                case CLOUDS:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (cloudFixture.tick()) beginAfterClouds();
                    break;
                case PBR:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (pbrFixture.tick()) beginAfterPbr();
                    break;
                case FIRSTPERSON:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (firstPersonFixture.tick()) beginAfterFirstPerson();
                    break;
                case HELD_LIGHTS:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (heldLightsFixture.tick()) beginAfterHeldLights();
                    break;
                case TILES:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (tileFixture.tick()) beginAfterTiles();
                    break;
                case GLINT:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (glintFixture.tick()) beginAfterGlint();
                    break;
                case FOLIAGE:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (foliageFixture.tick()) beginAfterFoliage();
                    break;
                case WINDOW:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (windowFixture.tick()) beginAfterWindow();
                    break;
                case COMPATIBILITY:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (compatibilityFixture.tick()) beginAfterCompatibility();
                    break;
                case SURVIVAL:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (survivalFixture.tick()) {
                        survivalFixture.close();
                        survivalFixture = null;
                        beginAfterSurvival();
                    }
                    break;
                case DIMENSIONS:
                    // A real transfer replaces WorldClient and temporarily has no client world.
                    lastFrames = events.renderedFrames();
                    if (dimensionsFixture.tick()) {
                        dimensionsFixture.close();
                        dimensionsFixture = null;
                        fixtureClientWorld = mc.world;
                        beginAfterDimensions();
                    }
                    break;
                case TERRAIN_OPTIMIZATION:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    // The fixture owns its one intentional failure and the recovery assertions.
                    if (optimizationFixture.tick()) beginAfterTerrainOptimization();
                    break;
                case SOAK:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (soakFixture.tick()) beginAfterSoak();
                    break;
                case TERRAIN:
                    if (!fixtureReady()) break;
                    lastFrames = events.renderedFrames();
                    if (terrainFixture.tick()) {
                        if (includeSky) beginSkyPhase(SkyPhase.DAY);
                        else beginRuleRestoration(true, null);
                    }
                    break;
                case SKY_APPLY:
                    if (!fixtureReady()) break;
                    holdPose();
                    if (skyStep.isDone()) {
                        skyStep.get();
                        requireOwnedClientWorld();
                        FixtureEnvironment.setSky(fixtureClientWorld, skyPhase.time, skyPhase.rain);
                        frameBaseline = events.renderedFrames();
                        lastFrames = frameBaseline;
                        transition(State.SKY_CAPTURE);
                    }
                    break;
                case SKY_CAPTURE:
                    captureSkyPhase();
                    break;
                case RESTORE_SURVIVAL:
                    if (survivalFixture.cleanupComplete()) {
                        survivalFixture = null;
                        beginDimensionRestoration();
                    }
                    break;
                case RESTORE_DIMENSIONS:
                    if (dimensionsFixture.cleanupComplete()) {
                        fixtureClientWorld = mc.world;
                        dimensionsFixture = null;
                        beginWorldRuleRestoration();
                    }
                    break;
                case RESTORE_RULES:
                    if (ruleRestoration.isDone()) {
                        ruleRestoration.get();
                        requireOwnedClientWorld();
                        clientEnvironment.restore(fixtureClientWorld);
                        clientRules.restore(fixtureClientWorld.getGameRules());
                        rulesRestored = true;
                        log("RULES_RESTORED", "world=" + saveName
                                + " sides=server,client environmentRestored=true");
                        if (restoreForSuccess && (events.failureMessage() != null || !events.isEnabled())) {
                            restoreForSuccess = false;
                            pendingFailure = "reason=renderer_failed_during_rule_restoration phase=RESTORE_RULES";
                        }
                        beginFixtureDisconnect();
                    }
                    break;
                case DISCONNECT:
                    awaitFixtureDisconnect();
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
                        if (includeSky && events.skyResourceCount() != 0) {
                            fail("sky_resources_present_after_unload");
                            break;
                        }
                        if (includeSky) log("SKY_RELEASE_PASS", "liveResources=0");
                        if (includeTerrain && events.terrainResourceCount() != 0) {
                            fail("terrain_resources_present_after_unload");
                            break;
                        }
                        if (includeTerrain) log("TERRAIN_RELEASE_PASS", "liveResources=0");
                        if (includeMaterials && !materialsReleased()) {
                            fail("material_resources_present_after_unload");
                            break;
                        }
                        if (includeMaterials) log("MATERIAL_RELEASE_PASS", "terrainResources=0 lightResources=0 fluidVertices=0");
                        if (!objectsReleased()) { fail("object_resources_present_after_unload"); break; }
                        if (includeObjects) log("OBJECT_RELEASE_PASS", "tileParts=0 itemResources=0 effectResources=0");
                        if (includeEffects) log("EFFECT_RELEASE_PASS", "liveResources=0");
                        if (includeCombat) log("COMBAT_RELEASE_PASS", "liveResources=0");
                        if (includeClouds) log("CLOUD_RELEASE_PASS", "liveResources=0");
                        if (includePbr && events.pbrTextureCount() != 0) { fail("pbr_resources_present_after_unload"); break; }
                        if (includePbr) log("PBR_RELEASE_PASS", "liveResources=0");
                        log("WORLD_UNLOAD_PASS", "menuTicks=" + ticks + " rendererEnabled=true");
                        finish(true, "frames=" + capturedFrames + " world=" + saveName
                                + " remix=" + remixScreenshot + " vanilla=" + vanillaScreenshot
                                + " reenableFrames=" + reenabledFrames + " unloadTicks=" + ticks
                                + " entities=" + includeEntities + " sky=" + includeSky
                                + " terrain=" + includeTerrain + " naturalTerrain=" + naturalTerrain
                                + " materials=" + includeMaterials + " objects=" + includeObjects + " effects=" + includeEffects
                                + " combat=" + includeCombat + " clouds=" + includeClouds + " pbr=" + includePbr
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
        // Validate the optional property before taking ownership of any client settings.
        int terrainRadius = includeTerrain ? FixtureTerrain.RADIUS : 0;
        previousPauseOnLostFocus = mc.gameSettings.pauseOnLostFocus;
        previousThirdPerson = mc.gameSettings.thirdPersonView;
        previousRenderDistance = mc.gameSettings.renderDistanceChunks;
        settingsSaved = true;
        mc.gameSettings.pauseOnLostFocus = false;
        mc.gameSettings.thirdPersonView = 0;
        if (includeTerrain) {
            FixtureTerrain.setRadius(mc, terrainRadius);
            log("TERRAIN_SETTINGS_SAVED", "renderDistance=" + previousRenderDistance
                    + " fixtureRenderDistance=" + terrainRadius);
        }

        saveName = "rlcraft-remix-smoke-" + id;
        Path saves = mc.gameDir.toPath().resolve("saves");
        Files.createDirectories(saves);
        // Atomic reservation: never reuse, overwrite, or delete an existing world directory.
        Files.createDirectory(saves.resolve(saveName));
        WorldSettings settings = new WorldSettings(SEED, GameType.CREATIVE, false, false,
                naturalTerrain ? WorldType.DEFAULT : WorldType.FLAT).enableCommands();
        if (!naturalTerrain) settings.setGeneratorOptions("3;minecraft:bedrock,2*minecraft:dirt,minecraft:grass;1;");
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
        clientEnvironment = FixtureEnvironment.capture(fixtureClientWorld);
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
        serverEnvironment = FixtureEnvironment.capture(world);
        world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
        world.getGameRules().setOrCreateGameRule("doWeatherCycle", "false");
        world.getGameRules().setOrCreateGameRule("randomTickSpeed", "0");
        FixtureEnvironment.setSky(world, 6000L, false);

        if (naturalTerrain) {
            // Clear only the owned fixture's scene and camera corridor, including foliage.
            for (int x = -10; x <= 10; x++) {
                for (int z = -5; z <= 19; z++) {
                    for (int y = 1; y <= 16; y++) {
                        world.setBlockToAir(new BlockPos(cx + x, floorY + y, cz + z));
                    }
                }
            }
        }

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
        if (includeMaterials) materialLayout = FixtureMaterials.build(world, cx + 32, floorY, cz);
        if (includeMaterials) glassLayout = FixtureGlass.build(world, cx + 64, floorY, cz);
        if (includeObjects) objectLayout = FixtureObjects.build(world, cx - 32, floorY, cz);
        if (includeEffects) effectLayout = FixtureEffects.build(world, cx, floorY, cz + 40);
        if (includeCombat) combatLayout = FixtureCombat.build(world, cx, floorY, cz - 40);
        if (includePbr) pbrLayout = FixturePbr.build(world, cx + 32, floorY, cz + 40);
        if (includeFirstPerson) firstPersonLayout = FixtureFirstPerson.build(world, cx - 32, floorY, cz - 40);
        if (includeTiles) tileLayout = FixtureTiles.build(world, cx + 128, floorY, cz);
        if (includeHeldLights) heldLightsLayout = FixtureHeldLights.build(world, cx - 80, floorY, cz - 40);
        if (includeGlint) glintLayout = FixtureGlint.build(world, cx + 240, floorY, cz);
        if (includeFoliage) foliageLayout = FixtureFoliage.build(world, cx + 176, floorY, cz);
        if (includeCompatibility) compatibilityLayout = FixtureCompatibility.build(world, cx + 400, floorY, cz);
        if (includeSurvival) survivalLayout = FixtureSurvival.build(world, cx + 560, floorY, cz);
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

    private void closeRenderFixtures() {
        // Closing an owned pause screen lets the integrated server drain cleanup work.
        if (objectFixture != null) objectFixture.close();
        if (glassFixture != null) glassFixture.close();
        if (effectFixture != null) effectFixture.close();
        if (combatFixture != null) combatFixture.close();
        if (cloudFixture != null) cloudFixture.close();
        if (pbrFixture != null) pbrFixture.close();
        if (firstPersonFixture != null) firstPersonFixture.close();
        if (tileFixture != null) tileFixture.close();
        if (heldLightsFixture != null) heldLightsFixture.close();
        if (glintFixture != null) glintFixture.close();
        if (foliageFixture != null) foliageFixture.close();
        if (windowFixture != null) windowFixture.close();
        if (compatibilityFixture != null) compatibilityFixture.close();
        if (survivalFixture != null) survivalFixture.close();
        if (optimizationFixture != null) optimizationFixture.close();
        if (soakFixture != null) soakFixture.close();
        if (multiplayerFixture != null) multiplayerFixture.close();
    }

    private void beginRuleRestoration(boolean success, String failureFields) {
        closeRenderFixtures();
        restoreForSuccess = success;
        pendingFailure = failureFields;
        if (survivalFixture != null) {
            transition(State.RESTORE_SURVIVAL);
            return;
        }
        beginDimensionRestoration();
    }

    private void beginDimensionRestoration() {
        if (dimensionsFixture != null) {
            dimensionsFixture.close();
            transition(State.RESTORE_DIMENSIONS);
            return;
        }
        beginWorldRuleRestoration();
    }

    private void beginWorldRuleRestoration() {
        transition(State.RESTORE_RULES);
        if (!restoreForSuccess) events.setEnabled(false);
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
                FixtureEnvironment environment = serverEnvironment;
                if (environment != null) environment.restore(fixtureServerWorld);
                original.restore(fixtureServerWorld.getGameRules());
            }
            return null;
        });
        log("RULES_RESTORE_STARTED", "world=" + saveName + " continuation=" + (restoreForSuccess ? "unload_check" : "failure"));
    }

    private void requireOwnedClientWorld() {
        if (mc.world != fixtureClientWorld || mc.getIntegratedServer() != fixtureServer
                || !saveName.equals(fixtureServer.getFolderName())) {
            throw new IllegalStateException("Unexpected fixture client world during cleanup");
        }
    }

    private void beginTerrainFixture() {
        terrainFixture = new FixtureTerrain(mc, events, fixtureServer, fixtureServerWorld,
                saveName, pose.x, pose.y, pose.z, new FixtureTerrain.Output() {
                    @Override public BufferedImage image() { return readScreenshot(); }
                    @Override public String capture(String name, BufferedImage image) throws Exception {
                        return SmokeController.this.capture(name, image);
                    }
                    @Override public void log(String event, String fields) { SmokeController.this.log(event, fields); }
                });
        transition(State.TERRAIN);
    }

    private void beginMaterialFixture() {
        materialFixture = new FixtureMaterials(mc, events, fixtureServer, fixtureServerWorld,
                saveName, materialLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, new FixtureTerrain.Output() {
                    @Override public BufferedImage image() { return readScreenshot(); }
                    @Override public String capture(String name, BufferedImage image) throws Exception {
                        return SmokeController.this.capture(name, image);
                    }
                    @Override public void log(String event, String fields) { SmokeController.this.log(event, fields); }
                });
        transition(State.MATERIALS);
    }

    private void beginAfterMaterials() throws java.io.IOException {
        if (includeMaterials) {
            glassFixture = new FixtureGlass(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, glassLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, new FixtureTerrain.Output() {
                        @Override public BufferedImage image() { return readScreenshot(); }
                        @Override public String capture(String name, BufferedImage image) throws Exception {
                            return SmokeController.this.capture(name, image);
                        }
                        @Override public void log(String event, String fields) { SmokeController.this.log(event, fields); }
                    });
            transition(State.GLASS);
        } else beginAfterGlass();
    }

    private void beginAfterGlass() throws java.io.IOException {
        if (includeObjects) {
            objectFixture = new FixtureObjects(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, objectLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, new FixtureTerrain.Output() {
                        @Override public BufferedImage image() { return readScreenshot(); }
                        @Override public String capture(String name, BufferedImage image) throws Exception {
                            return SmokeController.this.capture(name, image);
                        }
                        @Override public void log(String event, String fields) { SmokeController.this.log(event, fields); }
                    });
            transition(State.OBJECTS);
        } else beginAfterObjects();
    }

    private void beginAfterObjects() throws java.io.IOException {
        if (includeEffects) {
            effectFixture = new FixtureEffects(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, effectLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, new FixtureTerrain.Output() {
                        @Override public BufferedImage image() { return readScreenshot(); }
                        @Override public String capture(String name, BufferedImage image) throws Exception {
                            return SmokeController.this.capture(name, image);
                        }
                        @Override public void log(String event, String fields) { SmokeController.this.log(event, fields); }
                    });
            transition(State.EFFECTS);
        } else beginAfterEffects();
    }

    private void beginAfterEffects() throws java.io.IOException {
        if (includeCombat) {
            combatFixture = new FixtureCombat(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, combatLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.COMBAT);
        } else beginAfterCombat();
    }

    private void beginAfterCombat() throws java.io.IOException {
        if (includeClouds) {
            cloudFixture = new FixtureClouds(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.CLOUDS);
        } else beginAfterClouds();
    }

    private void beginAfterClouds() throws java.io.IOException {
        if (includePbr) {
            pbrFixture = new FixturePbr(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, pbrLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.PBR);
        } else beginAfterPbr();
    }

    private void beginAfterPbr() {
        if (includeFirstPerson) {
            firstPersonFixture = new FixtureFirstPerson(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, firstPersonLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.FIRSTPERSON);
        } else beginAfterFirstPerson();
    }

    private void beginAfterFirstPerson() {
        if (includeHeldLights) {
            heldLightsFixture = new FixtureHeldLights(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, heldLightsLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.HELD_LIGHTS);
        } else beginAfterHeldLights();
    }

    private void beginAfterHeldLights() {
        if (includeGlint) {
            glintFixture = new FixtureGlint(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, glintLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.GLINT);
        } else beginAfterGlint();
    }

    private void beginAfterGlint() {
        if (includeTiles) {
            tileFixture = new FixtureTiles(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, tileLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.TILES);
        } else beginAfterTiles();
    }

    private void beginAfterTiles() {
        if (includeFoliage) {
            foliageFixture = new FixtureFoliage(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, foliageLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.FOLIAGE);
        } else beginAfterFoliage();
    }

    private void beginAfterFoliage() {
        if (includeCompatibility) {
            compatibilityFixture = new FixtureCompatibility(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, compatibilityLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.COMPATIBILITY);
        } else beginAfterCompatibility();
    }

    private void beginAfterCompatibility() {
        if (includeSurvival) {
            survivalFixture = new FixtureSurvival(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, survivalLayout, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.SURVIVAL);
        } else beginAfterSurvival();
    }

    private void beginAfterSurvival() {
        if (includeWindow) {
            windowFixture = new FixtureWindowOverlay(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, fixtureOutput());
            transition(State.WINDOW);
        } else beginAfterWindow();
    }

    private void beginAfterWindow() {
        if (includeDimensions) {
            closeRenderFixtures();
            dimensionsFixture = new FixtureDimensions(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.DIMENSIONS);
        } else beginAfterDimensions();
    }

    private void beginAfterDimensions() {
        if (includeTerrainOptimization) {
            optimizationFixture = new FixtureTerrainOptimization(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.TERRAIN_OPTIMIZATION);
        } else beginAfterTerrainOptimization();
    }

    private void beginAfterTerrainOptimization() {
        if (includeSoak) {
            soakFixture = new FixtureSoak(mc, events, fixtureServer, fixtureServerWorld,
                    saveName, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, fixtureOutput());
            transition(State.SOAK);
        } else beginAfterSoak();
    }

    private void beginAfterSoak() {
        if (includeTerrain) beginTerrainFixture();
        else if (includeSky) beginSkyPhase(SkyPhase.DAY);
        else beginRuleRestoration(true, null);
    }

    private FixtureTerrain.Output fixtureOutput() {
        return new FixtureTerrain.Output() {
            @Override public BufferedImage image() { return readScreenshot(); }
            @Override public String capture(String name, BufferedImage image) throws Exception {
                return SmokeController.this.capture(name, image);
            }
            @Override public void log(String event, String fields) { SmokeController.this.log(event, fields); }
        };
    }

    private boolean materialsReleased() {
        return events.terrainResourceCount() == 0 && events.blockLightResourceCount() == 0
                && events.cameraMediumResourceCount() == 0 && events.cameraMediumHandle() == 0
                && events.blockLightCount() == 0 && events.terrainWaterVertexCount() == 0
                && events.terrainLavaVertexCount() == 0 && events.terrainClearGlassVertexCount() == 0
                && events.terrainStainedGlassVertexCount() == 0;
    }

    private boolean objectsReleased() {
        return events.capturedTileParts() == 0 && events.entityResourceCount() == 0
                && events.vboCacheBytes() == 0 && events.itemResourceCount() == 0 && events.effectResourceCount() == 0
                && events.cloudResourceCount() == 0;
    }

    private void beginSkyPhase(SkyPhase phase) {
        skyPhase = phase;
        skyUpdateBaseline = events.skyTextureUpdates();
        transition(State.SKY_APPLY);
        skyStep = fixtureServer.callFromMainThread(() -> {
            if (!saveName.equals(fixtureServer.getFolderName())
                    || fixtureServer.getWorld(0) != fixtureServerWorld) {
                throw new IllegalStateException("Unexpected fixture world during sky probe");
            }
            FixtureEnvironment.setSky(fixtureServerWorld, phase.time, phase.rain);
            return null;
        });
        log("SKY_STARTED", "phase=" + phase.name() + " time=" + phase.time + " rain=" + phase.rain);
    }

    private void captureSkyPhase() throws Exception {
        if (!fixtureReady()) return;
        holdPose();
        if (events.failureMessage() != null || !events.isEnabled()) {
            fail("sky_renderer_unavailable");
            return;
        }
        if (!canRender()) return;
        long frames = events.renderedFrames();
        if (frames < lastFrames) {
            fail("sky_frame_counter_reset");
            return;
        }
        lastFrames = frames;
        if (++ticks < SKY_SETTLE_TICKS || frames - frameBaseline < REENABLE_NATIVE_FRAMES) return;
        if (events.skyResourceCount() == 0) {
            fail("sky_resources_missing");
            return;
        }
        if (skyPhase != SkyPhase.DAY && events.skyTextureUpdates() <= skyUpdateBaseline) {
            fail("sky_texture_not_updated");
            return;
        }
        BufferedImage screenshot = readScreenshot();
        FixtureSkyImage.Result image = FixtureSkyImage.evaluate(screenshot);
        String file = capture("sky-" + skyPhase.name().toLowerCase(java.util.Locale.ROOT), screenshot);
        if (skyPhase == SkyPhase.DAY) {
            if (!image.isVisible()) {
                log("SKY_IMAGE_FAIL", "phase=DAY file=" + file + " " + image.describe());
                fail("day_sky_not_visible");
                return;
            }
            daySkyImage = image;
        } else if ((skyPhase == SkyPhase.NIGHT || skyPhase == SkyPhase.RAIN)
                && image.difference(daySkyImage) < 8.0) {
            log("SKY_IMAGE_FAIL", "phase=" + skyPhase.name() + " file=" + file + " " + image.describe());
            fail("sky_did_not_change_" + skyPhase.name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        log("SKY_CAPTURE_PASS", "phase=" + skyPhase.name() + " file=" + file
                + " frames=" + (frames - frameBaseline) + " resources=" + events.skyResourceCount()
                + " worldTime=" + mc.world.getWorldTime() + " rain=" + mc.world.getRainStrength(1.0f)
                + " textureUpdates=" + events.skyTextureUpdates() + " " + image.describe()
                + " dayDifference=" + image.difference(daySkyImage));
        if (skyPhase == SkyPhase.RAIN) beginRuleRestoration(true, null);
        else beginSkyPhase(SkyPhase.values()[skyPhase.ordinal() + 1]);
    }

    private void beginFixtureDisconnect() {
        requireOwnedClientWorld();
        transition(State.DISCONNECT);
        log("WORLD_UNLOAD", "world=" + saveName + " rendererEnabled=" + events.isEnabled());
        final IntegratedServer server = fixtureServer;
        final WorldServer world = fixtureServerWorld;
        // Shutdown must begin on the server thread: closing the host's channel first
        // can stop that thread while client loadWorld is enqueueing its shutdown task.
        serverShutdown = server.callFromMainThread(() -> {
            if (!server.isCallingFromMinecraftThread() || !saveName.equals(server.getFolderName())
                    || server.getWorld(0) != world) {
                throw new IllegalStateException("Unexpected fixture server during shutdown");
            }
            server.initiateShutdown();
            return null;
        });
    }

    private void awaitFixtureDisconnect() throws Exception {
        if (!serverShutdown.isDone()) return;
        serverShutdown.get();
        if (restoreForSuccess && (events.failureMessage() != null || !events.isEnabled())) {
            restoreForSuccess = false;
            pendingFailure = "reason=renderer_failed_during_disconnect phase=DISCONNECT";
        }
        if (mc.world != null && mc.world != fixtureClientWorld) {
            throw new IllegalStateException("Unexpected client world during fixture disconnect");
        }
        // Server teardown disconnects its host. Let normal client networking drain
        // queued world packets and unload, while its handler still has a live world.
        if (mc.world != null || mc.getIntegratedServer() != null || mc.getConnection() != null) return;
        lastFrames = events.renderedFrames();
        transition(State.UNLOAD);
        mc.displayGuiScreen(new GuiMainMenu());
        if (!restoreForSuccess) finish(false, pendingFailure);
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
        boolean skyProbe = state == State.SKY_APPLY || state == State.SKY_CAPTURE;
        float pitch = skyProbe ? (skyPhase == SkyPhase.SUNSET ? -10f : -65f) : pose.pitch;
        float yaw = skyProbe ? 90f : pose.yaw;
        mc.player.setPositionAndRotation(pose.x, pose.y, pose.z, yaw, pitch);
        mc.player.prevPosX = mc.player.lastTickPosX = pose.x;
        mc.player.prevPosY = mc.player.lastTickPosY = pose.y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = pose.z;
        mc.player.prevRotationYaw = mc.player.rotationYawHead = mc.player.prevRotationYawHead = yaw;
        mc.player.prevRotationPitch = pitch;
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
        imageReadiness = FixtureImageReadiness.evaluate(readScreenshot());
        if (firstProbe || imageReadiness.isReady()) {
            log(imageReadiness.isReady() ? "IMAGE_READY" : "IMAGE_WAIT",
                    "phase=" + state.name() + " frames=" + (frames - frameBaseline)
                            + " " + imageReadiness.describe());
        }
        return imageReadiness.isReady();
    }

    private BufferedImage readScreenshot() {
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int unpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        try {
            return ScreenShotHelper.createScreenshot(mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        } finally {
            // The screenshot helper changes all three; keep Minecraft's texture cache in sync.
            GlStateManager.bindTexture(texture);
            GlStateManager.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment);
            GlStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment);
        }
    }

    private String capture(String renderer) throws Exception {
        return capture(renderer, readScreenshot());
    }

    private String capture(String renderer, BufferedImage image) throws Exception {
        String name = "rlcraft-remix-smoke-" + id + "-" + renderer + ".png";
        File file = new File(new File(mc.gameDir, "screenshots"), name);
        if (file.exists()) throw new IllegalStateException("Screenshot filename already exists");
        Files.createDirectories(file.toPath().getParent());
        if (!ImageIO.write(image, "png", file)) throw new IllegalStateException("PNG writer is unavailable");
        if (!file.isFile() || file.length() == 0) throw new IllegalStateException("Screenshot was not written");
        return name;
    }

    private long timeoutForState() {
        if (state == State.SOAK) return TimeUnit.SECONDS.toNanos(FixtureSoak.seconds() + 600);
        if (state == State.MULTIPLAYER) return NATIVE_TIMEOUT * 4;
        if (state == State.RESTORE_MULTIPLAYER || state == State.RESTORE_SURVIVAL) return SETTLE_TIMEOUT;
        // Terrain owns a bounded deadline for each independent readiness phase.
        if (state == State.TERRAIN || state == State.MATERIALS || state == State.GLASS
                || state == State.OBJECTS || state == State.EFFECTS || state == State.COMBAT
                || state == State.CLOUDS || state == State.PBR || state == State.FIRSTPERSON || state == State.HELD_LIGHTS || state == State.FOLIAGE
                || state == State.TILES || state == State.WINDOW || state == State.GLINT
                || state == State.COMPATIBILITY || state == State.SURVIVAL || state == State.DIMENSIONS || state == State.RESTORE_DIMENSIONS
                || state == State.TERRAIN_OPTIMIZATION)
            return NATIVE_TIMEOUT * 10;
        if (state == State.REMIX || state == State.REENABLE || state == State.SKY_CAPTURE) return NATIVE_TIMEOUT;
        if (state == State.SETTLE || state == State.VANILLA || state == State.RESTORE_RULES || state == State.DISCONNECT
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
        if (state == State.MULTIPLAYER && multiplayerFixture != null) {
            pendingFailure = "reason=" + reason + " multiplayer=true " + multiplayerFixture.status();
            multiplayerFixture.close(); transition(State.RESTORE_MULTIPLAYER); return;
        }
        if (state == State.RESTORE_RULES || state == State.DISCONNECT || state == State.RESTORE_DIMENSIONS || state == State.RESTORE_SURVIVAL
                || state == State.RESTORE_MULTIPLAYER) {
            // A failed cleanup must not close another world or exit while the fixture
            // could still be restoring settings or saving on the server thread.
            finishNow(false, (pendingFailure == null ? "reason=fixture_cleanup_failed" : pendingFailure)
                    + " phase=" + state.name()
                    + (state == State.RESTORE_SURVIVAL && survivalFixture != null ? " " + survivalFixture.status() : "")
                    + " cleanup=" + reason + " rulesRestored=" + rulesRestored + " exitSuppressed=true", false);
            return;
        }
        finish(false, "reason=" + reason + " phase=" + state.name()
                + " frames=" + Math.max(0L, lastFrames - frameBaseline)
                + (includeEntities && pose != null ? " " + entityCaptureSummary() : "")
                + (terrainFixture == null ? "" : " " + terrainFixture.status())
                + (materialFixture == null ? "" : " " + materialFixture.status())
                + (glassFixture == null ? "" : " " + glassFixture.status())
                + (effectFixture == null ? "" : " " + effectFixture.status())
                + (combatFixture == null ? "" : " " + combatFixture.status())
                + (cloudFixture == null ? "" : " " + cloudFixture.status())
                + (pbrFixture == null ? "" : " " + pbrFixture.status())
                + (firstPersonFixture == null ? "" : " " + firstPersonFixture.status())
                + (tileFixture == null ? "" : " " + tileFixture.status())
                + (heldLightsFixture == null ? "" : " " + heldLightsFixture.status())
                + (glintFixture == null ? "" : " " + glintFixture.status())
                + (foliageFixture == null ? "" : " " + foliageFixture.status())
                + (windowFixture == null ? "" : " " + windowFixture.status())
                + (compatibilityFixture == null ? "" : " " + compatibilityFixture.status())
                + (survivalFixture == null ? "" : " " + survivalFixture.status())
                + (dimensionsFixture == null ? "" : " " + dimensionsFixture.status())
                + (optimizationFixture == null ? "" : " " + optimizationFixture.status())
                + (soakFixture == null ? "" : " " + soakFixture.status())
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
        closeRenderFixtures();
        events.setEnabled(false);
        if (settingsSaved) {
            mc.gameSettings.pauseOnLostFocus = previousPauseOnLostFocus;
            mc.gameSettings.thirdPersonView = previousThirdPerson;
            if (includeTerrain) {
                FixtureTerrain.setRadius(mc, previousRenderDistance);
                log("TERRAIN_SETTINGS_RESTORED", "renderDistance=" + mc.gameSettings.renderDistanceChunks);
            }
            settingsSaved = false;
        }
        log(success ? "PASS" : "FAIL", fields);
        if (rulesRestored) {
            setup = null;
            entityStep = null;
            ruleRestoration = null;
            skyStep = null;
            serverShutdown = null;
            terrainFixture = null;
            materialFixture = null;
            materialLayout = null;
            glassFixture = null;
            glassLayout = null;
            objectFixture = null;
            objectLayout = null;
            effectFixture = null;
            effectLayout = null;
            combatFixture = null;
            combatLayout = null;
            cloudFixture = null;
            pbrFixture = null;
            pbrLayout = null;
            firstPersonFixture = null;
            firstPersonLayout = null;
            tileFixture = null;
            tileLayout = null;
            heldLightsFixture = null; heldLightsLayout = null;
            glintFixture = null; glintLayout = null;
            foliageFixture = null; foliageLayout = null;
            windowFixture = null;
            compatibilityFixture = null; compatibilityLayout = null; dimensionsFixture = null;
            survivalFixture = null; survivalLayout = null;
            optimizationFixture = null;
            soakFixture = null;
            clientRules = null;
            serverRules = null;
            clientEnvironment = null;
            serverEnvironment = null;
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

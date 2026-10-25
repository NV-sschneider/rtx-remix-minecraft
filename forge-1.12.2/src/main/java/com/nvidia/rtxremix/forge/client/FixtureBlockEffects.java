/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.effect.EffectCaptureHooks;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Real FBP debris/placement and vanilla crack decals in a controller-owned smoke arena. */
final class FixtureBlockEffects implements AutoCloseable {
    private static final String DIGGING = "com.TominoCZ.FBP.particle.FBPParticleDigging";
    private static final String PLACEMENT = "com.TominoCZ.FBP.particle.FBPParticleBlock";
    private static final int BREAKER = Integer.MIN_VALUE + 7290;
    private enum Phase { SETUP, ARRIVE, BASELINE, BASELINE_STABLE, CRACK_EARLY, CRACK_LATE, CRACK_CLEAR,
        DEBRIS_BACKGROUND, DEBRIS_BASELINE, DEBRIS_STABLE, PLANT, STONE, HIT, DEBRIS_FADE, DEBRIS_REMOVE,
        PLACE_STONE_READY, PLACE_STONE, PLACE_STONE_REST, PLACE_GRASS_READY, PLACE_GRASS, PLACE_GRASS_REST, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String save;
    private final Layout layout;
    private final FixtureTerrain.Output output;
    private final UUID playerId;
    private final int originalView, originalParticles;
    private final boolean originalHud, originalFlying, originalBobbing;
    private final float originalFov;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private Constructor<?> placementConstructor;
    private Method killPlacement;
    private Field particleAge, placementHeight, particleQueue, particleAlpha, previousDebrisAlpha;
    private final List<Particle> debris = new ArrayList<>(24);
    private Phase phase = Phase.SETUP;
    private Future<?> action;
    private Particle placement;
    private BufferedImage firstPoseImage;
    private BufferedImage crackBaseline;
    private CrackImageObservation crackObservation;
    private BufferedImage debrisBaseline, debrisEmptyImage, debrisFirstImage, debrisLastImage, debrisFadeImage;
    private DebrisImageObservation debrisObservation;
    private DebrisImageObservation.Result debrisResult;
    private Phase debrisKind = Phase.PLANT;
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private float projectionX, projectionY;
    private boolean registered;
    private BlockEffectObservation observation;
    private long since = System.nanoTime(), progress = since, readyFrame = -1, lastFrame = -1, damageHash, debrisHash;
    private int debrisSamples, bursts, attempts, lastBurstTick = Integer.MIN_VALUE;
    private int renderedFadingDebris;
    private double renderedAlphaMin = 1, renderedAlphaMax = 1;
    private Throwable alphaObservationFailure;
    private boolean shaded;
    private int lastAge = -1;
    private float lastHeight;
    private boolean placementCleaned, cleanupQueued, closed, smoothLighting, clientConfigured;
    // Only server tasks read/write these fields; cleanup is ordered after the setup task.
    private boolean serverConfigured, serverFlying;
    private final IBlockState[] backdropOriginal = new IBlockState[28];
    private int backdropSaved;
    private final IBlockState[] shadeOriginal = new IBlockState[153];
    private int shadeSaved;

    FixtureBlockEffects(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        FixtureCompatibility.requireSmokeSave(save);
        if (mc.getIntegratedServer() != server || !save.equals(server.getFolderName()) || mc.world == null || mc.player == null)
            throw new IllegalStateException("Block-effect fixture requires its current integrated smoke world");
        this.mc = mc; this.events = events; this.server = server; this.world = world; this.save = save;
        this.layout = layout; this.output = output; clientWorld = mc.world; playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        originalView = mc.gameSettings.thirdPersonView; originalHud = mc.gameSettings.hideGUI;
        originalParticles = mc.gameSettings.particleSetting; originalFlying = mc.player.capabilities.isFlying;
        originalFov = mc.gameSettings.fovSetting; originalBobbing = mc.gameSettings.viewBobbing;
    }

    private void setup() throws ReflectiveOperationException {
        Class<?> fbp = Class.forName("com.TominoCZ.FBP.FBP");
        if (!(Boolean) fbp.getMethod("isEnabled").invoke(null) || !fbp.getField("fancyPlaceAnim").getBoolean(null)
                || fbp.getField("frozen").getBoolean(null)
                || !mc.effectRenderer.getClass().getName().equals("com.TominoCZ.FBP.particle.FBPParticleManager"))
            throw new IllegalStateException("Block-effect fixture requires enabled, unfrozen FBP placement and its actual particle manager");
        smoothLighting = fbp.getField("animSmoothLighting").getBoolean(null);
        Class<?> placementType = Class.forName(PLACEMENT);
        placementConstructor = placementType.getConstructor(World.class, double.class, double.class, double.class, IBlockState.class, long.class);
        killPlacement = placementType.getMethod("killParticle");
        particleAge = ReflectionHelper.findField(Particle.class, "particleAge", "field_70546_d");
        particleQueue = ReflectionHelper.findField(ParticleManager.class, "queue", "field_187241_h");
        particleAlpha = ReflectionHelper.findField(Particle.class, "particleAlpha", "field_82339_as");
        previousDebrisAlpha = ReflectionHelper.findField(Class.forName(DIGGING), "prevParticleAlpha");
        // ClientTick END follows the next particle update, while the screenshot
        // and native counters describe the preceding rendered pose.
        placementHeight = ReflectionHelper.findField(placementType, "prevHeight");
        clientConfigured = true;
        mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = true; mc.gameSettings.particleSetting = 0;
        mc.gameSettings.fovSetting = 70; mc.gameSettings.viewBobbing = false;
        MinecraftForge.EVENT_BUS.register(this); registered = true;
        action = server.callFromMainThread(() -> {
            requireWorld(); EntityPlayerMP player = player();
            serverFlying = player.capabilities.isFlying; serverConfigured = true;
            player.capabilities.isFlying = true; player.sendPlayerAbilities();
            player.connection.setPlayerLocation(cameraX(), cameraY(), cameraZ(), 180, 18);
            return null;
        });
        log("STARTED");
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        FixtureCompatibility.requireSmokeSave(world.getMinecraftServer().getFolderName());
        if (!world.getMinecraftServer().isCallingFromMinecraftThread() || world.provider.getDimension() != 0 || y < 1 || y > 230)
            throw new IllegalStateException("Block-effect arena requires its owned overworld server thread and bounded height");
        Layout layout = new Layout(x, y, z);
        for (int dx = -8; dx <= 8; dx++) for (int dz = 0; dz <= 14; dz++) {
            world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.GRASS.getDefaultState(), 3);
            int top = Math.min(255, Math.max(y + 12, world.getHeight(new BlockPos(x + dx, y, z + dz)).getY()));
            for (int yy = y + 1; yy <= top; yy++) world.setBlockToAir(new BlockPos(x + dx, yy, z + dz));
        }
        world.setBlockState(layout.dark, Blocks.OBSIDIAN.getDefaultState(), 3);
        world.setBlockState(layout.bright, Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
        world.setBlockState(layout.plant, plantState(), 3);
        world.setBlockState(layout.stone, Blocks.STONE.getDefaultState(), 3);
        world.setBlockState(layout.grass, Blocks.GRASS.getDefaultState(), 3);
        return layout;
    }

    boolean tick() throws Exception {
        if (!ownsClient() || !events.isEnabled() || events.failureMessage() != null)
            throw new IllegalStateException("Block-effect fixture renderer/world unavailable");
        if (alphaObservationFailure != null) throw new IllegalStateException("Owned debris alpha observation failed", alphaObservationFailure);
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(60)) {
            capture("block-effects-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Block-effect fixture timed out: " + status());
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(5)) { log("PROGRESS"); progress = System.nanoTime(); }
        if (phase != Phase.SETUP && phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        switch (phase) {
            case SETUP:
                setup(); transition(Phase.ARRIVE);
                break;
            case ARRIVE:
                if (complete()) transition(Phase.BASELINE);
                break;
            case BASELINE:
                if (settled(arenaReady() && events.terrainPendingSectionCount() == 0 && events.terrainRetirementCount() == 0, 20)) {
                    crackBaseline = output.image();
                    output.capture("block-effects-baseline-obsidian-quartz-plant-stone-grass", crackBaseline);
                    transition(Phase.BASELINE_STABLE);
                }
                break;
            case BASELINE_STABLE:
                if (settled(projectionX > 0 && projectionY > 0, 20)) {
                    BufferedImage stable = output.image();
                    crackObservation = new CrackImageObservation(crackBaseline, stable,
                            face(layout.bright, stable), face(layout.stone, stable));
                    crackBaseline = null;
                    cracks(1); transition(Phase.CRACK_EARLY);
                }
                break;
            case CRACK_EARLY:
                if (settled(events.damageQuads() >= 2, 20)) {
                    checkCracks("block-effects-cracks-stage-1-obsidian-quartz", false); damageHash = events.damageGeometryHash();
                    cracks(8); transition(Phase.CRACK_LATE);
                }
                break;
            case CRACK_LATE:
                if (settled(events.damageQuads() >= 2 && events.damageGeometryHash() != damageHash, 20)) {
                    checkCracks("block-effects-cracks-stage-8-obsidian-quartz", true); log("CRACKS_PASS");
                    cracks(-1); transition(Phase.CRACK_CLEAR);
                }
                break;
            case CRACK_CLEAR:
                if (settled(events.damageQuads() == 0, 20)) {
                    BufferedImage image = output.image(); output.capture("block-effects-cracks-cleared", image);
                    if (!crackObservation.restored(image)) throw new IllegalStateException("Cancelled cracks did not restore the block image");
                    crackObservation = null; log("CRACKS_CLEAR_PASS");
                    action = server.callFromMainThread(() -> { requireWorld(); installBackdrop(); return null; });
                    transition(Phase.DEBRIS_BACKGROUND);
                }
                break;
            case DEBRIS_BACKGROUND:
                if (complete() && settled(backdropReady(), 20)) transition(Phase.DEBRIS_BASELINE);
                break;
            case DEBRIS_BASELINE:
                if (settled(backdropReady(), 20)) {
                    debrisBaseline = output.image(); transition(Phase.DEBRIS_STABLE);
                }
                break;
            case DEBRIS_STABLE:
                if (settled(backdropReady(), 20)) {
                    BufferedImage noise = output.image();
                    double eye = cameraY() + mc.player.getEyeHeight();
                    int[] target = DebrisImageObservation.box(noise.getWidth(), noise.getHeight(), -.85, .85,
                            layout.y + 1 - eye, layout.y + 3.1 - eye, 1.9, 2.5, projectionX, projectionY);
                    int[] control = DebrisImageObservation.box(noise.getWidth(), noise.getHeight(), 1.7, 2.2,
                            layout.y + 2.15 - eye, layout.y + 2.75 - eye, 2.49, 2.49, projectionX, projectionY);
                    debrisObservation = new DebrisImageObservation(debrisBaseline, noise, target, control, debrisKind == Phase.PLANT, shaded);
                    debrisEmptyImage = shaded ? noise : null;
                    debrisBaseline = null; debrisFirstImage = debrisLastImage = debrisFadeImage = null; debrisResult = null;
                    transition(debrisKind); seedDebris();
                }
                break;
            case PLANT:
            case STONE:
            case HIT:
                observeDebris();
                if (debrisObservation.passed()) {
                    if (shaded) { transition(Phase.DEBRIS_FADE); lastFrame = events.renderedFrames(); }
                    else finishDebris();
                } else if (mc.player.ticksExisted - lastBurstTick >= 10 && liveDebris() == 0) {
                    if (bursts >= 3) {
                        if (debrisLastImage != null) output.capture("block-effects-fbp-" + (shaded ? "shade-" : "")
                                + phase.name().toLowerCase(Locale.ROOT) + "-debris-failed", debrisLastImage);
                        throw new IllegalStateException("FBP debris lacked three visibly lit animated frames: " + status());
                    }
                    seedDebris();
                }
                break;
            case DEBRIS_FADE:
                // Keep the authored lifetime, gravity, scale and alpha. Delaying
                // PNG writes keeps short late-life poses available to rendering.
                if (renderedFadingDebris > 0 && events.renderedFrames() > lastFrame) {
                    debrisFadeImage = output.image(); log("DEBRIS_NATURAL_FADE_OBSERVED"); finishDebris();
                } else if (liveDebris() == 0) {
                    log("DEBRIS_NATURAL_FADE_NOT_OBSERVED"); finishDebris();
                }
                break;
            case DEBRIS_REMOVE:
                if (complete() && settled(arenaReady() && clientWorld.isAirBlock(layout.hitSource())
                        && events.terrainPendingSectionCount() == 0 && events.terrainRetirementCount() == 0, 20)) {
                    attempts = 0; transition(Phase.PLACE_STONE_READY);
                }
                break;
            case PLACE_STONE_READY:
            case PLACE_GRASS_READY:
                boolean nextGrass = phase == Phase.PLACE_GRASS_READY;
                BlockPos nextPos = nextGrass ? layout.grass : layout.stone;
                IBlockState nextState = nextGrass ? Blocks.GRASS.getDefaultState() : Blocks.STONE.getDefaultState();
                // PNG encoding may consume the entire ten-tick animation lifetime.
                // Drain catch-up ticks through actual rendered frames before spawning.
                if (complete() && settled(clientWorld.getBlockState(nextPos).getBlock() == nextState.getBlock()
                        && events.terrainSectionReady(nextPos.getX() >> 4, nextPos.getY() >> 4, nextPos.getZ() >> 4), 2)) {
                    transition(nextGrass ? Phase.PLACE_GRASS : Phase.PLACE_STONE);
                    spawnPlacement(nextState, nextPos);
                }
                break;
            case PLACE_STONE:
            case PLACE_GRASS:
                boolean grass = phase == Phase.PLACE_GRASS;
                observePlacement();
                if (observation.passed()) {
                    BufferedImage secondPose = output.image();
                    String name = grass ? "block-effects-fbp-placed-tinted-grass" : "block-effects-fbp-placed-stone";
                    output.capture(name + "-first-pose", firstPoseImage);
                    output.capture(name + "-intermediate", secondPose);
                    firstPoseImage = null;
                    log(grass ? "GRASS_PLACEMENT_PASS" : "STONE_PLACEMENT_PASS");
                    transition(grass ? Phase.PLACE_GRASS_REST : Phase.PLACE_STONE_REST);
                } else if (!placement.isAlive()) {
                    if (attempts >= 3) throw new IllegalStateException("FBP placement expired without two native intermediate poses: " + status());
                    finishPlacement();
                    firstPoseImage = null;
                    action = resendPlacement(grass ? layout.grass : layout.stone, grass ? Blocks.GRASS : Blocks.STONE);
                    transition(grass ? Phase.PLACE_GRASS_READY : Phase.PLACE_STONE_READY);
                }
                break;
            case PLACE_STONE_REST:
            case PLACE_GRASS_REST:
                boolean last = phase == Phase.PLACE_GRASS_REST;
                BlockPos pos = last ? layout.grass : layout.stone;
                Block block = last ? Blocks.GRASS : Blocks.STONE;
                if (!placement.isAlive()) {
                    if (!placementCleaned) { finishPlacement(); action = resendPlacement(pos, block); }
                    if (complete() && settled(clientWorld.getBlockState(pos).getBlock() == block
                            && events.terrainSectionReady(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4), 10)) {
                        capture(last ? "block-effects-fbp-tinted-grass-restored" : "block-effects-fbp-stone-restored");
                        if (!last) { attempts = 0; transition(Phase.PLACE_GRASS_READY); }
                        else {
                            restoreClient(); action = restoreServer(); cleanupQueued = true; transition(Phase.RETURN);
                        }
                    }
                }
                break;
            case RETURN:
                if (complete()) { transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private void seedDebris() throws ReflectiveOperationException {
        clearDebris(); lastBurstTick = mc.player.ticksExisted; bursts++;
        if (phase == Phase.HIT) seedHitDebris();
        else seedBreakingDebris(phase == Phase.PLANT ? plantState() : Blocks.STONE.getDefaultState());
        lastFrame = events.renderedFrames(); debrisHash = events.particleGeometryHash();
    }

    private void finishDebris() throws Exception {
        String kind = (shaded ? "shade-" : "") + debrisKind.name().toLowerCase(Locale.ROOT);
        if (debrisEmptyImage != null) output.capture("block-effects-fbp-" + kind + "-debris-empty", debrisEmptyImage);
        output.capture("block-effects-fbp-" + kind + "-debris-first-lit", debrisFirstImage);
        output.capture("block-effects-fbp-" + kind + "-debris-last-lit", debrisLastImage);
        if (debrisFadeImage != null) output.capture("block-effects-fbp-" + kind + "-debris-natural-fade", debrisFadeImage);
        log((shaded ? "SHADE_" : "") + debrisKind.name() + "_DEBRIS_PIXELS_PASS"); clearDebris();
        debrisEmptyImage = debrisFirstImage = debrisLastImage = debrisFadeImage = null; debrisObservation = null;
        if (debrisKind != Phase.HIT) {
            debrisKind = debrisKind == Phase.PLANT ? Phase.STONE : Phase.HIT;
            transition(Phase.DEBRIS_BASELINE);
        } else if (!shaded) {
            shaded = true; debrisKind = Phase.PLANT;
            action = server.callFromMainThread(() -> { requireWorld(); installShade(); return null; });
            transition(Phase.DEBRIS_BACKGROUND);
        } else {
            action = server.callFromMainThread(() -> { requireWorld(); restoreShade(); restoreBackdrop(); return null; });
            transition(Phase.DEBRIS_REMOVE);
        }
    }

    private void seedBreakingDebris(IBlockState state) {
        for (int i = 0; i < 18; i++) {
            Particle particle = mc.effectRenderer.spawnEffectParticle(EnumParticleTypes.BLOCK_CRACK.getParticleID(),
                    layout.x + .2 + i % 3 * .3, layout.y + 1.4 + i / 9 * .3, layout.z + 9.15 + i / 3 % 3 * .1,
                    (i % 3 - 1) * .025, .04, (i / 3 % 3 - 1) * .025, Block.getStateId(state));
            if (particle == null || !DIGGING.equals(particle.getClass().getName())) {
                if (particle != null) particle.setExpired();
                throw new IllegalStateException("Installed particle manager did not return real FBP debris for " + state.getBlock().getRegistryName());
            }
            debris.add(particle);
        }
    }

    @SuppressWarnings("unchecked")
    private void seedHitDebris() throws IllegalAccessException {
        Queue<Particle> queue = (Queue<Particle>) particleQueue.get(mc.effectRenderer);
        if (queue.size() > 1024) throw new IllegalStateException("Particle queue is too busy for owned hit observation");
        Set<Particle> before = Collections.newSetFromMap(new IdentityHashMap<Particle, Boolean>()); before.addAll(queue);
        RayTraceResult original = mc.objectMouseOver; BlockPos source = layout.hitSource();
        try {
            // FBP's smart hit path reads this ordinary Minecraft hit result.
            mc.objectMouseOver = new RayTraceResult(new Vec3d(source.getX() + .5, source.getY() + .5,
                    source.getZ() + 1), EnumFacing.SOUTH, source);
            for (int i = 0; i < 12; i++) mc.effectRenderer.addBlockHitEffects(source, EnumFacing.SOUTH);
        } finally {
            mc.objectMouseOver = original;
            for (Particle particle : queue) if (!before.contains(particle)) debris.add(particle);
        }
        if (debris.isEmpty()) throw new IllegalStateException("Installed manager emitted no block-hit debris");
        for (Particle particle : debris) if (!DIGGING.equals(particle.getClass().getName()))
            throw new IllegalStateException("Block-hit path emitted unsupported debris " + particle.getClass().getName());
    }

    private void observeDebris() throws Exception {
        long frame = events.renderedFrames(), hash = events.particleGeometryHash();
        if (frame <= lastFrame || liveDebris() == 0 || events.particleQuads() < 6 || hash == debrisHash) return;
        lastFrame = frame; debrisHash = hash; debrisSamples++;
        BufferedImage image = output.image(); debrisResult = debrisObservation.sample(image, frame, hash);
        debrisLastImage = image;
        if (debrisResult.visible && debrisFirstImage == null) debrisFirstImage = image;
        if (debrisSamples <= 3 || debrisResult.visible) output.log("BLOCK_EFFECTS_DEBRIS_PIXELS",
                "kind=" + phase + " shaded=" + shaded + " burst=" + bursts + " " + debrisResult.describe());
    }

    private void installBackdrop() {
        for (int i = 0; i < backdropOriginal.length; i++) {
            BlockPos pos = layout.backdrop(i); backdropOriginal[i] = world.getBlockState(pos); backdropSaved = i + 1;
            world.setBlockState(pos, pos.equals(layout.hitSource()) ? Blocks.STONE.getDefaultState() : Blocks.OBSIDIAN.getDefaultState(), 3);
        }
    }
    private void restoreBackdrop() {
        for (int i = 0; i < backdropSaved; i++) world.setBlockState(layout.backdrop(i), backdropOriginal[i], 3);
        backdropSaved = 0;
    }
    private void installShade() {
        for (int i = 0; i < shadeOriginal.length; i++) {
            BlockPos pos = layout.shade(i); shadeOriginal[i] = world.getBlockState(pos); shadeSaved = i + 1;
            world.setBlockState(pos, Blocks.STONE.getDefaultState(), 3);
        }
        // This block already belongs to the snapshotted backdrop transaction.
        world.setBlockState(layout.shadeReference(), Blocks.STONE.getDefaultState(), 3);
    }
    private void restoreShade() {
        for (int i = 0; i < shadeSaved; i++) world.setBlockState(layout.shade(i), shadeOriginal[i], 3);
        shadeSaved = 0;
    }
    private boolean backdropReady() {
        for (int i = 0; i < backdropOriginal.length; i++) {
            BlockPos pos = layout.backdrop(i);
            if (clientWorld.getBlockState(pos).getBlock() != (pos.equals(layout.hitSource())
                    || shaded && pos.equals(layout.shadeReference()) ? Blocks.STONE : Blocks.OBSIDIAN)
                    || !events.terrainSectionReady(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4)) return false;
        }
        if (shaded) {
            for (int i = 0; i < shadeOriginal.length; i++) {
                BlockPos pos = layout.shade(i);
                if (clientWorld.getBlockState(pos).getBlock() != Blocks.STONE
                        || !events.terrainSectionReady(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4)) return false;
            }
            if (clientWorld.canSeeSky(new BlockPos(layout.x, layout.y + 2, layout.z + 9))) return false;
        }
        return projectionX > 0 && projectionY > 0 && events.terrainPendingSectionCount() == 0 && events.terrainRetirementCount() == 0;
    }

    private void spawnPlacement(IBlockState state, BlockPos pos) throws ReflectiveOperationException {
        if (clientWorld.getBlockState(pos).getBlock() != state.getBlock())
            throw new IllegalStateException("Placement source block differs at " + pos + ": " + clientWorld.getBlockState(pos));
        placement = (Particle) placementConstructor.newInstance(clientWorld,
                pos.getX() + .5, pos.getY(), pos.getZ() + .5, state, 7290L);
        mc.effectRenderer.addEffect(placement);
        observation = new BlockEffectObservation(events.renderedFrames(), EffectCaptureHooks.placedOpaqueCutoutQuadTotal()); attempts++;
        firstPoseImage = null; placementCleaned = false; lastAge = -1; lastHeight = 0;
    }

    private void observePlacement() throws Exception {
        lastAge = particleAge.getInt(placement); lastHeight = placementHeight.getFloat(placement);
        observation.sample(events.renderedFrames(), lastAge, placement.isAlive(), lastHeight,
                EffectCaptureHooks.placedOpaqueCutoutQuadTotal(), events.particleGeometryHash());
        // Keep only one uncompressed image while the short animation runs; writing
        // it here would advance the client clock before the second pose can render.
        if (firstPoseImage == null && observation.samples() > 0) firstPoseImage = output.image();
    }

    private void finishPlacement() throws ReflectiveOperationException {
        if (placement == null || placementCleaned) return;
        // FBP's setExpired override only removes an event entry. Its public cleanup
        // also retires the animation node, so use that method for our exact particle.
        killPlacement.invoke(placement);
        placementCleaned = true;
    }

    private boolean arenaReady() {
        for (BlockPos pos : new BlockPos[] {layout.dark, layout.bright, layout.plant, layout.stone, layout.grass})
            if (!events.terrainSectionReady(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4)) return false;
        return clientWorld.getBlockState(layout.dark).getBlock() == Blocks.OBSIDIAN
                && clientWorld.getBlockState(layout.bright).getBlock() == Blocks.QUARTZ_BLOCK
                && clientWorld.getBlockState(layout.plant).getBlock() == Blocks.TALLGRASS
                && clientWorld.getBlockState(layout.stone).getBlock() == Blocks.STONE
                && clientWorld.getBlockState(layout.grass).getBlock() == Blocks.GRASS;
    }
    private static IBlockState plantState() { return Blocks.TALLGRASS.getDefaultState().withProperty(BlockTallGrass.TYPE, BlockTallGrass.EnumType.GRASS); }
    private void cracks(int stage) { mc.renderGlobal.sendBlockBreakProgress(BREAKER, layout.dark, stage); mc.renderGlobal.sendBlockBreakProgress(BREAKER + 1, layout.bright, stage); }
    @SubscribeEvent public void frame(RenderWorldLastEvent event) {
        if (!registered || !ownsClient()) return;
        projection.clear(); GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        projectionX = projection.get(0); projectionY = projection.get(5);
        renderedFadingDebris = 0; renderedAlphaMin = 1; renderedAlphaMax = 0;
        try {
            for (Particle particle : debris) if (particle.isAlive()) {
                double previous = previousDebrisAlpha.getDouble(particle);
                double current = particleAlpha.getFloat(particle);
                double alpha = previous + (current - previous) * event.getPartialTicks();
                renderedAlphaMin = Math.min(renderedAlphaMin, alpha); renderedAlphaMax = Math.max(renderedAlphaMax, alpha);
                if (current < previous && alpha >= .05 && alpha <= .85) renderedFadingDebris++;
            }
        } catch (IllegalAccessException error) { alphaObservationFailure = error; }
    }
    private int[] face(BlockPos position, BufferedImage image) {
        return CrackImageObservation.face(image.getWidth(), image.getHeight(), position.getX() - cameraX(),
                position.getY() - cameraY() - mc.player.getEyeHeight(), cameraZ() - position.getZ() - 1,
                projectionX, projectionY);
    }
    private void checkCracks(String name, boolean late) throws Exception {
        BufferedImage image = output.image(); output.capture(name, image);
        CrackImageObservation.Result proof = crackObservation.stage(image, late);
        output.log("BLOCK_EFFECTS_CRACK_PIXELS", "late=" + late + " " + proof.describe());
        if (!proof.visible) throw new IllegalStateException("Mining stage is not visibly readable: " + proof.describe());
    }
    private int liveDebris() { int count = 0; for (Particle particle : debris) if (particle.isAlive()) count++; return count; }
    private void clearDebris() { for (Particle particle : debris) particle.setExpired(); debris.clear(); }
    private boolean ownsClient() { return mc.getIntegratedServer() == server && mc.world == clientWorld && mc.player != null && playerId.equals(mc.player.getUniqueID()); }
    private double cameraX() { return layout.x + .5; }
    private double cameraY() { return layout.y + 1.2; }
    private double cameraZ() { return layout.z + 11.5; }
    private void holdPose() {
        mc.player.setPositionAndRotation(cameraX(), cameraY(), cameraZ(), 180, 18);
        mc.player.prevPosX = mc.player.lastTickPosX = cameraX(); mc.player.prevPosY = mc.player.lastTickPosY = cameraY();
        mc.player.prevPosZ = mc.player.lastTickPosZ = cameraZ(); mc.player.prevRotationYaw = 180; mc.player.prevRotationPitch = 18;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Block-effect fixture player unavailable"); return player;
    }
    private void requireWorld() {
        FixtureCompatibility.requireSmokeSave(save);
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Block-effect fixture no longer owns its server world");
    }
    private Future<?> resendPlacement(BlockPos pos, Block expected) {
        return server.callFromMainThread(() -> {
            requireWorld();
            IBlockState state = world.getBlockState(pos);
            if (state.getBlock() != expected) throw new IllegalStateException("Owned placement source changed at " + pos);
            // FBP's killParticle retires its node but can leave the client dummy
            // when no final animation frame rendered. Re-send the unchanged owner.
            world.notifyBlockUpdate(pos, state, state, 3);
            return null;
        });
    }
    private Future<?> restoreServer() {
        return server.callFromMainThread(() -> {
            requireWorld();
            restoreShade();
            restoreBackdrop();
            // Re-sending just the two owned animation blocks repairs a client-side
            // dummy left by interruption, without touching any unrelated block.
            world.notifyBlockUpdate(layout.stone, world.getBlockState(layout.stone), world.getBlockState(layout.stone), 3);
            world.notifyBlockUpdate(layout.grass, world.getBlockState(layout.grass), world.getBlockState(layout.grass), 3);
            if (serverConfigured) {
                EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
                if (player != null && player.world == world) {
                    player.capabilities.isFlying = serverFlying; player.sendPlayerAbilities();
                    player.connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch);
                }
                serverConfigured = false;
            }
            return null;
        });
    }
    private void restoreClient() {
        if (registered) { MinecraftForge.EVENT_BUS.unregister(this); registered = false; }
        if (!clientConfigured) return;
        mc.gameSettings.thirdPersonView = originalView; mc.gameSettings.hideGUI = originalHud;
        mc.gameSettings.particleSetting = originalParticles;
        mc.gameSettings.fovSetting = originalFov; mc.gameSettings.viewBobbing = originalBobbing;
        if (ownsClient()) mc.player.capabilities.isFlying = originalFlying;
        clientConfigured = false;
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready, int frames) {
        if (!ready) { readyFrame = -1; return false; }
        if (readyFrame < 0) readyFrame = events.renderedFrames(); return events.renderedFrames() - readyFrame >= frames;
    }
    private void transition(Phase next) {
        phase = next; since = progress = System.nanoTime(); readyFrame = lastFrame = -1;
        debrisSamples = bursts = 0; log("PHASE");
    }
    private void capture(String name) throws Exception { output.capture(name, output.image()); }
    private void log(String name) { output.log("BLOCK_EFFECTS_" + name, status()); }
    String status() {
        return "phase=" + phase + " smoothLighting=" + smoothLighting + " damageQuads=" + events.damageQuads()
                + " damageHash=" + events.damageGeometryHash() + " particleQuads=" + events.particleQuads()
                + " particleHash=" + events.particleGeometryHash() + " liveDebris=" + liveDebris()
                + " debrisSamples=" + debrisSamples + " bursts=" + bursts + " placementAttempts=" + attempts
                + " shaded=" + shaded + " fadingDebris=" + renderedFadingDebris
                + " alphaMin=" + renderedAlphaMin + " alphaMax=" + renderedAlphaMax
                + " debrisPixels={" + (debrisResult == null ? "not-sampled" : debrisResult.describe()) + "}"
                + " placementAlive=" + (placement != null && placement.isAlive()) + " age=" + lastAge + " height=" + lastHeight
                + " placementSamples=" + (observation == null ? 0 : observation.samples())
                + " correctedPlacementQuadTotal=" + EffectCaptureHooks.placedColorQuadTotal()
                + " opaqueCutoutPlacementQuadTotal=" + EffectCaptureHooks.placedOpaqueCutoutQuadTotal()
                + " debrisQuadTotal=" + EffectCaptureHooks.debrisQuadTotal()
                + " opaqueCutoutDebrisQuadTotal=" + EffectCaptureHooks.debrisOpaqueCutoutQuadTotal()
                + " fadedDebrisQuadTotal=" + EffectCaptureHooks.debrisFadedQuadTotal()
                + " stone=" + clientWorld.getBlockState(layout.stone).getBlock().getRegistryName()
                + " grass=" + clientWorld.getBlockState(layout.grass).getBlock().getRegistryName()
                + " pendingTerrain=" + events.terrainPendingSectionCount() + " skippedQuads=" + events.effectSkippedQuads();
    }
    @Override public void close() {
        if (closed) return;
        try {
            clearDebris();
            if (ownsClient() && clientConfigured) {
                cracks(-1);
                try { finishPlacement(); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException("Owned FBP animation cleanup failed", error); }
            }
        } finally {
            firstPoseImage = crackBaseline = debrisBaseline = debrisEmptyImage = debrisFirstImage = debrisLastImage = debrisFadeImage = null;
            crackObservation = null; debrisObservation = null;
            restoreClient();
            if (!cleanupQueued && action != null) { cleanupQueued = true; restoreServer(); }
            closed = true;
        }
    }

    static final class Layout {
        final int x, y, z;
        final BlockPos dark, bright, plant, stone, grass;
        Layout(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
            dark = new BlockPos(x - 2, y + 1, z + 2); bright = new BlockPos(x + 2, y + 1, z + 2);
            plant = new BlockPos(x - 2, y + 1, z + 5); stone = new BlockPos(x, y + 1, z + 5); grass = new BlockPos(x + 2, y + 1, z + 5);
        }
        BlockPos backdrop(int index) { return new BlockPos(x - 3 + index % 7, y + 1 + index / 7, z + 8); }
        BlockPos hitSource() { return new BlockPos(x, y + 2, z + 8); }
        BlockPos shadeReference() { return new BlockPos(x + 2, y + 2, z + 8); }
        BlockPos shade(int index) {
            if (index < 0 || index >= 153) throw new IndexOutOfBoundsException("Shade block " + index);
            if (index < 81) return new BlockPos(x - 4 + index % 9, y + 5, z + 6 + index / 9);
            int wall = index - 81;
            return new BlockPos(x + (wall < 36 ? -4 : 4), y + 1 + wall % 36 / 9, z + 6 + wall % 9);
        }
    }
}

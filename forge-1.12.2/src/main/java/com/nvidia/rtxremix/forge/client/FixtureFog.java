/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.fog.FogState;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.block.BlockLeaves;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

/** Owned Forge fog callbacks and two depth targets, measured separately under Remix and vanilla. */
public final class FixtureFog implements AutoCloseable {
    private enum Phase { SETUP, ARRIVE, NATIVE, VANILLA, EDGE_CLEAR, EDGE_LINEAR, EDGE_MOVE, EDGE_RESTABILIZE, EDGE_LAW_DRIFT, EDGE_LAW_FAST, EDGE_REMOVE, RESTORE, RETURN, DONE }
    private enum Preset {
        NONE(FogState.EXP, 0, 0, 1, 0.1f, 0.6f, 0.2f),
        LINEAR(FogState.LINEAR, 0, 10, 22, 0.1f, 0.6f, 0.2f),
        EXP(FogState.EXP, 0.05f, 0, 1, 0.1f, 0.6f, 0.2f),
        EXP2(FogState.EXP2, 0.05f, 0, 1, 0.1f, 0.6f, 0.2f),
        COLOR(FogState.EXP, 0.12f, 0, 1, 0.65f, 0.1f, 0.45f);
        final int mode;
        final float density, start, end, red, green, blue;
        Preset(int mode, float density, float start, float end, float red, float green, float blue) {
            this.mode = mode; this.density = density; this.start = start; this.end = end;
            this.red = red; this.green = green; this.blue = blue;
        }
    }
    private static final Preset[] CASES = Preset.values();
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
    private final boolean originalHud, originalFlying, originalBobbing, originalEnabled;
    private final float originalFov;
    private final boolean edgeDiagnostics = Boolean.getBoolean("rlcraftremix.smoke.fogEdges");
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final double[][][] clear = new double[2][2][];
    private final double[][][] linear = new double[2][2][];
    private Phase phase = Phase.SETUP;
    private Preset preset;
    private Future<?> action;
    private int caseIndex;
    private long since = System.nanoTime(), progress = since, stableSince, stableFrame = -1;
    private long worldFrames, densityCalls, colorCalls, densityBaseline, colorBaseline, appliedBaseline;
    private float projectionX, projectionY;
    private boolean radial, registered, configured, cleanupQueued, serverQueued, closed;
    // Server-thread-only fields; queued cleanup runs after setup even on an early client failure.
    private boolean serverConfigured, serverFlying;
    private double nearError, farError, nearTransmissionError, farTransmissionError;
    private double[] nearPixels, farPixels;
    private FogEdgeProbe leafEdges, farLeafEdges, solidEdge;
    private BufferedImage edgeFirst;
    private long edgeSampleFrame;
    private long historyResetBaseline;
    private int motionSteps, motionMinSamples = Integer.MAX_VALUE;
    private long lawStartFrame;
    private float observedStart, observedEnd;
    private float removalDrift;
    private float fastDriftBase, fastPeakWeight;
    private long removalHash, removalFrame = -1;

    FixtureFog(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, Layout layout, double x, double y, double z, float yaw, float pitch, FixtureTerrain.Output output) {
        FixtureCompatibility.requireSmokeSave(save);
        if (mc.getIntegratedServer() != server || !save.equals(server.getFolderName()) || mc.world == null || mc.player == null)
            throw new IllegalStateException("Fog fixture requires its current integrated smoke world");
        this.mc = mc; this.events = events; this.server = server; this.world = world; this.save = save;
        this.layout = layout; this.output = output; clientWorld = mc.world; playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        originalView = mc.gameSettings.thirdPersonView; originalHud = mc.gameSettings.hideGUI;
        originalFov = mc.gameSettings.fovSetting; originalBobbing = mc.gameSettings.viewBobbing;
        originalFlying = mc.player.capabilities.isFlying; originalEnabled = events.isEnabled();
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        FixtureCompatibility.requireSmokeSave(world.getMinecraftServer().getFolderName());
        if (!world.getMinecraftServer().isCallingFromMinecraftThread() || world.provider.getDimension() != 0 || y < 1 || y > 230)
            throw new IllegalStateException("Fog arena requires its owned overworld server thread and bounded height");
        boolean edges = Boolean.getBoolean("rlcraftremix.smoke.fogEdges");
        for (int dx = -12; dx <= (edges ? 16 : 12); dx++) for (int dz = -12; dz <= 20; dz++) {
            world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.STONE.getDefaultState(), 3);
            int top = Math.min(255, Math.max(y + 13, world.getHeight(new BlockPos(x + dx, y, z + dz)).getY()));
            for (int yy = y + 1; yy <= top; yy++) world.setBlockToAir(new BlockPos(x + dx, yy, z + dz));
        }
        for (int dx = -4; dx <= -2; dx++) for (int dy = 1; dy <= 6; dy++)
            world.setBlockState(new BlockPos(x + dx, y + dy, z + 10), Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
        for (int dx = 4; dx <= 10; dx++) for (int dy = 1; dy <= 10; dy++)
            world.setBlockState(new BlockPos(x + dx, y + dy, z - 10), Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
        // The left side is occluded by the near quartz target. Keep this panel
        // beyond the far target's right edge so its cutout silhouette is visible.
        if (edges)
            for (int dx = 12; dx <= 15; dx++) for (int dy = 1; dy <= 8; dy++)
                world.setBlockState(new BlockPos(x + dx, y + dy, z - 5), Blocks.LEAVES.getDefaultState()
                        .withProperty(BlockLeaves.DECAYABLE, false).withProperty(BlockLeaves.CHECK_DECAY, false), 3);
        if (edges) {
            for (int dx = -1; dx <= 7; dx++) for (int dz = -50; dz <= -46; dz++) {
                int top = Math.min(255, Math.max(y + 14, world.getHeight(new BlockPos(x + dx, y, z + dz)).getY()));
                for (int yy = y + 1; yy <= top; yy++) world.setBlockToAir(new BlockPos(x + dx, yy, z + dz));
            }
            // Smaller projected leaf holes expose single-sample misses hidden by the near panel.
            for (int dx = 0; dx <= 6; dx++) for (int dy = 5; dy <= 13; dy++)
                world.setBlockState(new BlockPos(x + dx, y + dy, z - 48), Blocks.LEAVES.getDefaultState()
                        .withProperty(BlockLeaves.DECAYABLE, false).withProperty(BlockLeaves.CHECK_DECAY, false), 3);
        }
        return new Layout(x, y, z);
    }

    boolean tick() throws Exception {
        if (!ownsClient() || events.failureMessage() != null || phase != Phase.SETUP && phase != Phase.RETURN
                && phase != Phase.DONE && events.isEnabled() != (phase != Phase.VANILLA))
            throw new IllegalStateException("Fog fixture lost its renderer/world: " + status());
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(150)) {
            output.capture("fog-timeout-" + phase.name().toLowerCase(Locale.ROOT), output.image());
            throw new IllegalStateException("Fog fixture timed out: " + status());
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(5)) { log("PROGRESS"); progress = System.nanoTime(); }
        if (phase != Phase.SETUP && phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        switch (phase) {
            case SETUP:
                if (edgeDiagnostics && !mc.gameSettings.fancyGraphics)
                    throw new IllegalStateException("Fog edge diagnostic requires fancy leaves for its cutout control");
                configured = true; mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = true;
                mc.gameSettings.fovSetting = 70; mc.gameSettings.viewBobbing = false;
                MinecraftForge.EVENT_BUS.register(this); registered = true; preset = Preset.NONE;
                events.setEnabled(true); serverQueued = true;
                action = server.callFromMainThread(() -> {
                    requireWorld(); EntityPlayerMP player = player(); serverFlying = player.capabilities.isFlying; serverConfigured = true;
                    player.capabilities.isFlying = true; player.sendPlayerAbilities();
                    player.connection.setPlayerLocation(cameraX(), cameraY(), cameraZ(), 180, 0); return null;
                });
                transition(Phase.ARRIVE); break;
            case ARRIVE:
                if (complete()) { select(0); transition(Phase.NATIVE); }
                break;
            case NATIVE:
            case VANILLA:
                if (settled(ready(), worldFrames)) {
                    captureAndCheck();
                    if (++caseIndex < CASES.length) { select(caseIndex); transition(phase); }
                    else if (phase == Phase.NATIVE) { events.setEnabled(false); select(0); transition(Phase.VANILLA); }
                    else if (edgeDiagnostics) {
                        events.setEnabled(true); select(0); transition(Phase.EDGE_CLEAR);
                    } else {
                        unregister(); events.setEnabled(true); appliedBaseline = events.fogAppliedFrames();
                        transition(Phase.RESTORE);
                    }
                }
                break;
            case EDGE_CLEAR:
            case EDGE_LINEAR:
            case EDGE_RESTABILIZE:
            case EDGE_LAW_DRIFT:
            case EDGE_LAW_FAST:
                if (settled(ready() && (nearestControl() || phase == Phase.EDGE_CLEAR
                        || phase == Phase.EDGE_LAW_DRIFT || phase == Phase.EDGE_LAW_FAST
                        || events.fogHistorySamples() == 32), worldFrames) && worldFrames - edgeSampleFrame >= 3) {
                    if (sampleEdges()) {
                        if (phase == Phase.EDGE_CLEAR) { select(1); transition(Phase.EDGE_LINEAR); }
                        else if (phase == Phase.EDGE_LINEAR && !nearestControl()) {
                            historyResetBaseline = events.fogHistoryResets(); motionSteps = 0;
                            motionMinSamples = Integer.MAX_VALUE; transition(Phase.EDGE_MOVE);
                        }
                        else if (phase == Phase.EDGE_RESTABILIZE) {
                            select(1); lawStartFrame = worldFrames; historyResetBaseline = events.fogHistoryResets();
                            transition(Phase.EDGE_LAW_DRIFT);
                        }
                        else if (phase == Phase.EDGE_LAW_DRIFT) {
                            if (events.fogHistorySamples() != 32 || events.fogHistoryResets() != historyResetBaseline)
                                throw new IllegalStateException("Gradual fog drift repeatedly discarded stationary coverage: " + status());
                            fastDriftBase = observedStart - preset.start; fastPeakWeight = 0;
                            select(1); lawStartFrame = worldFrames; transition(Phase.EDGE_LAW_FAST);
                        }
                        else if (phase == Phase.EDGE_LAW_FAST) {
                            if (events.fogHistorySamples() != 32 || events.fogHistoryResets() != historyResetBaseline
                                    || !(fastPeakWeight > 1.0f / 32))
                                throw new IllegalStateException("Changing fog did not exercise bounded adaptive weight: " + status());
                            removalDrift = observedStart - preset.start; historyResetBaseline = events.fogHistoryResets();
                            BlockPos marker = farLeafMarker();
                            removalHash = events.sectionGeometryHash(marker.getX() >> 4, marker.getY() >> 4, marker.getZ() >> 4);
                            action = server.callFromMainThread(() -> {
                                requireWorld();
                                for (int dx = 0; dx <= 6; dx++) for (int dy = 5; dy <= 13; dy++)
                                    world.setBlockToAir(new BlockPos(layout.x + dx, layout.y + dy, layout.z - 48));
                                return null;
                            });
                            transition(Phase.EDGE_REMOVE);
                        }
                        else {
                            unregister(); appliedBaseline = events.fogAppliedFrames(); transition(Phase.RESTORE);
                        }
                    }
                }
                break;
            case EDGE_MOVE:
                if (settled(ready(), worldFrames)) {
                    if (events.fogHistoryResets() <= historyResetBaseline || motionMinSamples != 1)
                        throw new IllegalStateException("Fog history was retained across moving camera: " + status());
                    output.capture("fog-moving-reset", output.image()); log("MOTION_RESET_PASS");
                    select(1); transition(Phase.EDGE_RESTABILIZE);
                }
                break;
            case EDGE_REMOVE:
                BlockPos marker = farLeafMarker();
                if (complete() && clientWorld.isAirBlock(marker) && events.terrainPendingSectionCount() == 0
                        && events.terrainRetirementCount() == 0
                        && events.sectionGeometryHash(marker.getX() >> 4, marker.getY() >> 4, marker.getZ() >> 4) != removalHash) {
                    if (removalFrame < 0) removalFrame = events.renderedFrames();
                    if (events.renderedFrames() - removalFrame >= 3) {
                        BufferedImage image = output.image(); int green = farLeafFogPixels(image);
                        output.capture("fog-far-leaves-removed", image);
                        if (green != 0 || events.fogHistoryResets() != historyResetBaseline)
                            throw new IllegalStateException("Removed foliage retained fog coverage or reset history: green=" + green + " " + status());
                        log("LEAF_REMOVAL_PASS"); unregister(); appliedBaseline = events.fogAppliedFrames(); transition(Phase.RESTORE);
                    }
                }
                break;
            case RESTORE:
                if (settled(events.fogAppliedFrames() > appliedBaseline && events.fogState() != null
                        && !matches(events.fogState(), Preset.COLOR) && arenaReady(), events.renderedFrames())) {
                    output.capture("fog-normal-restored", output.image()); log("RESTORE_PASS");
                    restoreClient(); action = restoreServer(); cleanupQueued = true; transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) { transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void density(EntityViewRenderEvent.FogDensity event) {
        if (!observing(event)) return;
        GlStateManager.setFog(preset.mode == FogState.LINEAR ? GlStateManager.FogMode.LINEAR
                : preset.mode == FogState.EXP ? GlStateManager.FogMode.EXP : GlStateManager.FogMode.EXP2);
        float drift = phase == Phase.EDGE_LAW_DRIFT ? Math.min(0.5f, (worldFrames - lawStartFrame) * 0.00005f)
                : phase == Phase.EDGE_LAW_FAST ? fastDriftBase + (float) (0.08 * Math.sin((worldFrames - lawStartFrame) * 0.25))
                : phase == Phase.EDGE_REMOVE ? removalDrift : 0;
        observedStart = preset.start + drift; observedEnd = preset.end + drift;
        GlStateManager.setFogStart(observedStart); GlStateManager.setFogEnd(observedEnd);
        radial = GLContext.getCapabilities().GL_NV_fog_distance;
        if (radial) GlStateManager.glFogi(0x855A, 0x855B);
        event.setDensity(preset.density); event.setCanceled(true); densityCalls++;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void colors(EntityViewRenderEvent.FogColors event) {
        if (!observing(event)) return;
        event.setRed(preset.red); event.setGreen(preset.green); event.setBlue(preset.blue); colorCalls++;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void frame(RenderWorldLastEvent event) {
        if (!registered || !ownsClient()) return;
        if (phase == Phase.EDGE_MOVE) motionMinSamples = Math.min(motionMinSamples, events.fogHistorySamples());
        if (phase == Phase.EDGE_LAW_FAST) fastPeakWeight = Math.max(fastPeakWeight, events.fogHistoryWeight());
        projection.clear(); GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        projectionX = projection.get(0); projectionY = projection.get(5); worldFrames++;
    }

    private boolean observing(EntityViewRenderEvent event) {
        return registered && preset != null && ownsClient() && event.getEntity() == mc.getRenderViewEntity()
                && event.getEntity().world == clientWorld;
    }

    private void select(int index) {
        caseIndex = index; preset = CASES[index]; densityBaseline = densityCalls; colorBaseline = colorCalls;
        appliedBaseline = events.fogAppliedFrames(); nearError = farError = 0;
        leafEdges = farLeafEdges = solidEdge = null; edgeFirst = null; edgeSampleFrame = worldFrames;
    }

    private boolean sampleEdges() throws Exception {
        BufferedImage image = output.image();
        if (leafEdges == null) {
            double eye = cameraY() + mc.player.getEyeHeight();
            double marginX = 4.0 / image.getWidth(), marginY = 4.0 / image.getHeight();
            leafEdges = new FogEdgeProbe(image,
                    projectX(11.5, 22.5) - marginX, projectY(layout.y + 9 - eye, 22.5) - marginY,
                    projectX(15.5, 22.5) + marginX, projectY(layout.y + 1 - eye, 22.5) + marginY);
            farLeafEdges = new FogEdgeProbe(image,
                    projectX(-0.5, 65.5) - marginX, projectY(layout.y + 14 - eye, 65.5) - marginY,
                    projectX(6.5, 65.5) + marginX, projectY(layout.y + 5 - eye, 65.5) + marginY);
            double edge = projectX(3.5, 27.5);
            solidEdge = new FogEdgeProbe(image, edge - marginX,
                    projectY(layout.y + 9 - eye, 27.5), edge + marginX,
                    projectY(layout.y + 5 - eye, 27.5));
            edgeFirst = image;
        }
        leafEdges.sample(image); farLeafEdges.sample(image); solidEdge.sample(image); edgeSampleFrame = worldFrames;
        if (leafEdges.samples() != FogEdgeProbe.SAMPLES) return false;
        String name = preset == Preset.NONE ? "clear" : phase == Phase.EDGE_RESTABILIZE ? "linear-restabilized"
                : phase == Phase.EDGE_LAW_DRIFT ? "linear-drift" : phase == Phase.EDGE_LAW_FAST ? "linear-fast" : "linear";
        output.log("FOG_EDGE_OBSERVATION", "preset=" + name
                + " resolve=" + (nearestControl() ? "nearest-control" : "temporal-coverage")
                + " historySamples=" + events.fogHistorySamples() + " historyResets=" + events.fogHistoryResets()
                + " history=" + events.fogHistoryDiagnostics()
                + " leaf=" + leafEdges.summary()
                + " farLeaf=" + farLeafEdges.summary()
                + " solid=" + solidEdge.summary() + " output=" + image.getWidth() + "x" + image.getHeight());
        // PNG encoding happens only after the last sample, so it cannot select the sampled frame spacing.
        output.capture("fog-edges-" + name + "-first", edgeFirst);
        output.capture("fog-edges-" + name + "-last", image);
        edgeFirst = null;
        return true;
    }
    private double projectX(double relative, double distance) { return 0.5 + 0.5 * relative / distance * projectionX; }
    private double projectY(double relative, double distance) { return 0.5 - 0.5 * relative / distance * projectionY; }

    private BlockPos farLeafMarker() { return new BlockPos(layout.x + 3, layout.y + 9, layout.z - 48); }
    private int farLeafFogPixels(BufferedImage image) {
        double eye = cameraY() + mc.player.getEyeHeight();
        int left = Math.max(0, (int) Math.floor(projectX(-0.5, 65.5) * image.getWidth()) - 4);
        int right = Math.min(image.getWidth(), (int) Math.ceil(projectX(6.5, 65.5) * image.getWidth()) + 4);
        int top = Math.max(0, (int) Math.floor(projectY(layout.y + 14 - eye, 65.5) * image.getHeight()) - 4);
        int bottom = Math.min(image.getHeight(), (int) Math.ceil(projectY(layout.y + 5 - eye, 65.5) * image.getHeight()) + 4);
        int green = 0;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            int rgb = image.getRGB(x, y), g = rgb >>> 8 & 255;
            if (g > (rgb >>> 16 & 255) + 32 && g > (rgb & 255) + 32) green++;
        }
        return green;
    }

    private boolean ready() {
        if (!arenaReady() || densityCalls <= densityBaseline || colorCalls <= colorBaseline
                || !Float.isFinite(projectionX) || !Float.isFinite(projectionY) || projectionX <= 0 || projectionY <= 0) return false;
        FogState state = events.fogState();
        boolean lawMatches = phase == Phase.EDGE_LAW_DRIFT || phase == Phase.EDGE_LAW_FAST ? state != null && state.enabled()
                && state.mode() == FogState.LINEAR && close(state.start(), observedStart) && close(state.end(), observedEnd)
                : matches(state, preset);
        return phase == Phase.VANILLA || events.fogAppliedFrames() > appliedBaseline
                && events.fogMissingDepthFrames() == 0 && lawMatches;
    }

    private void captureAndCheck() throws Exception {
        BufferedImage image = output.image(); int renderer = phase == Phase.VANILLA ? 1 : 0;
        double eye = cameraY() + mc.player.getEyeHeight(), vertical = layout.y + 4 - eye;
        nearPixels = FogImageProbe.sample(image, 0.5 - 0.5 * 3 / 7.5 * projectionX, 0.5 - 0.5 * vertical / 7.5 * projectionY);
        farPixels = FogImageProbe.sample(image, 0.5 + 0.5 * 7 / 27.5 * projectionX, 0.5 - 0.5 * vertical / 27.5 * projectionY);
        output.capture("fog-" + (renderer == 0 ? "remix-" : "vanilla-") + preset.name().toLowerCase(Locale.ROOT), image);
        if (preset == Preset.NONE) {
            clear[renderer][0] = nearPixels; clear[renderer][1] = farPixels;
            double[] green = {25.5, 153, 51};
            if (FogImageProbe.difference(nearPixels, green) < 35 || FogImageProbe.difference(farPixels, green) < 35)
                throw new IllegalStateException("Unfogged targets lack contrast for fog measurement: " + status());
        } else {
            double nearDistance = radial ? Math.sqrt(7.5 * 7.5 + 9 + vertical * vertical) : 7.5;
            double farDistance = radial ? Math.sqrt(27.5 * 27.5 + 49 + vertical * vertical) : 27.5;
            double nearTransmission = transmission(nearDistance), farTransmission = transmission(farDistance);
            nearError = FogImageProbe.error(clear[renderer][0], nearPixels, preset.red, preset.green, preset.blue, nearTransmission);
            farError = FogImageProbe.error(clear[renderer][1], farPixels, preset.red, preset.green, preset.blue, farTransmission);
            nearTransmissionError = Math.abs(FogImageProbe.transmission(clear[renderer][0], nearPixels,
                    preset.red, preset.green, preset.blue) - nearTransmission);
            farTransmissionError = Math.abs(FogImageProbe.transmission(clear[renderer][1], farPixels,
                    preset.red, preset.green, preset.blue) - farTransmission);
            // Small target patches tolerate texture/temporal lighting variation, but reject omitted fog,
            // uniform screen tint, wrong depth linearization, wrong equation, or an unchanged old color.
            if (nearError > 22 || farError > 22 || !(nearTransmissionError <= 0.075) || !(farTransmissionError <= 0.075))
                throw new IllegalStateException("Fog target pixels disagree with captured distance law: " + status());
            if (preset == Preset.LINEAR) {
                linear[renderer][0] = nearPixels; linear[renderer][1] = farPixels;
                if (FogImageProbe.difference(clear[renderer][1], farPixels) < 35)
                    throw new IllegalStateException("Far target did not receive linear fog: " + status());
                if (renderer == 1 && FogImageProbe.difference(linear[0][1], farPixels) > 12)
                    throw new IllegalStateException("Fully fogged native/vanilla target colors differ: " + status());
            }
        }
        log((renderer == 0 ? "REMIX_" : "VANILLA_") + preset.name() + "_PASS");
    }

    private double transmission(double distance) {
        if (preset.mode == FogState.LINEAR) return Math.max(0, Math.min(1, (preset.end - distance) / (preset.end - preset.start)));
        double optical = preset.density * distance;
        return Math.exp(preset.mode == FogState.EXP ? -optical : -optical * optical);
    }
    private static boolean matches(FogState state, Preset preset) {
        return state != null && state.enabled() && state.mode() == preset.mode && close(state.density(), preset.density)
                && close(state.start(), preset.start) && close(state.end(), preset.end)
                && close(state.red(), preset.red) && close(state.green(), preset.green) && close(state.blue(), preset.blue);
    }
    private static boolean close(float first, float second) { return Math.abs(first - second) < 0.0001f; }
    private boolean arenaReady() {
        if (mc.world.getBlockState(layout.near).getBlock() != Blocks.QUARTZ_BLOCK
                || mc.world.getBlockState(layout.far).getBlock() != Blocks.QUARTZ_BLOCK) return false;
        if (edgeDiagnostics) {
            BlockPos farLeaf = farLeafMarker();
            if (phase != Phase.RESTORE && (clientWorld.getBlockState(farLeaf).getBlock() != Blocks.LEAVES
                    || phase != Phase.VANILLA && !events.terrainSectionReady(farLeaf.getX() >> 4, farLeaf.getY() >> 4, farLeaf.getZ() >> 4))) return false;
            for (int dx = 12; dx <= 15; dx++) for (int dy = 1; dy <= 8; dy++) {
                BlockPos leaf = new BlockPos(layout.x + dx, layout.y + dy, layout.z - 5);
                if (clientWorld.getBlockState(leaf).getBlock() != Blocks.LEAVES
                        || phase != Phase.VANILLA && !events.terrainSectionReady(leaf.getX() >> 4, leaf.getY() >> 4, leaf.getZ() >> 4)) return false;
            }
        }
        return phase == Phase.VANILLA || events.terrainSectionReady(layout.near.getX() >> 4, layout.near.getY() >> 4, layout.near.getZ() >> 4)
                && events.terrainSectionReady(layout.far.getX() >> 4, layout.far.getY() >> 4, layout.far.getZ() >> 4)
                && events.terrainPendingSectionCount() == 0 && events.terrainRetirementCount() == 0;
    }
    private double cameraX() { return layout.x + 0.5; }
    private double cameraY() { return layout.y + 2; }
    private double cameraZ() { return layout.z + 18.5; }
    private boolean nearestControl() { return Boolean.getBoolean("rlcraftremix.smoke.fogNearest"); }
    private void holdPose() {
        float yaw = phase == Phase.EDGE_MOVE ? 180 + (motionSteps++ % 20) * 0.05f : 180;
        mc.player.setPositionAndRotation(cameraX(), cameraY(), cameraZ(), yaw, 0);
        mc.player.prevPosX = mc.player.lastTickPosX = cameraX(); mc.player.prevPosY = mc.player.lastTickPosY = cameraY();
        mc.player.prevPosZ = mc.player.lastTickPosZ = cameraZ(); mc.player.prevRotationYaw = yaw; mc.player.prevRotationPitch = 0;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private boolean ownsClient() {
        return mc.getIntegratedServer() == server && mc.world == clientWorld && mc.player != null && playerId.equals(mc.player.getUniqueID());
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null || player.world != world) throw new IllegalStateException("Fog fixture player unavailable"); return player;
    }
    private void requireWorld() {
        FixtureCompatibility.requireSmokeSave(save);
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Fog fixture no longer owns server world");
    }
    private Future<?> restoreServer() {
        return server.callFromMainThread(() -> {
            requireWorld();
            if (serverConfigured) {
                EntityPlayerMP player = player(); player.capabilities.isFlying = serverFlying; player.sendPlayerAbilities();
                player.connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); serverConfigured = false;
            }
            return null;
        });
    }
    private void unregister() {
        preset = null; if (registered) { MinecraftForge.EVENT_BUS.unregister(this); registered = false; }
    }
    private void restoreClient() {
        unregister(); edgeFirst = null; leafEdges = farLeafEdges = solidEdge = null; if (!configured) return;
        mc.gameSettings.thirdPersonView = originalView; mc.gameSettings.hideGUI = originalHud;
        mc.gameSettings.fovSetting = originalFov; mc.gameSettings.viewBobbing = originalBobbing;
        if (ownsClient()) {
            mc.player.capabilities.isFlying = originalFlying;
            if (events.failureMessage() == null && events.isEnabled() != originalEnabled) events.setEnabled(originalEnabled);
        }
        configured = false;
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready, long frames) {
        if (!ready) { stableFrame = -1; return false; }
        if (stableFrame < 0) { stableFrame = frames; stableSince = System.nanoTime(); }
        return frames - stableFrame >= 40 && System.nanoTime() - stableSince >= TimeUnit.SECONDS.toNanos(2);
    }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); stableFrame = -1; log("PHASE"); }
    private void log(String name) { output.log("FOG_" + name, status()); }
    String status() {
        FogState state = events.fogState();
        return "phase=" + phase + " preset=" + preset + " worldFrames=" + worldFrames + " applied=" + events.fogAppliedFrames()
                + " missingDepth=" + events.fogMissingDepthFrames() + " densityCalls=" + densityCalls + " colorCalls=" + colorCalls
                + " registered=" + registered + " capturedMode=" + (state == null ? -1 : state.mode())
                + " capturedDensity=" + (state == null ? -1 : state.density()) + " nearPixels=" + Arrays.toString(nearPixels)
                + " farPixels=" + Arrays.toString(farPixels) + " nearError=" + nearError + " farError=" + farError
                + " nearTransmissionError=" + nearTransmissionError + " farTransmissionError=" + farTransmissionError
                + " edgeSamples=" + (leafEdges == null ? 0 : leafEdges.samples())
                + " historySamples=" + events.fogHistorySamples() + " historyResets=" + events.fogHistoryResets()
                + " history=" + events.fogHistoryDiagnostics() + " fastPeakWeight=" + fastPeakWeight;
    }
    @Override public void close() {
        if (closed) return; closed = true;
        try { restoreClient(); } finally { if (serverQueued && !cleanupQueued) { cleanupQueued = true; restoreServer(); } }
    }
    static final class Layout {
        final int x, y, z;
        final BlockPos near, far;
        Layout(int x, int y, int z) { this.x = x; this.y = y; this.z = z; near = new BlockPos(x - 3, y + 4, z + 10); far = new BlockPos(x + 7, y + 4, z - 10); }
    }
}

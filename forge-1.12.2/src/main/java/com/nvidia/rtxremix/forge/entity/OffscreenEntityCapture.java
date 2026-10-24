/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.forge.item.ItemCaptureHooks;
import java.util.IdentityHashMap;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Bounded supplementary draws for nearby shadows and reflections outside vanilla's visible set. */
final class OffscreenEntityCapture {
    static final int MAX_ENTITIES = 32;
    static final double RADIUS = 48;
    private static final int MAX_OBSERVED = 4096;
    private static final Logger LOGGER = LogManager.getLogger("RLCraftRemixEntities");
    private final IdentityHashMap<Object, Boolean> observed = new IdentityHashMap<>();
    private final IdentityHashMap<Class<?>, Boolean> unsupported = new IdentityHashMap<>();
    private final ClosestEntities<Entity> closest = new ClosestEntities<>(MAX_ENTITIES);
    private EntityRenderState state;
    private boolean overflow;
    private int captured;
    private long failures;
    private boolean supplementary;

    void beginFrame() { observed.clear(); closest.clear(); overflow = false; captured = 0; }
    void observe(Object entity) {
        if (supplementary) return;
        if (observed.size() >= MAX_OBSERVED) { overflow = true; return; }
        observed.put(entity, Boolean.TRUE);
    }
    int capturedCount() { return captured; }
    long failureCount() { return failures; }
    boolean normallyRendered(UUID id) {
        for (Object owner : observed.keySet()) if (owner instanceof Entity && id.equals(((Entity) owner).getUniqueID())) return true;
        return false;
    }

    void capture(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.getRenderViewEntity();
        if (overflow || unsupported.size() >= 64 || mc.world == null || camera == null) return;
        for (int i = 0; i < mc.world.loadedEntityList.size(); i++) {
            Entity entity = mc.world.loadedEntityList.get(i);
            if (observed.containsKey(entity) || unsupported.containsKey(entity.getClass())
                    || entity.isDead || entity.world != mc.world || entity.isInvisible()
                    || entity == camera && mc.gameSettings.thirdPersonView == 0
                    || entity instanceof EntityPlayer && ((EntityPlayer) entity).isSpectator()) continue;
            double distance = entity.getDistanceSq(camera.posX, camera.posY, camera.posZ);
            if (distance <= RADIUS * RADIUS) closest.offer(entity, distance);
        }
        if (closest.size() == 0) return;
        if (state == null) state = new EntityRenderState();
        int pass = MinecraftForgeClient.getRenderPass();
        state.begin();
        supplementary = true;
        try {
            for (int i = 0; i < closest.size(); i++) {
                Entity entity = closest.get(i);
                boolean rendered = false;
                EntityCaptureHooks.beginSupplementary();
                ItemCaptureHooks.beginSupplementary();
                try {
                    for (int renderPass = 0; renderPass <= 1; renderPass++) {
                        if (!entity.shouldRenderInPass(renderPass)) continue;
                        ForgeHooksClient.setRenderPass(renderPass);
                        mc.getRenderManager().renderEntityStatic(entity, partialTicks, true);
                        rendered = true;
                    }
                } catch (RuntimeException | LinkageError error) {
                    EntityCaptureHooks.discardSupplementary();
                    ItemCaptureHooks.discardSupplementary();
                    unsupported.put(entity.getClass(), Boolean.TRUE);
                    failures++;
                    LOGGER.warn("Supplementary entity rendering omitted {} after {}", entity.getClass().getName(), error.getClass().getSimpleName());
                    state.end();
                    state.begin();
                    continue;
                }
                if (rendered) captured++;
            }
        } finally {
            supplementary = false;
            ForgeHooksClient.setRenderPass(pass);
            try { state.end(); } finally { closest.clear(); }
        }
    }

    /** A first-person camera still needs the local player's body for shadows and reflections. */
    void captureLocalPlayer(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity player = mc.player;
        if (player == null || observed.containsKey(player) || unsupported.containsKey(player.getClass())) return;
        if (state == null) state = new EntityRenderState();
        int pass = MinecraftForgeClient.getRenderPass();
        state.begin();
        supplementary = true;
        EntityCaptureHooks.beginSupplementary();
        ItemCaptureHooks.beginSupplementary();
        try {
            ForgeHooksClient.setRenderPass(0);
            mc.getRenderManager().renderEntityStatic(player, partialTicks, true);
            captured++;
        } catch (RuntimeException | LinkageError error) {
            EntityCaptureHooks.discardSupplementary();
            ItemCaptureHooks.discardSupplementary();
            unsupported.put(player.getClass(), Boolean.TRUE);
            failures++;
            LOGGER.warn("Local player shadow rendering omitted after {}", error.getClass().getSimpleName());
        } finally {
            supplementary = false;
            ForgeHooksClient.setRenderPass(pass);
            state.end();
        }
    }

    void release(boolean clearFrame) {
        if (clearFrame) beginFrame();
        unsupported.clear();
        if (state != null) { state.release(); state = null; }
    }
}

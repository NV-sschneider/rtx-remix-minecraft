/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.util.Arrays;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.util.vector.Matrix4f;

/** Observes the original passes and successful baked quads; it never evaluates a model. */
public final class EntityGlintHooks {
    private static final int MAX_QUADS = 1024;
    private static final Logger LOGGER = LogManager.getLogger("RLCraftRemixGlint");
    private static final TextureAtlasSprite[] SPRITES = new TextureAtlasSprite[MAX_QUADS];
    private static final ItemGlintGeometry GEOMETRY = new ItemGlintGeometry();
    private static int depth;
    private static int count, pendingStart;
    private static Object buffer;
    private static TextureAtlasSprite pending;
    private static boolean itemPass, rejected, warned;
    private static long rejectedPasses;
    private EntityGlintHooks() { }
    public static void begin() { if (depth++ == 0) { clearBatch(); itemPass = false; } }
    public static void end() { if (depth > 0 && --depth == 0) { clearBatch(); itemPass = false; } }
    static boolean active() { return depth == 1; }
    static void reset() { depth = 0; clearBatch(); itemPass = false; }
    static void beginFrame() { GEOMETRY.beginFrame(); }
    static void clearCache() { reset(); GEOMETRY.clear(); }
    static boolean hasItemQuads() { return active() && itemPass; }

    /** Called around the existing successful Forge quad-color draw, including its glint overload. */
    public static void beforeQuad(Object quad, Object drawnBuffer) {
        if (!active() || !EntityCaptureHooks.isArmed() || EntityCaptureHooks.activeOwner() == null) return;
        itemPass = true;
        try {
            if (rejected || count == MAX_QUADS || buffer != null && buffer != drawnBuffer) { rejected = true; return; }
            buffer = drawnBuffer;
            pendingStart = ((BufferBuilder) drawnBuffer).getVertexCount();
            if (pendingStart != count * 4) { rejected = true; return; }
            pending = ((BakedQuad) quad).getSprite();
        } catch (RuntimeException | LinkageError failure) { rejected = true; }
    }
    public static void afterQuad(Object drawnBuffer) {
        if (!active() || !itemPass || rejected) return;
        try {
            if (drawnBuffer != buffer || ((BufferBuilder) drawnBuffer).getVertexCount() != pendingStart + 4) rejected = true;
            else SPRITES[count++] = pending;
        } catch (RuntimeException | LinkageError failure) { rejected = true; }
        finally { pending = null; }
    }
    static boolean append(EntityTriangles destination, BufferBuilder completed, int stride, int uv, int color,
            int normal, int argb, Matrix4f texture, int alphaReference) {
        boolean accepted = false;
        try {
            accepted = !rejected && completed == buffer && GEOMETRY.append(destination, completed.getByteBuffer(),
                    completed.getVertexCount(), stride, uv, color, normal, argb, texture, SPRITES, count, alphaReference);
            return accepted;
        } catch (RuntimeException | LinkageError failure) { return false; }
        finally {
            if (!accepted) {
                rejectedPasses++;
                if (!warned) {
                    warned = true;
                    LOGGER.warn("An unsupported or over-budget item glint pass was omitted; its base item is retained");
                }
            }
            clearBatch();
        }
    }
    public static long rejectedItemPassCount() { return rejectedPasses; }
    private static void clearBatch() {
        Arrays.fill(SPRITES, 0, count, null); count = 0; buffer = null; pending = null; rejected = false;
    }
}

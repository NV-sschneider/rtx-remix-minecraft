/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

/** Invokes Better Foliage's own renderers. No mod geometry or shading algorithms are duplicated here. */
final class BetterFoliageCapture {
    private final Minecraft mc;
    private final BakedBlockCapture baked;
    private final BlockCaptureBuffer buffer = new BlockCaptureBuffer();
    private final MethodHandle render, layer;
    private final MethodHandle context, getWorld, getPos, setWorld, setPos;
    private final BlockRendererDispatcher dispatcher;
    private int originX, originY, originZ;
    private long captures;

    static BetterFoliageCapture create(Minecraft mc, BakedBlockCapture baked) {
        ModContainer mod = Loader.instance().getIndexedModList().get("betterfoliage");
        if (mod == null) return null;
        if (!"2.3.2".equals(mod.getVersion()) && !"2.3.3".equals(mod.getVersion()))
            throw new CaptureFailure("Unsupported Better Foliage " + mod.getVersion()
                    + "; the capture contract requires the Minecraft 1.12.2 2.3.3 release (metadata 2.3.2)", null);
        return new BetterFoliageCapture(mc, baked);
    }

    private BetterFoliageCapture(Minecraft mc, BakedBlockCapture baked) {
        this.mc = mc; this.baked = baked;
        try {
            ClassLoader loader = getClass().getClassLoader();
            Class<?> model = Class.forName("mods.octarinecore.client.render.ModelRenderer", false, loader);
            if (model.getField("rtxremixCaptureContract").getInt(null) != 1)
                throw new NoSuchFieldException("rtxremixCaptureContract=1");
            Class<?> hooks = Class.forName("mods.betterfoliage.client.Hooks", true, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            render = lookup.findStatic(hooks, "renderWorldBlock", MethodType.methodType(boolean.class,
                    BlockRendererDispatcher.class, IBlockState.class, BlockPos.class, IBlockAccess.class,
                    BufferBuilder.class, BlockRenderLayer.class));
            layer = lookup.findStatic(hooks, "canRenderBlockInLayer", MethodType.methodType(boolean.class,
                    Block.class, IBlockState.class, BlockRenderLayer.class));
            Class<?> holder = Class.forName("mods.octarinecore.client.render.RendererHolder", true, loader);
            Class<?> blockContext = Class.forName("mods.octarinecore.client.render.BlockContext", false, loader);
            context = lookup.findStatic(holder, "getBlockContext", MethodType.methodType(blockContext))
                    .asType(MethodType.methodType(Object.class));
            getWorld = lookup.findVirtual(blockContext, "getWorld", MethodType.methodType(IBlockAccess.class))
                    .asType(MethodType.methodType(IBlockAccess.class, Object.class));
            getPos = lookup.findVirtual(blockContext, "getPos", MethodType.methodType(BlockPos.class))
                    .asType(MethodType.methodType(BlockPos.class, Object.class));
            setWorld = lookup.findVirtual(blockContext, "setWorld", MethodType.methodType(void.class, IBlockAccess.class))
                    .asType(MethodType.methodType(void.class, Object.class, IBlockAccess.class));
            setPos = lookup.findVirtual(blockContext, "setPos", MethodType.methodType(void.class, BlockPos.class))
                    .asType(MethodType.methodType(void.class, Object.class, BlockPos.class));
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new CaptureFailure("Better Foliage's unlit capture contract is unavailable; check the mod version and coremod conflicts", failure);
        }
        dispatcher = new BlockRendererDispatcher(mc.getBlockRendererDispatcher().getBlockModelShapes(), mc.getBlockColors()) {
            @Override public boolean renderBlock(IBlockState state, BlockPos pos, IBlockAccess world, BufferBuilder target) {
                if (target != buffer || !BetterFoliageCaptureHooks.active(target))
                    throw new IllegalStateException("Better Foliage base render escaped its capture scope");
                int before = buffer.getVertexCount();
                BetterFoliageCapture.this.baked.emit(state, world, pos, originX, originY, originZ, buffer);
                return buffer.getVertexCount() != before;
            }
        };
    }

    boolean canRender(IBlockState state, BlockRenderLayer target) {
        try { return (boolean) layer.invokeExact(state.getBlock(), state, target); }
        catch (Throwable failure) { throw failed(failure); }
    }

    int capture(IBlockState state, IBlockAccess world, BlockPos pos, BlockRenderLayer target,
                int x, int y, int z, MeshPacking packed) {
        if (!mc.isCallingFromMinecraftThread()) throw new IllegalStateException("Better Foliage capture requires the render thread");
        originX = x; originY = y; originZ = z;
        try {
            buffer.beginBlock(x, y, z);
            Object blockContext = (Object) context.invokeExact();
            IBlockAccess previousWorld = (IBlockAccess) getWorld.invokeExact(blockContext);
            BlockPos previousPos = (BlockPos) getPos.invokeExact(blockContext);
            BetterFoliageCaptureHooks.begin(buffer);
            try {
                boolean rendered = (boolean) render.invokeExact(dispatcher, state, pos, world, (BufferBuilder) buffer, target);
            } finally {
                try { setWorld.invokeExact(blockContext, previousWorld); setPos.invokeExact(blockContext, previousPos); }
                finally { BetterFoliageCaptureHooks.end(); }
            }
            buffer.finishDrawing();
            int vertices = buffer.append(packed); captures++; return vertices;
        } catch (Throwable failure) { throw failed(failure); }
        finally { buffer.releaseBlock(); }
    }

    long captureCount() { return captures; }
    long geometryHash() { return buffer.geometryHash(); }
    boolean complete() { return buffer.complete(); }

    private static CaptureFailure failed(Throwable failure) {
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof CaptureFailure) return (CaptureFailure) failure;
        return new CaptureFailure("Better Foliage capture failed; retaining vanilla rendering instead of publishing incomplete terrain", failure);
    }

    static final class CaptureFailure extends IllegalStateException {
        CaptureFailure(String message, Throwable cause) { super(message, cause); }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.Float3D;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Pinned 1.12 viewpoint semantics evaluated at the rendered camera, not the player's eyes. */
final class CameraWater {
    static final int NOT_LIQUID = -2;
    static final int CUSTOM_LIQUID = -1;

    interface States<S> {
        S get(int x, int y, int z);
        /** Vanilla level 0..15, CUSTOM_LIQUID for other liquids, or NOT_LIQUID. */
        int liquidLevel(S state);
        S atViewpoint(S state, int x, int y, int z, double cameraX, double cameraY, double cameraZ);
    }

    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private Vec3d point;
    private WorldClient world;
    private final States<IBlockState> states = new States<IBlockState>() {
        @Override public IBlockState get(int x, int y, int z) {
            position.setPos(x, y, z);
            return world.isBlockLoaded(position) ? world.getBlockState(position) : Blocks.AIR.getDefaultState();
        }
        @Override public int liquidLevel(IBlockState state) {
            if (!state.getMaterial().isLiquid()) return NOT_LIQUID;
            return state.getBlock() instanceof BlockLiquid ? state.getValue(BlockLiquid.LEVEL) : CUSTOM_LIQUID;
        }
        @Override public IBlockState atViewpoint(IBlockState state, int x, int y, int z,
                double cameraX, double cameraY, double cameraZ) {
            // ActiveRenderInfo passes the original position even when it selected the block above.
            position.setPos(x, y, z);
            return state.getBlock().getStateAtViewpoint(state, world, position, point);
        }
    };

    boolean sample(WorldClient world, Float3D camera) {
        if (world == null) return false;
        if (point == null || point.x != camera.x || point.y != camera.y || point.z != camera.z)
            point = new Vec3d(camera.x, camera.y, camera.z);
        this.world = world;
        try { return resolve(states, camera.x, camera.y, camera.z).getMaterial() == Material.WATER; }
        finally { this.world = null; }
    }

    static <S> S resolve(States<S> states, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Camera viewpoint must be finite");
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        S state = states.get(bx, by, bz);
        int level = states.liquidLevel(state);
        if (level != NOT_LIQUID && y >= surfaceAt(by, level)) state = states.get(bx, by + 1, bz);
        return states.atViewpoint(state, bx, by, bz, x, y, z);
    }

    /** Matches ActiveRenderInfo's getLiquidHeightPercent(level) - 1/9 boundary. */
    private static float surfaceAt(int blockY, int level) {
        if (level == CUSTOM_LIQUID) return blockY + 1;
        if (level < 0 || level > 15) throw new IllegalArgumentException("Invalid vanilla liquid level");
        if (level >= 8) level = 0;
        return (float) (blockY + 1) - ((level + 1) / 9.0f - 0.11111111f);
    }
}

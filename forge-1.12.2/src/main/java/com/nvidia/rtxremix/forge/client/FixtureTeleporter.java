/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ITeleporter;

/** One player and destination per transfer; vanilla still sends all dimension/session packets. */
final class FixtureTeleporter implements ITeleporter {
    private final World destination;
    private final Entity player;
    private final double x, y, z;
    private final float yaw, pitch;

    FixtureTeleporter(World destination, Entity player, double x, double y, double z, float yaw, float pitch) {
        if (destination == null || player == null) throw new IllegalArgumentException("Fixture transfer requires an owner and destination");
        this.destination = destination; this.player = player;
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
    }

    @Override public void placeEntity(World world, Entity entity, float sourceYaw) {
        if (world != destination || entity != player) throw new IllegalStateException("Fixture transfer lost world/player ownership");
        entity.setLocationAndAngles(x, y, z, yaw, pitch);
        entity.motionX = entity.motionY = entity.motionZ = 0;
        entity.fallDistance = 0;
    }

    // The Forge transfer path must place and spawn the player when leaving the End, too.
    @Override public boolean isVanilla() { return false; }
}

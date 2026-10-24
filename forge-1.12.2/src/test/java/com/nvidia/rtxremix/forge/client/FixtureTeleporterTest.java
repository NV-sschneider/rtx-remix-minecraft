/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import org.junit.Test;
import sun.reflect.ReflectionFactory;
import static org.junit.Assert.*;

public class FixtureTeleporterTest {
    @Test public void endReturnUsesAuthoredPlacementAndClearsTravelMomentum() throws Exception {
        WorldServer destination = withoutConstructor(WorldServer.class);
        Player player = withoutConstructor(Player.class);
        player.motionX = 3; player.motionY = -8; player.motionZ = 4; player.fallDistance = 19;
        FixtureTeleporter teleporter = new FixtureTeleporter(destination, player, 4.5, 83, -11.5, 180, 12);
        assertFalse(teleporter.isVanilla());
        teleporter.placeEntity(destination, player, -30);
        assertEquals(1, player.placements);
        assertEquals(4.5, player.posX, 0); assertEquals(83, player.posY, 0); assertEquals(-11.5, player.posZ, 0);
        assertEquals(180, player.rotationYaw, 0); assertEquals(12, player.rotationPitch, 0);
        assertEquals(0, player.motionX, 0); assertEquals(0, player.motionY, 0); assertEquals(0, player.motionZ, 0);
        assertEquals(0, player.fallDistance, 0);
    }

    @Test public void unexpectedWorldOrPlayerCannotBeMoved() throws Exception {
        WorldServer destination = withoutConstructor(WorldServer.class), unrelatedWorld = withoutConstructor(WorldServer.class);
        Player player = withoutConstructor(Player.class), unrelatedPlayer = withoutConstructor(Player.class);
        FixtureTeleporter teleporter = new FixtureTeleporter(destination, player, 4, 83, 9, 0, 0);
        try { teleporter.placeEntity(unrelatedWorld, player, 0); fail("Unexpected world accepted"); }
        catch (IllegalStateException expected) { }
        try { teleporter.placeEntity(destination, unrelatedPlayer, 0); fail("Unexpected player accepted"); }
        catch (IllegalStateException expected) { }
        assertEquals(0, player.placements); assertEquals(0, unrelatedPlayer.placements);
    }

    @Test public void compatibilityFixturesRejectOrdinarySaves() {
        FixtureCompatibility.requireSmokeSave("rlcraft-remix-smoke-20261024-owned");
        for (String name : new String[] {null, "", "World", "RLCraft", "rlcraft-remix-smoke-", "other-rlcraft-remix-smoke-id"}) {
            try { FixtureCompatibility.requireSmokeSave(name); fail("Ordinary save accepted: " + name); }
            catch (IllegalStateException expected) { }
        }
    }

    private static <T> T withoutConstructor(Class<T> type) throws Exception {
        return type.cast(ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, Object.class.getDeclaredConstructor()).newInstance());
    }
    private static final class Player extends Entity {
        int placements;
        Player() { super(null); }
        @Override public void setLocationAndAngles(double x, double y, double z, float yaw, float pitch) {
            placements++; posX = x; posY = y; posZ = z; rotationYaw = yaw; rotationPitch = pitch;
        }
        @Override protected void entityInit() { }
        @Override protected void readEntityFromNBT(NBTTagCompound tag) { }
        @Override protected void writeEntityToNBT(NBTTagCompound tag) { }
    }
}

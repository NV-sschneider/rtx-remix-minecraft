/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.WorldInfo;
import org.junit.Test;

import static org.junit.Assert.*;

public class FixtureEnvironmentTest {
    @Test
    public void restoresWeatherInterpolationAndTimeWithoutChangingRulesOrTotalTime() {
        World world = new FixtureWorld();
        WorldInfo info = world.getWorldInfo();
        info.setWorldTime(23456L);
        info.setCleanWeatherTime(777);
        info.setRainTime(888);
        info.setThunderTime(999);
        info.setRaining(true);
        info.setThundering(true);
        world.rainingStrength = 0.7f;
        world.prevRainingStrength = 0.6f;
        world.thunderingStrength = 0.4f;
        world.prevThunderingStrength = 0.3f;
        world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        FixtureEnvironment original = FixtureEnvironment.capture(world);

        FixtureEnvironment.setSky(world, 6000L, false);
        assertEquals(6000L, world.getWorldTime());
        assertEquals(0f, world.getRainStrength(0.5f), 0f);
        assertFalse(info.isRaining());
        FixtureEnvironment.setSky(world, 18000L, true);
        assertEquals(1f, world.getRainStrength(0.5f), 0f);
        world.setTotalWorldTime(123456L);
        world.getGameRules().setOrCreateGameRule("fixtureRule", "preserve");
        original.restore(world);

        assertEquals(23456L, world.getWorldTime());
        assertEquals(777, info.getCleanWeatherTime());
        assertEquals(888, info.getRainTime());
        assertEquals(999, info.getThunderTime());
        assertTrue(info.isRaining());
        assertTrue(info.isThundering());
        assertEquals(0.7f, world.rainingStrength, 0f);
        assertEquals(0.6f, world.prevRainingStrength, 0f);
        assertEquals(0.4f, world.thunderingStrength, 0f);
        assertEquals(0.3f, world.prevThunderingStrength, 0f);
        assertEquals(123456L, world.getTotalWorldTime());
        assertEquals("false", world.getGameRules().getString("doDaylightCycle"));
        assertEquals("preserve", world.getGameRules().getString("fixtureRule"));
    }

    @Test
    public void eachWorldRestoresItsOwnSnapshotAfterPartialMutation() {
        World client = new FixtureWorld();
        World server = new FixtureWorld();
        client.getWorldInfo().setWorldTime(101L);
        server.getWorldInfo().setWorldTime(202L);
        client.getWorldInfo().setRaining(true);
        FixtureEnvironment clientOriginal = FixtureEnvironment.capture(client);
        FixtureEnvironment serverOriginal = FixtureEnvironment.capture(server);

        client.getWorldInfo().setRaining(false);
        FixtureEnvironment.setSky(server, 6000L, true);
        serverOriginal.restore(server);
        clientOriginal.restore(client);

        assertEquals(101L, client.getWorldTime());
        assertEquals(202L, server.getWorldTime());
        assertTrue(client.getWorldInfo().isRaining());
        assertFalse(server.getWorldInfo().isRaining());
    }

    /** A real World container without chunks, biome registration, a server, or a GL context. */
    private static final class FixtureWorld extends World {
        FixtureWorld() {
            super(new SaveHandlerMP(), new EmptyWorldInfo(), new WorldProviderSurface() {
                @Override protected void init() {}
            }, new Profiler(), true);
            provider.setWorld(this);
        }

        @Override public void setWorldTime(long time) {
            // The client override is a packet decoder that also toggles doDaylightCycle.
            throw new AssertionError("The fixture must set provider time without decoding a time packet");
        }

        @Override protected IChunkProvider createChunkProvider() { return null; }
        @Override protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) { return false; }
    }

    private static final class EmptyWorldInfo extends WorldInfo {}
}

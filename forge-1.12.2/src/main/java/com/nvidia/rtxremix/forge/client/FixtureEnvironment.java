/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;

/** Time and weather changed by the smoke fixture, captured independently on each world thread. */
final class FixtureEnvironment {
    private final long time;
    private final int clearTime, rainTime, thunderTime;
    private final boolean raining, thundering;
    private final float rain, previousRain, thunder, previousThunder;

    private FixtureEnvironment(World world) {
        WorldInfo info = world.getWorldInfo();
        time = world.getWorldTime();
        clearTime = info.getCleanWeatherTime();
        rainTime = info.getRainTime();
        thunderTime = info.getThunderTime();
        raining = info.isRaining();
        thundering = info.isThundering();
        rain = world.rainingStrength;
        previousRain = world.prevRainingStrength;
        thunder = world.thunderingStrength;
        previousThunder = world.prevThunderingStrength;
    }

    static FixtureEnvironment capture(World world) { return new FixtureEnvironment(world); }

    void restore(World world) {
        WorldInfo info = world.getWorldInfo();
        // WorldClient.setWorldTime interprets packet signs and changes doDaylightCycle.
        world.provider.setWorldTime(time);
        info.setCleanWeatherTime(clearTime);
        info.setRainTime(rainTime);
        info.setThunderTime(thunderTime);
        info.setRaining(raining);
        info.setThundering(thundering);
        world.rainingStrength = rain;
        world.prevRainingStrength = previousRain;
        world.thunderingStrength = thunder;
        world.prevThunderingStrength = previousThunder;
    }

    static void setSky(World world, long time, boolean rain) {
        world.provider.setWorldTime(time);
        WorldInfo info = world.getWorldInfo();
        info.setCleanWeatherTime(0);
        info.setRainTime(12000);
        info.setThunderTime(12000);
        info.setRaining(rain);
        info.setThundering(false);
        world.setRainStrength(rain ? 1f : 0f);
        world.setThunderStrength(0f);
    }
}

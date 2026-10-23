/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import net.minecraft.world.GameRules;

/** Original values for exactly the game rules temporarily changed by the smoke fixture. */
final class FixtureGameRules {
    private static final String[] NAMES = {
        "doDaylightCycle", "doMobSpawning", "doWeatherCycle", "randomTickSpeed"
    };
    private final String[] values;

    private FixtureGameRules(String[] values) { this.values = values; }

    static FixtureGameRules capture(GameRules rules) {
        String[] values = new String[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) { values[i] = rules.getString(NAMES[i]); }
        return new FixtureGameRules(values);
    }

    void restore(GameRules rules) {
        for (int i = 0; i < NAMES.length; i++) { rules.setOrCreateGameRule(NAMES[i], values[i]); }
    }
}

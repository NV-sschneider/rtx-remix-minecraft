/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import net.minecraft.world.GameRules;
import org.junit.Test;

import static org.junit.Assert.*;

public class FixtureGameRulesTest {
    @Test
    public void restoresOriginalStringsWithoutResettingOtherRules() {
        GameRules rules = new GameRules();
        rules.setOrCreateGameRule("doDaylightCycle", "false");
        rules.setOrCreateGameRule("doMobSpawning", "true");
        rules.setOrCreateGameRule("doWeatherCycle", "false");
        rules.setOrCreateGameRule("randomTickSpeed", "17");
        FixtureGameRules snapshot = FixtureGameRules.capture(rules);

        rules.setOrCreateGameRule("doDaylightCycle", "true");
        rules.setOrCreateGameRule("doMobSpawning", "false");
        rules.setOrCreateGameRule("doWeatherCycle", "true");
        rules.setOrCreateGameRule("randomTickSpeed", "0");
        rules.setOrCreateGameRule("keepInventory", "true");
        rules.setOrCreateGameRule("fixtureUnrelatedRule", "custom value");
        snapshot.restore(rules);

        assertEquals("false", rules.getString("doDaylightCycle"));
        assertEquals("true", rules.getString("doMobSpawning"));
        assertEquals("false", rules.getString("doWeatherCycle"));
        assertEquals("17", rules.getString("randomTickSpeed"));
        assertEquals("true", rules.getString("keepInventory"));
        assertEquals("custom value", rules.getString("fixtureUnrelatedRule"));
    }

    @Test
    public void snapshotKeepsNoLiveRuleReferenceAndCanRestoreAfterPartialMutation() {
        GameRules original = new GameRules();
        original.setOrCreateGameRule("randomTickSpeed", "9");
        FixtureGameRules snapshot = FixtureGameRules.capture(original);
        original.setOrCreateGameRule("doDaylightCycle", "false");

        snapshot.restore(original);
        assertEquals("true", original.getString("doDaylightCycle"));
        assertEquals("9", original.getString("randomTickSpeed"));

        GameRules independent = new GameRules();
        snapshot.restore(independent);
        independent.setOrCreateGameRule("randomTickSpeed", "0");
        snapshot.restore(independent);
        assertEquals("9", independent.getString("randomTickSpeed"));
        assertEquals("true", independent.getString("doMobSpawning"));
        assertEquals("true", independent.getString("doWeatherCycle"));
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.resources.IResourcePack;
import org.junit.Test;
import static org.junit.Assert.*;

public class FixturePackScopeTest {
    @Test public void replacementAndRemovalPreserveTheOriginalObjectsAndPriorityOrder() throws Exception {
        IResourcePack base = new FixturePbrPack(1), selected = new FixturePbrPack(2), fixture = new FixturePbrPack(1);
        List<IResourcePack> original = new ArrayList<IResourcePack>(Arrays.asList(base, selected));
        List<List<IResourcePack>> loads = new ArrayList<>();
        FixturePackScope scope = new FixturePackScope(original, packs -> loads.add(new ArrayList<IResourcePack>(packs)));
        original.clear();
        scope.install(fixture);
        scope.install(fixture);
        scope.close(); scope.close();
        assertEquals(3, loads.size());
        assertEquals(Arrays.asList(base, selected, fixture), loads.get(0));
        assertEquals(Arrays.asList(base, selected, fixture), loads.get(1));
        assertEquals(Arrays.asList(base, selected), loads.get(2));
        assertSame(base, loads.get(2).get(0)); assertSame(selected, loads.get(2).get(1));
        assertFalse(scope.installed());
    }

    @Test public void partialReloadFailureStillRestoresThePriorResources() throws Exception {
        IResourcePack base = new FixturePbrPack(1), fixture = new FixturePbrPack(2);
        final int[] calls = {0};
        FixturePackScope scope = new FixturePackScope(Arrays.asList(base), packs -> {
            if (++calls[0] == 1) throw new IllegalStateException("listener failed");
            assertEquals(Arrays.asList(base), packs);
        });
        try { scope.install(fixture); fail("Listener must fail"); }
        catch (IllegalStateException expected) { assertEquals("listener failed", expected.getMessage()); }
        assertTrue(scope.installed());
        scope.close();
        assertEquals(2, calls[0]); assertFalse(scope.installed());
    }

    @Test public void failedRestorationRetainsOwnershipForTheNextCleanupAttempt() throws Exception {
        IResourcePack base = new FixturePbrPack(1), fixture = new FixturePbrPack(2);
        final int[] calls = {0};
        FixturePackScope scope = new FixturePackScope(Arrays.asList(base), packs -> {
            if (++calls[0] == 2) throw new LinkageError("restore failed");
        });
        scope.install(fixture);
        try { scope.close(); fail("First restore must fail"); }
        catch (LinkageError expected) { assertEquals("restore failed", expected.getMessage()); }
        assertTrue(scope.installed());
        scope.close(); scope.close();
        assertEquals(3, calls[0]); assertFalse(scope.installed());
    }
}

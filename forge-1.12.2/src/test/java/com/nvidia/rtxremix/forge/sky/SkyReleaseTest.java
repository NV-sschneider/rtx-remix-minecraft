/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongConsumer;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkyReleaseTest {
    @Test public void failedDomeReleaseRetainsItsTextureAndRetriesOnlyOwnedHandles() {
        long[] lights = {1, 2, 3}, texture = {4};
        Releases releases = new Releases();
        releases.failOnce = 3;
        try {
            SkyRelease.scene(lights, texture, () -> { }, releases, releases);
            fail();
        } catch (IllegalStateException expected) { }
        assertArrayEquals(new long[] {0, 0, 3}, lights);
        assertArrayEquals(new long[] {4}, texture);
        assertFalse(releases.destroyed.contains(4L));
        SkyRelease.scene(lights, texture, () -> { }, releases, releases);
        SkyRelease.scene(lights, texture, () -> { }, releases, releases);
        assertArrayEquals(new long[3], lights);
        assertArrayEquals(new long[1], texture);
        assertEquals(4, releases.destroyed.size());
    }

    @Test public void failedMoonMeshKeepsOnlyItsMaterialTextureAndBlend() {
        long[] meshes = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        long[] materials = {101, 102, 103}, textures = {201, 202}, blend = {301};
        Releases releases = new Releases();
        releases.failOnce = 5;
        Runnable freeBlend = () -> { if (blend[0] != 0) { releases.accept(blend[0]); blend[0] = 0; } };
        try {
            SkyRelease.celestials(meshes, materials, textures, freeBlend, releases, releases, releases);
            fail();
        } catch (IllegalStateException expected) { }
        assertArrayEquals(new long[] {0, 0, 0, 0, 5, 0, 0, 0, 0, 0}, meshes);
        assertArrayEquals(new long[] {0, 102, 0}, materials);
        assertArrayEquals(new long[] {0, 202}, textures);
        assertEquals(301, blend[0]);
        SkyRelease.celestials(meshes, materials, textures, freeBlend, releases, releases, releases);
        SkyRelease.celestials(meshes, materials, textures, freeBlend, releases, releases, releases);
        assertArrayEquals(new long[10], meshes);
        assertArrayEquals(new long[3], materials);
        assertArrayEquals(new long[2], textures);
        assertEquals(0, blend[0]);
        assertEquals(16, releases.destroyed.size());
    }

    @Test public void failedMaterialPreservesItsTextureAfterOtherGroupsAreReleased() {
        long[] meshes = new long[10], materials = {101, 102, 0}, textures = {201, 202};
        Releases releases = new Releases();
        releases.failOnce = 101;
        try {
            SkyRelease.celestials(meshes, materials, textures, () -> { }, releases, releases, releases);
            fail();
        } catch (IllegalStateException expected) { }
        assertArrayEquals(new long[] {101, 0, 0}, materials);
        assertArrayEquals(new long[] {201, 0}, textures);
        SkyRelease.celestials(meshes, materials, textures, () -> { }, releases, releases, releases);
        assertArrayEquals(new long[3], materials);
        assertArrayEquals(new long[2], textures);
        assertEquals(4, releases.destroyed.size());
    }

    @Test public void independentReleasesContinueAndAggregateLinkageFailures() {
        long[] lights = {1, 2, 3}, texture = {4};
        Releases releases = new Releases();
        releases.failOnce = 1;
        try {
            SkyRelease.scene(lights, texture, () -> { throw new UnsatisfiedLinkError("celestials"); }, releases, releases);
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause() instanceof UnsatisfiedLinkError);
            assertEquals(1, expected.getCause().getSuppressed().length);
        }
        assertArrayEquals(new long[] {1, 0, 0}, lights);
        assertArrayEquals(new long[1], texture);
        SkyRelease.scene(lights, texture, () -> { }, releases, releases);
        assertEquals(4, releases.destroyed.size());
    }

    private static final class Releases implements LongConsumer {
        final Set<Long> destroyed = new HashSet<>();
        long failOnce;
        @Override public void accept(long handle) {
            assertTrue(handle != 0);
            if (handle == failOnce) { failOnce = 0; throw new IllegalStateException("release " + handle); }
            assertTrue("A successful release must never be retried", destroyed.add(handle));
        }
    }
}

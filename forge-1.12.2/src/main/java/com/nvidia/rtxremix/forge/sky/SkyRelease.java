/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

import java.util.function.LongConsumer;

/** Retryable sky teardown. A native handle remains owned until its release succeeds. */
final class SkyRelease {
    private SkyRelease() { }

    /** Lights are sun, moon and dome; only the dome references the illumination texture. */
    static void scene(long[] lights, long[] texture, Runnable celestials,
                      LongConsumer destroyLight, LongConsumer destroyTexture) {
        Throwable failure = null;
        try { celestials.run(); }
        catch (RuntimeException | LinkageError error) { failure = error; }
        for (int i = 0; i < lights.length; i++) failure = release(lights, i, destroyLight, failure);
        if (lights[2] == 0) failure = release(texture, 0, destroyTexture, failure);
        if (failure != null) throw new IllegalStateException("Cannot release sky scene", failure);
    }

    /** Meshes are sun, eight moon phases and stars; each group has its own material. */
    static void celestials(long[] meshes, long[] materials, long[] textures, Runnable freeBlend,
                          LongConsumer destroyMesh, LongConsumer destroyMaterial, LongConsumer destroyTexture) {
        Throwable failure = null;
        for (int i = 0; i < meshes.length; i++) failure = release(meshes, i, destroyMesh, failure);
        for (int i = 0; i < materials.length; i++) {
            boolean referenced = false;
            for (int mesh = 0; mesh < meshes.length; mesh++) {
                int material = mesh == 0 ? 0 : mesh == 9 ? 2 : 1;
                if (material == i && meshes[mesh] != 0) referenced = true;
            }
            if (!referenced) failure = release(materials, i, destroyMaterial, failure);
        }
        for (int i = 0; i < textures.length; i++) {
            if (materials[i] == 0) failure = release(textures, i, destroyTexture, failure);
        }
        boolean meshesReleased = true;
        for (long mesh : meshes) if (mesh != 0) meshesReleased = false;
        if (meshesReleased) {
            try { freeBlend.run(); }
            catch (RuntimeException | LinkageError error) { failure = collect(failure, error); }
        }
        if (failure != null) throw new IllegalStateException("Cannot release celestial resources", failure);
    }

    private static Throwable release(long[] handles, int index, LongConsumer destroy, Throwable failure) {
        if (handles[index] == 0) return failure;
        try { destroy.accept(handles[index]); handles[index] = 0; }
        catch (RuntimeException | LinkageError error) { failure = collect(failure, error); }
        return failure;
    }

    private static Throwable collect(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
}

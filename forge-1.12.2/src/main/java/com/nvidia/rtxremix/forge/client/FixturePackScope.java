/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.resources.IResourcePack;

/** A fixture-only resource layer that restores the exact original pack objects, including order. */
final class FixturePackScope implements AutoCloseable {
    interface Reload { void load(List<IResourcePack> packs); }
    private final List<IResourcePack> original;
    private final Reload reload;
    private boolean installed;

    FixturePackScope(List<IResourcePack> original, Reload reload) {
        this.original = Collections.unmodifiableList(new ArrayList<IResourcePack>(original));
        this.reload = reload;
    }

    void install(IResourcePack pack) {
        List<IResourcePack> next = new ArrayList<IResourcePack>(original);
        next.add(pack);
        // A listener can throw after partially loading the new resources.
        installed = true;
        reload.load(next);
    }

    boolean installed() { return installed; }
    @Override public void close() {
        if (!installed) return;
        reload.load(original);
        installed = false;
    }
}

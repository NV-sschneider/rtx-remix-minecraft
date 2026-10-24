/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.InstanceCategoryBit;

/** The supported fixed-function surface contracts; unsupported blending is never guessed. */
final class EntitySurface {
    static final int ORDINARY = 0, EYES = 1, GLINT = 2, ADDITIVE_ALPHA = 3;
    // Matches the upstream entity shell spacing; ordinary and model-box passes stay unchanged.
    static final float ITEM_GLINT_SHELL_OFFSET = 0.0005f;
    private EntitySurface() { }

    static int categories(int ownerCategory, int surface, boolean separatedShell) {
        // Offset item glint is an additive surface. Decals use deferred material/opacity
        // blending instead of unordered emissive accumulation, even on a view model.
        return surface == ORDINARY || surface == GLINT && separatedShell ? ownerCategory
                : ownerCategory | InstanceCategoryBit.DECAL_DYNAMIC | InstanceCategoryBit.DECAL_NO_OFFSET;
    }

    static int classify(boolean blend, int source, int destination, int equation, boolean glint) {
        if (!blend) return glint ? -1 : ORDINARY;
        if (equation != 32774 /* GL_FUNC_ADD */) return -1;
        if (glint) return source == 768 /* GL_SRC_COLOR */ && destination == 1 ? GLINT : -1;
        if (source == 1 && destination == 1) return EYES;
        if (source == 770 /* GL_SRC_ALPHA */ && destination == 1) return ADDITIVE_ALPHA;
        return source == 770 && destination == 771 ? ORDINARY : -1;
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.item;

import net.minecraft.entity.item.EntityItem;

/** A render-thread snapshot valid until the next beginFrame or discard. */
public final class CapturedItem {
    EntityItem owner;
    Object model;
    Object stack;
    ItemGeometry geometry;
    int occurrence;
    int firstVertex;
    int vertexCount;
    long fingerprint;
    boolean alphaBlend;
    int alphaCompare;
    int alphaReference;
    final float[] transform = new float[12];

    void clear() { owner = null; model = null; stack = null; geometry = null; }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.EntityLivingBase;

/** Render-thread scratch data, valid only until the next capture frame. */
public final class CapturedEntityPart {
    public EntityLivingBase owner;
    public ModelRenderer modelPart;
    public int occurrence;
    public float scale;
    public int textureId;
    public Object textureIdentity;
    public int argb;
    public boolean alphaBlend;
    public int alphaCompare;
    public int alphaReference;
    public final float[] transform = new float[12];

    void clear() {
        owner = null;
        modelPart = null;
        textureIdentity = null;
    }
}

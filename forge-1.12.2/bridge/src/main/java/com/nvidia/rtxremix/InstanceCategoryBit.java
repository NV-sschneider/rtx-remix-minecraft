/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Instance category bit flags
 */
public final class InstanceCategoryBit {
    public static final int WORLD_UI = 1 << 0;
    public static final int WORLD_MATTE = 1 << 1;
    public static final int SKY = 1 << 2;
    public static final int IGNORE = 1 << 3;
    public static final int IGNORE_LIGHTS = 1 << 4;
    public static final int IGNORE_ANTI_CULLING = 1 << 5;
    public static final int IGNORE_MOTION_BLUR = 1 << 6;
    public static final int IGNORE_OPACITY_MICROMAP = 1 << 7;
    public static final int HIDDEN = 1 << 8;
    public static final int PARTICLE = 1 << 9;
    public static final int BEAM = 1 << 10;
    public static final int DECAL_STATIC = 1 << 11;
    public static final int DECAL_DYNAMIC = 1 << 12;
    public static final int DECAL_SINGLE_OFFSET = 1 << 13;
    public static final int DECAL_NO_OFFSET = 1 << 14;
    public static final int ALPHA_BLEND_TO_CUTOUT = 1 << 15;
    public static final int TERRAIN = 1 << 16;
    public static final int ANIMATED_WATER = 1 << 17;
    public static final int THIRD_PERSON_PLAYER_MODEL = 1 << 18;
    public static final int THIRD_PERSON_PLAYER_BODY = 1 << 19;
    public static final int IGNORE_BAKED_LIGHTING = 1 << 20;
    public static final int IGNORE_ALPHA_CHANNEL = 1 << 21;
    public static final int IGNORE_TRANSPARENCY_LAYER = 1 << 22;
    public static final int PARTICLE_EMITTER = 1 << 23;
    public static final int SMOOTH_NORMALS = 1 << 24;
    public static final int HAIR_CARDS = 1 << 25;
    public static final int VIEW_MODEL = 1 << 26;

    private InstanceCategoryBit() {}
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Structure type identifiers for Remix API structures
 */
public final class StructType {
    public static final int NONE = 0;
    public static final int INITIALIZE_LIBRARY_INFO = 1;
    public static final int MATERIAL_INFO = 2;
    public static final int MATERIAL_INFO_PORTAL_EXT = 3;
    public static final int MATERIAL_INFO_TRANSLUCENT_EXT = 4;
    public static final int MATERIAL_INFO_OPAQUE_EXT = 5;
    public static final int LIGHT_INFO = 6;
    public static final int LIGHT_INFO_DISTANT_EXT = 7;
    public static final int LIGHT_INFO_CYLINDER_EXT = 8;
    public static final int LIGHT_INFO_DISK_EXT = 9;
    public static final int LIGHT_INFO_RECT_EXT = 10;
    public static final int LIGHT_INFO_SPHERE_EXT = 11;
    public static final int MESH_INFO = 12;
    public static final int INSTANCE_INFO = 13;
    public static final int INSTANCE_INFO_BONE_TRANSFORMS_EXT = 14;
    public static final int INSTANCE_INFO_BLEND_EXT = 15;
    public static final int CAMERA_INFO = 16;
    public static final int CAMERA_INFO_PARAMETERIZED_EXT = 17;
    public static final int MATERIAL_INFO_OPAQUE_SUBSURFACE_EXT = 18;
    public static final int INSTANCE_INFO_OBJECT_PICKING_EXT = 19;
    public static final int LIGHT_INFO_DOME_EXT = 20;
    public static final int LIGHT_INFO_USD_EXT = 21;
    public static final int STARTUP_INFO = 22;
    public static final int PRESENT_INFO = 23;
    public static final int DEPRECATED_LEGACY_PARTICLE_SYSTEM = 24;
    public static final int INSTANCE_INFO_PARTICLE_SYSTEM_EXT = 25;
    public static final int INSTANCE_INFO_GPU_INSTANCING_EXT = 26;
    public static final int CAMERA_MEDIUM_INFO = 27;
    public static final int TEXTURE_FROM_MEMORY_INFO = 28;
    public static final int TEXTURE_PATCH_INFO = 29;
    public static final int SUBMIT_INFO = 30;
    public static final int CONFIG_VARIABLE_RESOLVED_INFO = 31;

    private StructType() {}
}

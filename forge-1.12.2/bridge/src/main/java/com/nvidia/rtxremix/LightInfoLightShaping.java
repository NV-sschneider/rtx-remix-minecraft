/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Light shaping parameters for spot lights.
 */
public class LightInfoLightShaping {
    public Float3D direction = new Float3D();
    public float coneAngleDegrees;
    public float coneSoftness;
    public float focusExponent;

    public LightInfoLightShaping() {}
}

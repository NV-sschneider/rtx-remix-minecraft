/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Submit information structure
 */
public class SubmitInfo {
    // remixapi_SubmitInteropFlagBit
    public static final int FORCE_WAIT_DEVICE_IDLE          = 1 << 0;
    public static final int DISABLE_CPU_WAIT_FOR_VK_SUBMIT  = 1 << 1;
    public static final int DISABLE_QUEUE_SUBMIT            = 1 << 2;

    public int interopFlags; // see remixapi_SubmitInteropFlagBit

    public SubmitInfo() {}
}

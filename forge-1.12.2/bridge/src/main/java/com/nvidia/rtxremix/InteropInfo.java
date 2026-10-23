/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

public class InteropInfo {
    /** Win32 HANDLE: DXVK's Vulkan signals this semaphore after writing */
    public long dxvkDoneSemaphore_Handle;
    /** Timeline value signaled by Remix after rendering; wait for this value in glWaitSemaphoreEXT */
    public long dxvkDoneSemaphore_ValueToWait;
    /** Win32 HANDLE: client signals this semaphore after writing */
    public long clientDoneSemaphore_Handle;
    /** Win32 HANDLE: image memory, import into the client graphics API */
    public long imageMemory_Handle;
    /** Byte size of the shared image memory */
    public long allocationSize;
    /** Image width in pixels */
    public int width;
    /** Image height in pixels */
    public int height;
    /** Remix texture format of the color image; the client maps it to its own API's format */
    public int interopImageFormat;
    /** Win32 HANDLE: read-only primary-depth image memory */
    public long depthImageMemory_Handle;
    /** Byte size of the shared depth image memory */
    public long depthAllocationSize;
    /** Primary-depth image width in pixels */
    public int depthWidth;
    /** Primary-depth image height in pixels */
    public int depthHeight;
    /** Primary-depth image format */
    public int interopDepthImageFormat;

    public InteropInfo() {}
}


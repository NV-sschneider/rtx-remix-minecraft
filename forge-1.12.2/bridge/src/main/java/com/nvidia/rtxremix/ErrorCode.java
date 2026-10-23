/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Error codes returned by Remix API functions
 */
public final class ErrorCode {
    public static final int SUCCESS = 0;
    public static final int GENERAL_FAILURE = 1;
    public static final int LOAD_LIBRARY_FAILURE = 2;
    public static final int INVALID_ARGUMENTS = 3;
    public static final int GET_PROC_ADDRESS_FAILURE = 4;
    public static final int ALREADY_EXISTS = 5;
    public static final int REGISTERING_NON_REMIX_D3D9_DEVICE = 6;
    public static final int REMIX_DEVICE_WAS_NOT_REGISTERED = 7;
    public static final int INCOMPATIBLE_VERSION = 8;
    public static final int SET_DLL_DIRECTORY_FAILURE = 9;
    public static final int GET_FULL_PATH_NAME_FAILURE = 10;
    public static final int NOT_INITIALIZED = 11;
    public static final int INDEX_OUT_OF_RANGE = 12;
    public static final int BUFFER_TOO_SMALL = 13;

    // HRESULT error codes
    public static final int HRESULT_NO_REQUIRED_GPU_FEATURES = 0x88960001;
    public static final int HRESULT_DRIVER_VERSION_BELOW_MINIMUM = 0x88960002;
    public static final int HRESULT_DXVK_INSTANCE_EXTENSION_FAIL = 0x88960003;
    public static final int HRESULT_VK_CREATE_INSTANCE_FAIL = 0x88960004;
    public static final int HRESULT_VK_CREATE_DEVICE_FAIL = 0x88960005;
    public static final int HRESULT_GRAPHICS_QUEUE_FAMILY_MISSING = 0x88960006;

    private ErrorCode() {}

    /**
     * Returns a human-readable string for an error code
     */
    public static String toString(int code) {
        switch (code) {
            case SUCCESS: return "SUCCESS";
            case GENERAL_FAILURE: return "GENERAL_FAILURE";
            case LOAD_LIBRARY_FAILURE: return "LOAD_LIBRARY_FAILURE";
            case INVALID_ARGUMENTS: return "INVALID_ARGUMENTS";
            case GET_PROC_ADDRESS_FAILURE: return "GET_PROC_ADDRESS_FAILURE";
            case ALREADY_EXISTS: return "ALREADY_EXISTS";
            case REGISTERING_NON_REMIX_D3D9_DEVICE: return "REGISTERING_NON_REMIX_D3D9_DEVICE";
            case REMIX_DEVICE_WAS_NOT_REGISTERED: return "REMIX_DEVICE_WAS_NOT_REGISTERED";
            case INCOMPATIBLE_VERSION: return "INCOMPATIBLE_VERSION";
            case SET_DLL_DIRECTORY_FAILURE: return "SET_DLL_DIRECTORY_FAILURE";
            case GET_FULL_PATH_NAME_FAILURE: return "GET_FULL_PATH_NAME_FAILURE";
            case NOT_INITIALIZED: return "NOT_INITIALIZED";
            case INDEX_OUT_OF_RANGE: return "INDEX_OUT_OF_RANGE";
            case BUFFER_TOO_SMALL: return "BUFFER_TOO_SMALL";
            case HRESULT_NO_REQUIRED_GPU_FEATURES: return "NO_REQUIRED_GPU_FEATURES";
            case HRESULT_DRIVER_VERSION_BELOW_MINIMUM: return "DRIVER_VERSION_BELOW_MINIMUM";
            case HRESULT_DXVK_INSTANCE_EXTENSION_FAIL: return "DXVK_INSTANCE_EXTENSION_FAIL";
            case HRESULT_VK_CREATE_INSTANCE_FAIL: return "VK_CREATE_INSTANCE_FAIL";
            case HRESULT_VK_CREATE_DEVICE_FAIL: return "VK_CREATE_DEVICE_FAIL";
            case HRESULT_GRAPHICS_QUEUE_FAMILY_MISSING: return "GRAPHICS_QUEUE_FAMILY_MISSING";
            default: return "UNKNOWN_ERROR(" + code + ")";
        }
    }
}

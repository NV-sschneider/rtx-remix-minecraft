/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Exception thrown when a Remix API call fails
 */
public class RemixException extends RuntimeException {
    private final int errorCode;

    public RemixException(String message, int errorCode) {
        super(message + ": " + ErrorCode.toString(errorCode));
        this.errorCode = errorCode;
    }

    public RemixException(String message) {
        super(message);
        this.errorCode = ErrorCode.GENERAL_FAILURE;
    }

    /**
     * Returns the Remix API error code
     */
    public int getErrorCode() {
        return errorCode;
    }
}

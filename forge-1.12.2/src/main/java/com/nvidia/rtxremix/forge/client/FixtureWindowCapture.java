/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiConsumer;

/** Holds an opt-in smoke checkpoint until its external owned-window capture is acknowledged. */
final class FixtureWindowCapture {
    private final Path acknowledgement;
    private String requested;
    private String acknowledged;

    FixtureWindowCapture() { this(System.getProperty("rlcraftremix.smoke.windowCaptureAck", "")); }
    FixtureWindowCapture(String path) {
        acknowledgement = path.isEmpty() ? null : Paths.get(path);
        if (acknowledgement != null && !acknowledgement.isAbsolute())
            throw new IllegalArgumentException("Window capture acknowledgment path must be absolute");
    }

    boolean configured() { return acknowledgement != null; }

    boolean await(String save, String checkpoint, String status, BiConsumer<String, String> log) throws IOException {
        String token = save + ":" + checkpoint;
        String detail = "checkpoint=" + checkpoint + " token=" + token + " " + status;
        if (!token.equals(requested)) {
            requested = token;
            log.accept("WINDOW_CAPTURE_READY", detail);
        }
        if (acknowledgement == null) return true;
        if (!Files.exists(acknowledgement, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isRegularFile(acknowledgement, LinkOption.NOFOLLOW_LINKS) || Files.size(acknowledgement) > 512)
            throw new IllegalStateException("Invalid window capture acknowledgment file");
        if (!token.equals(new String(Files.readAllBytes(acknowledgement), StandardCharsets.UTF_8).trim())) return false;
        if (!token.equals(acknowledged)) {
            acknowledged = token;
            log.accept("WINDOW_CAPTURE_ACK", detail);
        }
        return true;
    }
}

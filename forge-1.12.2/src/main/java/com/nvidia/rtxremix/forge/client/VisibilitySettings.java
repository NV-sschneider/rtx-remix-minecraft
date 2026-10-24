/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.function.BiConsumer;

/** Explicit Forge-side preferences; an absent override leaves Remix's configuration untouched. */
final class VisibilitySettings {
    static final String FILE_NAME = "rlcraft-remix-visibility.properties";
    static final String MODE_KEY = "rtx.tonemappingMode";
    static final String SHADOWS_KEY = "rtx.localtonemap.shadows";
    static final float DEFAULT_SHADOWS = 2;
    private final Path file;
    private final Properties properties = new Properties();
    private Float shadows;
    private long revision;

    VisibilitySettings(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        if (!Files.exists(this.file)) return;
        if (Files.size(this.file) > 16 * 1024) throw new IOException("Visibility preferences exceed 16 KiB");
        try (InputStream input = Files.newInputStream(this.file)) { properties.load(input); }
        String version = properties.getProperty("version", "1");
        if (!"1".equals(version)) throw new IOException("Unsupported visibility preferences version " + version);
        String value = properties.getProperty("shadows");
        if (value != null) {
            try { shadows = parseShadows(value); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid saved shadow visibility: " + value, error); }
            revision = 1;
        }
    }

    static float parseShadows(String text) {
        final float value;
        try { value = Float.parseFloat(text); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Shadow visibility must be a number from 0 to 5", error); }
        validate(value); return value;
    }
    private static void validate(float value) {
        if (!Float.isFinite(value) || value < 0 || value > 5)
            throw new IllegalArgumentException("Shadow visibility must be a number from 0 to 5");
    }

    void setShadows(float value) throws IOException {
        validate(value);
        Properties next = new Properties(); next.putAll(properties);
        next.setProperty("version", "1"); next.setProperty("shadows", Float.toString(value));
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "rlcraft-remix-visibility-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                next.store(output, "RTX Remix Forge shadow visibility; native Remix configuration is managed separately.");
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        properties.clear(); properties.putAll(next); shadows = value; revision++;
    }

    /** Reset is an explicit request for the pinned runtime's normal shadow level. */
    void reset() throws IOException { setShadows(DEFAULT_SHADOWS); }

    /** Called once per saved revision, on the render thread after the native runtime is ready. */
    void apply(BiConsumer<String, String> writer) {
        if (shadows == null) return;
        writer.accept(MODE_KEY, "1"); writer.accept(SHADOWS_KEY, Float.toString(shadows));
    }

    boolean configured() { return shadows != null; }
    float shadows() { return shadows == null ? DEFAULT_SHADOWS : shadows; }
    long revision() { return revision; }
    String description() {
        return shadows == null ? "Shadow visibility follows Remix settings (no saved override)."
                : "Shadow visibility " + shadows + " saved for local tone mapping. Range 0-5, reset " + DEFAULT_SHADOWS + ".";
    }
}

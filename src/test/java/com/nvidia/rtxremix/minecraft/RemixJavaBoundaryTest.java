/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixJavaBoundaryTest {
    private static final Pattern GAME_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?net\\.(?:minecraft|fabricmc)\\.");

    @Test
    void forgeAdaptersDoNotDuplicateTheSharedFacade() throws Exception {
        Path shared = Path.of("rtxremix-java", "src");
        Path adapters = Path.of("forge-1.12.2", "bridge", "src", "main", "java");
        assertTrue(Files.isDirectory(adapters), "missing Forge adapters: " + adapters);
        Set<String> duplicates = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(adapters)) {
            var iterator = sources.filter(path -> path.toString().endsWith(".java")).iterator();
            while (iterator.hasNext()) {
                Path relative = adapters.relativize(iterator.next());
                if (Files.exists(shared.resolve(relative))) duplicates.add(relative.toString());
            }
        }
        assertEquals(Set.of(), duplicates, "clients must compile the shared facade directly");
    }

    @Test
    void shimSourcesDoNotImportMinecraftOrFabric() throws Exception {
        Path sourceRoot = Path.of("rtxremix-java", "src");
        assertTrue(Files.isDirectory(sourceRoot), "missing Java shim sources: " + sourceRoot);

        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            var iterator = sources.filter(path -> path.toString().endsWith(".java")).iterator();
            while (iterator.hasNext()) {
                Path source = iterator.next();
                if (GAME_IMPORT.matcher(Files.readString(source)).find()) {
                    offenders.add(sourceRoot.relativize(source).toString());
                }
            }
        }

        assertEquals(Set.of(), offenders,
                "the Java shim must remain independent of Minecraft and Fabric");
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.model.ModelBox;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Optional box contracts; unknown render overrides cannot be inferred from their inherited faces. */
final class ModelBoxFaces {
    private static final String MUTATED_BOX = "goblinbob.mobends.core.client.model.MutatedBox";
    private static final Logger LOGGER = LogManager.getLogger("RLCraftRemixEntities");
    private static final Filter VANILLA = new Filter(true, null);
    private static final Filter UNSUPPORTED = new Filter(false, null);
    private static final ClassValue<Filter> FILTERS = new ClassValue<Filter>() {
        @Override protected Filter computeValue(Class<?> type) {
            if (type == ModelBox.class) return VANILLA;
            if (!MUTATED_BOX.equals(type.getName())) {
                LOGGER.warn("Unsupported model box renderer {}; its faces are omitted from Remix", type.getName());
                return UNSUPPORTED;
            }
            try {
                MethodHandle visible = MethodHandles.publicLookup().findVirtual(type,
                        "isFaceVisible", MethodType.methodType(boolean.class, int.class));
                return new Filter(true, visible.asType(MethodType.methodType(boolean.class, ModelBox.class, int.class)));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Mo' Bends MutatedBox has an incompatible face visibility contract", failure);
            }
        }
    };

    private ModelBoxFaces() { }

    static Filter forBox(ModelBox box) { return FILTERS.get(box.getClass()); }

    static final class Filter {
        final boolean supported;
        private final MethodHandle visibility;

        private Filter(boolean supported, MethodHandle visibility) {
            this.supported = supported;
            this.visibility = visibility;
        }

        boolean visible(ModelBox box, int face) {
            if (!supported) return false;
            if (visibility == null) return true;
            try { return (boolean) visibility.invokeExact(box, face); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Cannot read Mo' Bends face visibility", failure); }
        }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.item;

import java.io.File;
import java.io.InputStream;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

/** Checks the installed renderer's state contract without linking or copying its implementation. */
public class ItemPhysicContractTest {
    @Test public void installedGroundItemsUseOrdinaryBlendingForEveryBakedModelDraw() throws Exception {
        String path = System.getProperty("rtxremix.rlcraftMods"); Assume.assumeNotNull(path);
        try (JarFile jar = new JarFile(new File(path, "ItemPhysic_Full_1.4.37_mc1.12.2.jar"));
             InputStream bytes = jar.getInputStream(jar.getJarEntry("com/creativemd/itemphysic/physics/ClientPhysic.class"))) {
            ClassNode type = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(type, 0);
            int draws = 0;
            for (MethodNode method : type.methods) {
                boolean blend = false, sourceAlpha = false, inverseAlpha = false;
                for (AbstractInsnNode node : method.instructions.toArray()) {
                    if (node instanceof FieldInsnNode) {
                        FieldInsnNode field = (FieldInsnNode) node;
                        sourceAlpha |= field.owner.equals("net/minecraft/client/renderer/GlStateManager$SourceFactor")
                                && field.name.equals("SRC_ALPHA");
                        inverseAlpha |= field.owner.equals("net/minecraft/client/renderer/GlStateManager$DestFactor")
                                && field.name.equals("ONE_MINUS_SRC_ALPHA");
                    }
                    if (!(node instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) node;
                    if (call.owner.equals("net/minecraft/client/renderer/GlStateManager")) {
                        if (call.name.equals("func_179147_l")) blend = true;
                        if (call.name.equals("func_179084_k")) blend = false;
                    }
                    if (call.owner.equals("net/minecraft/client/renderer/RenderItem") && call.name.equals("func_180454_a")) {
                        assertTrue("Opaque items inherit the surrounding translucent draw state", blend && sourceAlpha && inverseAlpha);
                        draws++;
                    }
                }
            }
            assertEquals("3-D and flat item paths both use the baked RenderItem entry", 2, draws);
        }
    }
}

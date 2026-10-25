/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.forge.core.EffectCaptureTransformer;
import java.io.File;
import java.io.InputStream;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.Assert.*;

/** Optional renderer contracts; no FBP implementation is linked or copied. */
public class DebrisCaptureTransformerTest {
    private static final String DEBRIS = "com/TominoCZ/FBP/particle/FBPParticleDigging";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/effect/EffectCaptureHooks";

    @Test public void installedDiggingPreservesTintAndActualAlphaWhileEnteringItsExactScope() throws Exception {
        String path = System.getProperty("rtxremix.rlcraftMods"); Assume.assumeNotNull(path);
        try (JarFile jar = new JarFile(new File(path, "FancyBlockParticles-1.12.x-2.4.1.jar"))) {
            ClassNode source;
            try (InputStream input = jar.getInputStream(jar.getJarEntry(DEBRIS + ".class"))) {
                source = new ClassNode(Opcodes.ASM5); new ClassReader(input).accept(source, 0);
            }
            MethodNode render = render(source);
            String cube = "com/TominoCZ/FBP/util/FBPRenderUtil";
            assertEquals(1, calls(render, cube, "renderCubeShaded_S"));
            for (String field : new String[] {"field_187119_C", "prevParticleAlpha", "field_82339_as",
                    "field_70552_h", "field_70553_i", "field_70551_j"}) assertTrue(reads(render, field));
            for (AbstractInsnNode node : render.instructions.toArray())
                if (node instanceof FieldInsnNode && node.getOpcode() == Opcodes.PUTFIELD)
                    assertNotEquals("field_187119_C", ((FieldInsnNode) node).name);
            assertEquals(0, calls(render, "net/minecraft/client/renderer/GlStateManager", "func_179084_k"));
            ClassWriter writer = new ClassWriter(0); source.accept(writer);
            byte[] original = writer.toByteArray(), changed = transform(original);
            ClassNode result = new ClassNode(Opcodes.ASM5); new ClassReader(changed).accept(result, 0);
            assertEquals(1, calls(render(result), HOOKS, "beginDebris"));
            assertEquals(2, calls(render(result), HOOKS, "endDebris"));
            assertEquals(1, calls(render(result), cube, "renderCubeShaded_S"));
            assertEquals(render.tryCatchBlocks.size() + 1, render(result).tryCatchBlocks.size());
            assertEquals(null, render(result).tryCatchBlocks.get(render(result).tryCatchBlocks.size() - 1).type);
            new Analyzer<>(new BasicVerifier()).analyze(result.name, render(result));
            assertArrayEquals(changed, transform(changed));
            assertArrayEquals(original, new EffectCaptureTransformer().transform("ignored",
                    "com.TominoCZ.FBP.particle.FBPParticleSmokeNormal", original));
        }
    }

    private static byte[] transform(byte[] bytes) {
        return new EffectCaptureTransformer().transform(DEBRIS, DEBRIS.replace('/', '.'), bytes);
    }
    private static MethodNode render(ClassNode type) {
        for (MethodNode method : type.methods) if ("func_180434_a".equals(method.name)) return method;
        throw new AssertionError("Missing particle renderer");
    }
    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode node : method.instructions.toArray()) if (node instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) node;
            if (owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }
    private static boolean reads(MethodNode method, String name) {
        for (AbstractInsnNode node : method.instructions.toArray()) if (node instanceof FieldInsnNode
                && node.getOpcode() == Opcodes.GETFIELD && name.equals(((FieldInsnNode) node).name)) return true;
        return false;
    }
}

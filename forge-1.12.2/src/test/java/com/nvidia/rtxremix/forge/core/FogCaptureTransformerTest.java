/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.Assert.*;

public class FogCaptureTransformerTest {
    private static final String TARGET = "net.minecraft.client.renderer.EntityRenderer";
    private static final String HOOK = "com/nvidia/rtxremix/forge/fog/FogCaptureHooks";

    @Test public void pinnedFogCaptureRunsAfterEventsAndFinalEnableWithoutChangingVanillaCalls() throws Exception {
        byte[] input = original(); ClassNode before = read(input);
        FogCaptureTransformer transformer = new FogCaptureTransformer();
        byte[] output = transformer.transform("obfuscated", TARGET, input); ClassNode after = read(output);
        MethodNode fog = fog(after, "setupFog"), oldFog = fog(before, "setupFog");
        assertEquals(oldFog.instructions.size() + 2, fog.instructions.size());
        assertEquals(1, count(fog, HOOK, "afterSetup"));
        assertEquals(count(oldFog, "net/minecraftforge/client/ForgeHooksClient", "getFogDensity"),
                count(fog, "net/minecraftforge/client/ForgeHooksClient", "getFogDensity"));
        assertEquals(1, count(fog, "net/minecraftforge/client/ForgeHooksClient", "onFogRender"));
        int callback = -1, enable = -1, observation = -1, index = 0;
        for (AbstractInsnNode instruction : fog.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.name.equals("onFogRender")) callback = index;
                if (call.name.equals("enableFog")) enable = index;
                if (call.owner.equals(HOOK)) {
                    observation = index; assertEquals("(I)V", call.desc);
                    VarInsnNode argument = (VarInsnNode) call.getPrevious();
                    assertEquals(Opcodes.ILOAD, argument.getOpcode()); assertEquals(1, argument.var);
                    assertEquals(Opcodes.RETURN, call.getNext().getOpcode());
                }
            }
            index++;
        }
        assertTrue(callback >= 0 && enable > callback && observation > enable);
        verify(after); assertArrayEquals(output, transformer.transform("obfuscated", TARGET, output));
    }

    @Test public void supportsProductionSrgAndCoexistsWithOtherEntityRendererHooks() throws Exception {
        byte[] input = new EffectCaptureTransformer().transform("obfuscated", TARGET, original());
        ClassNode srg = read(input); fog(srg, "setupFog").name = "func_78468_a";
        ClassWriter writer = new ClassWriter(0); srg.accept(writer);
        byte[] output = new FogCaptureTransformer().transform("obfuscated", TARGET, writer.toByteArray());
        ClassNode after = read(output); assertEquals(1, count(fog(after, "func_78468_a"), HOOK, "afterSetup"));
        verify(after);
        assertArrayEquals(input, new FogCaptureTransformer().transform("other", "other", input));
        assertNull(new FogCaptureTransformer().transform("other", TARGET, null));
    }

    private static byte[] original() throws Exception {
        try (InputStream input = FogCaptureTransformerTest.class.getClassLoader().getResourceAsStream(TARGET.replace('.', '/') + ".class")) {
            assertNotNull(input); ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[8192]; int count;
            while ((count = input.read(bytes)) >= 0) output.write(bytes, 0, count); return output.toByteArray();
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, 0); return node; }
    private static MethodNode fog(ClassNode node, String name) {
        for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals("(IF)V")) return method;
        throw new AssertionError("Missing pinned fog method");
    }
    private static int count(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction; if (call.owner.equals(owner) && call.name.equals(name)) count++;
        }
        return count;
    }
    private static void verify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.Assert.*;

public class TerrainDrawTransformerTest {
    private static final String VBO = "net.minecraft.client.renderer.VboRenderList";
    private static final String HOOK = "com/nvidia/rtxremix/forge/terrain/VanillaTerrainDraws";

    @Test public void replacesOnlyThePinnedDrawAndPreservesTraversalBindingPointersAndCleanup() throws Exception {
        for (boolean srg : new boolean[] {false, true}) {
            ClassNode source = read(bytes(VBO));
            if (srg) for (MethodNode method : source.methods) {
                if (method.name.equals("renderChunkLayer")) method.name = "func_178001_a";
                for (AbstractInsnNode node : method.instructions.toArray()) if (node instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) node;
                    if (call.name.equals("getVertexBufferByLayer")) call.name = "func_178565_b";
                    if (call.name.equals("drawArrays")) call.name = "func_177358_a";
                }
            }
            byte[] transformed = transform(write(source)); ClassNode result = read(transformed);
            assertTrue(marker(result)); assertSame(transformed, transform(transformed));
            assertEquals(calls(source, false), calls(result, true));
            int hooks = 0;
            for (MethodNode method : result.methods) {
                new Analyzer<>(new BasicVerifier()).analyze(result.name, method);
                for (AbstractInsnNode node : method.instructions.toArray())
                    if (node instanceof MethodInsnNode && HOOK.equals(((MethodInsnNode) node).owner)) hooks++;
            }
            assertEquals(1, hooks);
        }
    }

    @Test public void changedModeAndDisplayListsCannotAcquireTheSuppressionContract() throws Exception {
        ClassNode source = read(bytes(VBO));
        for (MethodNode method : source.methods) for (AbstractInsnNode node : method.instructions.toArray())
            if (node instanceof IntInsnNode && node.getOpcode() == Opcodes.BIPUSH && ((IntInsnNode) node).operand == 7)
                ((IntInsnNode) node).operand = 4;
        byte[] changed = write(source); assertSame(changed, transform(changed)); assertFalse(marker(read(changed)));
        String listed = "net.minecraft.client.renderer.RenderList"; byte[] list = bytes(listed);
        assertSame(list, new TerrainDrawTransformer().transform(listed, listed, list));
    }

    private static List<String> calls(ClassNode type, boolean transformed) {
        List<String> result = new ArrayList<>();
        for (MethodNode method : type.methods) for (AbstractInsnNode node : method.instructions.toArray()) if (node instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) node;
            if (transformed && HOOK.equals(call.owner)) continue;
            if (!transformed && call.owner.equals("net/minecraft/client/renderer/vertex/VertexBuffer")
                    && (call.name.equals("drawArrays") || call.name.equals("func_177358_a"))) continue;
            result.add(call.getOpcode() + ":" + call.owner + ":" + call.name + call.desc);
        }
        return result;
    }
    private static boolean marker(ClassNode type) {
        for (FieldNode field : type.fields) if (field.name.equals("rtxremixVboDrawContract") && Integer.valueOf(1).equals(field.value)) return true;
        return false;
    }
    private static byte[] transform(byte[] data) { return new TerrainDrawTransformer().transform(VBO, VBO, data); }
    private static ClassNode read(byte[] data) { ClassNode type = new ClassNode(Opcodes.ASM5); new ClassReader(data).accept(type, 0); return type; }
    private static byte[] write(ClassNode type) { ClassWriter writer = new ClassWriter(0); type.accept(writer); return writer.toByteArray(); }
    private static byte[] bytes(String name) throws Exception {
        try (InputStream input = TerrainDrawTransformerTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(input); ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read); return output.toByteArray();
        }
    }
}

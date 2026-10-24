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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.Assert.*;

public class FastTileCaptureTransformerTest {
    private static final String DISPATCHER = "net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/entity/FastTileCaptureHooks";

    @Test public void pinnedMcpAndSrgCallsAreObservedWithoutReplacingDispatchOrDraw() throws Exception {
        for (boolean srg : new boolean[] {false, true}) {
            ClassNode source = read(original());
            if (srg) for (MethodNode method : source.methods) {
                if (method.name.equals("render") && method.desc.equals("(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V"))
                    method.name = "func_192854_a";
                for (AbstractInsnNode node : method.instructions.toArray()) if (node instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) node;
                    if (call.owner.equals("net/minecraft/client/renderer/Tessellator")) {
                        if (call.name.equals("draw")) call.name = "func_78381_a";
                        if (call.name.equals("getBuffer")) call.name = "func_178180_c";
                    }
                }
            }
            byte[] input = write(source), result = transform(input);
            ClassNode type = read(result); verify(type);
            assertEquals(1, hooks(type, "enterDispatch")); assertEquals(1, hooks(type, "beginRange"));
            assertEquals(1, hooks(type, "completeRange")); assertEquals(2, hooks(type, "leaveDispatch"));
            assertEquals(1, hooks(type, "beginBatch")); assertEquals(1, hooks(type, "prepareBatch"));
            assertEquals(1, hooks(type, "completeBatch")); assertEquals(2, hooks(type, "endBatch"));
            assertEquals(calls(source, "renderTileEntityFast"), calls(type, "renderTileEntityFast"));
            assertEquals(1, calls(type, srg ? "func_78381_a" : "draw"));
            assertSame(result, transform(result));
        }
    }

    @Test public void composesWithExistingEntityFinallyInBothTransformerOrders() throws Exception {
        String type = DISPATCHER;
        byte[] fastFirst = new EntityCaptureTransformer().transform(type, type, transform(original()));
        byte[] entityFirst = transform(new EntityCaptureTransformer().transform(type, type, original()));
        verify(read(fastFirst)); verify(read(entityFirst));
        assertEquals(2, hooks(read(fastFirst), "leaveDispatch")); assertEquals(2, hooks(read(entityFirst), "leaveDispatch"));
    }

    @Test public void missingFastDispatchFailsRatherThanPartiallyInstrumentingTheBatch() throws Exception {
        ClassNode type = read(original());
        for (MethodNode method : type.methods) for (AbstractInsnNode node : method.instructions.toArray())
            if (node instanceof MethodInsnNode && ((MethodInsnNode) node).name.equals("renderTileEntityFast"))
                ((MethodInsnNode) node).name = "unknownFastContract";
        try { transform(write(type)); fail("Expected contract failure"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("fast dispatch")); }
    }

    private static byte[] transform(byte[] bytes) { return new FastTileCaptureTransformer().transform(DISPATCHER, DISPATCHER, bytes); }
    private static ClassNode read(byte[] bytes) { ClassNode type = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(type, 0); return type; }
    private static byte[] write(ClassNode type) { ClassWriter writer = new ClassWriter(0); type.accept(writer); return writer.toByteArray(); }
    private static void verify(ClassNode type) throws Exception {
        for (MethodNode method : type.methods) if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
            new Analyzer<>(new BasicVerifier()).analyze(type.name, method);
    }
    private static int hooks(ClassNode type, String name) {
        int count = 0;
        for (MethodNode method : type.methods) for (AbstractInsnNode node : method.instructions.toArray())
            if (node instanceof MethodInsnNode && HOOKS.equals(((MethodInsnNode) node).owner)
                    && name.equals(((MethodInsnNode) node).name)) count++;
        return count;
    }
    private static int calls(ClassNode type, String name) {
        int count = 0;
        for (MethodNode method : type.methods) for (AbstractInsnNode node : method.instructions.toArray())
            if (node instanceof MethodInsnNode && name.equals(((MethodInsnNode) node).name)) count++;
        return count;
    }
    private static byte[] original() throws Exception {
        try (InputStream stream = FastTileCaptureTransformerTest.class.getClassLoader()
                .getResourceAsStream(DISPATCHER.replace('.', '/') + ".class")) {
            assertNotNull(stream); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }
}

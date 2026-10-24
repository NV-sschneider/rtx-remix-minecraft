/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import com.nvidia.rtxremix.forge.item.HeldItemLights;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.Assert.*;

public class HeldLightCaptureTransformerTest {
    private static final String TARGET = "net/minecraft/client/renderer/ItemRenderer";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/item/HeldItemLights";
    private static final String SIDE = "(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;"
            + "Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;Z)V";

    @Test public void pinnedSideScopeUsesActualHolderAndPhysicalArmWithoutRepeatingDraws() throws Exception {
        for (boolean srg : new boolean[] {false, true}) {
            ClassNode original = read(classBytes());
            if (srg) for (MethodNode method : original.methods) if (method.name.equals("renderItemSide")) method.name = "func_187462_a";
            ClassWriter writer = new ClassWriter(0); original.accept(writer);
            byte[] output = transform(writer.toByteArray());
            ClassNode modified = read(output); int scopes = 0;
            for (int i = 0; i < original.methods.size(); i++) {
                MethodNode before = original.methods.get(i), after = modified.methods.get(i);
                int starts = count(after, "beginSide"); scopes += starts;
                assertEquals(before.maxLocals, after.maxLocals);
                assertEquals(drawCalls(before), drawCalls(after));
                if (SIDE.equals(after.desc)) {
                    assertEquals(1, starts); assertEquals(2, count(after, "endSide"));
                    assertEquals(before.tryCatchBlocks.size() + 1, after.tryCatchBlocks.size());
                    for (AbstractInsnNode instruction : after.instructions.toArray()) if (hook(instruction, "beginSide")) {
                        assertEquals(4, ((VarInsnNode) instruction.getPrevious()).var);
                        assertEquals(Opcodes.ILOAD, instruction.getPrevious().getOpcode());
                        assertEquals(1, ((VarInsnNode) instruction.getPrevious().getPrevious()).var);
                    }
                } else assertEquals(0, starts);
                if ((after.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) new Analyzer<>(new BasicVerifier()).analyze(modified.name, after);
            }
            assertEquals(1, scopes); assertSame(output, transform(output));
        }
    }

    @Test public void jvmClosesRealSideScopeOnReturnAndThrow() throws Exception {
        HeldItemLights.reset();
        Class<?> fixture = new Loader().define(transform(fixture()));
        Object instance = fixture.newInstance();
        Method method = fixture.getMethod("renderItemSide", EntityLivingBase.class, ItemStack.class, ItemCameraTransforms.TransformType.class, boolean.class);
        Field depth = HeldItemLights.class.getDeclaredField("sideDepth"); depth.setAccessible(true);
        method.invoke(instance, null, null, null, false); assertEquals(0, depth.getInt(null));
        try { method.invoke(instance, null, null, null, true); fail(); }
        catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IllegalStateException); }
        assertEquals(0, depth.getInt(null));
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
        method = writer.visitMethod(Opcodes.ACC_PUBLIC, "renderItemSide", SIDE, null, null); method.visitCode();
        Label end = new Label(); method.visitVarInsn(Opcodes.ILOAD, 4); method.visitJumpInsn(Opcodes.IFEQ, end);
        method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false); method.visitInsn(Opcodes.ATHROW);
        method.visitLabel(end); method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] transform(byte[] bytes) { return new HeldLightCaptureTransformer().transform(TARGET.replace('/', '.'), TARGET.replace('/', '.'), bytes); }
    private static ClassNode read(byte[] bytes) { ClassNode type = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(type, 0); return type; }
    private static int count(MethodNode method, String name) { int n = 0; for (AbstractInsnNode instruction : method.instructions.toArray()) if (hook(instruction, name)) n++; return n; }
    private static boolean hook(AbstractInsnNode instruction, String name) {
        return instruction instanceof MethodInsnNode && HOOKS.equals(((MethodInsnNode) instruction).owner) && name.equals(((MethodInsnNode) instruction).name);
    }
    private static int drawCalls(MethodNode method) {
        int count = 0; for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode
                && !HOOKS.equals(((MethodInsnNode) instruction).owner)) count++; return count;
    }
    private static byte[] classBytes() throws Exception {
        try (InputStream stream = HeldLightCaptureTransformerTest.class.getClassLoader().getResourceAsStream(TARGET + ".class")) {
            assertNotNull(stream); ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) >= 0) out.write(buffer, 0, count); return out.toByteArray();
        }
    }
    private static final class Loader extends ClassLoader { Class<?> define(byte[] bytes) { return defineClass(TARGET.replace('/', '.'), bytes, 0, bytes.length); } }
}

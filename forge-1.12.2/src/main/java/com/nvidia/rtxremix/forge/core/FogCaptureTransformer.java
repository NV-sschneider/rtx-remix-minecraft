/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Observes fog after all Forge callbacks and vanilla's final fog enable. */
public final class FogCaptureTransformer implements IClassTransformer {
    private static final String TARGET = "net.minecraft.client.renderer.EntityRenderer";
    private static final String HOOK = "com/nvidia/rtxremix/forge/fog/FogCaptureHooks";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !TARGET.equals(transformedName)) return bytes;
        ClassReader reader = new ClassReader(bytes); ClassNode type = new ClassNode(Opcodes.ASM5);
        reader.accept(type, 0); boolean changed = false;
        for (MethodNode method : type.methods) {
            if (!"(IF)V".equals(method.desc)
                    || !("setupFog".equals(method.name) || "func_78468_a".equals(method.name))) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode
                    && HOOK.equals(((MethodInsnNode) instruction).owner)) return bytes;
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList call = new InsnList(); call.add(new VarInsnNode(Opcodes.ILOAD, 1));
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "afterSetup", "(I)V", false));
                method.instructions.insertBefore(instruction, call); changed = true;
            }
        }
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); type.accept(writer); return writer.toByteArray();
    }
}

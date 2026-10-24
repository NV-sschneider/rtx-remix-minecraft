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
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Records the real holder and arm side; light position still comes from the observed baked-model draw. */
public final class HeldLightCaptureTransformer implements IClassTransformer {
    private static final String TARGET = "net.minecraft.client.renderer.ItemRenderer";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/item/HeldItemLights";
    private static final String SIDE = "(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;"
            + "Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;Z)V";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !TARGET.equals(transformedName)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassNode type = new ClassNode(Opcodes.ASM5); reader.accept(type, 0);
        boolean changed = false;
        for (MethodNode method : type.methods) if (SIDE.equals(method.desc)
                && ("renderItemSide".equals(method.name) || "func_187462_a".equals(method.name))) {
            if (hasHook(method)) continue;
            InsnList begin = new InsnList();
            begin.add(new VarInsnNode(Opcodes.ALOAD, 1)); begin.add(new VarInsnNode(Opcodes.ILOAD, 4));
            begin.add(hook("beginSide", "(Ljava/lang/Object;Z)V"));
            LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
            begin.add(start); method.instructions.insert(begin);
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
                method.instructions.set(instruction, new JumpInsnNode(Opcodes.GOTO, end));
            }
            method.instructions.add(end);
            method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 0, new Object[0]));
            method.instructions.add(hook("endSide", "()V")); method.instructions.add(new InsnNode(Opcodes.RETURN));
            method.instructions.add(handler);
            method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {"java/lang/Throwable"}));
            method.instructions.add(hook("endSide", "()V")); method.instructions.add(new InsnNode(Opcodes.ATHROW));
            method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
            changed = true;
        }
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); type.accept(writer); return writer.toByteArray();
    }
    private static boolean hasHook(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (HOOKS.equals(call.owner) && "beginSide".equals(call.name)) return true;
        }
        return false;
    }
    private static MethodInsnNode hook(String name, String descriptor) { return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, descriptor, false); }
}

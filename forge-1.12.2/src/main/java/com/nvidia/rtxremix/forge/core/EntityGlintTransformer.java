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

/** Identifies vanilla's existing item and armor glint passes without invoking their models again. */
public final class EntityGlintTransformer implements IClassTransformer {
    private static final String TARGET = "net.minecraft.client.renderer.RenderItem";
    private static final String ARMOR = "net.minecraft.client.renderer.entity.layers.LayerArmorBase";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/entity/EntityGlintHooks";
    private static final String EFFECT = "(Lnet/minecraft/client/renderer/block/model/IBakedModel;)V";
    private static final String ARMOR_EFFECT = "(Lnet/minecraft/client/renderer/entity/RenderLivingBase;Lnet/minecraft/entity/EntityLivingBase;"
            + "Lnet/minecraft/client/model/ModelBase;FFFFFFF)V";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !(TARGET.equals(transformedName) || ARMOR.equals(transformedName))) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassNode type = new ClassNode(Opcodes.ASM5); reader.accept(type, 0);
        boolean changed = false;
        for (MethodNode method : type.methods) if (TARGET.equals(transformedName) && EFFECT.equals(method.desc)
                && ("renderEffect".equals(method.name) || "func_191966_a".equals(method.name))
                || ARMOR.equals(transformedName) && ARMOR_EFFECT.equals(method.desc)
                && ("renderEnchantedGlint".equals(method.name) || "func_188364_a".equals(method.name))) {
            if (hasHook(method)) continue;
            LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
            InsnList begin = new InsnList(); begin.add(hook("begin")); begin.add(start);
            method.instructions.insert(begin);
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
                method.instructions.set(instruction, new JumpInsnNode(Opcodes.GOTO, end));
            }
            method.instructions.add(end);
            method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 0, new Object[0]));
            method.instructions.add(hook("end")); method.instructions.add(new InsnNode(Opcodes.RETURN));
            method.instructions.add(handler);
            method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {"java/lang/Throwable"}));
            method.instructions.add(hook("end")); method.instructions.add(new InsnNode(Opcodes.ATHROW));
            method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
            changed = true;
        }
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); type.accept(writer); return writer.toByteArray();
    }
    private static boolean hasHook(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode
                && HOOKS.equals(((MethodInsnNode) instruction).owner)) return true;
        return false;
    }
    private static MethodInsnNode hook(String name) { return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, "()V", false); }
}

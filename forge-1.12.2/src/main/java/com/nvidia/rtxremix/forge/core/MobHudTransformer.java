/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Isolates Neat's original OpenGL overlay from the native world replacement. */
public final class MobHudTransformer implements IClassTransformer {
    private static final String TARGET = "vazkii.neat.HealthBarRenderer";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/hud/MobHudHooks";
    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !TARGET.equals(transformedName)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassNode type = new ClassNode(Opcodes.ASM5); reader.accept(type, 0);
        for (MethodNode method : type.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode && HOOKS.equals(((MethodInsnNode) instruction).owner)) return bytes;
        }
        int events = 0, blends = 0;
        for (MethodNode method : type.methods) {
            if (method.name.equals("onRenderWorldLast") && method.desc.equals("(Lnet/minecraftforge/client/event/RenderWorldLastEvent;)V")) {
                scope(method); events++;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals("net/minecraft/client/renderer/GlStateManager")
                        && (call.name.equals("blendFunc") || call.name.equals("func_179112_b")) && call.desc.equals("(II)V")) {
                    method.instructions.set(call, hook("blendFunc", "(II)V")); blends++;
                }
            }
        }
        if (events != 1 || blends == 0) throw new IllegalStateException("Unsupported Neat health bar renderer");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); type.accept(writer); return writer.toByteArray();
    }
    private static void scope(MethodNode method) {
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        InsnList begin = new InsnList(); begin.add(hook("begin", "()V")); begin.add(start); method.instructions.insert(begin);
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
            InsnList finish = new InsnList(); finish.add(new InsnNode(Opcodes.ICONST_1)); finish.add(hook("end", "(Z)V"));
            method.instructions.insertBefore(instruction, finish);
        }
        method.instructions.add(end); method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {"java/lang/Throwable"}));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0)); method.instructions.add(hook("end", "(Z)V"));
        method.instructions.add(new InsnNode(Opcodes.ATHROW)); method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
    }
    private static MethodInsnNode hook(String name, String desc) { return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, desc, false); }
}

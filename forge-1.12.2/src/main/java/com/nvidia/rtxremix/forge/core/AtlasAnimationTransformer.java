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

/** Observes completed texture uploads inside vanilla's animation tick without replacing GL work. */
public final class AtlasAnimationTransformer implements IClassTransformer {
    private static final String ATLAS = "net.minecraft.client.renderer.texture.TextureMap";
    private static final String GL_STATE = "net.minecraft.client.renderer.GlStateManager";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/texture/AtlasUploadHooks";
    private static final String UPLOAD = "(IIIIIIIILjava/nio/IntBuffer;)V";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !(ATLAS.equals(transformedName) || GL_STATE.equals(transformedName))) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassNode type = new ClassNode(Opcodes.ASM5);
        reader.accept(type, 0);
        boolean changed = false;
        for (MethodNode method : type.methods) {
            if (ATLAS.equals(transformedName) && "()V".equals(method.desc)
                    && named(method.name, "updateAnimations", "func_94248_c")) {
                changed |= scope(method);
            } else if (GL_STATE.equals(transformedName) && UPLOAD.equals(method.desc)
                    && named(method.name, "glTexSubImage2D", "func_187414_b")) {
                changed |= upload(method);
            }
        }
        if (!changed) return bytes;
        // Preserve original frames and avoid recursively loading game classes while transforming them.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static boolean scope(MethodNode method) {
        if (hasHook(method, "beginAtlas")) return false;
        LabelNode start = new LabelNode();
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 0));
        begin.add(hook("beginAtlas", "(Ljava/lang/Object;)V"));
        begin.add(start);
        method.instructions.insert(begin);
        LabelNode end = new LabelNode();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                method.instructions.set(instruction, new JumpInsnNode(Opcodes.GOTO, end));
            }
        }
        method.instructions.add(end);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 0, new Object[0]));
        method.instructions.add(hook("endAtlas", "()V"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        LabelNode handler = new LabelNode();
        method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1,
                new Object[] {"java/lang/Throwable"}));
        method.instructions.add(hook("endAtlas", "()V"));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        return true;
    }

    private static boolean upload(MethodNode method) {
        if (hasHook(method, "uploaded")) return false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList capture = new InsnList();
            for (int i = 0; i < 8; i++) capture.add(new VarInsnNode(Opcodes.ILOAD, i));
            capture.add(new VarInsnNode(Opcodes.ALOAD, 8));
            capture.add(hook("uploaded", UPLOAD));
            method.instructions.insertBefore(instruction, capture);
        }
        return true;
    }

    private static boolean named(String name, String mapped, String srg) {
        return mapped.equals(name) || srg.equals(name);
    }
    private static boolean hasHook(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (HOOKS.equals(call.owner) && name.equals(call.name)) return true;
            }
        }
        return false;
    }
    private static MethodInsnNode hook(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, descriptor, false);
    }
}

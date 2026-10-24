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

/** Associates successful mip-zero CPU uploads with their real GL identity without replacing any upload. */
public final class TextureRevisionTransformer implements IClassTransformer {
    private static final String UTIL = "net.minecraft.client.renderer.texture.TextureUtil";
    private static final String STATE = "net.minecraft.client.renderer.GlStateManager";
    private static final String ATLAS = "net.minecraft.client.renderer.texture.TextureMap";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/texture/TextureRevisionHooks";
    private static final String UPLOAD = "(IIIIIIIILjava/nio/IntBuffer;)V";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !(UTIL.equals(transformedName) || STATE.equals(transformedName) || ATLAS.equals(transformedName))) return bytes;
        ClassReader reader = new ClassReader(bytes); ClassNode type = new ClassNode(Opcodes.ASM5); reader.accept(type, 0);
        boolean changed = false;
        for (MethodNode method : type.methods) {
            if (UTIL.equals(transformedName)) {
                if (named(method.name, "uploadTexture", "func_110988_a") && "(I[III)V".equals(method.desc)) changed |= scope(method, 0);
                else if (named(method.name, "uploadTextureImageSub", "func_110995_a") && "(ILjava/awt/image/BufferedImage;IIZZ)I".equals(method.desc)) changed |= scope(method, 1);
                else if (named(method.name, "deleteTexture", "func_147942_a") && "(I)V".equals(method.desc)) changed |= observe(method, true);
            } else if (STATE.equals(transformedName) && named(method.name, "glTexSubImage2D", "func_187414_b") && UPLOAD.equals(method.desc)) changed |= observe(method, false);
            else if (ATLAS.equals(transformedName) && named(method.name, "updateAnimations", "func_94248_c") && "()V".equals(method.desc)) changed |= scope(method, 2);
        }
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); type.accept(writer); return writer.toByteArray();
    }

    private static boolean scope(MethodNode method, int kind) {
        String name = kind == 0 ? "begin" : kind == 1 ? "beginImage" : "beginAtlas";
        if (hasHook(method, name)) return false;
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(kind == 2 ? Opcodes.ALOAD : Opcodes.ILOAD, 0));
        if (kind == 1) begin.add(new VarInsnNode(Opcodes.ALOAD, 1));
        if (kind != 2) { begin.add(new VarInsnNode(Opcodes.ILOAD, 2)); begin.add(new VarInsnNode(Opcodes.ILOAD, 3)); }
        begin.add(hook(name, kind == 0 ? "(III)V" : kind == 1 ? "(ILjava/lang/Object;II)V" : "(Ljava/lang/Object;)V"));
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode(); begin.add(start); method.instructions.insert(begin);
        int opcode = kind == 1 ? Opcodes.IRETURN : Opcodes.RETURN;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == opcode)
            method.instructions.set(instruction, new JumpInsnNode(Opcodes.GOTO, end));
        method.instructions.add(end);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], kind == 1 ? 1 : 0,
                kind == 1 ? new Object[] {Opcodes.INTEGER} : new Object[0]));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1)); method.instructions.add(hook("end", "(Z)V")); method.instructions.add(new InsnNode(opcode));
        method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {"java/lang/Throwable"}));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0)); method.instructions.add(hook("end", "(Z)V")); method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null)); return true;
    }
    private static boolean observe(MethodNode method, boolean delete) {
        String name = delete ? "deleted" : "uploaded";
        if (hasHook(method, name)) return false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
            InsnList capture = new InsnList();
            for (int i = 0; i < (delete ? 1 : 8); i++) capture.add(new VarInsnNode(Opcodes.ILOAD, i));
            if (!delete) capture.add(new VarInsnNode(Opcodes.ALOAD, 8));
            capture.add(hook(name, delete ? "(I)V" : UPLOAD)); method.instructions.insertBefore(instruction, capture);
        }
        return true;
    }
    private static boolean named(String name, String mapped, String srg) { return mapped.equals(name) || srg.equals(name); }
    private static boolean hasHook(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction; if (HOOKS.equals(call.owner) && name.equals(call.name)) return true;
        } return false;
    }
    private static MethodInsnNode hook(String name, String descriptor) { return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, descriptor, false); }
}

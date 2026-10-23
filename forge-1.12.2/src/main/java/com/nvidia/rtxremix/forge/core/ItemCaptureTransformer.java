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

/** Observes the ordinary dropped-item draw after model selection and mod transforms. */
public final class ItemCaptureTransformer implements IClassTransformer {
    private static final String MANAGER = "net.minecraft.client.renderer.entity.RenderManager";
    private static final String ITEM = "net.minecraft.client.renderer.RenderItem";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/item/ItemCaptureHooks";
    private static final String ENTITY_DESC = "(Lnet/minecraft/entity/Entity;DDDFFZ)V";
    private static final String MODEL_DESC = "(Lnet/minecraft/client/renderer/block/model/IBakedModel;Lnet/minecraft/item/ItemStack;)V";
    private static final String QUADS_DESC = "(Lnet/minecraft/client/renderer/BufferBuilder;Ljava/util/List;ILnet/minecraft/item/ItemStack;)V";
    private static final String QUAD_DESC = "(Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/client/renderer/block/model/BakedQuad;I)V";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !(MANAGER.equals(transformedName) || ITEM.equals(transformedName))) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassNode type = new ClassNode(Opcodes.ASM5);
        reader.accept(type, 0);
        boolean changed = false;
        for (MethodNode method : type.methods) {
            if (MANAGER.equals(transformedName) && ENTITY_DESC.equals(method.desc)
                    && named(method.name, "renderEntity", "func_188391_a")) {
                changed |= scope(method, "beginEntity", "endEntity", false);
            } else if (ITEM.equals(transformedName) && MODEL_DESC.equals(method.desc)
                    && named(method.name, "renderModel", "func_191961_a")) {
                changed |= scope(method, "beginModel", "endModel", true);
            } else if (ITEM.equals(transformedName) && QUADS_DESC.equals(method.desc)
                    && named(method.name, "renderQuads", "func_191970_a")) {
                changed |= quads(method);
            }
        }
        if (!changed) return bytes;
        // New finally targets use no locals. Keeping original frames avoids loading game
        // classes to resolve their hierarchy while LaunchWrapper is transforming them.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static boolean scope(MethodNode method, String beginName, String endName, boolean model) {
        if (hasHook(method, beginName)) return false;
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 1));
        if (model) begin.add(new VarInsnNode(Opcodes.ALOAD, 2));
        begin.add(hook(beginName, model ? "(Ljava/lang/Object;Ljava/lang/Object;)V" : "(Ljava/lang/Object;)V"));
        LabelNode start = new LabelNode();
        begin.add(start);
        method.instructions.insert(begin);
        LabelNode end = new LabelNode();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) method.instructions.set(instruction, new JumpInsnNode(Opcodes.GOTO, end));
        }
        method.instructions.add(end);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 0, new Object[0]));
        method.instructions.add(hook(endName, "()V"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        LabelNode handler = new LabelNode();
        method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {"java/lang/Throwable"}));
        method.instructions.add(hook(endName, "()V"));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        return true;
    }

    private static boolean quads(MethodNode method) {
        if (hasHook(method, "beforeQuad")) return false;
        int calls = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() != Opcodes.INVOKESTATIC
                    || !"net/minecraftforge/client/model/pipeline/LightUtil".equals(call.owner)
                    || !"renderQuadColor".equals(call.name) || !QUAD_DESC.equals(call.desc)) continue;
            InsnList before = new InsnList();
            // Leave the original buffer/quad/tint operands untouched for Forge's draw.
            before.add(new InsnNode(Opcodes.DUP2));
            before.add(new VarInsnNode(Opcodes.ALOAD, 1));
            before.add(hook("beforeQuad", "(Ljava/lang/Object;ILjava/lang/Object;)V"));
            method.instructions.insertBefore(call, before);
            InsnList after = new InsnList();
            after.add(new VarInsnNode(Opcodes.ALOAD, 1));
            after.add(hook("afterQuad", "(Ljava/lang/Object;)V"));
            method.instructions.insert(call, after);
            calls++;
        }
        if (calls != 1) throw new IllegalStateException("Item capture requires one Forge quad-color draw");
        return true;
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
    private static boolean named(String name, String mapped, String srg) { return mapped.equals(name) || srg.equals(name); }
    private static MethodInsnNode hook(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, descriptor, false);
    }
}

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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Observes the pinned Forge dispatcher; optional mods keep their renderer implementations. */
public final class FastTileCaptureTransformer implements IClassTransformer {
    private static final String DISPATCHER = "net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher";
    private static final String TESR = "net/minecraft/client/renderer/tileentity/TileEntitySpecialRenderer";
    private static final String BUFFER = "net/minecraft/client/renderer/BufferBuilder";
    private static final String TESSELLATOR = "net/minecraft/client/renderer/Tessellator";
    private static final String TILE_DESCRIPTOR = "(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V";
    private static final String FAST_DESCRIPTOR = "(Lnet/minecraft/tileentity/TileEntity;DDDFIFL" + BUFFER + ";)V";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/entity/FastTileCaptureHooks";

    @Override public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !DISPATCHER.equals(transformedName)) return basicClass;
        ClassReader reader = new ClassReader(basicClass);
        ClassNode type = new ClassNode(Opcodes.ASM5); reader.accept(type, 0);
        int changed = 0;
        for (MethodNode method : type.methods) {
            if (TILE_DESCRIPTOR.equals(method.desc) && named(method.name, "render", "func_192854_a")) {
                if (dispatch(method)) changed++;
            } else if ("preDrawBatch".equals(method.name) && "()V".equals(method.desc)) {
                if (beginBatch(method)) changed++;
            } else if ("drawBatch".equals(method.name) && "(I)V".equals(method.desc)) {
                if (drawBatch(method)) changed++;
            }
        }
        if (changed == 0) return basicClass;
        if (changed != 3) throw new IllegalStateException("Fast tile capture requires all three Forge batch contracts");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer); return writer.toByteArray();
    }

    private static boolean dispatch(MethodNode method) {
        if (hasHook(method, "enterDispatch")) return false;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!TESR.equals(call.owner) || !"renderTileEntityFast".equals(call.name)
                    || !FAST_DESCRIPTOR.equals(call.desc) || call.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            InsnList begin = new InsnList();
            begin.add(new InsnNode(Opcodes.DUP));
            begin.add(new VarInsnNode(Opcodes.ALOAD, 1));
            begin.add(new VarInsnNode(Opcodes.DLOAD, 2)); begin.add(new VarInsnNode(Opcodes.DLOAD, 4));
            begin.add(new VarInsnNode(Opcodes.DLOAD, 6)); begin.add(new VarInsnNode(Opcodes.ILOAD, 9));
            begin.add(hook("beginRange", "(Ljava/lang/Object;Ljava/lang/Object;DDDI)V"));
            method.instructions.insertBefore(call, begin);
            method.instructions.insert(call, hook("completeRange", "()V")); count++;
        }
        if (count != 1) throw new IllegalStateException("Fast tile capture requires one Forge fast dispatch");
        InsnList begin = new InsnList(); begin.add(hook("enterDispatch", "()V"));
        LabelNode start = new LabelNode(); begin.add(start); method.instructions.insert(begin);
        finishScope(method, start, "leaveDispatch"); return true;
    }

    private static boolean beginBatch(MethodNode method) {
        if (hasHook(method, "beginBatch")) return false;
        FieldInsnNode field = null; MethodInsnNode getter = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode && ("L" + TESSELLATOR + ";").equals(((FieldInsnNode) instruction).desc))
                field = (FieldInsnNode) instruction;
            if (instruction instanceof MethodInsnNode && TESSELLATOR.equals(((MethodInsnNode) instruction).owner)
                    && ("()L" + BUFFER + ";").equals(((MethodInsnNode) instruction).desc)) getter = (MethodInsnNode) instruction;
        }
        if (field == null || getter == null) throw new IllegalStateException("Fast tile capture requires Forge's batch buffer");
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
            InsnList capture = new InsnList(); capture.add(new VarInsnNode(Opcodes.ALOAD, 0));
            capture.add(new FieldInsnNode(field.getOpcode(), field.owner, field.name, field.desc));
            capture.add(new MethodInsnNode(getter.getOpcode(), getter.owner, getter.name, getter.desc, getter.itf));
            capture.add(hook("beginBatch", "(Ljava/lang/Object;)V")); method.instructions.insertBefore(instruction, capture);
        }
        return true;
    }

    private static boolean drawBatch(MethodNode method) {
        if (hasHook(method, "prepareBatch")) return false;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!TESSELLATOR.equals(call.owner) || !named(call.name, "draw", "func_78381_a")
                    || !"()V".equals(call.desc) || call.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            method.instructions.insertBefore(call, hook("prepareBatch", "()V"));
            method.instructions.insert(call, hook("completeBatch", "()V")); count++;
        }
        if (count != 1) throw new IllegalStateException("Fast tile capture requires one Forge batch draw");
        LabelNode start = new LabelNode(); method.instructions.insert(start);
        finishScope(method, start, "endBatch"); return true;
    }

    private static void finishScope(MethodNode method, LabelNode start, String cleanup) {
        LabelNode end = new LabelNode();
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN)
            method.instructions.set(instruction, new JumpInsnNode(Opcodes.GOTO, end));
        method.instructions.add(end);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 0, new Object[0]));
        method.instructions.add(hook(cleanup, "()V")); method.instructions.add(new InsnNode(Opcodes.RETURN));
        LabelNode handler = new LabelNode(); method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {"java/lang/Throwable"}));
        method.instructions.add(hook(cleanup, "()V")); method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
    }
    private static MethodInsnNode hook(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, descriptor, false);
    }
    private static boolean hasHook(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (HOOKS.equals(call.owner) && name.equals(call.name)) return true;
        }
        return false;
    }
    private static boolean named(String name, String mcp, String srg) { return mcp.equals(name) || srg.equals(name); }
}

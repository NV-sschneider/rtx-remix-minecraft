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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Observes the pinned VBO terrain draw only; display-list compilation and replay are untouched. */
public final class TerrainDrawTransformer implements IClassTransformer {
    private static final String VBO_LIST = "net.minecraft.client.renderer.VboRenderList";
    private static final String BUFFER = "net/minecraft/client/renderer/vertex/VertexBuffer";
    private static final String CHUNK = "net/minecraft/client/renderer/chunk/RenderChunk";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/terrain/VanillaTerrainDraws";

    @Override public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !VBO_LIST.equals(transformedName)) return basicClass;
        ClassReader reader = new ClassReader(basicClass);
        ClassNode type = new ClassNode(Opcodes.ASM5); reader.accept(type, 0);
        for (FieldNode field : type.fields) if ("rtxremixVboDrawContract".equals(field.name)) return basicClass;
        MethodNode layer = null;
        for (MethodNode method : type.methods) if (named(method.name, "renderChunkLayer", "func_178001_a")
                && "(Lnet/minecraft/util/BlockRenderLayer;)V".equals(method.desc)) layer = method;
        if (layer == null) return basicClass;
        MethodInsnNode draw = null, acquire = null;
        int draws = 0, acquisitions = 0;
        for (AbstractInsnNode instruction : layer.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (BUFFER.equals(call.owner) && named(call.name, "drawArrays", "func_177358_a") && "(I)V".equals(call.desc)) {
                draw = call; draws++;
            }
            if (CHUNK.equals(call.owner) && named(call.name, "getVertexBufferByLayer", "func_178565_b")
                    && ("(I)L" + BUFFER + ";").equals(call.desc)) { acquire = call; acquisitions++; }
        }
        if (draws != 1 || acquisitions != 1) return basicClass;
        AbstractInsnNode ordinal = previous(acquire), layerLoad = previous(ordinal), chunkLoad = previous(layerLoad);
        AbstractInsnNode bufferStore = next(acquire), mode = previous(draw), bufferLoad = previous(mode);
        if (!(ordinal instanceof MethodInsnNode) || !"net/minecraft/util/BlockRenderLayer".equals(((MethodInsnNode) ordinal).owner)
                || !"ordinal".equals(((MethodInsnNode) ordinal).name) || !"()I".equals(((MethodInsnNode) ordinal).desc)
                || !variable(layerLoad, Opcodes.ALOAD, 1) || !(chunkLoad instanceof VarInsnNode) || chunkLoad.getOpcode() != Opcodes.ALOAD
                || !(bufferStore instanceof VarInsnNode) || bufferStore.getOpcode() != Opcodes.ASTORE
                || !variable(bufferLoad, Opcodes.ALOAD, ((VarInsnNode) bufferStore).var)
                || !(mode instanceof IntInsnNode) || mode.getOpcode() != Opcodes.BIPUSH || ((IntInsnNode) mode).operand != 7)
            return basicClass;
        InsnList args = new InsnList(); args.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) chunkLoad).var));
        args.add(new VarInsnNode(Opcodes.ALOAD, 1)); layer.instructions.insertBefore(draw, args);
        layer.instructions.set(draw, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "draw",
                "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;)V", false));
        type.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "rtxremixVboDrawContract", "I", null, 1));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); type.accept(writer); return writer.toByteArray();
    }
    private static boolean named(String value, String mcp, String srg) { return mcp.equals(value) || srg.equals(value); }
    private static boolean variable(AbstractInsnNode node, int opcode, int slot) {
        return node instanceof VarInsnNode && node.getOpcode() == opcode && ((VarInsnNode) node).var == slot;
    }
    private static AbstractInsnNode previous(AbstractInsnNode node) {
        if (node == null) return null;
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0); return node;
    }
    private static AbstractInsnNode next(AbstractInsnNode node) {
        if (node == null) return null;
        do { node = node.getNext(); } while (node != null && node.getOpcode() < 0); return node;
    }
}

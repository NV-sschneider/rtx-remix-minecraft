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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Observes living and tile model draws without replacing their animation or OpenGL work. */
public final class EntityCaptureTransformer implements IClassTransformer {
    private static final String LIVING = "net.minecraft.client.renderer.entity.RenderLivingBase";
    private static final String MODEL = "net.minecraft.client.model.ModelRenderer";
    private static final String ADVANCED_MODEL = "net.ilexiconn.llibrary.client.model.tools.AdvancedModelRenderer";
    private static final String BENDS_MODEL = "goblinbob.mobends.core.client.model.ModelPart";
    private static final String BENDS_EXTENDED = "goblinbob.mobends.core.client.model.ModelPartExtended";
    private static final String OBJ_MODEL = "com.lycanitesmobs.client.obj.TessellatorModel";
    private static final String VBO_MODEL = "com.lycanitesmobs.client.obj.VBOModel";
    private static final String VBO_MESH = "com.lycanitesmobs.client.obj.Mesh";
    private static final String BUFFER = "net/minecraft/client/renderer/BufferBuilder";
    private static final String OBJ_DESCRIPTOR = "(Lcom/lycanitesmobs/client/obj/ObjObject;Ljavax/vecmath/Vector4f;"
            + "Ljavax/vecmath/Vector2f;Lnet/minecraft/client/renderer/vertex/VertexFormat;)V";
    private static final String TEXTURES = "net.minecraft.client.renderer.texture.TextureManager";
    private static final String TILES = "net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher";
    private static final String TESR = "net/minecraft/client/renderer/tileentity/TileEntitySpecialRenderer";
    private static final String GL_STATE = "net/minecraft/client/renderer/GlStateManager";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/entity/EntityCaptureHooks";
    private static final String LIVING_DESCRIPTOR = "(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V";
    private static final String TEXTURE_DESCRIPTOR = "(Lnet/minecraft/util/ResourceLocation;)V";
    private static final String TILE_DESCRIPTOR = "(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !(LIVING.equals(transformedName) || MODEL.equals(transformedName)
                || ADVANCED_MODEL.equals(transformedName) || bendsModel(transformedName)
                || OBJ_MODEL.equals(transformedName) || VBO_MODEL.equals(transformedName) || VBO_MESH.equals(transformedName)
                || TEXTURES.equals(transformedName) || TILES.equals(transformedName))) {
            return basicClass;
        }
        ClassReader reader = new ClassReader(basicClass);
        ClassNode type = new ClassNode(Opcodes.ASM5);
        reader.accept(type, 0);
        boolean changed = false;
        for (MethodNode method : type.methods) {
            if (LIVING.equals(transformedName) && LIVING_DESCRIPTOR.equals(method.desc)
                    && named(method.name, "doRender", "func_76986_a")) {
                changed |= scopeLiving(method);
            } else if (TILES.equals(transformedName) && TILE_DESCRIPTOR.equals(method.desc)
                    && named(method.name, "render", "func_192854_a")) {
                changed |= scopeTile(method);
            } else if (TEXTURES.equals(transformedName) && TEXTURE_DESCRIPTOR.equals(method.desc)
                    && named(method.name, "bindTexture", "func_110577_a")) {
                changed |= observeTexture(method);
            } else if ((MODEL.equals(transformedName) || ADVANCED_MODEL.equals(transformedName))
                    && "(F)V".equals(method.desc)
                    && (named(method.name, "render", "func_78785_a")
                    || MODEL.equals(transformedName) && named(method.name, "renderWithRotation", "func_78791_b"))) {
                changed |= observePart(method);
            } else if (bendsModel(transformedName) && "(F)V".equals(method.desc)
                    && ("renderPart".equals(method.name) || "renderJustPart".equals(method.name))) {
                changed |= observePart(method);
            } else if (OBJ_DESCRIPTOR.equals(method.desc) && "renderGroupImpl".equals(method.name)) {
                if (OBJ_MODEL.equals(transformedName)) changed |= observeTriangles(method);
                else if (VBO_MODEL.equals(transformedName)) changed |= observeVboDraw(method);
            } else if (VBO_MESH.equals(transformedName)) {
                if ("getVbo".equals(method.name) && "()I".equals(method.desc)) changed |= observeVboUpload(method);
                else if ("delete".equals(method.name) && "()V".equals(method.desc)) changed |= observeVboDelete(method);
            }
        }
        if (!changed) { return basicClass; }

        // Existing frames stay valid at the observation sites. The two new cleanup
        // targets have explicit frames and use no locals; resolving Minecraft's class
        // hierarchy here would recursively load classes inside LaunchWrapper.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static boolean scopeLiving(MethodNode method) {
        if (hasHook(method, "beginLiving")) { return false; }
        MethodInsnNode firstPush = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (glCall(instruction, "pushMatrix", "func_179094_E", "()V")) {
                firstPush = (MethodInsnNode) instruction;
                break;
            }
        }
        if (firstPush == null) {
            throw new IllegalStateException("Living entity capture requires the world model matrix scope");
        }

        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 1));
        begin.add(hook("beginLiving", "(Ljava/lang/Object;)V"));
        LabelNode start = new LabelNode();
        begin.add(start);
        method.instructions.insert(begin);
        method.instructions.insertBefore(firstPush, hook("activateLiving", "()V"));
        finishScope(method, start, "endLiving");
        return true;
    }

    private static boolean scopeTile(MethodNode method) {
        if (hasHook(method, "beginTile")) return false;
        int normalCalls = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && TESR.equals(call.owner)
                    && named(call.name, "render", "func_192841_a") && TILE_DESCRIPTOR.equals(call.desc)) {
                method.instructions.insertBefore(call, hook("activateTile", "()V"));
                normalCalls++;
            }
        }
        if (normalCalls != 1) throw new IllegalStateException("Tile model capture requires one normal TESR dispatch");
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 1));
        begin.add(new VarInsnNode(Opcodes.DLOAD, 2));
        begin.add(new VarInsnNode(Opcodes.DLOAD, 4));
        begin.add(new VarInsnNode(Opcodes.DLOAD, 6));
        begin.add(new VarInsnNode(Opcodes.ILOAD, 9));
        begin.add(hook("beginTile", "(Ljava/lang/Object;DDDI)V"));
        LabelNode start = new LabelNode();
        begin.add(start);
        method.instructions.insert(begin);
        finishScope(method, start, "endTile");
        return true;
    }

    private static void finishScope(MethodNode method, LabelNode start, String endHook) {
        LabelNode end = new LabelNode();
        boolean inScope = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction == start) { inScope = true; }
            if (inScope && instruction.getOpcode() == Opcodes.RETURN) {
                method.instructions.set(instruction, new JumpInsnNode(Opcodes.GOTO, end));
            }
        }
        method.instructions.add(end);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 0, new Object[0]));
        method.instructions.add(hook(endHook, "()V"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        LabelNode handler = new LabelNode();
        method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1,
                new Object[] { "java/lang/Throwable" }));
        method.instructions.add(hook(endHook, "()V"));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        // Appending preserves the renderer's own exception handling before our finally.
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
    }

    private static boolean observePart(MethodNode method) {
        if (hasHook(method, "capturePart")) { return false; }
        boolean changed = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!glCall(instruction, "callList", "func_179148_o", "(I)V")) { continue; }
            InsnList capture = new InsnList();
            // Keep the actual display-list identity as the geometry revision, without
            // reading a possibly shadowed model field or consuming vanilla's argument.
            capture.add(new InsnNode(Opcodes.DUP));
            capture.add(new VarInsnNode(Opcodes.ALOAD, 0));
            capture.add(new VarInsnNode(Opcodes.FLOAD, 1));
            capture.add(hook("capturePart", "(ILjava/lang/Object;F)V"));
            method.instructions.insertBefore(instruction, capture);
            changed = true;
        }
        if (!changed) {
            throw new IllegalStateException("Living model capture requires a display-list draw in " + method.name);
        }
        return true;
    }

    private static boolean observeTexture(MethodNode method) {
        if (hasHook(method, "boundTexture")) { return false; }
        boolean changed = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) { continue; }
            InsnList capture = new InsnList();
            capture.add(new VarInsnNode(Opcodes.ALOAD, 1));
            capture.add(hook("boundTexture", "(Ljava/lang/Object;)V"));
            method.instructions.insertBefore(instruction, capture);
            changed = true;
        }
        return changed;
    }

    private static boolean observeTriangles(MethodNode method) {
        if (hasHook(method, "captureTriangles")) return false;
        int calls = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !BUFFER.equals(call.owner)
                    || !named(call.name, "finishDrawing", "func_178977_d") || !"()V".equals(call.desc)) continue;
            // Retain the receiver only after finishDrawing succeeds; no local-slot or
            // optional-mod class resolution is needed to observe its completed bytes.
            method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP));
            InsnList capture = new InsnList();
            capture.add(new VarInsnNode(Opcodes.ALOAD, 1));
            capture.add(hook("captureTriangles", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
            method.instructions.insert(call, capture);
            calls++;
        }
        if (calls != 1) throw new IllegalStateException("Lycanites OBJ capture requires one completed triangle buffer");
        return true;
    }

    private static boolean observeVboDraw(MethodNode method) {
        if (hasHook(method, "captureVboTriangles")) return false;
        int calls = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!nativeCall(instruction, "org/lwjgl/opengl/GL11", "glDrawArrays", "(III)V")) continue;
            int mode = method.maxLocals++, first = method.maxLocals++, count = method.maxLocals++;
            InsnList capture = new InsnList();
            capture.add(new VarInsnNode(Opcodes.ISTORE, count));
            capture.add(new VarInsnNode(Opcodes.ISTORE, first));
            capture.add(new VarInsnNode(Opcodes.ISTORE, mode));
            capture.add(new VarInsnNode(Opcodes.ILOAD, mode));
            capture.add(new VarInsnNode(Opcodes.ILOAD, first));
            capture.add(new VarInsnNode(Opcodes.ILOAD, count));
            capture.add(new VarInsnNode(Opcodes.ILOAD, mode));
            capture.add(new VarInsnNode(Opcodes.ILOAD, first));
            capture.add(new VarInsnNode(Opcodes.ILOAD, count));
            capture.add(new VarInsnNode(Opcodes.ALOAD, 1));
            capture.add(new VarInsnNode(Opcodes.ALOAD, 1));
            capture.add(new FieldInsnNode(Opcodes.GETFIELD, "com/lycanitesmobs/client/obj/ObjObject", "mesh",
                    "Lcom/lycanitesmobs/client/obj/Mesh;"));
            capture.add(hook("captureVboTriangles", "(IIILjava/lang/Object;Ljava/lang/Object;)V"));
            method.instructions.insertBefore(instruction, capture);
            calls++;
        }
        if (calls != 1) throw new IllegalStateException("Lycanites VBO capture requires one array draw");
        return true;
    }

    private static boolean observeVboUpload(MethodNode method) {
        if (hasHook(method, "captureVboUpload")) return false;
        int calls = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!nativeCall(instruction, "org/lwjgl/opengl/GL15", "glBufferData", "(ILjava/nio/ByteBuffer;I)V")) continue;
            int bytes = method.maxLocals++;
            InsnList keep = new InsnList();
            keep.add(new InsnNode(Opcodes.DUP2));
            keep.add(new InsnNode(Opcodes.POP));
            keep.add(new VarInsnNode(Opcodes.ASTORE, bytes));
            method.instructions.insertBefore(instruction, keep);
            InsnList capture = new InsnList();
            capture.add(new VarInsnNode(Opcodes.ALOAD, bytes));
            capture.add(new VarInsnNode(Opcodes.ALOAD, 0));
            capture.add(hook("captureVboUpload", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
            method.instructions.insert(instruction, capture);
            calls++;
        }
        if (calls != 1) throw new IllegalStateException("Lycanites VBO capture requires one CPU buffer upload");
        return true;
    }

    private static boolean observeVboDelete(MethodNode method) {
        if (hasHook(method, "deleteVbo")) return false;
        int calls = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!nativeCall(instruction, "org/lwjgl/opengl/GL15", "glDeleteBuffers", "(I)V")) continue;
            InsnList clear = new InsnList();
            clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clear.add(hook("deleteVbo", "(Ljava/lang/Object;)V"));
            method.instructions.insert(instruction, clear);
            calls++;
        }
        if (calls != 1) throw new IllegalStateException("Lycanites VBO capture requires one buffer deletion");
        return true;
    }

    private static boolean nativeCall(AbstractInsnNode instruction, String owner, String name, String desc) {
        if (!(instruction instanceof MethodInsnNode)) return false;
        MethodInsnNode call = (MethodInsnNode) instruction;
        return call.getOpcode() == Opcodes.INVOKESTATIC && owner.equals(call.owner)
                && name.equals(call.name) && desc.equals(call.desc);
    }

    private static boolean hasHook(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (HOOKS.equals(call.owner) && name.equals(call.name)) { return true; }
            }
        }
        return false;
    }

    private static boolean glCall(AbstractInsnNode instruction, String mapped, String srg, String descriptor) {
        if (!(instruction instanceof MethodInsnNode)) { return false; }
        MethodInsnNode call = (MethodInsnNode) instruction;
        return call.getOpcode() == Opcodes.INVOKESTATIC && GL_STATE.equals(call.owner)
                && named(call.name, mapped, srg) && descriptor.equals(call.desc);
    }

    private static boolean named(String name, String mapped, String srg) {
        return mapped.equals(name) || srg.equals(name);
    }

    private static boolean bendsModel(String name) {
        return BENDS_MODEL.equals(name) || BENDS_EXTENDED.equals(name);
    }

    private static MethodInsnNode hook(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, descriptor, false);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.Assert.assertTrue;

/** Verifies the pinned game's ordering used to associate a menu marker with its real screen. */
public class MenuInitializationContractTest {
    @Test
    public void activeScreenIsAssignedBeforeInitializationEvent() throws IOException {
        MethodNode display = method("net/minecraft/client/Minecraft", "displayGuiScreen",
                "(Lnet/minecraft/client/gui/GuiScreen;)V");
        int assignedScreen = -1;
        int initializeScreen = -1;
        AbstractInsnNode[] instructions = display.instructions.toArray();
        for (int i = 0; i < instructions.length; i++) {
            AbstractInsnNode instruction = instructions[i];
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == Opcodes.PUTFIELD
                        && "net/minecraft/client/Minecraft".equals(field.owner)
                        && "currentScreen".equals(field.name)) assignedScreen = i;
            }
            if (isCall(instruction, "net/minecraft/client/gui/GuiScreen", "setWorldAndResolution")) {
                initializeScreen = i;
            }
        }
        assertTrue("The real screen must be active before its initialization event",
                assignedScreen >= 0 && initializeScreen > assignedScreen);

        MethodNode initialize = method("net/minecraft/client/gui/GuiScreen", "setWorldAndResolution",
                "(Lnet/minecraft/client/Minecraft;II)V");
        int initGui = -1;
        int postEvent = -1;
        instructions = initialize.instructions.toArray();
        for (int i = 0; i < instructions.length; i++) {
            if (isCall(instructions[i], "net/minecraft/client/gui/GuiScreen", "initGui")) initGui = i;
            if (isCall(instructions[i], "net/minecraftforge/client/event/GuiScreenEvent$InitGuiEvent$Post",
                    "<init>")) postEvent = i;
        }
        assertTrue("A menu must complete initialization before its Post event",
                initGui >= 0 && postEvent > initGui);
    }

    private static boolean isCall(AbstractInsnNode instruction, String owner, String name) {
        if (!(instruction instanceof MethodInsnNode)) return false;
        MethodInsnNode call = (MethodInsnNode) instruction;
        return owner.equals(call.owner) && name.equals(call.name);
    }

    private static MethodNode method(String owner, String name, String descriptor) throws IOException {
        // Inspect bytecode without constructing Minecraft, a GUI, or a native GL context.
        try (InputStream input = MenuInitializationContractTest.class.getClassLoader()
                .getResourceAsStream(owner + ".class")) {
            if (input == null) throw new IOException("Missing pinned game class: " + owner);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            ClassNode type = new ClassNode(Opcodes.ASM5);
            new ClassReader(bytes.toByteArray()).accept(type, 0);
            for (MethodNode method : type.methods) {
                if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
            }
            throw new IOException("Missing pinned game method: " + owner + "." + name);
        }
    }
}

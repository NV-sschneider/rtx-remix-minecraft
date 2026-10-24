/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import static org.junit.Assert.*;

public class FixtureConnectionThreadTest {
    private static final String CONNECTOR = "com/nvidia/rtxremix/forge/client/FixtureConnection";
    private static final String MENU = "net/minecraft/client/gui/GuiMainMenu";
    private static final String LOGIN = "net/minecraft/client/network/NetHandlerLoginClient";

    @Test public void pinnedMainMenuConstructionRequiresTheOpenGlThread() throws Exception {
        MethodNode constructor = method(read(MENU), "<init>");
        boolean capabilities = false;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            capabilities |= call.owner.equals("org/lwjgl/opengl/GLContext") && call.name.equals("getCapabilities");
        }
        assertTrue("GuiMainMenu is not a safe worker-thread login argument", capabilities);
    }

    @Test public void loginHandlerReceivesTheFinalParentCreatedByTheClientConstructor() throws Exception {
        ClassNode connector = read(CONNECTOR);
        FieldInsnNode savedParent = null;
        for (AbstractInsnNode instruction : method(connector, "<init>").instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!MENU.equals(call.owner) || !"<init>".equals(call.name)) continue;
            AbstractInsnNode next = nextInstruction(instruction);
            assertTrue("Create and retain the parent before starting the connector", next instanceof FieldInsnNode);
            savedParent = (FieldInsnNode) next;
            assertEquals(Opcodes.PUTFIELD, savedParent.getOpcode());
            assertEquals(CONNECTOR, savedParent.owner);
        }
        assertNotNull("Client constructor must own parent-screen creation", savedParent);
        boolean finalField = false;
        for (FieldNode field : connector.fields) if (field.name.equals(savedParent.name)) {
            finalField = (field.access & Opcodes.ACC_FINAL) != 0;
            assertTrue(field.desc.equals("Lnet/minecraft/client/gui/GuiScreen;") || field.desc.equals("L" + MENU + ";"));
        }
        assertTrue("Publish the immutable parent reference before Thread.start", finalField);
        int loginConstructors = 0;
        for (AbstractInsnNode instruction : method(connector, "connectWorker").instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!LOGIN.equals(call.owner) || !"<init>".equals(call.name)) continue;
            loginConstructors++;
            AbstractInsnNode previous = previousInstruction(instruction);
            assertTrue("The login parent's argument must be the captured field", previous instanceof FieldInsnNode);
            FieldInsnNode parent = (FieldInsnNode) previous;
            assertEquals(Opcodes.GETFIELD, parent.getOpcode());
            assertEquals(savedParent.owner, parent.owner); assertEquals(savedParent.name, parent.name);
            assertEquals(savedParent.desc, parent.desc);
        }
        assertEquals(1, loginConstructors);
    }

    @Test public void connectorWorkerDoesNotConstructScreens() throws Exception {
        MethodNode worker = method(read(CONNECTOR), "connectWorker");
        for (AbstractInsnNode instruction : worker.instructions.toArray()) if (instruction instanceof TypeInsnNode) {
            TypeInsnNode type = (TypeInsnNode) instruction;
            assertFalse("GUI constructors can access the current OpenGL context",
                    type.getOpcode() == Opcodes.NEW && type.desc.startsWith("net/minecraft/client/gui/"));
        }
    }

    private static AbstractInsnNode nextInstruction(AbstractInsnNode value) {
        do { value = value.getNext(); } while (value != null && value.getOpcode() < 0);
        return value;
    }
    private static AbstractInsnNode previousInstruction(AbstractInsnNode value) {
        do { value = value.getPrevious(); } while (value != null && value.getOpcode() < 0);
        return value;
    }
    private static MethodNode method(ClassNode owner, String name) {
        for (MethodNode method : owner.methods) if (name.equals(method.name)) return method;
        throw new AssertionError("Missing method " + owner.name + "." + name);
    }
    private static ClassNode read(String name) throws Exception {
        try (InputStream input = FixtureConnectionThreadTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(name, input);
            ClassNode result = new ClassNode(Opcodes.ASM5);
            new ClassReader(input).accept(result, ClassReader.SKIP_DEBUG); return result;
        }
    }
}

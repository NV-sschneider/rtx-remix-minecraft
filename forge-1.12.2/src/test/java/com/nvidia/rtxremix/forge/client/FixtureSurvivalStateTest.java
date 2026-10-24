/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.jar.JarFile;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.INBTSerializable;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

public class FixtureSurvivalStateTest {
    @Test public void independentLimbHealthIsRestoredFromOwnedNbtAndResynced() throws Exception {
        DamageModel model = new DamageModel(); model.part.currentHealth = 2.5f; model.absorption = 3;
        FixtureSurvivalState.FirstAid state = new FixtureSurvivalState.FirstAid(model,
                DamageModel.class.getMethod("scheduleResync"));
        // Deliberately mutate the model's reusable serialization object after the snapshot.
        model.serialized.setFloat("health", 0);
        state.prepare();
        assertEquals(8, model.part.currentHealth, 0); assertEquals(0, model.absorption, 0); assertEquals(1, model.resyncs);
        model.part.currentHealth = 1;
        state.restore();
        assertEquals(2.5f, model.part.currentHealth, 0); assertEquals(3, model.absorption, 0); assertEquals(2, model.resyncs);
        model.deserialized.setFloat("health", 0);
        state.restore();
        assertEquals(2.5f, model.part.currentHealth, 0); assertEquals(3, model.resyncs);
    }

    @Test public void installedFirstAidRetainsItsPublicCapabilityAndRestorationContracts() throws Exception {
        String path = System.getProperty("rtxremix.rlcraftMods"); Assume.assumeNotNull(path);
        try (JarFile jar = new JarFile(new File(path, "firstaid-1.6.22.jar"))) {
            String api = "ichttt/mods/firstaid/api/";
            ClassNode capability = read(jar, api + "CapabilityExtendedHealthSystem");
            assertTrue(field(capability, "INSTANCE", "Lnet/minecraftforge/common/capabilities/Capability;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            ClassNode model = read(jar, api + "damagesystem/AbstractPlayerDamageModel");
            assertTrue(model.interfaces.contains("java/lang/Iterable"));
            assertTrue(model.interfaces.contains("net/minecraftforge/common/util/INBTSerializable"));
            assertTrue(method(model, "scheduleResync", "()V")); assertTrue(method(model, "setAbsorption", "(F)V"));
            ClassNode part = read(jar, api + "damagesystem/AbstractDamageablePart");
            assertTrue(field(part, "currentHealth", "F", Opcodes.ACC_PUBLIC)); assertTrue(method(part, "getMaxHealth", "()I"));
        }
    }
    private static ClassNode read(JarFile jar, String name) throws Exception {
        assertNotNull(jar.getJarEntry(name + ".class"));
        try (InputStream stream = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            ClassNode node = new ClassNode(Opcodes.ASM5);
            new ClassReader(stream).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG); return node;
        }
    }
    private static boolean field(ClassNode node, String name, String descriptor, int access) {
        for (FieldNode field : node.fields) if (name.equals(field.name) && descriptor.equals(field.desc)
                && (field.access & access) == access) return true;
        return false;
    }
    private static boolean method(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) if (name.equals(method.name) && descriptor.equals(method.desc)
                && (method.access & Opcodes.ACC_PUBLIC) != 0) return true;
        return false;
    }
    public static final class Part {
        public float currentHealth;
        public int getMaxHealth() { return 8; }
    }
    public static final class DamageModel implements INBTSerializable<NBTTagCompound>, Iterable<Part> {
        final Part part = new Part();
        final NBTTagCompound serialized = new NBTTagCompound();
        NBTTagCompound deserialized;
        float absorption;
        int resyncs;
        public void setAbsorption(float value) { absorption = value; }
        public void scheduleResync() { resyncs++; }
        public Iterator<Part> iterator() { return Arrays.asList(part).iterator(); }
        public NBTTagCompound serializeNBT() {
            serialized.setFloat("health", part.currentHealth); serialized.setFloat("absorption", absorption); return serialized;
        }
        public void deserializeNBT(NBTTagCompound value) {
            deserialized = value; part.currentHealth = value.getFloat("health"); absorption = value.getFloat("absorption");
        }
    }
}

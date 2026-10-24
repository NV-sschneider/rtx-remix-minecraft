/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayDeque;
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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

public class FixtureSurvivalStateTest {
    @Test public void independentLimbHealthIsRestoredFromOwnedNbtAndResynced() throws Exception {
        DamageModel model = new DamageModel(); model.part.currentHealth = 2.5f; model.part.absorption = 3;
        FixtureSurvivalState.FirstAid state = new FixtureSurvivalState.FirstAid(model,
                DamageModel.class.getMethod("scheduleResync"));
        // Deliberately mutate the model's reusable serialization object after the snapshot.
        model.serialized.setFloat("health0", 0);
        state.prepare();
        assertEquals(8, model.part.currentHealth, 0); assertEquals(0, model.part.absorption, 0); assertEquals(1, model.resyncs);
        model.part.currentHealth = 1;
        state.restore(() -> model.setAbsorption(0));
        assertEquals(2.5f, model.part.currentHealth, 0); assertEquals(3, model.part.absorption, 0); assertEquals(2, model.resyncs);
        assertTrue(state.matches(model));
        model.deserialized.setFloat("health0", 0);
        state.restore(() -> model.setAbsorption(0));
        assertEquals(2.5f, model.part.currentHealth, 0); assertEquals(3, model.resyncs);
    }

    @Test public void absentNbtKeysClearTransientAbsorptionAndHealerBeforeRestore() throws Exception {
        DamageModel model = new DamageModel(); model.part.absorption = 3;
        FixtureSurvivalState.FirstAid state = new FixtureSurvivalState.FirstAid(model, DamageModel.class.getMethod("scheduleResync"));
        // Pinned setAbsorption(total) distributes equally; NBT omits the originally zero second part.
        model.setAbsorption(3); model.other.activeHealer = new Object();
        assertFalse(state.matches(model));
        state.restore(() -> model.setAbsorption(0));
        assertEquals(3, model.part.absorption, 0); assertEquals(0, model.other.absorption, 0);
        assertNull(model.other.activeHealer); assertTrue(state.matches(model));
    }

    @Test public void activeHealersAreRejectedBeforePreparationMutatesTheFreshPlayer() throws Exception {
        DamageModel model = new DamageModel(); model.part.currentHealth = 2; model.part.activeHealer = new Object();
        assertThrows(IllegalStateException.class, () -> new FixtureSurvivalState.FirstAid(model,
                DamageModel.class.getMethod("scheduleResync")));
        assertEquals(2, model.part.currentHealth, 0); assertNotNull(model.part.activeHealer); assertEquals(0, model.resyncs);
    }

    @Test public void clientLimbHealthAndDistributionMustMatchRatherThanOnlyTheirTotal() throws Exception {
        DamageModel server = new DamageModel(); server.part.currentHealth = 2; server.other.currentHealth = 7;
        FixtureSurvivalState.FirstAid state = new FixtureSurvivalState.FirstAid(server, DamageModel.class.getMethod("scheduleResync"));
        DamageModel client = new DamageModel(); client.part.currentHealth = 7; client.other.currentHealth = 2;
        assertFalse(state.matches(client));
        client.part.currentHealth = 2; client.other.currentHealth = 7;
        assertTrue(state.matches(client));
        client.other.absorption = 1;
        assertFalse(state.matches(client));
    }

    @Test public void orderedAbsorptionClearThenDelayedNbtRestoresTheExistingClientModel() throws Exception {
        DamageModel server = new DamageModel(); server.part.absorption = 3;
        FixtureSurvivalState.FirstAid state = new FixtureSurvivalState.FirstAid(server, DamageModel.class.getMethod("scheduleResync"));
        DamageModel client = new DamageModel(); client.setAbsorption(10);
        client.deserializeNBT(server.serializeNBT().copy());
        assertFalse("A full sync alone leaves the client's originally zero limb stale", state.matches(client));
        client.part.currentHealth = 1;

        ArrayDeque<Runnable> packets = new ArrayDeque<>();
        server.delayedSync = () -> {
            NBTTagCompound message = server.serializeNBT().copy();
            packets.add(() -> client.deserializeNBT(message));
        };
        state.restore(() -> {
            // Pinned public player setter sends MessageApplyAbsorption before its server mutation.
            packets.add(() -> client.setAbsorption(0));
            server.setAbsorption(0);
        });
        assertTrue(state.matches(server)); assertEquals(1, packets.size());
        assertFalse(state.matches(client));
        packets.remove().run();
        assertEquals(0, client.part.absorption, 0); assertEquals(0, client.other.absorption, 0);
        assertFalse("Clearing absorption alone has not restored the body", state.matches(client));
        server.tick(); server.tick(); assertTrue(packets.isEmpty());
        server.tick(); assertEquals(1, packets.size());
        packets.remove().run();
        assertTrue(state.matches(client));
        assertEquals(3, client.part.absorption, 0); assertEquals(0, client.other.absorption, 0);
        assertTrue(packets.isEmpty());
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
            assertTrue(field(part, "activeHealer", "L" + api + "damagesystem/AbstractPartHealer;", Opcodes.ACC_PUBLIC));
            assertTrue(method(part, "setAbsorption", "(F)V")); assertTrue(method(part, "getAbsorption", "()F"));

            MethodNode tick = methodNode(read(jar, "ichttt/mods/firstaid/common/EventHandler"), "tickPlayers");
            assertTrue(calls(tick, "ichttt/mods/firstaid/common/util/CommonUtils", "isSurvivalOrAdventure"));
            assertTrue(calls(tick, api + "damagesystem/AbstractPlayerDamageModel", "tick"));
            ClassNode implementation = read(jar, "ichttt/mods/firstaid/common/damagesystem/PlayerDamageModel");
            MethodNode resync = methodNode(implementation, "scheduleResync");
            boolean threeTicks = false;
            for (AbstractInsnNode instruction : resync.instructions.toArray()) if (instruction instanceof FieldInsnNode) {
                FieldInsnNode assignment = (FieldInsnNode) instruction;
                if (assignment.getOpcode() == Opcodes.PUTFIELD && "resyncTimer".equals(assignment.name)
                        && instruction.getPrevious().getOpcode() == Opcodes.ICONST_3) threeTicks = true;
            }
            assertTrue("Creative must not return before the scheduled three player ticks", threeTicks);
            assertTrue(calls(methodNode(implementation, "tick"),
                    "net/minecraftforge/fml/common/network/simpleimpl/SimpleNetworkWrapper", "sendTo"));
            MethodNode setter = methodNode(read(jar, "ichttt/mods/firstaid/common/DataManagerWrapper"), "func_187227_b");
            assertTrue(calls(setter,
                    "ichttt/mods/firstaid/common/damagesystem/distribution/HealthDistribution", "addRandomHealth"));
            assertTrue(calls(setter, "ichttt/mods/firstaid/common/network/MessageApplyAbsorption", "<init>"));
            assertTrue(callIndex(setter, "net/minecraftforge/fml/common/network/simpleimpl/SimpleNetworkWrapper", "sendTo")
                    < callIndex(setter, api + "damagesystem/AbstractPlayerDamageModel", "setAbsorption"));
            MethodNode clearPacket = methodNode(read(jar,
                    "ichttt/mods/firstaid/common/network/MessageApplyAbsorption$Handler"), "lambda$onMessage$0");
            assertTrue(calls(clearPacket, api + "damagesystem/AbstractPlayerDamageModel", "setAbsorption"));
            MethodNode fullPacket = methodNode(read(jar,
                    "ichttt/mods/firstaid/common/network/MessageSyncDamageModel$Handler"), "lambda$onMessage$0");
            assertTrue(calls(fullPacket, "net/minecraft/client/entity/EntityPlayerSP", "getCapability"));
            assertTrue(calls(fullPacket, api + "damagesystem/AbstractPlayerDamageModel", "deserializeNBT"));
            assertFalse("The full packet does not clear omitted client absorption", calls(fullPacket,
                    api + "damagesystem/AbstractPlayerDamageModel", "setAbsorption"));
        }
    }
    private static ClassNode read(JarFile jar, String name) throws Exception {
        assertNotNull(jar.getJarEntry(name + ".class"));
        try (InputStream stream = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            ClassNode node = new ClassNode(Opcodes.ASM5);
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG); return node;
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
    private static MethodNode methodNode(ClassNode type, String name) {
        for (MethodNode method : type.methods) if (name.equals(method.name)) return method;
        throw new AssertionError("Missing pinned method " + name);
    }
    private static boolean calls(MethodNode method, String owner, String name) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name)) return true;
        }
        return false;
    }
    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (owner.equals(call.owner) && name.equals(call.name)) return index;
            }
            index++;
        }
        throw new AssertionError("Missing pinned call " + owner + "." + name);
    }
    public static final class Part {
        public float currentHealth = 8;
        public Object activeHealer;
        float absorption;
        public int getMaxHealth() { return 8; }
        public void setAbsorption(float value) { absorption = value; }
        public float getAbsorption() { return absorption; }
    }
    public static final class DamageModel implements INBTSerializable<NBTTagCompound>, Iterable<Part> {
        final Part part = new Part(), other = new Part();
        final NBTTagCompound serialized = new NBTTagCompound();
        NBTTagCompound deserialized;
        int resyncs;
        int resyncTicks;
        Runnable delayedSync;
        public void setAbsorption(float value) { part.absorption = other.absorption = value / 2; }
        public void scheduleResync() { resyncs++; if (delayedSync != null) resyncTicks = 3; }
        void tick() { if (resyncTicks > 0 && --resyncTicks == 0) delayedSync.run(); }
        public Iterator<Part> iterator() { return Arrays.asList(part, other).iterator(); }
        public NBTTagCompound serializeNBT() {
            int index = 0;
            for (Part value : this) {
                serialized.setFloat("health" + index, value.currentHealth);
                if (value.absorption > 0) serialized.setFloat("absorption" + index, value.absorption);
                else serialized.removeTag("absorption" + index);
                index++;
            }
            return serialized;
        }
        public void deserializeNBT(NBTTagCompound value) {
            deserialized = value;
            int index = 0;
            for (Part target : this) {
                target.currentHealth = value.getFloat("health" + index);
                if (value.hasKey("absorption" + index)) target.absorption = value.getFloat("absorption" + index);
                index++;
            }
        }
    }
}

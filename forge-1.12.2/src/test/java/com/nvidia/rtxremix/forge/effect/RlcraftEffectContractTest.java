/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import java.io.File;
import java.io.InputStream;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

/** Audits optional local artifacts' capture interfaces; no mod implementation is linked or copied. */
public class RlcraftEffectContractTest {
    @Test public void installedParticlesAndSpellsUseTheObservedVanillaCpuEntryPoints() throws Exception {
        String path = System.getProperty("rtxremix.rlcraftMods"); Assume.assumeNotNull(path);
        File directory = new File(path);
        try (JarFile lycanites = new JarFile(new File(directory, "lycanitesmobs-1.12.2-2.0.8.9.jar"));
             JarFile ice = new JarFile(new File(directory, "iceandfire-1.7.1-1.12.2.jar"));
             JarFile fbp = new JarFile(new File(directory, "FancyBlockParticles-1.12.x-2.4.1.jar"));
             JarFile mixins = new JarFile(new File(directory, "RLMixins-1.2.15.jar"))) {
            ClassNode particle = read(lycanites, "com/lycanitesmobs/client/renderer/RenderParticle");
            ClassNode spell = read(lycanites, "com/lycanitesmobs/client/renderer/RenderProjectileSprite");
            assertEquals("net/minecraft/client/renderer/entity/Render", particle.superName);
            assertEquals("net/minecraft/client/renderer/entity/Render", spell.superName);
            assertTrue(calls(particle, "net/minecraft/client/renderer/BufferBuilder", "func_181668_a"));
            assertTrue(calls(spell, "net/minecraft/client/renderer/BufferBuilder", "func_181668_a"));
            for (String name : new String[] {"ParticleDragonFire", "ParticleDragonIce"}) {
                ClassNode dragon = read(ice, "com/github/alexthe666/iceandfire/client/particle/" + name);
                assertTrue(hasMethod(dragon, "func_180434_a", "(Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/entity/Entity;FFFFFF)V"));
            }
            ClassNode manager = read(fbp, "com/TominoCZ/FBP/particle/FBPParticleManager");
            assertEquals("net/minecraft/client/particle/ParticleManager", manager.superName);
            assertFalse(hasMethod(manager, "func_78874_a", "(Lnet/minecraft/entity/Entity;F)V"));
            assertFalse(hasMethod(manager, "func_78872_b", "(Lnet/minecraft/entity/Entity;F)V"));
            assertTrue(calls(read(fbp, "com/TominoCZ/FBP/util/FBPRenderUtil"),
                    "net/minecraft/client/renderer/Tessellator", "func_78381_a"));
            ClassNode flare = read(mixins, "rlmixins/entity/flare/RenderFlareNonAlbedo");
            assertEquals("net/minecraft/client/renderer/entity/Render", flare.superName);
            assertTrue(hasMethod(flare, "func_76986_a", "(Lnet/minecraft/entity/Entity;DDDFF)V"));
        }
    }
    private static ClassNode read(JarFile jar, String name) throws Exception {
        assertNotNull(jar.getJarEntry(name + ".class"));
        try (InputStream stream = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG); return node;
        }
    }
    private static boolean hasMethod(ClassNode type, String name, String descriptor) {
        for (MethodNode method : type.methods) if (name.equals(method.name) && descriptor.equals(method.desc)) return true;
        return false;
    }
    private static boolean calls(ClassNode type, String owner, String name) {
        for (MethodNode method : type.methods) for (AbstractInsnNode node : method.instructions.toArray()) {
            if (node instanceof MethodInsnNode && owner.equals(((MethodInsnNode) node).owner) && name.equals(((MethodInsnNode) node).name)) return true;
        }
        return false;
    }
}

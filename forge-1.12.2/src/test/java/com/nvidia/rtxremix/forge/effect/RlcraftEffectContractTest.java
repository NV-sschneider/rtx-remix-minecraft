/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import java.io.File;
import java.io.InputStream;
import java.util.jar.JarFile;
import java.util.ArrayList;
import java.util.List;
import com.nvidia.rtxremix.forge.core.EffectCaptureTransformer;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.Assert.*;

/** Audits optional local artifacts' capture interfaces; no mod implementation is linked or copied. */
public class RlcraftEffectContractTest {
    @Test public void installedFbpUsesOutwardNormalsOppositeItsCubeWindingAndScopedPlacementCalls() throws Exception {
        String path = System.getProperty("rtxremix.rlcraftMods"); Assume.assumeNotNull(path);
        try (JarFile jar = new JarFile(new File(path, "FancyBlockParticles-1.12.x-2.4.1.jar"))) {
            ClassNode constants = read(jar, "com/TominoCZ/FBP/FBP");
            List<double[]> cube = vectors(constants, "CUBE"), normals = vectors(constants, "CUBE_NORMALS");
            assertEquals(24, cube.size()); assertEquals(6, normals.size());
            for (int face = 0; face < 6; face++) {
                double[] p = cube.get(face * 4), a = cube.get(face * 4 + 1), b = cube.get(face * 4 + 2), n = normals.get(face);
                double ax = a[0] - p[0], ay = a[1] - p[1], az = a[2] - p[2];
                double bx = b[0] - p[0], by = b[1] - p[1], bz = b[2] - p[2];
                assertTrue("The supplied lighting normal must not be replaced by winding",
                        (ay * bz - az * by) * n[0] + (az * bx - ax * bz) * n[1] + (ax * by - ay * bx) * n[2] < 0);
            }
            assertTrue(calls(read(jar, "com/TominoCZ/FBP/util/FBPRenderUtil"),
                    "net/minecraft/client/renderer/BufferBuilder", "func_181663_c"));
            String name = "com/TominoCZ/FBP/particle/FBPParticleBlock";
            ClassNode source = read(jar, name);
            assertTrue(calls(source, "net/minecraft/client/renderer/BlockModelRenderer", "func_187498_b"));
            assertTrue(calls(source, "net/minecraft/client/renderer/BlockModelRenderer", "func_187497_c"));
            ClassWriter writer = new ClassWriter(0); source.accept(writer);
            EffectCaptureTransformer transformer = new EffectCaptureTransformer();
            byte[] patched = transformer.transform(name, name.replace('/', '.'), writer.toByteArray());
            ClassNode result = new ClassNode(Opcodes.ASM5); new ClassReader(patched).accept(result, 0);
            assertFalse(calls(result, "net/minecraft/client/renderer/BlockModelRenderer", "func_187498_b"));
            assertFalse(calls(result, "net/minecraft/client/renderer/BlockModelRenderer", "func_187497_c"));
            assertTrue(calls(result, "com/nvidia/rtxremix/forge/effect/EffectCaptureHooks", "renderPlacedSmooth"));
            assertTrue(calls(result, "com/nvidia/rtxremix/forge/effect/EffectCaptureHooks", "renderPlacedFlat"));
            for (MethodNode method : result.methods) new Analyzer<>(new BasicVerifier()).analyze(result.name, method);
            assertArrayEquals(patched, transformer.transform(name, name.replace('/', '.'), patched));
        }
    }

    private static List<double[]> vectors(ClassNode type, String field) {
        List<double[]> result = new ArrayList<>();
        for (MethodNode method : type.methods) if ("<clinit>".equals(method.name)) {
            for (AbstractInsnNode node : method.instructions.toArray()) {
                if (node instanceof MethodInsnNode && "net/minecraft/util/math/Vec3d".equals(((MethodInsnNode) node).owner)
                        && "<init>".equals(((MethodInsnNode) node).name)) {
                    AbstractInsnNode z = previous(node), y = previous(z), x = previous(y);
                    result.add(new double[] {number(x), number(y), number(z)});
                }
                if (node instanceof FieldInsnNode && node.getOpcode() == Opcodes.PUTSTATIC) {
                    if (field.equals(((FieldInsnNode) node).name)) return result;
                    result.clear();
                }
            }
        }
        throw new AssertionError("Missing vector constants " + field);
    }
    private static AbstractInsnNode previous(AbstractInsnNode node) {
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        return node;
    }
    private static double number(AbstractInsnNode node) {
        if (node.getOpcode() == Opcodes.DCONST_0) return 0;
        if (node.getOpcode() == Opcodes.DCONST_1) return 1;
        return ((Number) ((LdcInsnNode) node).cst).doubleValue();
    }

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

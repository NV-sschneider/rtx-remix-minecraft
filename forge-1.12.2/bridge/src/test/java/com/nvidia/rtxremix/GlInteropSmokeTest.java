/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import com.nvidia.rtxremix.forge.interop.LegacyGlInterop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;

/** Verifies color and normal texture updates through native rendering and readback. */
public final class GlInteropSmokeTest {
    private static native int createContext();
    private static native byte[] readImageAndVerifyState();
    private static native void destroyContext();

    public static void main(String[] args) throws Exception {
        System.load(new File(args[0]).getAbsolutePath());
        System.load(new File(args[1]).getAbsolutePath());
        int framebuffer = createContext();
        RemixAPI api = null;
        LegacyGlInterop interop = null;
        try {
            api = RemixAPI.load(new File(args[2]).getAbsolutePath());
            long hwnd = LegacyGlInterop.currentWindowHandle();
            BridgeContractTest.check(hwnd != 0, "Hidden test window must have an HWND");
            api.startup(StartupInfo.builder().hwnd(hwnd).forceNoVkSwapchain(true).interopImage(true).build());
            System.out.println("GPU: runtime startup with app-owned swapchain succeeded");
            interop = new LegacyGlInterop(api.dxvkGetInteropInfo());
            interop.validateContext();
            System.out.println("GPU: external timeline semaphores imported");
            if (Boolean.getBoolean("rlcraftremix.gpuRetainedAlphaOnly")) {
                verifyRetainedAlpha(api, interop, framebuffer, hwnd);
                return;
            }
            if (Boolean.getBoolean("rlcraftremix.gpuViewModelReplacementOnly")) {
                verifyViewModelReplacement(api, interop, framebuffer, hwnd);
                return;
            }
            if (Boolean.getBoolean("rlcraftremix.gpuViewModelOnly")) {
                verifyViewModelPose(api, interop, framebuffer, hwnd);
                return;
            }
            long texture = api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(0x12340003L)
                    .pixelData(solidPixels(255, 0, 0)).size(2, 2)
                    .format(TextureFormat.RGBA8_SRGB).purpose(TexturePurpose.COLOR).build());
            MaterialInfo materialInfo = MaterialInfo.builder().hash(0x12340001L)
                    .albedoTexture(texture).emissiveTexture(texture)
                    .emissiveIntensity(4).emissiveColorConstant(1, 1, 1)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(1, 1, 1).build()).build();
            long material = api.createMaterial(materialInfo);
            MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
            surface.vertexBuffer = ByteBuffer.allocateDirect(3 * 64).order(ByteOrder.LITTLE_ENDIAN);
            HardcodedVertex.create(-1, -1, 3, 0, 0, -1, 0, 0, 0xFFFFFFFF).writeTo(surface.vertexBuffer);
            HardcodedVertex.create(1, -1, 3, 0, 0, -1, 1, 0, 0xFFFFFFFF).writeTo(surface.vertexBuffer);
            HardcodedVertex.create(0, 1, 3, 0, 0, -1, 0.5f, 1, 0xFFFFFFFF).writeTo(surface.vertexBuffer);
            surface.vertexBuffer.flip();
            surface.indexBuffer = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
            surface.indexBuffer.putInt(0).putInt(1).putInt(2);
            surface.indexBuffer.flip();
            surface.vertexCount = surface.indexCount = 3;
            surface.material = material;
            MeshInfo meshInfo = new MeshInfo();
            meshInfo.hash = 0x12340002L;
            meshInfo.surfaces = new MeshInfoSurfaceTriangles[] {surface};
            long mesh = api.createMesh(meshInfo);
            long instance = api.sceneInstanceAdd(mesh, Transform.identity().matrix, 0, 1, 0);
            CameraInfo camera = CameraInfo.builder().parameterizedExt(CameraInfoParameterizedEXT.builder()
                    .aspect(320f / 240f).fovYInDegrees(60).nearPlane(0.1f).farPlane(100).build()).build();
            for (int i = 0; i < 30; i++) {
                api.setupCamera(camera);
                api.submit(null);
                InteropInfo info = api.dxvkGetInteropInfo();
                long done;
                if (i < 10) {
                    done = interop.copyToFramebuffer(info, framebuffer, 320, 240, true);
                } else if (i == 10) {
                    try { interop.acquireToFramebuffer(info, -1, 320, 240, true); throw new AssertionError("Invalid framebuffer was accepted"); }
                    catch (IllegalStateException expected) { }
                    BridgeContractTest.check(!interop.hasAcquiredFrame(), "Failed initial blit must release ownership");
                    done = interop.releaseAcquired();
                    readImageAndVerifyState();
                } else {
                    if (i == 29) interop.acquire(info);
                    else interop.acquireToFramebuffer(info, framebuffer, 320, 240, true);
                    BridgeContractTest.check(interop.hasAcquiredFrame(), "Split interop must retain ownership through client UI");
                    try { interop.acquire(info); throw new AssertionError("Double acquire was allowed"); }
                    catch (IllegalStateException expected) { }
                    readImageAndVerifyState();
                    done = interop.finishFromFramebuffer(framebuffer, 320, 240, true);
                    BridgeContractTest.check(!interop.hasAcquiredFrame(), "Final client copy must release shared ownership");
                    BridgeContractTest.check(done == interop.releaseAcquired(), "Already released frame must not advance its semaphore");
                    try { interop.finishFromFramebuffer(framebuffer, 320, 240, true); throw new AssertionError("Post-release image write was allowed"); }
                    catch (IllegalStateException expected) { }
                }
                PresentInfo present = new PresentInfo();
                present.hwndOverride = hwnd;
                present.skipVulkanSwapchain = 1;
                present.clientDoneSemaphore_ValueToWait = done;
                api.present(present);
                if (i < 3 || i == 29) System.out.println("GPU: frame " + (i + 1) + " copied, size "
                        + info.width + "x" + info.height + ", Vulkan timeline=" + info.dxvkDoneSemaphore_ValueToWait);
            }
            byte[] pixels = readImageAndVerifyState();
            int colored = 0;
            BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 240; y++) {
                for (int x = 0; x < 320; x++) {
                    int offset = (y * 320 + x) * 4;
                    int r = pixels[offset] & 255, g = pixels[offset + 1] & 255, b = pixels[offset + 2] & 255;
                    if (r > 30 && r > g * 1.2 && r > b * 1.2) colored++;
                    image.setRGB(x, 239 - y, 0xFF000000 | (r << 16) | (g << 8) | b);
                }
            }
            ImageIO.write(image, "png", new File("gpu-smoke.png"));
            BridgeContractTest.check(colored > 100, "Expected a visible red emissive triangle; red pixels=" + colored);

            TexturePatch patch = new TexturePatch();
            patch.width = patch.height = 2;
            patch.rowPitch = 2 * 4;
            patch.format = TextureFormat.RGBA8_SRGB;
            patch.pixelData = solidPixels(0, 255, 0);
            TextureBatchEntry entry = new TextureBatchEntry();
            entry.handle = texture;
            entry.patches = new TexturePatch[] {patch};
            TexturePatchInfo update = new TexturePatchInfo();
            update.entries = new TextureBatchEntry[] {entry};
            api.updateTextureFromMemory(update);
            // Keep the texture handle, material, mesh and instance unchanged: a
            // successful Java return alone does not prove the native patch applied.
            for (int i = 0; i < 30; i++) {
                api.setupCamera(camera);
                api.submit(null);
                long done = interop.copyToFramebuffer(api.dxvkGetInteropInfo(), framebuffer, 320, 240, true);
                PresentInfo present = new PresentInfo();
                present.hwndOverride = hwnd;
                present.skipVulkanSwapchain = 1;
                present.clientDoneSemaphore_ValueToWait = done;
                api.present(present);
            }
            byte[] patched = readImageAndVerifyState();
            int changedInterior = 0;
            for (int y = 0; y < 240; y++) {
                for (int x = 0; x < 320; x++) {
                    int offset = (y * 320 + x) * 4;
                    int r = patched[offset] & 255, g = patched[offset + 1] & 255, b = patched[offset + 2] & 255;
                    image.setRGB(x, 239 - y, 0xFF000000 | (r << 16) | (g << 8) | b);
                    // This fixed 32x32 square lies inside the triangle, away from
                    // its antialiased edges. Compare the same pixels in both frames.
                    if (x >= 144 && x < 176 && y >= 104 && y < 136) {
                        int oldR = pixels[offset] & 255, oldG = pixels[offset + 1] & 255, oldB = pixels[offset + 2] & 255;
                        // Tone mapping desaturates bright emission. Require clear
                        // channel dominance plus a large change in signed contrast,
                        // rather than a saturation ratio or merely a bright result.
                        if (oldR > 30 && oldR - Math.max(oldG, oldB) >= 12
                                && g > 30 && g - Math.max(r, b) >= 12
                                && (g - r) - (oldG - oldR) >= 64) changedInterior++;
                    }
                }
            }
            ImageIO.write(image, "png", new File("gpu-smoke-texture-patched.png"));
            BridgeContractTest.check(changedInterior >= 768,
                    "Expected the retained sRGB texture patch to turn the same triangle pixels from red to green; changed interior pixels="
                            + changedInterior + "/1024");
            api.sceneInstanceRemove(instance);
            api.destroyMesh(mesh);
            api.destroyMaterial(material);
            api.destroyTexture(texture);
            System.out.println("PASS: GPU path tracing, shared color import, split ownership, idle acquire, timeline sync, bidirectional framebuffer copies, GL state restoration, and retained sRGB texture patch; red pixels="
                    + colored + ", red-to-green interior pixels=" + changedInterior + "/1024");
            verifyNormalPatch(api, interop, framebuffer, hwnd);
            verifyViewModelPose(api, interop, framebuffer, hwnd);
        } catch (Throwable failure) {
            failure.printStackTrace();
            throw failure;
        } finally {
            System.out.println("GPU: closing GL imports");
            try { if (interop != null) interop.close(); }
            finally {
                System.out.println("GPU: destroying test window before runtime shutdown");
                try { destroyContext(); }
                finally {
                    System.out.println("GPU: entering native runtime shutdown after test window destruction");
                    if (api != null) api.shutdown();
                    System.out.println("GPU: native runtime shutdown completed");
                }
            }
        }
    }

    private static ByteBuffer solidPixels(int red, int green, int blue) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) pixels.put((byte) red).put((byte) green).put((byte) blue).put((byte) 255);
        pixels.flip();
        return pixels;
    }

    /** Compares ordinary world blending at the same pixels, with a visible fractional-alpha control. */
    private static void verifyRetainedAlpha(RemixAPI api, LegacyGlInterop interop, int framebuffer, long hwnd) throws Exception {
        long texture = 0, light = 0;
        long[] materials = new long[3], meshes = new long[3], instances = new long[3], blends = new long[3];
        CameraInfo camera = CameraInfo.builder().parameterizedExt(CameraInfoParameterizedEXT.builder()
                .aspect(320f / 240f).fovYInDegrees(60).nearPlane(0.1f).farPlane(100).build()).build();
        float[] transform = Transform.identity().matrix;
        try {
            System.out.println("GPU RETAINED ALPHA: gpuResidentScene=" + api.getConfigVariableResolved("rtx.gpuResidentScene")
                    + ", sceneScale=" + api.getConfigVariableResolved("rtx.sceneScale")
                    + ", ordinary category=0, nonemissive rough foreground, white distant light, volumetricRadianceScale=1");
            // The 78 authored fractional texels are outside every foreground UV.
            // Interior body samples remain far from them at the tested ray footprint.
            texture = api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(0x12340600L)
                    .pixelData(alphaPixels(255)).size(512, 512)
                    .format(TextureFormat.RGBA8_SRGB).purpose(TexturePurpose.COLOR).build());
            MaterialInfo foreground = MaterialInfo.builder().hash(0x12340601L).albedoTexture(texture)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(1, 1, 1)
                            .roughnessConstant(1).metallicConstant(0).useDrawCallAlphaState(true).build()).build();
            foreground.filterMode = 0;
            foreground.wrapModeU = foreground.wrapModeV = MaterialInfo.WRAP_MODE_CLAMP;
            materials[0] = api.createMaterial(foreground);
            for (int half = 0; half < 2; half++) {
                materials[half + 1] = api.createMaterial(MaterialInfo.builder().hash(0x12340602L + half)
                        .emissiveIntensity(1).emissiveColorConstant(0, half == 0 ? 1 : 0, half == 0 ? 0 : 1)
                        .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(0, 0, 0)
                                .roughnessConstant(1).metallicConstant(0).build()).build());
                meshes[half + 1] = alphaQuad(api, materials[half + 1], 0x12340610L + half,
                        -4, 4, half == 0 ? -4 : 0, half == 0 ? 0 : 4, 5, 0, 1);
                instances[half + 1] = api.sceneInstanceAdd(meshes[half + 1], transform, 0, 1, 0);
            }
            // Match SkyScene's sun: blended lighting may sample the volumetric
            // radiance cache even though this surface has no particle category.
            light = api.createLight(LightInfo.builder().hash(0x12340620L).radiance(1, 1, 1)
                    .distantExt(LightInfoDistantEXT.builder().direction(0, 0, 1)
                            .angularDiameterDegrees(0.5f).volumetricRadianceScale(1).build()).build());
            byte[] background = alphaImage(api, interop, framebuffer, hwnd, camera, light, "background");
            meshes[0] = alphaQuad(api, materials[0], 0x12340612L, -1.1f, 1.1f, -1.2f, 1.2f, 3, 0.125f, 0.375f);
            blends[0] = InstanceInfoBlendEXT.textureTimesVertexColor().allocNative(0);
            blends[1] = InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(26).allocNative(0);
            blends[2] = InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(26).allocNative(0);
            instances[0] = api.sceneInstanceAdd(meshes[0], transform, 0, 1, blends[0]);
            byte[] opaque = alphaImage(api, interop, framebuffer, hwnd, camera, light, "opaque");
            api.sceneInstanceUpdate(instances[0], meshes[0], transform, 0, 1, blends[1]);
            byte[] cutout = alphaImage(api, interop, framebuffer, hwnd, camera, light, "cutout");
            api.sceneInstanceUpdate(instances[0], meshes[0], transform, 0, 1, blends[2]);
            byte[] blended = alphaImage(api, interop, framebuffer, hwnd, camera, light, "blended-body");
            alphaPatch(api, texture, 128);
            byte[] fractional = alphaImage(api, interop, framebuffer, hwnd, camera, light, "fractional");
            alphaPatch(api, texture, 255);
            byte[] restored = alphaImage(api, interop, framebuffer, hwnd, camera, light, "blended-restored");
            double backgroundContrast = colorDistance(alphaRegion(background, 0), alphaRegion(background, 1));
            double blendedHorizonContrast = colorDistance(alphaRegion(blended, 0), alphaRegion(blended, 1));
            boolean controlPass = backgroundContrast >= 80;
            // The uniformly lit plane must hide the colored boundary. Blended
            // surfaces can use approximate lighting, so opaque BRDF equality is
            // not an opacity contract; use the measured blended endpoint instead.
            boolean blendPass = blendedHorizonContrast <= 12 && blendedHorizonContrast <= backgroundContrast * 0.1;
            boolean fractionalPass = true, restoredPass = true;
            System.out.println("GPU RETAINED ALPHA: backgroundHorizonContrast=" + backgroundContrast
                    + ", blendedHorizonContrast=" + blendedHorizonContrast
                    + ", blendedHorizonLeak=" + blendedHorizonContrast / backgroundContrast);
            for (int half = 0; half < 2; half++) {
                double[] bg = alphaRegion(background, half), solid = alphaRegion(opaque, half);
                double[] clipped = alphaRegion(cutout, half), blend = alphaRegion(blended, half);
                double[] partial = alphaRegion(fractional, half), again = alphaRegion(restored, half);
                double contrast = colorDistance(bg, solid), cutoutError = colorDistance(solid, clipped);
                double blendLightingDifference = colorDistance(solid, blend), restoredError = colorDistance(blend, again);
                double fraction = backgroundFraction(blend, bg, partial);
                controlPass &= contrast >= 40 && cutoutError <= 12;
                blendPass &= blend[0] >= 60 && blend[0] - Math.max(blend[1], blend[2]) >= 40
                        && colorDistance(blend, bg) >= 40;
                // Projection alone can place a black or neutral image between
                // colored endpoints. Require both foreground and background hues.
                int backgroundChannel = half == 0 ? 1 : 2, unusedChannel = half == 0 ? 2 : 1;
                fractionalPass &= colorDistance(partial, blend) >= 15 && colorDistance(partial, bg) >= 15
                        && partial[0] - partial[unusedChannel] >= 30
                        && partial[backgroundChannel] - partial[unusedChannel] >= 30
                        && fraction > 0.08 && fraction < 0.92;
                restoredPass &= restoredError <= 12;
                System.out.println("GPU RETAINED ALPHA: region=" + (half == 0 ? "lower-green" : "upper-blue")
                        + ", background=" + java.util.Arrays.toString(bg) + ", opaque=" + java.util.Arrays.toString(solid)
                        + ", cutout=" + java.util.Arrays.toString(clipped) + ", blended=" + java.util.Arrays.toString(blend)
                        + ", fractional=" + java.util.Arrays.toString(partial) + ", restored=" + java.util.Arrays.toString(again)
                        + ", contrast=" + contrast + ", cutoutError=" + cutoutError + ", blendLightingDifference=" + blendLightingDifference
                        + ", restoredBlendError=" + restoredError
                        + ", fractionalBackgroundFraction=" + fraction);
            }
            BridgeContractTest.check(controlPass, "Opaque/cutout controls must agree and occlude both colored backgrounds");
            BridgeContractTest.check(fractionalPass, "Alpha128 must reveal a measurable partial background in both regions; black/frozen/opaque controls fail");
            BridgeContractTest.check(blendPass && restoredPass,
                    "Alpha255 body samples must remain solid with ordinary retained blending, including after a same-handle fractional texture patch");
            System.out.println("PASS: ordinary retained alpha255 body stays solid above/below the background horizon; fractional alpha remains visible.");
        } finally {
            for (long instance : instances) if (instance != 0) api.sceneInstanceRemove(instance);
            for (long mesh : meshes) if (mesh != 0) api.destroyMesh(mesh);
            for (long blend : blends) if (blend != 0) RemixBridge.freeExt(blend);
            for (long material : materials) if (material != 0) api.destroyMaterial(material);
            if (texture != 0) api.destroyTexture(texture);
            if (light != 0) api.destroyLight(light);
        }
    }

    private static ByteBuffer alphaPixels(int bodyAlpha) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(512 * 512 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) {
            int alpha = x == 510 && y < 78 ? 128 : bodyAlpha;
            pixels.put((byte) 210).put((byte) 70).put((byte) 30).put((byte) alpha);
        }
        pixels.flip();
        return pixels;
    }

    private static void alphaPatch(RemixAPI api, long texture, int alpha) {
        TexturePatch patch = new TexturePatch();
        patch.width = patch.height = 512; patch.rowPitch = 512 * 4;
        patch.format = TextureFormat.RGBA8_SRGB; patch.pixelData = alphaPixels(alpha);
        TextureBatchEntry entry = new TextureBatchEntry(); entry.handle = texture; entry.patches = new TexturePatch[] {patch};
        TexturePatchInfo update = new TexturePatchInfo(); update.entries = new TextureBatchEntry[] {entry};
        api.updateTextureFromMemory(update);
    }

    private static long alphaQuad(RemixAPI api, long material, long hash,
            float left, float right, float bottom, float top, float depth, float uvMin, float uvMax) {
        MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
        surface.vertexBuffer = ByteBuffer.allocateDirect(4 * HardcodedVertex.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        HardcodedVertex.create(left, bottom, depth, 0, 0, -1, uvMin, uvMin, 0xffffffff).writeTo(surface.vertexBuffer);
        HardcodedVertex.create(right, bottom, depth, 0, 0, -1, uvMax, uvMin, 0xffffffff).writeTo(surface.vertexBuffer);
        HardcodedVertex.create(right, top, depth, 0, 0, -1, uvMax, uvMax, 0xffffffff).writeTo(surface.vertexBuffer);
        HardcodedVertex.create(left, top, depth, 0, 0, -1, uvMin, uvMax, 0xffffffff).writeTo(surface.vertexBuffer);
        surface.vertexBuffer.flip();
        surface.indexBuffer = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.LITTLE_ENDIAN);
        // Both geometric and interpolated normals face the camera at -Z.
        surface.indexBuffer.putInt(0).putInt(2).putInt(1).putInt(0).putInt(3).putInt(2).flip();
        surface.vertexCount = 4; surface.indexCount = 6; surface.material = material;
        MeshInfo mesh = new MeshInfo(); mesh.hash = hash; mesh.surfaces = new MeshInfoSurfaceTriangles[] {surface};
        return api.createMesh(mesh);
    }

    private static byte[] alphaImage(RemixAPI api, LegacyGlInterop interop, int framebuffer,
            long hwnd, CameraInfo camera, long light, String name) throws Exception {
        long first = 0, last = 0;
        for (int frame = 0; frame < 30; frame++) {
            api.setupCamera(camera); api.drawLightInstance(light); api.submit(null);
            InteropInfo info = api.dxvkGetInteropInfo();
            last = info.dxvkDoneSemaphore_ValueToWait;
            if (frame == 0) first = last;
            long done = interop.copyToFramebuffer(info, framebuffer, 320, 240, true);
            PresentInfo present = new PresentInfo(); present.hwndOverride = hwnd;
            present.skipVulkanSwapchain = 1; present.clientDoneSemaphore_ValueToWait = done; api.present(present);
        }
        byte[] pixels = readImageAndVerifyState();
        saveImage(pixels, "gpu-retained-alpha-" + name + ".png");
        System.out.println("GPU RETAINED ALPHA: stage=" + name + ", timeline=" + first + ".." + last);
        BridgeContractTest.check(last > first, "Retained alpha diagnostic must advance native frames: " + name);
        return pixels;
    }

    private static double[] alphaRegion(byte[] pixels, int half) {
        double[] mean = new double[3];
        int firstY = half == 0 ? 88 : 136;
        for (int y = firstY; y < firstY + 16; y++) for (int x = 148; x < 172; x++) {
            int at = (y * 320 + x) * 4;
            for (int channel = 0; channel < 3; channel++) mean[channel] += pixels[at + channel] & 255;
        }
        for (int channel = 0; channel < 3; channel++) mean[channel] /= 16 * 24;
        return mean;
    }

    private static double colorDistance(double[] first, double[] second) {
        return (Math.abs(first[0] - second[0]) + Math.abs(first[1] - second[1]) + Math.abs(first[2] - second[2])) / 3;
    }

    /** Contrast along the measured opaque-to-background axis, independent of tone-mapped saturation. */
    private static double backgroundFraction(double[] opaque, double[] background, double[] sample) {
        double dot = 0, length = 0;
        for (int channel = 0; channel < 3; channel++) {
            double delta = background[channel] - opaque[channel];
            dot += (sample[channel] - opaque[channel]) * delta; length += delta * delta;
        }
        return length == 0 ? Double.NaN : dot / length;
    }

    /** Matches ItemScene's create/update/retire order while changing local vertices every frame. */
    private static void verifyViewModelReplacement(RemixAPI api, LegacyGlInterop interop, int framebuffer, long hwnd) throws Exception {
        api.setConfigVariable("rtx.viewModel.enable", "True");
        api.setConfigVariable("rtx.viewModel.perspectiveCorrection", "True");
        api.setConfigVariable("rtx.viewModel.scale", "1.0");
        // The caller selects its actual runtime configuration. Never mask a scene-scale
        // mismatch by changing it here; print the resolved value with the geometry depth.
        float scale = Float.parseFloat(System.getProperty("rlcraftremix.gpuViewModelTriangleScale", "0.01"));
        BridgeContractTest.check(Float.isFinite(scale) && scale > 0, "Triangle scale must be positive and finite");
        System.out.println("GPU VIEW_MODEL REPLACEMENT: sceneScale=" + api.getConfigVariableResolved("rtx.sceneScale")
                + ", triangleScale=" + scale + ", cameraDepth=" + (3 * scale));
        long texture = 0, instance = 0, mesh = 0, pendingMesh = 0, retiredMesh = 0;
        long[] materials = new long[2], blends = new long[3];
        String[] names = {"opaque", "textured-cutout", "textured-alpha-blend"};
        try {
            materials[0] = api.createMaterial(MaterialInfo.builder().hash(0x12340400L)
                    .emissiveIntensity(4).emissiveColorConstant(1, 0, 0)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(1, 0, 0).build()).build());
            texture = api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(0x12340401L)
                    .pixelData(solidPixels(255, 0, 0)).size(2, 2)
                    .format(TextureFormat.RGBA8_SRGB).purpose(TexturePurpose.COLOR).build());
            MaterialInfo textured = MaterialInfo.builder().hash(0x12340402L)
                    .albedoTexture(texture).emissiveTexture(texture)
                    .emissiveIntensity(4).emissiveColorConstant(1, 1, 1)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(1, 1, 1)
                            .roughnessConstant(1).metallicConstant(0).useDrawCallAlphaState(true).build()).build();
            textured.filterMode = 0;
            textured.wrapModeU = textured.wrapModeV = MaterialInfo.WRAP_MODE_CLAMP;
            materials[1] = api.createMaterial(textured);
            blends[1] = InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(26).allocNative(0);
            blends[2] = InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(26).allocNative(0);
            float cy = (float) Math.cos(0.8), sy = (float) Math.sin(0.8);
            float cp = (float) Math.cos(0.3), sp = (float) Math.sin(0.3);
            long hash = 0x12340500L;
            int passed = 0;
            for (int mode = 0; mode < names.length; mode++) for (int origin : new int[] {0, 15000, -15000}) {
                CameraInfo world = viewModelCamera(CameraType.WORLD, origin, cy, sy, cp, sp);
                CameraInfo view = viewModelCamera(CameraType.VIEW_MODEL, origin, cy, sy, cp, sp);
                float[] anchor = effectiveCameraPosition(world.parameterizedExt);
                float[] transform = {1, 0, 0, anchor[0], 0, 1, 0, anchor[1], 0, 0, 1, anchor[2]};
                long material = materials[mode == 0 ? 0 : 1];
                mesh = viewModelTriangle(api, material, hash++, scale, 0, cy, sy, cp, sp);
                instance = api.sceneInstanceAdd(mesh, transform, InstanceCategoryBit.VIEW_MODEL, 1, blends[mode]);
                for (int frame = 0; frame < 30; frame++) presentViewModelFrame(api, interop, framebuffer, hwnd, world, view);
                byte[] pixels = readImageAndVerifyState();
                double[] control = viewModelRedPixels(pixels);
                String name = "gpu-viewmodel-replacement-" + names[mode] + "-" + origin;
                saveImage(pixels, name + "-control.png");
                int visibleFrames = 0, minimumRed = Integer.MAX_VALUE;
                double minimumX = Double.POSITIVE_INFINITY, maximumX = Double.NEGATIVE_INFINITY;
                long firstTimeline = 0, lastTimeline = 0;
                for (int frame = 0; frame < 60; frame++) {
                    // Relative amplitude retains the same projected six-pixel sweep
                    // when the caller varies depth to diagnose the native ray minimum.
                    float offset = scale * 0.04f * (float) Math.sin(frame * Math.PI / 30);
                    pendingMesh = viewModelTriangle(api, material, hash++, scale, offset, cy, sy, cp, sp);
                    api.sceneInstanceUpdate(instance, pendingMesh, transform, InstanceCategoryBit.VIEW_MODEL, 1, blends[mode]);
                    retiredMesh = mesh; mesh = pendingMesh; pendingMesh = 0;
                    api.destroyMesh(retiredMesh); retiredMesh = 0;
                    lastTimeline = presentViewModelFrame(api, interop, framebuffer, hwnd, world, view);
                    if (frame == 0) firstTimeline = lastTimeline;
                    pixels = readImageAndVerifyState();
                    if (frame >= 10) {
                        double[] sample = viewModelRedPixels(pixels);
                        int red = (int) sample[0];
                        minimumRed = Math.min(minimumRed, red);
                        if (red >= 5000 && red >= control[0] * 0.75) visibleFrames++;
                        if (red > 0) { minimumX = Math.min(minimumX, sample[1]); maximumX = Math.max(maximumX, sample[1]); }
                    }
                }
                saveImage(pixels, name + "-replaced.png");
                double motion = maximumX - minimumX;
                boolean visible = control[0] >= 5000 && visibleFrames == 50;
                boolean centered = Math.abs(control[1] - 159.5) < 1.5;
                boolean moving = motion >= 3 && motion <= 8;
                boolean advanced = lastTimeline > firstTimeline;
                if (visible && centered && moving && advanced) passed++;
                System.out.println("GPU VIEW_MODEL REPLACEMENT: mode=" + names[mode] + ", origin=" + origin
                        + ", controlRed=" + (int) control[0] + ", controlCentroid=" + control[1]
                        + ", visibleFrames=" + visibleFrames + "/50, minimumRed=" + minimumRed
                        + ", centroidRange=" + minimumX + ".." + maximumX + ", motion=" + motion
                        + ", timeline=" + firstTimeline + ".." + lastTimeline
                        + ", passed=" + (visible && centered && moving && advanced));
                api.sceneInstanceRemove(instance); instance = 0;
                api.destroyMesh(mesh); mesh = 0;
            }
            BridgeContractTest.check(passed == 9,
                    "Every per-frame replacement case must remain visible and animated under the selected runtime config; passed=" + passed + "/9");
            System.out.println("PASS: per-frame view-model mesh replacement preserves opaque, textured-cutout and opaque-textured alpha-blended geometry at near/far coordinates.");
        } finally {
            if (instance != 0) api.sceneInstanceRemove(instance);
            if (pendingMesh != 0) api.destroyMesh(pendingMesh);
            if (retiredMesh != 0) api.destroyMesh(retiredMesh);
            if (mesh != 0) api.destroyMesh(mesh);
            for (long blend : blends) if (blend != 0) RemixBridge.freeExt(blend);
            for (long material : materials) if (material != 0) api.destroyMaterial(material);
            if (texture != 0) api.destroyTexture(texture);
        }
    }

    private static long presentViewModelFrame(RemixAPI api, LegacyGlInterop interop, int framebuffer, long hwnd,
            CameraInfo world, CameraInfo view) {
        api.setupCamera(world); api.setupCamera(view); api.submit(null);
        InteropInfo info = api.dxvkGetInteropInfo();
        long done = interop.copyToFramebuffer(info, framebuffer, 320, 240, true);
        PresentInfo present = new PresentInfo(); present.hwndOverride = hwnd;
        present.skipVulkanSwapchain = 1; present.clientDoneSemaphore_ValueToWait = done; api.present(present);
        return info.dxvkDoneSemaphore_ValueToWait;
    }

    private static double[] viewModelRedPixels(byte[] pixels) {
        int red = 0; long sumX = 0;
        for (int y = 0; y < 240; y++) for (int x = 0; x < 320; x++) {
            int at = (y * 320 + x) * 4;
            int r = pixels[at] & 255, g = pixels[at + 1] & 255, b = pixels[at + 2] & 255;
            if (r > 30 && r - Math.max(g, b) >= 12) { red++; sumX += x; }
        }
        return new double[] {red, red == 0 ? Double.NaN : sumX / (double) red};
    }

    /** Keeps sub-ULP animated poses in local BLAS vertices under the submitted world camera. */
    private static void verifyViewModelPose(RemixAPI api, LegacyGlInterop interop, int framebuffer, long hwnd) throws Exception {
        api.setConfigVariable("rtx.viewModel.enable", "True");
        api.setConfigVariable("rtx.viewModel.perspectiveCorrection", "True");
        api.setConfigVariable("rtx.viewModel.scale", "1.0");
        long material = 0, instance = 0, retiredMesh = 0;
        long[] meshes = new long[2];
        try {
            material = api.createMaterial(MaterialInfo.builder().hash(0x12340300L)
                    .emissiveIntensity(4).emissiveColorConstant(1, 0, 0)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(1, 0, 0).build()).build());
            meshes[0] = viewModelTriangle(api, material, 0x12340301L, 1, 0, 1, 0, 1, 0);
            long geometryHash = 0x12340310L;
            int visible = 0, moving = 0;
            for (int rotated = 0; rotated < 2; rotated++) {
                float cy = rotated == 0 ? 1 : (float) Math.cos(0.8), sy = rotated == 0 ? 0 : (float) Math.sin(0.8);
                float cp = rotated == 0 ? 1 : (float) Math.cos(0.3), sp = rotated == 0 ? 0 : (float) Math.sin(0.3);
                for (int origin : new int[] {0, 15000, -15000}) {
                    CameraInfo world = viewModelCamera(CameraType.WORLD, origin, cy, sy, cp, sp);
                    CameraInfo view = viewModelCamera(CameraType.VIEW_MODEL, origin, cy, sy, cp, sp);
                    float[] anchor = effectiveCameraPosition(world.parameterizedExt);
                    for (int baked = 0; baked < 2; baked++) {
                        double previous = Double.NaN;
                        for (int step = 0; step < 2; step++) {
                            float offset = step * 0.0004f, scale = baked == 0 ? 0.01f : 1;
                            float[] transform = {cy * scale, sy * sp * scale, sy * cp * scale, origin + (baked == 0 ? cy * offset : 0),
                                    0, cp * scale, -sp * scale, 64,
                                    -sy * scale, cy * sp * scale, cy * cp * scale, -origin - (baked == 0 ? sy * offset : 0)};
                            if (baked != 0) {
                                transform[0] = transform[5] = transform[10] = 1;
                                transform[1] = transform[2] = transform[4] = transform[6] = transform[8] = transform[9] = 0;
                                transform[3] = anchor[0]; transform[7] = anchor[1]; transform[11] = anchor[2];
                                long created = viewModelTriangle(api, material, geometryHash++, 0.01f, offset, cy, sy, cp, sp);
                                retiredMesh = meshes[1]; meshes[1] = created;
                            }
                            if (instance == 0) instance = api.sceneInstanceAdd(meshes[baked], transform, InstanceCategoryBit.VIEW_MODEL, 1, 0);
                            else api.sceneInstanceUpdate(instance, meshes[baked], transform, InstanceCategoryBit.VIEW_MODEL, 1, 0);
                            if (retiredMesh != 0) { api.destroyMesh(retiredMesh); retiredMesh = 0; }
                            long firstTimeline = 0, lastTimeline = 0;
                            for (int frame = 0; frame < 30; frame++) {
                                api.setupCamera(world); api.setupCamera(view); api.submit(null);
                                InteropInfo info = api.dxvkGetInteropInfo();
                                if (frame == 0) firstTimeline = info.dxvkDoneSemaphore_ValueToWait;
                                lastTimeline = info.dxvkDoneSemaphore_ValueToWait;
                                long done = interop.copyToFramebuffer(info, framebuffer, 320, 240, true);
                                PresentInfo present = new PresentInfo(); present.hwndOverride = hwnd;
                                present.skipVulkanSwapchain = 1; present.clientDoneSemaphore_ValueToWait = done; api.present(present);
                            }
                            byte[] pixels = readImageAndVerifyState();
                            saveImage(pixels, "gpu-viewmodel-pose-" + rotated + "-" + origin + "-" + baked + "-" + step + ".png");
                            int red = 0; long sumX = 0;
                            for (int y = 0; y < 240; y++) for (int x = 0; x < 320; x++) {
                                int at = (y * 320 + x) * 4;
                                int r = pixels[at] & 255, g = pixels[at + 1] & 255, b = pixels[at + 2] & 255;
                                if (r > 30 && r - Math.max(g, b) >= 12) { red++; sumX += x; }
                            }
                            double centroid = red == 0 ? Double.NaN : sumX / (double) red;
                            double shift = centroid - previous;
                            if (red >= 5000) visible++;
                            if (baked != 0 && step != 0 && shift >= 1.5 && shift <= 4.0) moving++;
                            System.out.println("GPU VIEW_MODEL POSE: rotated=" + rotated + ", origin=" + origin + ", baked=" + baked
                                    + ", offset=" + offset + ", redPixels=" + red + ", centroidX=" + centroid + ", shift=" + shift
                                    + ", anchor=" + anchor[0] + "," + anchor[1] + "," + anchor[2]
                                    + ", timeline=" + firstTimeline + ".." + lastTimeline);
                            BridgeContractTest.check(lastTimeline > firstTimeline, "Native presentation did not advance during the pose sample");
                            if (origin == 0 && baked == 0 && step == 1) {
                                BridgeContractTest.check(shift >= 1.5 && shift <= 4.0,
                                        "Near-origin rigid control did not move; the pose comparison is invalid: shift=" + shift);
                            }
                            if (baked != 0 && step == 0) BridgeContractTest.check(Math.abs(centroid - 159.5) < 1.5,
                                    "Camera-anchored geometry must stay centered when the world camera rotates: centroid=" + centroid);
                            previous = centroid;
                        }
                    }
                }
            }
            BridgeContractTest.check(visible == 24, "All retained view-model samples must render; visible=" + visible + "/24");
            BridgeContractTest.check(moving == 6, "Baked poses must retain the expected 2.77-pixel movement below the world float ULP; moving=" + moving + "/6");
            System.out.println("PASS: retained camera-anchored view models preserve visible local motion at near/far coordinates and rotated cameras.");
        } finally {
            if (instance != 0) api.sceneInstanceRemove(instance);
            if (retiredMesh != 0) api.destroyMesh(retiredMesh);
            for (long mesh : meshes) if (mesh != 0) api.destroyMesh(mesh);
            if (material != 0) api.destroyMaterial(material);
        }
    }

    private static long viewModelTriangle(RemixAPI api, long material, long hash, float scale, float offset,
            float cy, float sy, float cp, float sp) {
        MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
        surface.vertexBuffer = ByteBuffer.allocateDirect(3 * HardcodedVertex.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        writeViewVertex(surface.vertexBuffer, -scale + offset, -scale, -3 * scale, 0, 0, cy, sy, cp, sp);
        writeViewVertex(surface.vertexBuffer, scale + offset, -scale, -3 * scale, 1, 0, cy, sy, cp, sp);
        writeViewVertex(surface.vertexBuffer, offset, scale, -3 * scale, 0.5f, 1, cy, sy, cp, sp);
        surface.vertexBuffer.flip();
        surface.indexBuffer = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
        surface.indexBuffer.putInt(0).putInt(1).putInt(2).flip();
        surface.vertexCount = surface.indexCount = 3; surface.material = material;
        return api.createMesh(MeshInfo.builder().hash(hash).surfaces(surface).build());
    }

    private static void writeViewVertex(ByteBuffer output, float x, float y, float z, float u, float v,
            float cy, float sy, float cp, float sp) {
        HardcodedVertex.create(cy * x + sy * sp * y + sy * cp * z, cp * y - sp * z,
                -sy * x + cy * sp * y + cy * cp * z, sy * cp, -sp, cy * cp, u, v, 0xFFFFFFFF).writeTo(output);
    }

    private static CameraInfo viewModelCamera(int type, float origin, float cy, float sy, float cp, float sp) {
        return CameraInfo.builder().type(type).parameterizedExt(CameraInfoParameterizedEXT.builder()
                .position(origin, 64, -origin).forward(-sy * cp, sp, -cy * cp).right(cy, 0, -sy).up(sy * sp, cp, cy * sp)
                .aspect(320f / 240f).fovYInDegrees(60).nearPlane(0.05f).farPlane(1000).build()).build();
    }

    private static float[] effectiveCameraPosition(CameraInfoParameterizedEXT camera) {
        double[] axes = {camera.right.x, camera.up.x, camera.forward.x,
                camera.right.y, camera.up.y, camera.forward.y, camera.right.z, camera.up.z, camera.forward.z};
        for (int column = 0; column < 3; column++) {
            double length = Math.sqrt(axes[column] * axes[column] + axes[column + 3] * axes[column + 3]
                    + axes[column + 6] * axes[column + 6]);
            for (int row = 0; row < 3; row++) axes[row * 3 + column] /= length;
        }
        double[] view = inverse3(axes), position = {camera.position.x, camera.position.y, camera.position.z};
        double[] translation = new double[3];
        for (int row = 0; row < 3; row++) translation[row] = (float) -(view[row * 3] * position[0]
                + view[row * 3 + 1] * position[1] + view[row * 3 + 2] * position[2]);
        for (int at = 0; at < view.length; at++) view[at] = (float) view[at];
        double[] inverse = inverse3(view);
        float[] result = new float[3];
        for (int row = 0; row < 3; row++) result[row] = (float) -(inverse[row * 3] * translation[0]
                + inverse[row * 3 + 1] * translation[1] + inverse[row * 3 + 2] * translation[2]);
        return result;
    }

    private static double[] inverse3(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double[] result = {e * i - f * h, c * h - b * i, b * f - c * e,
                f * g - d * i, a * i - c * g, c * d - a * f, d * h - e * g, b * g - a * h, a * e - b * d};
        double determinant = a * result[0] + b * result[3] + c * result[6];
        for (int at = 0; at < result.length; at++) result[at] /= determinant;
        return result;
    }
    private static void verifyNormalPatch(RemixAPI api, LegacyGlInterop interop, int framebuffer, long hwnd) throws Exception {
        long[] textures = new long[2], materials = new long[2], meshes = new long[2], instances = new long[2];
        MaterialInfo[] materialInfo = new MaterialInfo[2];
        // Preserve the original projected size at z=20 while making both view
        // directions nearly parallel, so view-dependent rough shading is comparable.
        float fov = (float) Math.toDegrees(2 * Math.atan(3 * Math.tan(Math.PI / 6) / 20));
        CameraInfo normalCamera = CameraInfo.builder().parameterizedExt(CameraInfoParameterizedEXT.builder()
                .aspect(320f / 240f).fovYInDegrees(fov).nearPlane(0.1f).farPlane(100).build()).build();
        long light = 0;
        try {
            textures[0] = api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(0x12340100L)
                    .pixelData(solidPixels(242, 128, 184)).size(2, 2)
                    .format(TextureFormat.RGBA8_UNORM).purpose(TexturePurpose.NORMAL_DX).build());
            // The RGB normal is proportional to (229, 1, 113). Upper-hemisphere
            // oct encoding divides XY by L1=343, then maps each channel to UNORM.
            // Compare this directly with the pinned runtime's NORMAL_DX conversion
            // before relying on its NORMAL_OTH pass-through/update contract.
            textures[1] = api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(0x12340101L)
                    .pixelData(solidNormalPixels(213, 128)).size(2, 2)
                    .format(TextureFormat.RG8_UNORM).purpose(TexturePurpose.NORMAL_OTH).build());
            for (int side = 0; side < 2; side++) {
                materialInfo[side] = MaterialInfo.builder().hash(0x12340110L + side)
                        .normalTexture(textures[0]).opaqueExt(MaterialInfoOpaqueEXT.builder()
                                .albedoConstant(1, 1, 1).roughnessConstant(1).metallicConstant(0).build()).build();
                materials[side] = api.createMaterial(materialInfo[side]);
                MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
                surface.vertexBuffer = ByteBuffer.allocateDirect(3 * HardcodedVertex.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
                HardcodedVertex.create(-0.55f, -0.55f, 20, 0, 0, -1, 0, 0, 0xFFFFFFFF).writeTo(surface.vertexBuffer);
                HardcodedVertex.create(0.55f, -0.55f, 20, 0, 0, -1, 1, 0, 0xFFFFFFFF).writeTo(surface.vertexBuffer);
                HardcodedVertex.create(0, 0.55f, 20, 0, 0, -1, 0.5f, 1, 0xFFFFFFFF).writeTo(surface.vertexBuffer);
                surface.vertexBuffer.flip();
                surface.indexBuffer = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
                surface.indexBuffer.putInt(0).putInt(1).putInt(2);
                surface.indexBuffer.flip();
                surface.vertexCount = surface.indexCount = 3;
                surface.material = materials[side];
                MeshInfo mesh = new MeshInfo();
                mesh.hash = 0x12340120L + side;
                mesh.surfaces = new MeshInfoSurfaceTriangles[] {surface};
                meshes[side] = api.createMesh(mesh);
                instances[side] = api.sceneInstanceAdd(meshes[side],
                        Transform.fromTRS(side == 0 ? -0.75f : 0.75f, 0, 0, 0, 0, 0, 1, 1).matrix, 0, 1, 0);
            }
            // Incoming light illuminates +X-tilted normals; the negative-X patch
            // turns the right triangle away. Keep Y zero so changes along X cannot
            // be cancelled by an equal but opposite lighting contribution from Y.
            light = api.createLight(LightInfo.builder().hash(0x12340130L).radiance(1, 1, 1)
                    .distantExt(LightInfoDistantEXT.builder().direction(-0.8f, 0, 0.6f)
                            .angularDiameterDegrees(0.5f).volumetricRadianceScale(0).build()).build());
            byte[] control = normalImage(api, interop, framebuffer, hwnd, normalCamera, light);
            saveImage(control, "gpu-smoke-normal-control.png");
            materialInfo[1].normalTexture = textures[1];
            api.updateMaterial(materials[1], materialInfo[1]);
            byte[] before = normalImage(api, interop, framebuffer, hwnd, normalCamera, light);
            saveImage(before, "gpu-smoke-normal-equivalent.png");

            TexturePatch patch = new TexturePatch();
            patch.width = patch.height = 2;
            patch.rowPitch = 2 * 2;
            patch.format = TextureFormat.RG8_UNORM;
            patch.pixelData = solidNormalPixels(42, 128);
            TextureBatchEntry entry = new TextureBatchEntry();
            entry.handle = textures[1];
            entry.patches = new TexturePatch[] {patch};
            TexturePatchInfo update = new TexturePatchInfo();
            update.entries = new TextureBatchEntry[] {entry};
            api.updateTextureFromMemory(update);
            // Only this texture changes. The left reference, light, materials,
            // meshes and retained instances survive both readbacks unchanged.
            byte[] after = normalImage(api, interop, framebuffer, hwnd, normalCamera, light);
            saveImage(after, "gpu-smoke-normal-patched.png");
            int controlEquivalent = 0, equivalent = 0, changed = 0;
            long controlLeft = 0, controlRight = 0, beforeLeft = 0, beforeRight = 0, afterLeft = 0, afterRight = 0;
            for (int y = 112; y < 128; y++) for (int x = 100; x < 116; x++) {
                int left = (y * 320 + x) * 4, right = (y * 320 + 319 - x) * 4;
                int controlL = brightness(control, left), controlR = brightness(control, right);
                int oldL = brightness(before, left), oldR = brightness(before, right);
                int newL = brightness(after, left), newR = brightness(after, right);
                controlLeft += controlL; controlRight += controlR;
                beforeLeft += oldL; beforeRight += oldR; afterLeft += newL; afterRight += newR;
                if (controlL > 40 && controlR > 40 && Math.abs(controlL - controlR) <= 12) controlEquivalent++;
                if (oldL > 40 && oldR > 40 && Math.abs(oldL - oldR) <= 12) equivalent++;
                if (oldL > 40 && oldR > 40 && newL > 40 && oldR - newR >= 24
                        && (newL - newR) - (oldL - oldR) >= 32) changed++;
            }
            String measurements = "normal DX/DX control pixels=" + controlEquivalent
                    + "/256, equivalent pixels=" + equivalent + "/256, patched pixels=" + changed
                    + "/256, mean brightness control=" + controlLeft / 256.0 + "/" + controlRight / 256.0
                    + ", before=" + beforeLeft / 256.0 + "/" + beforeRight / 256.0
                    + ", after=" + afterLeft / 256.0 + "/" + afterRight / 256.0;
            System.out.println("GPU: " + measurements);
            BridgeContractTest.check(controlEquivalent >= 192,
                    "Identical NORMAL_DX materials must have comparable lighting before testing encoding; " + measurements);
            BridgeContractTest.check(equivalent >= 192,
                    "NORMAL_DX and encoded NORMAL_OTH must shade equivalently; " + measurements);
            BridgeContractTest.check(changed >= 192,
                    "A retained RG8 normal patch must change lighting relative to the untouched reference; " + measurements);
            System.out.println("PASS: native normal encoding equivalence and retained normal texture patch; " + measurements);
        } finally {
            for (long instance : instances) if (instance != 0) api.sceneInstanceRemove(instance);
            for (long mesh : meshes) if (mesh != 0) api.destroyMesh(mesh);
            for (long material : materials) if (material != 0) api.destroyMaterial(material);
            for (long texture : textures) if (texture != 0) api.destroyTexture(texture);
            if (light != 0) api.destroyLight(light);
        }
    }

    private static ByteBuffer solidNormalPixels(int red, int green) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(2 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) pixels.put((byte) red).put((byte) green);
        pixels.flip();
        return pixels;
    }

    private static byte[] normalImage(RemixAPI api, LegacyGlInterop interop, int framebuffer,
            long hwnd, CameraInfo camera, long light) {
        for (int i = 0; i < 30; i++) {
            api.setupCamera(camera);
            api.drawLightInstance(light);
            api.submit(null);
            long done = interop.copyToFramebuffer(api.dxvkGetInteropInfo(), framebuffer, 320, 240, true);
            PresentInfo present = new PresentInfo();
            present.hwndOverride = hwnd;
            present.skipVulkanSwapchain = 1;
            present.clientDoneSemaphore_ValueToWait = done;
            api.present(present);
        }
        return readImageAndVerifyState();
    }

    private static int brightness(byte[] pixels, int offset) {
        return ((pixels[offset] & 255) + (pixels[offset + 1] & 255) + (pixels[offset + 2] & 255)) / 3;
    }

    private static void saveImage(byte[] pixels, String filename) throws Exception {
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 240; y++) for (int x = 0; x < 320; x++) {
            int offset = (y * 320 + x) * 4;
            image.setRGB(x, 239 - y, 0xFF000000 | ((pixels[offset] & 255) << 16)
                    | ((pixels[offset + 1] & 255) << 8) | (pixels[offset + 2] & 255));
        }
        ImageIO.write(image, "png", new File(filename));
    }
}

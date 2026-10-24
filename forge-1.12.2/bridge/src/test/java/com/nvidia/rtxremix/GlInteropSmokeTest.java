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

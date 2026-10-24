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

/** Renders an emissive triangle through the real runtime into a hidden test context. */
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
            MaterialInfo materialInfo = MaterialInfo.builder().hash(0x12340001L)
                    .emissiveIntensity(4).emissiveColorConstant(1, 0.05f, 0.01f)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(1, 0.05f, 0.01f).build()).build();
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
            api.sceneInstanceRemove(instance);
            api.destroyMesh(mesh);
            api.destroyMaterial(material);
            System.out.println("PASS: GPU path tracing, shared color import, split ownership, idle acquire, timeline sync, bidirectional framebuffer copies, and GL state restoration; red pixels=" + colored);
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
}

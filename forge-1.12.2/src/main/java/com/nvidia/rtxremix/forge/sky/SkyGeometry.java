/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/** Static celestial geometry and camera-relative transforms, independent of Minecraft and GL. */
final class SkyGeometry {
    static final int STAR_COUNT = 500;
    static final float STAR_DISTANCE = 110.0f;
    static final float MIN_STAR_HALF_ANGLE = 0.0008f;
    static final float MAX_STAR_HALF_ANGLE = 0.0015f;
    private static final float CELESTIAL_DISTANCE = 100.0f;

    private SkyGeometry() {}

    static MeshInfo quad(long hash, long material, float minU, float minV, float maxU, float maxV) {
        MeshInfoSurfaceTriangles surface = surface(material, 1);
        vertex(surface.vertexBuffer, -1, -1, 0, 0, 0, -1, minU, maxV);
        vertex(surface.vertexBuffer, 1, -1, 0, 0, 0, -1, maxU, maxV);
        vertex(surface.vertexBuffer, 1, 1, 0, 0, 0, -1, maxU, minV);
        vertex(surface.vertexBuffer, -1, 1, 0, 0, 0, -1, minU, minV);
        return finish(hash, surface);
    }

    static MeshInfo stars(long hash, long material) {
        MeshInfoSurfaceTriangles surface = surface(material, STAR_COUNT);
        Random random = new Random(0x524c435354415253L);
        for (int star = 0; star < STAR_COUNT; star++) {
            double y = random.nextDouble() * 2.0 - 1.0;
            double phi = random.nextDouble() * Math.PI * 2.0;
            double ring = Math.sqrt(1.0 - y * y);
            double x = ring * Math.cos(phi), z = ring * Math.sin(phi);
            // Choose a tangent that remains finite at either pole.
            double rx = Math.abs(y) < 0.99 ? z : 0.0;
            double ry = Math.abs(y) < 0.99 ? 0.0 : -z;
            double rz = Math.abs(y) < 0.99 ? -x : y;
            double inverse = 1.0 / Math.sqrt(rx * rx + ry * ry + rz * rz);
            rx *= inverse;
            ry *= inverse;
            rz *= inverse;
            double ux = y * rz - z * ry, uy = z * rx - x * rz, uz = x * ry - y * rx;
            double rotation = random.nextDouble() * Math.PI * 2.0;
            double cosine = Math.cos(rotation), sine = Math.sin(rotation);
            double rightX = rx * cosine + ux * sine;
            double rightY = ry * cosine + uy * sine;
            double rightZ = rz * cosine + uz * sine;
            double upX = ux * cosine - rx * sine;
            double upY = uy * cosine - ry * sine;
            double upZ = uz * cosine - rz * sine;
            double half = STAR_DISTANCE * Math.tan(MIN_STAR_HALF_ANGLE
                    + random.nextDouble() * (MAX_STAR_HALF_ANGLE - MIN_STAR_HALF_ANGLE));
            for (int corner = 0; corner < 4; corner++) {
                int horizontal = corner == 0 || corner == 3 ? -1 : 1;
                int vertical = corner < 2 ? -1 : 1;
                vertex(surface.vertexBuffer,
                        (float) (x * STAR_DISTANCE + half * (rightX * horizontal + upX * vertical)),
                        (float) (y * STAR_DISTANCE + half * (rightY * horizontal + upY * vertical)),
                        (float) (z * STAR_DISTANCE + half * (rightZ * horizontal + upZ * vertical)),
                        (float) -x, (float) -y, (float) -z,
                        horizontal < 0 ? 0 : 1, vertical < 0 ? 1 : 0);
            }
        }
        return finish(hash, surface);
    }

    /** The orbit supplies the tangent basis, so the sun and moon never roll or collapse at zenith. */
    static void transform(float[] out, SkyState state, double cameraX, double cameraY,
            double cameraZ, boolean moon) {
        float sign = moon ? -1.0f : 1.0f;
        float half = moon ? 20.0f : 30.0f;
        float dx = sign * state.sunX, dy = sign * state.sunY;
        // Vanilla's sun U follows +Z; the moon reverses U. Both share the same texture-up axis.
        out[0] = 0;
        out[1] = state.sunY * half;
        out[2] = dx;
        out[3] = (float) (cameraX + dx * CELESTIAL_DISTANCE);
        out[4] = 0;
        out[5] = -state.sunX * half;
        out[6] = dy;
        out[7] = (float) (cameraY + dy * CELESTIAL_DISTANCE);
        out[8] = sign * half;
        out[9] = 0;
        out[10] = 0;
        out[11] = (float) cameraZ;
    }

    /** Rotates the fixed star sphere with the same east-to-west celestial orbit. */
    static void starTransform(float[] out, SkyState state, double cameraX, double cameraY, double cameraZ) {
        out[0] = state.sunY;
        out[1] = state.sunX;
        out[2] = 0;
        out[3] = (float) cameraX;
        out[4] = -state.sunX;
        out[5] = state.sunY;
        out[6] = 0;
        out[7] = (float) cameraY;
        out[8] = 0;
        out[9] = 0;
        out[10] = 1;
        out[11] = (float) cameraZ;
    }

    private static MeshInfoSurfaceTriangles surface(long material, int quads) {
        MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
        surface.material = material;
        surface.vertexCount = quads * 4;
        surface.indexCount = quads * 6;
        surface.vertexBuffer = direct(surface.vertexCount * HardcodedVertex.STRIDE);
        surface.indexBuffer = direct(surface.indexCount * Integer.BYTES);
        for (int base = 0; base < surface.vertexCount; base += 4) {
            surface.indexBuffer.putInt(base).putInt(base + 2).putInt(base + 1);
            surface.indexBuffer.putInt(base).putInt(base + 3).putInt(base + 2);
        }
        surface.indexBuffer.flip();
        return surface;
    }

    private static MeshInfo finish(long hash, MeshInfoSurfaceTriangles surface) {
        surface.vertexBuffer.flip();
        return MeshInfo.builder().hash(hash).surfaces(surface).build();
    }

    private static void vertex(ByteBuffer buffer, float x, float y, float z,
            float nx, float ny, float nz, float u, float v) {
        buffer.putFloat(x).putFloat(y).putFloat(z);
        buffer.putFloat(nx).putFloat(ny).putFloat(nz);
        buffer.putFloat(u).putFloat(v).putInt(0xffffffff);
        for (int pad = 0; pad < 7; pad++) buffer.putInt(0);
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }
}

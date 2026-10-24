/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Reusable local-space geometry scratch for ordinary boxes and explicitly supported box contracts. */
final class EntityMesh {
    static final int MAX_QUADS = 4096;
    private static final Logger LOGGER = LogManager.getLogger("RLCraftRemixEntities");
    private static final Field QUADS = ReflectionHelper.findField(ModelBox.class,
            new String[] {"quadList", "field_78254_i"});
    private static final Field INVERT_NORMAL = ReflectionHelper.findField(TexturedQuad.class,
            new String[] {"invertNormal", "field_78238_c"});
    private ByteBuffer vertices = direct(64 * HardcodedVertex.STRIDE);
    private ByteBuffer indices = direct(96 * Integer.BYTES);
    private final MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
    private final MeshInfo info = new MeshInfo();
    private boolean warnedCapacity;

    EntityMesh() { info.surfaces = new MeshInfoSurfaceTriangles[] {surface}; }

    /** The returned buffers remain valid only until the next prepare; submit them synchronously. */
    boolean prepare(ModelRenderer part, float scale, long material) {
        return prepare(part, scale, material, null);
    }

    boolean prepare(ModelRenderer part, float scale, long material, float[] texture) {
        if (!Float.isFinite(scale) || scale == 0) return false;
        if (texture != null) for (float value : texture) if (!Float.isFinite(value)) return false;
        if (part.cubeList.size() > MAX_QUADS) return capacityExceeded();
        int quadCount = 0;
        for (ModelBox box : part.cubeList) {
            ModelBoxFaces.Filter filter = ModelBoxFaces.forBox(box);
            if (!filter.supported) continue;
            TexturedQuad[] faces = quads(box);
            if (faces.length > MAX_QUADS) return capacityExceeded();
            for (int face = 0; face < faces.length; face++) {
                if (filter.visible(box, face) && supported(faces[face]) && ++quadCount > MAX_QUADS) return capacityExceeded();
            }
        }
        if (quadCount == 0) return false;
        vertices = capacity(vertices, quadCount * 4 * HardcodedVertex.STRIDE);
        indices = capacity(indices, quadCount * 6 * Integer.BYTES);
        vertices.clear();
        indices.clear();
        for (ModelBox box : part.cubeList) {
            ModelBoxFaces.Filter filter = ModelBoxFaces.forBox(box);
            if (!filter.supported) continue;
            TexturedQuad[] faces = quads(box);
            for (int face = 0; face < faces.length; face++) {
                if (filter.visible(box, face) && supported(faces[face])) append(faces[face], scale, texture);
            }
        }
        if (vertices.position() == 0) return false;
        surface.vertexCount = vertices.position() / HardcodedVertex.STRIDE;
        surface.indexCount = indices.position() / Integer.BYTES;
        vertices.flip();
        indices.flip();
        surface.vertexBuffer = vertices;
        surface.indexBuffer = indices;
        surface.material = material;
        return true;
    }

    private boolean capacityExceeded() {
        if (!warnedCapacity) {
            warnedCapacity = true;
            LOGGER.warn("Model capture exceeds the {} face/box limit; that part is omitted; further capacity warnings are suppressed", MAX_QUADS);
        }
        return false;
    }

    MeshInfo info(long hash) { info.hash = hash; return info; }

    long bytes() {
        return (long) surface.vertexCount * HardcodedVertex.STRIDE + (long) surface.indexCount * Integer.BYTES;
    }

    private static boolean supported(TexturedQuad quad) {
        if (quad == null || quad.getClass() != TexturedQuad.class || quad.vertexPositions.length != 4) return false;
        for (PositionTextureVertex vertex : quad.vertexPositions) {
            if (vertex == null || !Double.isFinite(vertex.vector3D.x) || !Double.isFinite(vertex.vector3D.y)
                    || !Double.isFinite(vertex.vector3D.z) || !Float.isFinite(vertex.texturePositionX)
                    || !Float.isFinite(vertex.texturePositionY)) return false;
        }
        return true;
    }

    private void append(TexturedQuad quad, float scale, float[] texture) {
        PositionTextureVertex[] face = quad.vertexPositions;
        double ax = face[1].vector3D.x - face[0].vector3D.x;
        double ay = face[1].vector3D.y - face[0].vector3D.y;
        double az = face[1].vector3D.z - face[0].vector3D.z;
        double bx = face[2].vector3D.x - face[0].vector3D.x;
        double by = face[2].vector3D.y - face[0].vector3D.y;
        double bz = face[2].vector3D.z - face[0].vector3D.z;
        double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-10 || !Double.isFinite(length)) return;
        double direction = inverted(quad) ? -1 : 1;
        nx *= direction / length;
        ny *= direction / length;
        nz *= direction / length;
        for (PositionTextureVertex vertex : face) {
            if (!Float.isFinite((float) (vertex.vector3D.x * scale))
                    || !Float.isFinite((float) (vertex.vector3D.y * scale))
                    || !Float.isFinite((float) (vertex.vector3D.z * scale))) return;
        }
        int base = vertices.position() / HardcodedVertex.STRIDE;
        for (PositionTextureVertex vertex : face) {
            vertices.putFloat((float) (vertex.vector3D.x * scale));
            vertices.putFloat((float) (vertex.vector3D.y * scale));
            vertices.putFloat((float) (vertex.vector3D.z * scale));
            vertices.putFloat((float) nx).putFloat((float) ny).putFloat((float) nz);
            float u = vertex.texturePositionX, v = vertex.texturePositionY;
            vertices.putFloat(texture == null ? u : texture[0] * u + texture[1] * v + texture[2]);
            vertices.putFloat(texture == null ? v : texture[3] * u + texture[4] * v + texture[5]);
            vertices.putInt(0xFFFFFFFF);
            for (int pad = 0; pad < 7; pad++) vertices.putInt(0);
        }
        indices.putInt(base).putInt(base + 2).putInt(base + 1);
        indices.putInt(base).putInt(base + 3).putInt(base + 2);
    }

    private static TexturedQuad[] quads(ModelBox box) {
        try { return (TexturedQuad[]) QUADS.get(box); }
        catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot read model box faces", failure); }
    }

    private static boolean inverted(TexturedQuad quad) {
        try { return INVERT_NORMAL.getBoolean(quad); }
        catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot read model face normal", failure); }
    }

    static ByteBuffer direct(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()); }

    private static ByteBuffer capacity(ByteBuffer buffer, int needed) {
        return needed <= buffer.capacity() ? buffer : direct(Math.max(needed, buffer.capacity() * 2));
    }
}

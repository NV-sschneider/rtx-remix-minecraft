/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.nio.ByteBuffer;

/**
 * Hardcoded vertex for mesh creation.
 * Contains position, normal, texcoord, color, and optional atlas UV bounds
 * matching the native layout.
 */
public class HardcodedVertex {

    /** Native struct size in bytes (must match remixapi_HardcodedVertex: 16 fields * 4 = 64). */
    public static final int STRIDE = 64;

    public float[] position = new float[3];
    public float[] normal = new float[3];
    public float[] texcoord = new float[2];
    public int color;
    /** Packed half2: min.u, min.v. Leave both bounds zero to disable atlas wrapping bounds. */
    public int texcoordBoundsMin;
    /** Packed half2: max.u, max.v. Leave both bounds zero to disable atlas wrapping bounds. */
    public int texcoordBoundsMax;
    public int _pad0;
    public int _pad1;
    public int _pad2;
    public int _pad3;
    public int _pad4;

    /** Packs two floats into a single int as two IEEE 754 binary16 values. */
    public static int packHalf2x16(float x, float y) {
        return (floatToHalf(x) & 0xFFFF) | ((floatToHalf(y) & 0xFFFF) << 16);
    }

    private static int floatToHalf(float f) {
        int bits = Float.floatToRawIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int expo = ((bits >>> 23) & 0xFF) - 112;
        int mant = bits & 0x7FFFFF;
        if (expo <= 0) return sign;
        if (expo >= 31) return sign | 0x7C00;
        return sign | (expo << 10) | (mant >>> 13);
    }

    public HardcodedVertex() {}

    /** Returns the native struct size. */
    public int size() { return STRIDE; }

    /** Writes this vertex's fields into a direct ByteBuffer at its current position. */
    public void writeTo(ByteBuffer buf) {
        buf.putFloat(position[0]).putFloat(position[1]).putFloat(position[2]);
        buf.putFloat(normal[0]).putFloat(normal[1]).putFloat(normal[2]);
        buf.putFloat(texcoord[0]).putFloat(texcoord[1]);
        buf.putInt(color);
        buf.putInt(texcoordBoundsMin);
        buf.putInt(texcoordBoundsMax);
        buf.putInt(_pad0);
        buf.putInt(_pad1);
        buf.putInt(_pad2);
        buf.putInt(_pad3);
        buf.putInt(_pad4);
    }

    public HardcodedVertex(float[] position, float[] normal, float[] texcoord, int color) {
        if (position != null && position.length >= 3) {
            System.arraycopy(position, 0, this.position, 0, 3);
        }
        if (normal != null && normal.length >= 3) {
            System.arraycopy(normal, 0, this.normal, 0, 3);
        }
        if (texcoord != null && texcoord.length >= 2) {
            System.arraycopy(texcoord, 0, this.texcoord, 0, 2);
        }
        this.color = color;
    }

    public void setTexcoordBounds(float minU, float minV, float maxU, float maxV) {
        this.texcoordBoundsMin = packHalf2x16(minU, minV);
        this.texcoordBoundsMax = packHalf2x16(maxU, maxV);
    }

    public static HardcodedVertex create(float x, float y, float z) {
        return create(x, y, z, 0, 0, -1, 0, 0, 0xFFFFFFFF);
    }

    public static HardcodedVertex create(float px, float py, float pz,
                                          float nx, float ny, float nz,
                                          float u, float v,
                                          int color) {
        HardcodedVertex vert = new HardcodedVertex();
        vert.position[0] = px;
        vert.position[1] = py;
        vert.position[2] = pz;
        vert.normal[0] = nx;
        vert.normal[1] = ny;
        vert.normal[2] = nz;
        vert.texcoord[0] = u;
        vert.texcoord[1] = v;
        vert.color = color;
        return vert;
    }
}

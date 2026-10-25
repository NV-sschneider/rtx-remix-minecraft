/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.fog;

/** Reusable render-thread snapshot of the completed fixed-function world fog setup. */
public final class FogState {
    public static final int LINEAR = 0x2601, EXP = 0x0800, EXP2 = 0x0801;
    private boolean valid, enabled, radial;
    private int mode;
    private float red, green, blue, density, start, end;

    boolean set(boolean enabled, int mode, boolean radial, float red, float green, float blue,
            float density, float start, float end) {
        clear();
        if (mode != LINEAR && mode != EXP && mode != EXP2 || !finite(red, green, blue)
                || red < 0 || red > 1 || green < 0 || green > 1 || blue < 0 || blue > 1
                || !finite(density, start, end) || density < 0 || mode == LINEAR && start == end) return false;
        this.enabled = enabled; this.mode = mode; this.radial = radial;
        this.red = red; this.green = green; this.blue = blue;
        this.density = density; this.start = start; this.end = end; valid = true;
        return true;
    }

    void clear() { valid = enabled = false; }
    public boolean valid() { return valid; }
    public boolean enabled() { return valid && enabled; }
    public int mode() { return mode; }
    public boolean radial() { return radial; }
    public float red() { return red; }
    public float green() { return green; }
    public float blue() { return blue; }
    public float density() { return density; }
    public float start() { return start; }
    public float end() { return end; }

    private static boolean finite(float a, float b, float c) {
        return Float.isFinite(a) && Float.isFinite(b) && Float.isFinite(c);
    }
}

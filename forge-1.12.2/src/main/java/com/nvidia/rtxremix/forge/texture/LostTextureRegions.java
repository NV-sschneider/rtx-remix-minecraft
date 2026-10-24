/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

/** Conservative, bounded coverage of pixels whose successful CPU upload could not be retained. */
final class LostTextureRegions {
    static final int MAX_REGIONS = 64;
    private final int width, height;
    private final int[] bounds = new int[MAX_REGIONS * 4];
    private int count;
    LostTextureRegions(int width, int height) { this.width = width; this.height = height; }
    boolean empty() { return count == 0; }
    int count() { return count; }
    void clear() { count = 0; }
    void all() { count = 0; append(0, 0, width, height); }
    void lose(int x, int y, int w, int h) {
        for (int i = 0; i < count;) {
            int at = i * 4, left = bounds[at], top = bounds[at + 1], right = left + bounds[at + 2], bottom = top + bounds[at + 3];
            if (left <= x && top <= y && right >= x + w && bottom >= y + h) return;
            if (x <= left && y <= top && x + w >= right && y + h >= bottom) remove(i); else i++;
        }
        if (count == MAX_REGIONS) all(); else append(x, y, w, h);
    }
    void repair(int x, int y, int w, int h) {
        for (int i = 0; i < count;) {
            int at = i * 4, left = bounds[at], top = bounds[at + 1], right = left + bounds[at + 2], bottom = top + bounds[at + 3];
            int l = Math.max(left, x), t = Math.max(top, y), r = Math.min(right, x + w), b = Math.min(bottom, y + h);
            if (l >= r || t >= b) { i++; continue; }
            int pieces = (top < t ? 1 : 0) + (b < bottom ? 1 : 0) + (left < l ? 1 : 0) + (r < right ? 1 : 0);
            if (count - 1 + pieces > MAX_REGIONS) { all(); return; }
            remove(i);
            if (top < t) append(left, top, right - left, t - top);
            if (b < bottom) append(left, b, right - left, bottom - b);
            if (left < l) append(left, t, l - left, b - t);
            if (r < right) append(r, t, right - r, b - t);
        }
    }
    private void append(int x, int y, int w, int h) { int at = count++ * 4; bounds[at] = x; bounds[at + 1] = y; bounds[at + 2] = w; bounds[at + 3] = h; }
    private void remove(int i) { int last = --count * 4; System.arraycopy(bounds, last, bounds, i * 4, 4); }
}

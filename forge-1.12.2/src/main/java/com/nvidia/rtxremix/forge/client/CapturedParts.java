/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.entity.CapturedEntityPart;
import java.util.AbstractList;
import java.util.Collections;
import java.util.List;

/** A frame-local view of both completed capture streams, without copying vertices or references. */
final class CapturedParts extends AbstractList<CapturedEntityPart> {
    private List<CapturedEntityPart> ordinary = Collections.emptyList();
    private List<CapturedEntityPart> fast = Collections.emptyList();

    CapturedParts set(List<CapturedEntityPart> ordinary, List<CapturedEntityPart> fast) {
        this.ordinary = ordinary;
        this.fast = fast;
        return this;
    }

    @Override public CapturedEntityPart get(int index) {
        return index < ordinary.size() ? ordinary.get(index) : fast.get(index - ordinary.size());
    }

    @Override public int size() { return ordinary.size() + fast.size(); }
}

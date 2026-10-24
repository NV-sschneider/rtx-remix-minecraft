/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Preserves primitive GL cache fields alongside a native attribute-stack snapshot. */
final class PrimitiveStateSnapshot {
    private final Entry[] entries;

    PrimitiveStateSnapshot(Class<?> root) {
        List<Entry> found = new ArrayList<>();
        collect(root, null, root.getName() + "$", new IdentityHashMap<Object, Boolean>(), found);
        entries = found.toArray(new Entry[found.size()]);
    }

    void capture() {
        try { for (Entry entry : entries) entry.capture(); }
        catch (IllegalAccessException error) { throw new IllegalStateException("Cannot snapshot renderer state cache", error); }
    }

    void restore() {
        try { for (Entry entry : entries) entry.restore(); }
        catch (IllegalAccessException error) { throw new IllegalStateException("Cannot restore renderer state cache", error); }
    }

    private static void collect(Class<?> type, Object owner, String prefix,
            IdentityHashMap<Object, Boolean> visited, List<Entry> entries) {
        if (owner != null && visited.put(owner, Boolean.TRUE) != null) return;
        try {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) != (owner == null) || field.isSynthetic()) continue;
                field.setAccessible(true);
                Class<?> valueType = field.getType();
                if (valueType.isPrimitive()) {
                    if (!Modifier.isFinal(field.getModifiers())) entries.add(new Entry(owner, field));
                    continue;
                }
                Object value = field.get(owner);
                if (value == null) continue;
                if (valueType.isArray() && valueType.getComponentType().getName().startsWith(prefix)) {
                    for (int i = 0; i < Array.getLength(value); i++) {
                        Object element = Array.get(value, i);
                        if (element != null) collect(element.getClass(), element, prefix, visited, entries);
                    }
                } else if (valueType.getName().startsWith(prefix)) collect(valueType, value, prefix, visited, entries);
            }
        } catch (IllegalAccessException error) { throw new IllegalStateException("Cannot inspect renderer state cache", error); }
    }

    private static final class Entry {
        final Object owner;
        final Field field;
        final Class<?> type;
        long bits;
        Entry(Object owner, Field field) { this.owner = owner; this.field = field; type = field.getType(); }
        void capture() throws IllegalAccessException {
            if (type == boolean.class) bits = field.getBoolean(owner) ? 1 : 0;
            else if (type == float.class) bits = Float.floatToRawIntBits(field.getFloat(owner));
            else if (type == double.class) bits = Double.doubleToRawLongBits(field.getDouble(owner));
            else if (type == char.class) bits = field.getChar(owner);
            else bits = field.getLong(owner);
        }
        void restore() throws IllegalAccessException {
            if (type == boolean.class) field.setBoolean(owner, bits != 0);
            else if (type == float.class) field.setFloat(owner, Float.intBitsToFloat((int) bits));
            else if (type == double.class) field.setDouble(owner, Double.longBitsToDouble(bits));
            else if (type == int.class) field.setInt(owner, (int) bits);
            else if (type == long.class) field.setLong(owner, bits);
            else if (type == short.class) field.setShort(owner, (short) bits);
            else if (type == byte.class) field.setByte(owner, (byte) bits);
            else if (type == char.class) field.setChar(owner, (char) bits);
        }
    }
}

/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.internal.lang.stable;

import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

public final class StableValueImpl<T> implements StableValue<T> {

    // Unsafe offsets for direct object access
    private static final long VALUE_OFFSET =
            StableValueUtil.UNSAFE.objectFieldOffset(StableValueImpl.class, "value");

    // Generally, fields annotated with `@Stable` are accessed by the JVM using special
    // memory semantics rules (see `parse.hpp` and `parse(1|2|3).cpp`).
    //
    // This field is reflectively accessed via Unsafe using explicit memory semantics.
    //
    // | Value          |  Meaning      |
    // | -------------- |  ------------ |
    // | null           |  Unset        |
    // | nullSentinel() |  Set(null)    |
    // | other          |  Set(other)   |
    //
    @Stable
    private T value;

    // Only allow creation via the factory `StableValueImpl::newInstance`
    private StableValueImpl() {}

    @ForceInline
    @Override
    public boolean trySet(T value) {
        if (value() != null) {
            return false;
        }
        return StableValueUtil.safelyPublish(this, VALUE_OFFSET, value);
    }

    @ForceInline
    @Override
    public T orElseThrow() {
        final T t = value();
        if (t != null) {
            return StableValueUtil.unwrap(t);
        }
        throw new NoSuchElementException("No value set");
    }

    @ForceInline
    @Override
    public T orElse(T other) {
        final T t = value();
        if (t != null) {
            return StableValueUtil.unwrap(t);
        }
        return other;
    }

    @ForceInline
    @Override
    public boolean isSet() {
        return value() != null;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value());
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof StableValueImpl<?> other &&
                // Note that the returned `value()` will be `null` if the holder value
                // is unset and `nullSentinel()` if the holder value is `null`.
                Objects.equals(value(), other.value());
    }

    @Override
    public String toString() {
        return "StableValue" + StableValueUtil.render(value());
    }

    @SuppressWarnings("unchecked")
    @ForceInline
    // First, try to read the value using plain memory semantics.
    // If not set, fall back to `volatile` memory semantics.
    public T value() {
        final T t = valuePlain();
        return t != null ? t : (T) StableValueUtil.UNSAFE.getReferenceVolatile(this, VALUE_OFFSET);
    }

    @ForceInline
    private T valuePlain() {
        // Appears to be faster than `(T) UNSAFE.getReference(this, VALUE_OFFSET)`
        return value;
    }

    // Factories

    public static <T> StableValueImpl<T> newInstance() {
        return new StableValueImpl<>();
    }

    public static <T> List<StableValueImpl<T>> ofList(int size) {
        if (size < 0) {
            throw new IllegalArgumentException();
        }
        @SuppressWarnings("unchecked")
        final var stableValues = (StableValueImpl<T>[]) new StableValueImpl<?>[size];
        for (int i = 0; i < size; i++) {
            stableValues[i] = newInstance();
        }
        return List.of(stableValues);
    }

    public static <K, T> Map<K, StableValueImpl<T>> ofMap(Set<K> keys) {
        Objects.requireNonNull(keys);
        @SuppressWarnings("unchecked")
        final var entries = (Map.Entry<K, StableValueImpl<T>>[]) new Map.Entry<?, ?>[keys.size()];
        int i = 0;
        for (K key : keys) {
            entries[i++] = Map.entry(key, newInstance());
        }
        return Map.ofEntries(entries);
    }

}

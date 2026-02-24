package com.github.liyibo1110.jdk.java.util;

import java.util.Objects;

/**
 * 用于存储键值对的不可变容器，适用于创建和填充Map实例。
 * 这是基于值的类；程序员应将相等的实例视为可互换，且不应将实例用于同步操作，否则可能导致不可预测的行为。
 * 例如，在未来的版本中，同步操作可能会失败。
 * @author liyibo
 * @date 2026-02-24 17:59
 */
final class KeyValueHolder<K, V> implements Map.Entry<K, V> {
    final K key;
    final V value;

    KeyValueHolder(K k, V v) {
        key = Objects.requireNonNull(k);
        value = Objects.requireNonNull(v);
    }

    @Override
    public K getKey() {
        return key;
    }

    @Override
    public V getValue() {
        return value;
    }

    @Override
    public V setValue(V value) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Map.Entry<?, ?> e
                && key.equals(e.getKey())
                && value.equals(e.getValue());
    }

    @Override
    public int hashCode() {
        return key.hashCode() ^ value.hashCode();
    }

    @Override
    public String toString() {
        return key + "=" + value;
    }
}

package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 一种提供线程安全性和原子性保证的Map。
 * 为了保持这些指定的保证，本接口的实现必须重写从Map继承的包括putIfAbsent在内的方法的默认实现。
 * 同样地，keySet、values和entrySet方法返回的集合的实现，在必要时必须重写 removeIf 等方法，以保持原子性保证。
 * 内存一致性影响：与其他并发集合一样，一个线程中在将对象作为键或值放入ConcurrentMap之前的操作，在时间上先于另一个线程中访问或从ConcurrentMap中移除该对象之后的操作。
 * @author liyibo
 * @date 2026-03-19 17:47
 */
public interface ConcurrentMap<K, V> extends Map<K, V> {

    @Override
    default V getOrDefault(Object key, V defaultValue) {
        V v;
        return ((v = get(key)) != null) ? v : defaultValue;
    }

    @Override
    default void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        for(Map.Entry<K,V> entry : entrySet()) {
            K k;
            V v;
            try {
                k = entry.getKey();
                v = entry.getValue();
            } catch (IllegalStateException e) {
                continue;
            }
            action.accept(k, v);
        }
    }

    /** 需要子类重写以保证并发安全 */
    V putIfAbsent(K key, V value);

    /** 需要子类重写以保证并发安全 */
    boolean remove(Object key, Object value);

    /** 需要子类重写以保证并发安全 */
    boolean replace(K key, V oldValue, V newValue);

    /** 需要子类重写以保证并发安全 */
    V replace(K key, V value);

    @Override
    default void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        forEach((k, v) -> {
            while(!replace(k, v, function.apply(k, v))) {
                if((v = get(k)) == null)
                    break;
            }
        });
    }

    @Override
    default V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        V oldValue, newValue;
        return ((oldValue = get(key)) == null
                && (newValue = mappingFunction.apply(key)) != null
                && (oldValue = putIfAbsent(key, newValue)) == null)
                ? newValue
                : oldValue;
    }

    @Override
    default V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        for(V oldValue; (oldValue = get(key)) != null; ) {
            V newValue = remappingFunction.apply(key, oldValue);
            if((newValue == null)
                    ? remove(key, oldValue)
                    : replace(key, oldValue, newValue))
                return newValue;
        }
        return null;
    }

    @Override
    default V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        retry: for (;;) {
            V oldValue = get(key);
            // if putIfAbsent fails, opportunistically use its return value
            haveOldValue: for (;;) {
                V newValue = remappingFunction.apply(key, oldValue);
                if (newValue != null) {
                    if (oldValue != null) {
                        if (replace(key, oldValue, newValue))
                            return newValue;
                    }
                    else if ((oldValue = putIfAbsent(key, newValue)) == null)
                        return newValue;
                    else continue haveOldValue;
                } else if (oldValue == null || remove(key, oldValue)) {
                    return null;
                }
                continue retry;
            }
        }
    }

    @Override
    default V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Objects.requireNonNull(value);
        retry: for (;;) {
            V oldValue = get(key);
            // if putIfAbsent fails, opportunistically use its return value
            haveOldValue: for (;;) {
                if (oldValue != null) {
                    V newValue = remappingFunction.apply(oldValue, value);
                    if (newValue != null) {
                        if (replace(key, oldValue, newValue))
                            return newValue;
                    } else if (remove(key, oldValue)) {
                        return null;
                    }
                    continue retry;
                } else {
                    if ((oldValue = putIfAbsent(key, value)) == null)
                        return value;
                    continue haveOldValue;
                }
            }
        }
    }
}

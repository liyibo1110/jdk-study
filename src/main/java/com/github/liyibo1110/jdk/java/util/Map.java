package com.github.liyibo1110.jdk.java.util;

import java.io.Serializable;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 一种将key映射到value的对象，Map不能包含重复key，每个Key最多只能映射到一个value。
 * 该接口取代了Dictionary类，这是一个完全抽象类而非接口。
 * Map接口提供三种集合视图，可将Map内容呈现为key集合、value集合或键值映射集合，Map的顺序由其集合视图迭代器返回元素的顺序定义。
 * 某些实现（如TreeMap）对顺序提供特定保证，而另一些（如HashMap）则不作此类保证。
 *
 * 注意：若将可变对象用作key，必须格外谨慎，当对象作为key存在期间，若其value发生改变导致等值比较结果改变，则Map的行为将无法保证。
 * 此禁令的特殊情况是：Map不可将自身作为key包含其中，虽然允许Map将自身作为value存储，但需极度谨慎：此类映射的equals和hashCode方法将不再具有良好定义。
 *
 * 所有通用的Map实现类都应该提供两个标准的构造方法，一个无参构造方法用于创建空映射，另一个带单个Map类型参数的构造方法用于创建具有与参数相同键值映射的新Map。
 * 后者实质上允许用户复制任意Map，生成目标类型的等效映射，虽然无法强制执行此建议（因接口无法包含构造函数），但JDK中所有通用映射实现均遵循此规范。
 *
 * 该接口中包含的“破坏性”方法（即会修改操作对象的映射的方法）规定：若目标映射不支持该操作，则抛出UnsupportedOperationException异常。
 * 若调用该方法对映射无实际影响，这些方法可抛出（但非必须抛出）UnsupportedOperationException异常。
 * 例如，对不可修改的映射调用putAll(Map) 方法时，若待“覆盖”的映射为空，则可能（但非必须）抛出该异常。
 *
 * 某些映射实现对键值的类型存在限制。例如，某些实现禁止使用空键值，另一些则限制键的类型。
 * 尝试插入无效键值将抛出非检查异常（通常为NullPointerException或ClassCastException）。
 * 查询无效键值的存在性可能抛出异常，也可能直接返回false；不同实现可能表现为上述两种行为之一。
 * 更普遍而言，对无效键值执行操作（即使该操作不会导致无效元素被插入映射）时，实现可选择抛出异常或直接成功。此类异常在接口规范中标记为“可选”。
 *
 * 集合框架接口中的许多方法都是基于equals方法定义的。例如，containsKey(Object key)方法的规范说明如下：“仅当该映射包含满足(key==null ? k==null : key.equals(k))条件的键k时返回true。”
 * 该规范不应被解释为：当Map.containsKey方法接收非空键参数时，必然会为任意键k调用key.equals(k)。
 * 实现方可自由采用优化方案避免equals调用，例如通过预先比较两个键的哈希码来实现。（Object.hashCode()规范保证哈希码不相等的两个对象不可能相等。）
 * 更普遍而言，各类集合框架接口的实现可自由利用底层Object方法的规范行为，具体取决于实现者的判断。
 *
 * 某些执行递归遍历的映射操作可能因自引用实例（即映射直接或间接包含自身的情况）而抛出异常。
 * 这包括clone()、equals()、hashCode()和toString()方法。实现可选择性处理自引用场景，但当前多数实现并未如此。
 *
 * Unmodifiable Maps
 *
 * Map.of、Map.ofEntries和Map.copyOf静态工厂方法提供了创建不可修改映射的便捷方式。通过这些方法创建的Map实例具有以下特性：
 * 1、它们不可修改。无法添加、删除或更新键值对。调用 Map 的任何修改方法都会抛出UnsupportedOperationException异常。
 * 然而，若所含键值本身可变，可能导致Map行为不一致或内容看似发生变化。
 * 2、禁止使用空键值。尝试创建空键值将引发NullPointerException异常。
 * 3、当所有键值均为序列化对象时，Map可被序列化。
 * 4、创建时拒绝重复键，向静态工厂方法传递重复键将引发IllegalArgumentException异常。
 * 5、映射的迭代顺序未指定且可能变更。
 * 6、它们基于值进行比较。程序员应将相等的实例视为可互换，且不应用于同步操作，否则可能引发不可预测的行为。
 * 例如在未来版本中，同步操作可能失败。调用方不应预设返回实例的身份。工厂可自由创建新实例或复用现有实例。
 * 7、其序列化方式遵循《序列化形式》页面的规定。
 * @author liyibo
 * @date 2026-02-24 17:09
 */
public interface Map<K, V> {
    // Query Operations

    int size();

    boolean isEmpty();

    boolean containsKey(Object key);

    boolean containsValue(Object value);

    V get(Object key);

    // Modification Operations

    V put(K key, V value);

    V remove(Object key);

    // Bulk Operations

    void putAll(Map<? extends K, ? extends V> m);

    void clear();

    // Views

    Set<K> keySet();

    Collection<V> values();

    Set<Map.Entry<K, V>> entrySet();

    /**
     * 一个映射条目（键值对）。该条目可能不可修改，或当实现了可选的setValue方法时，其值可被修改。
     * 该条目可能独立于任何映射，也可能代表映射条目集合视图中的一个条目。
     * 通过迭代地图的条目集视图可获取Map.Entry接口的实例。这些实例与原始后备地图保持关联，但该关联仅在迭代条目集视图期间有效。迭代过程中，若后备地图支持，通过setValue方法修改Map.Entry的值将反映在后备地图中。此类Map.Entry实例在脱离条目集视图迭代范围时行为未定义。若在迭代器返回Map.Entry后修改底层映射（通过setValue方法除外），其行为同样未定义。特别注意：底层映射中条目值的变更，可能在条目集视图的对应Map.Entry元素中可见，也可能不可见。
     */
    interface Entry<K, V> {
        K getKey();

        V getValue();

        V setValue(V value);

        boolean equals(Object o);

        int hashCode();

        /**
         * 返回一个Comparator，该比较器按key的自然顺序比较Map.Entry。
         * 返回Comparator可序列化，当比较具有null key的条目时会抛出NullPointerException。
         * @return 用户按key的自然顺序比较Map的条目（即Entry）
         */
        public static <K extends Comparable<? super K>, V> Comparator<Map.Entry<K, V>> comparingByKey() {
            /**
             * 方法本身没什么可说的，但是注意这里用到了Java8推出的交叉类型语法（Intersection Type），用来支持Lambda的多接口实现
             */
            return (Comparator<Entry<K, V>> & Serializable)
                    (c1, c2) -> c1.getKey().compareTo(c2.getKey());
        }

        public static <K, V extends Comparable<? super V>> Comparator<Map.Entry<K, V>> comparingByValue() {
            return (Comparator<Map.Entry<K, V>> & Serializable)
                    (c1, c2) -> c1.getValue().compareTo(c2.getValue());
        }

        public static <K, V> Comparator<Map.Entry<K, V>> comparingByKey(Comparator<? super K> cmp) {
            Objects.requireNonNull(cmp);
            return (Comparator<Map.Entry<K, V>> & Serializable)
                    (c1, c2) -> cmp.compare(c1.getKey(), c2.getKey());
        }

        public static <K, V> Comparator<Map.Entry<K, V>> comparingByValue(Comparator<? super V> cmp) {
            Objects.requireNonNull(cmp);
            return (Comparator<Map.Entry<K, V>> & Serializable)
                    (c1, c2) -> cmp.compare(c1.getValue(), c2.getValue());
        }

        public static <K, V> Map.Entry<K, V> copyOf(Map.Entry<? extends K, ? extends V> e) {
            Objects.requireNonNull(e);
            if(e instanceof KeyValueHolder)
                return (Map.Entry<K, V>) e;
            else
                return Map.entry(e.getKey(), e.getValue());
        }
    }

    // Comparison and hashing

    boolean equals(Object o);

    int hashCode();

    // Defaultable methods

    default V getOrDefault(Object key, V defaultValue) {
        V v;
        return ((v = get(key)) != null || containsKey(key)) ? v : defaultValue;
    }

    default void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        for(Map.Entry<K, V> entry : entrySet()) {
            K k;
            V v;
            try {
                k = entry.getKey();
                v = entry.getValue();
            } catch (IllegalStateException e) {
                // this usually means the entry is no longer in the map.
                throw new ConcurrentModificationException(e);
            }
            action.accept(k, v);
        }
    }

    default void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        for(Map.Entry<K, V> entry : entrySet()) {
            K k;
            V v;
            try {
                k = entry.getKey();
                v = entry.getValue();
            } catch (IllegalStateException e) {
                // this usually means the entry is no longer in the map.
                throw new ConcurrentModificationException(e);
            }

            v = function.apply(k, v);
            try {
                entry.setValue(v);
            } catch (IllegalStateException ise) {
                // this usually means the entry is no longer in the map.
                throw new ConcurrentModificationException(ise);
            }
        }
    }

    default V putIfAbsent(K key, V value) {
        V v = get(key);
        if(v == null)
            v = put(key, value);
        return v;
    }

    default boolean remove(Object key, Object value) {
        Object curValue = get(key);
        if(!Objects.equals(curValue, value) || (curValue == null && !containsKey(key)))
            return false;
        remove(key);
        return true;
    }

    default boolean replace(K key, V oldValue, V newValue) {
        Object curValue = get(key);
        if (!Objects.equals(curValue, oldValue) || (curValue == null && !containsKey(key)))
            return false;
        put(key, newValue);
        return true;
    }

    default V replace(K key, V value) {
        V curValue;
        if(((curValue = get(key)) != null) || containsKey(key))
            curValue = put(key, value);
        return curValue;
    }

    default V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        V v;
        if((v = get(key)) == null) {
            V newValue;
            if((newValue = mappingFunction.apply(key)) != null) {
                put(key, newValue);
                return newValue;
            }
        }
        return v;
    }

    default V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        V oldValue;
        if((oldValue = get(key)) != null) {
            V newValue = remappingFunction.apply(key, oldValue);
            if(newValue != null) {
                put(key, newValue);
                return newValue;
            }else {
                remove(key);
                return null;
            }
        }else {
            return null;
        }
    }

    default V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        V oldValue = get(key);

        V newValue = remappingFunction.apply(key, oldValue);
        if(newValue == null) {
            /**
             * 需要关注这里的containsKey，本质是个细节优化，其实只有containsKey就够了，
             * 为了性能更好，特意先加了个oldValue != null（因为value不为null，说明一定有key），用来代替containsKey的开销
             */
            if(oldValue != null || containsKey(key)) {
                remove(key);
                return null;
            }else {
                return null;
            }
        }else {
            put(key, newValue);
            return newValue;
        }
    }

    default V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Objects.requireNonNull(value);
        V oldValue = get(key);
        V newValue = (oldValue == null) ? value : remappingFunction.apply(oldValue, value);
        if(newValue == null)
            remove(key);
        else
            put(key, newValue);
        return newValue;
    }

    static <K, V> Map<K, V> of() {
        return (Map<K,V>) ImmutableCollections.EMPTY_MAP;
    }

    static <K, V> Map<K, V> of(K k1, V v1) {
        return new ImmutableCollections.Map1<>(k1, v1);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2) {
        return new ImmutableCollections.MapN<>(k1, v1, k2, v2);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
        return new ImmutableCollections.MapN<>(k1, v1, k2, v2, k3, v3);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        return new ImmutableCollections.MapN<>(k1, v1, k2, v2, k3, v3, k4, v4);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        return new ImmutableCollections.MapN<>(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6) {
        return new ImmutableCollections.MapN<>(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7) {
        return new ImmutableCollections.MapN<>(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8) {
        return new ImmutableCollections.MapN<>(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9) {
        return new ImmutableCollections.MapN<>(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9, K k10, V v10) {
        return new ImmutableCollections.MapN<>(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    static <K, V> Map<K, V> ofEntries(Entry<? extends K, ? extends V>... entries) {
        if(entries.length == 0) { // implicit null check of entries array
            @SuppressWarnings("unchecked")
            var map = (Map<K,V>) ImmutableCollections.EMPTY_MAP;
            return map;
        }else if (entries.length == 1) {
            // implicit null check of the array slot
            return new ImmutableCollections.Map1<>(entries[0].getKey(), entries[0].getValue());
        } else {
            Object[] kva = new Object[entries.length << 1];
            int a = 0;
            for(Entry<? extends K, ? extends V> entry : entries) {
                // implicit null checks of each array slot
                kva[a++] = entry.getKey();
                kva[a++] = entry.getValue();
            }
            return new ImmutableCollections.MapN<>(kva);
        }
    }

    static <K, V> Entry<K, V> entry(K k, V v) {
        // KeyValueHolder checks for nulls
        return new KeyValueHolder<>(k, v);
    }

    static <K, V> Map<K, V> copyOf(Map<? extends K, ? extends V> map) {
        if(map instanceof ImmutableCollections.AbstractImmutableMap)
            return (Map<K,V>)map;
        else
            return (Map<K,V>)Map.ofEntries(map.entrySet().toArray(new Entry[0]));
    }
}

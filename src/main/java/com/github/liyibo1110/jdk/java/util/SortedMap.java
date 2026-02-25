package com.github.liyibo1110.jdk.java.util;

import java.util.Comparator;

/**
 * 一种在key提供全序的Map，该Map根据key的自然排序顺序排列，或通过创建SortedMap时通过提供的Comparator进行排序。
 * 当遍历SortedMap的集合视图（由entrySet、keySet和values方法返回）时，该排序顺序得以体现。为充分利用排序特性，还提供了若干附加操作（该接口相当于SortedSet在映射中的对应实现）。
 *
 * 插入排序映射的所有键必须实现Comparable接口（或被指定比较器接受）。
 * 此外，所有此类键必须相互可比较：对于排序映射中的任意键k1和k2，k1.compareTo(k2)（或 comparator.compare(k1, k2)）均不得抛出ClassCastException。违反此限制的尝试将导致违规方法或构造函数调用抛出ClassCastException。
 *
 * 请注意，排序映射（无论是否提供显式比较器）所维护的排序顺序必须与equals方法保持一致，才能正确实现Map接口。（
 * 关于与equals一致的精确定义，请参见Comparable接口或Comparator接口。） 这是因为Map接口基于equals操作定义，而排序映射通过compareTo（或compare）方法执行所有键值比较——该方法判定相等的两个键，在排序映射中即视为相等。
 * 即使树形映射的排序顺序与equals不一致，其行为仍属规范范畴，只是未能遵守Map接口的通用契约。
 *
 * 所有通用排序映射实现类都应提供四个“标准”构造函数。但由于接口无法强制规定构造函数，此建议无法强制执行。所有排序映射实现应具备的“标准”构造函数如下：
 * 1、一个无参数构造函数，用于创建一个根据键的自然排序规则排序的空排序映射。
 * 2、一个带单个Comparator类型参数的构造函数，用于创建一个根据指定比较器排序的空排序映射。
 * 3、一个带单个Map类型参数的构造函数，用于创建一个与参数具有相同键值映射的新映射，并按键的自然排序规则排序。
 * 4、带单个 SortedMap 类型参数的构造器，创建具有与输入排序映射相同键值映射和排序规则的新排序映射。
 * @author liyibo
 * @date 2026-02-25 13:28
 */
public interface SortedMap<K, V> extends Map<K, V> {

    Comparator<? super K> comparator();

    SortedMap<K, V> subMap(K fromKey, K toKey);

    SortedMap<K, V> headMap(K toKey);

    SortedMap<K, V> tailMap(K fromKey);

    K firstKey();

    K lastKey();

    Set<K> keySet();

    Collection<V> values();

    Set<Map.Entry<K, V>> entrySet();
}

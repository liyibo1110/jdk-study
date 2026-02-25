package com.github.liyibo1110.jdk.java.util;

import java.util.SortedMap;

/**
 * 一种扩展了导航方法的SortedMap，可返回给定搜索目标的最近匹配项。
 * lowerEntry、floorEntry、ceilingEntry和higherEntry方法分别返回与小于、小于等于、大于等于及大于给定键的键关联的Map.Entry对象，若不存在该键则返回null。
 * 类似地，lowerKey、floorKey、ceilingKey和higherKey方法仅返回关联的键。所有这些方法均用于定位而非遍历条目。
 *
 * NavigableMap可按升序或降序访问和遍历键值。descendingMap方法返回一个视图，其中所有关系和方向方法的含义均被反转。升序操作和视图的性能通常优于降序操作。
 * subMap(K, boolean, K, boolean)、headMap(K, boolean) 和tailMap(K, boolean)方法与SortedMap同名方法的区别在于：它们额外接受参数来描述上下界是否包含边界。
 * 任何NavigableMap的子图必须实现NavigableMap接口。
 *
 * 一种扩展了导航方法的SortedMap，可返回给定搜索目标的最近匹配项。
 * lowerEntry、floorEntry、ceilingEntry和higherEntry方法分别返回与小于、小于等于、大于等于及大于给定键的键关联的Map.Entry对象，若不存在该键则返回null。
 * 类似地，lowerKey、floorKey、ceilingKey和higherKey方法仅返回关联的键。所有这些方法均用于定位而非遍历条目。
 *
 * NavigableMap可按升序或降序访问和遍历键值。
 * descendingMap方法返回一个视图，其中所有关系和方向方法的含义均被反转。升序操作和视图的性能通常优于降序操作。
 * subMap(K, boolean, K, boolean)、headMap(K, boolean) 和 tailMap(K, boolean) 方法与SortedMap同名方法的区别在于：它们额外接受参数来描述上下界是否包含边界。
 * 任何NavigableMap的子图必须实现NavigableMap接口。
 * @author liyibo
 * @date 2026-02-25 17:09
 */
public interface NavigableMap<K, V> extends SortedMap<K, V> {

    Map.Entry<K, V> lowerEntry(K key);

    K lowerKey(K key);

    Map.Entry<K, V> floorEntry(K key);

    K floorKey(K key);

    Map.Entry<K, V> ceilingEntry(K key);

    K ceilingKey(K key);

    Map.Entry<K, V> higherEntry(K key);

    K higherKey(K key);

    Map.Entry<K, V> firstEntry();

    Map.Entry<K, V> lastEntry();

    Map.Entry<K, V> pollFirstEntry();

    Map.Entry<K, V> pollLastEntry();

    NavigableMap<K, V> descendingMap();

    NavigableSet<K> navigableKeySet();

    NavigableSet<K> descendingKeySet();

    NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive);

    NavigableMap<K, V> headMap(K toKey, boolean inclusive);

    NavigableMap<K, V> tailMap(K fromKey, boolean inclusive);

    SortedMap<K, V> subMap(K fromKey, K toKey);

    SortedMap<K, V> headMap(K toKey);

    SortedMap<K,V> tailMap(K fromKey);
}

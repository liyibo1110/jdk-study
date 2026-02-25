package com.github.liyibo1110.jdk.java.util;

import java.util.Iterator;
import java.util.SortedSet;

/**
 * 一个扩展了导航方法的有序Set，用于报告给定搜索目标的最近匹配项。
 * lower、floor、ceiling和higher方法分别返回小于、小于等于、大于等于以及大于给定元素的元素，若不存在此类元素则返回null。
 *
 * NavigableSet支持按升序或降序访问和遍历集合，descendingSet方法返回集合的视图，其中所有关系和方向方法的操作方向均被反转，升序操作的视图的性能通常优于降序操作。
 * 该接口还定义了pollFirst和pollLast方法，用于返回并移除最低/最高元素，subSet、headSet和tailSet方法与SortedSet同名方法的区别在于：
 * 它们额外接受参数以描述上下界是否包含边界值，任何NavigableSet的子集都必须实现NavigableSet接口。
 *
 * 在允许null元素的实现中，导航方法的返回值可能存在歧义。但即使在此情况下，通过调用contains(null)仍可消除歧义。
 * 为避免此类问题，建议该接口的实现禁止插入null元素（需注意：Comparable 元素的排序集合本质上不允许null）。
 *
 * subSet(E, E)、headSet(E)和tailSet(E)方法被指定为返回SortedSet，以便现有SortedSet实现能兼容地改造为实现NavigableSet。
 * 但鼓励此接口的扩展和实现重写这些方法以返回NavigableSet。
 * @author liyibo
 * @date 2026-02-25 13:16
 */
public interface NavigableSet<E> extends SortedSet<E> {

    /**
     * 返回集合中严格小于给定元素的最大元素，不存在则返回null。
     */
    E lower(E e);

    /**
     * 返回集合中小于等于给定元素的最大元素，不存在则返回null。
     */
    E floor(E e);

    /**
     * 返回集合中大于等于给定元素的最小元素，不存在则返回null。
     */
    E ceiling(E e);

    /**
     * 返回集合中严格大于给定元素的最小元素，不存在则返回null。
     */
    E higher(E e);

    /**
     * 检索并移除第一个（最低）元素
     */
    E pollFirst();

    /**
     * 检索并移除最后（最高）元素
     */
    E pollLast();

    Iterator<E> iterator();

    /**
     * 返回本集合中元素的逆序视图。该降序集合由本集合提供支持，因此对本集合的修改将反映在降序集合中，反之亦然。
     * 若在遍历任一集合期间对其进行修改（通过迭代器自身的移除操作除外），则遍历结果将无法定义。
     *
     * 返回的集合排序方式等同于Collections.reverseOrder(comparator())的处理结果。
     * 表达式s.descendingSet().descendingSet()返回的s的视图本质上等同于s本身。
     */
    NavigableSet<E> descendingSet();

    Iterator<E> descendingIterator();

    /**
     * 返回本集合中元素范围从fromElement到toElement的子集视图。
     * 若fromElement与toElement相等，则返回的集合为空，除非fromInclusive和toInclusive均为 true。
     * 返回的集合由本集合提供支持，因此返回集合的变更将反映在本集合中，反之亦然。返回的集合支持本集合支持的所有可选集合运算。
     */
    NavigableSet<E> subSet(E fromElement, boolean fromInclusive, E toElement, boolean toInclusive);

    NavigableSet<E> headSet(E toElement, boolean inclusive);

    NavigableSet<E> tailSet(E fromElement, boolean inclusive);

    SortedSet<E> subSet(E fromElement, E toElement);

    SortedSet<E> headSet(E toElement);

    SortedSet<E> tailSet(E fromElement);
}

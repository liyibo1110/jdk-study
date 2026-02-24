package com.github.liyibo1110.jdk.java.util;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;

/**
 * 一个不包含重复元素的集合。
 * Set接口在继承自Collection的基础上，对所有构造方法以及add、equals和hashCode方法的契约提出了额外的要求。
 * 为方便起见，此处同时列出其他继承方法的声明（这些声明的规范已针对Set接口进行调整，但未包含额外限制）。
 * 构造方法的额外限制要求显而易见：所有构造函数必须创建不含重复元素的集合（如上所述）。
 *
 * 注意：若将可变对象用作集合元素，必须格外谨慎。当对象作为集合元素存在期间，若其值发生改变并影响等值比较，则集合的行为不作规定。
 * 此禁令的特殊情况是：集合不允许包含自身作为元素。
 * 某些集合实现对其元素类型存在限制。例如，部分实现禁止包含空元素，另一些则对元素类型设限。
 * 尝试添加不符合条件的元素将抛出未检查异常，通常为NullPointerException或ClassCastException。
 * 查询不符合条件元素的存在性可能抛出异常，也可能直接返回false；不同实现可能表现为上述两种行为之一。
 * 更普遍而言，对无效元素执行操作（即使该操作不会导致无效元素被插入集合）可能抛出异常，也可能成功——具体取决于实现方案的选择。
 * 此类异常在该接口规范中被标记为“可选”。
 *
 * Unmodifiable Sets
 *
 * Set.of和Set.copyOf静态工厂方法提供了创建不可修改集合的便捷方式。通过这些方法创建的Set实例具有以下特性：
 * 1、它们不可修改。无法添加或移除元素。对Set调用任何修改器方法都会抛出UnsupportedOperationException异常。
 * 但若集合内部元素本身可变，可能导致集合行为不一致或内容看似发生变化。
 * 2、禁止包含空元素。尝试使用空元素创建集合将抛出NullPointerException。
 * 3、当所有元素均可序列化时，集合本身可序列化。
 * 4、创建时拒绝重复元素。向静态工厂方法传递重复元素将抛出IllegalArgumentException。
 * 5、集合元素的迭代顺序未作规定且可能变更。
 * 6、它们基于值进行比较。程序员应将相等的实例视为可互换，且不应用于同步操作，否则可能引发不可预测的行为。
 * 例如在未来版本中，同步操作可能失败。调用方不应预设返回实例的身份属性。工厂可自由创建新实例或复用现有实例。
 * 7、其序列化方式遵循“序列化形式”页面的规定。
 *
 * @author liyibo
 * @date 2026-02-24 16:52
 */
public interface Set<E> extends Collection<E> {

    // Query Operations

    int size();

    boolean isEmpty();

    boolean contains(Object o);

    Iterator<E> iterator();

    Object[] toArray();

    <T> T[] toArray(T[] a);

    // Modification Operations

    boolean add(E e);

    boolean remove(Object o);

    // Bulk Operations

    boolean containsAll(Collection<?> c);

    boolean addAll(Collection<? extends E> c);

    boolean retainAll(Collection<?> c);

    boolean removeAll(Collection<?> c);

    void clear();

    // Comparison and hashing

    boolean equals(Object o);

    int hashCode();

    @Override
    default Spliterator<E> spliterator() {
        return Spliterators.spliterator(this, Spliterator.DISTINCT);
    }

    static <E> Set<E> of() {
        return (Set<E>) ImmutableCollections.EMPTY_SET;
    }

    static <E> Set<E> of(E e1) {
        return new ImmutableCollections.Set12<>(e1);
    }

    static <E> Set<E> of(E e1, E e2) {
        return new ImmutableCollections.Set12<>(e1, e2);
    }

    static <E> Set<E> of(E e1, E e2, E e3) {
        return new ImmutableCollections.SetN<>(e1, e2, e3);
    }

    static <E> Set<E> of(E e1, E e2, E e3, E e4) {
        return new ImmutableCollections.SetN<>(e1, e2, e3, e4);
    }

    static <E> Set<E> of(E e1, E e2, E e3, E e4, E e5) {
        return new ImmutableCollections.SetN<>(e1, e2, e3, e4, e5);
    }

    static <E> Set<E> of(E e1, E e2, E e3, E e4, E e5, E e6) {
        return new ImmutableCollections.SetN<>(e1, e2, e3, e4, e5, e6);
    }

    static <E> Set<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7) {
        return new ImmutableCollections.SetN<>(e1, e2, e3, e4, e5, e6, e7);
    }

    static <E> Set<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8) {
        return new ImmutableCollections.SetN<>(e1, e2, e3, e4, e5, e6, e7, e8);
    }

    static <E> Set<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9) {
        return new ImmutableCollections.SetN<>(e1, e2, e3, e4, e5, e6, e7, e8, e9);
    }

    static <E> Set<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9, E e10) {
        return new ImmutableCollections.SetN<>(e1, e2, e3, e4, e5, e6, e7, e8, e9, e10);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    static <E> Set<E> of(E... elements) {
        switch(elements.length) { // implicit null check of elements
            case 0:
                @SuppressWarnings("unchecked")
                var set = (Set<E>)ImmutableCollections.EMPTY_SET;
                return set;
            case 1:
                return new ImmutableCollections.Set12<>(elements[0]);
            case 2:
                return new ImmutableCollections.Set12<>(elements[0], elements[1]);
            default:
                return new ImmutableCollections.SetN<>(elements);
        }
    }

    static <E> Set<E> copyOf(Collection<? extends E> coll) {
        if(coll instanceof ImmutableCollections.AbstractImmutableSet) {
            return (Set<E>)coll;
        else
            return (Set<E>)Set.of(new HashSet<>(coll).toArray());
    }
}

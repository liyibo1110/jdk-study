package com.github.liyibo1110.jdk.java.util;

import java.util.Comparator;
import java.util.Spliterator;

/**
 * 一种在元素上提供全序关系的Set，元素通过其自然排序关系或通常在创建排序集合时指定的比较器进行排序。
 * 该集合的迭代器按元素升序遍历集合，还提供若干额外操作以利用排序特性。
 *
 * 插入排序集合的所有元素必须实现Comparable接口（或被指定比较器接受），此外所有元素必须相可比较：
 * 对于SortedSet中任意元素e1和e2，e1.compareTo(e2)（或comparator.compare(e1, e2)）均不得抛出ClassCastException异常，违反此限制将导致违规方法或构造方法调用抛出ClassCastException。
 *
 * 需注意：排序集合维持的排序关系（无论是否提供显式比较器）必须与equals方法保持一致，方能正确实现Set接口。
 * （关于“与equals一致”的精确定义，请参阅Comparable接口或Comparator接口说明。） 这是因为Set接口基于equals操作定义，而排序集合通过compareTo（或compare）方法执行所有元素比较——该方法判定相等的两个元素，在排序集合视角下即为相等。即使排序集合的顺序与equals不一致，其行为仍属明确定义，只是未能遵守Set接口的通用契约。
 *
 * 所有通用排序集合实现类都应提供四个“标准”构造函数：
 * 1) 无参数构造函数，创建按元素自然排序的空排序集合；
 * 2) 带单个Comparator类型参数的构造函数，创建按指定比较器排序的空排序集合；
 * 3) 一个带单个Collection类型参数的构造器，创建包含与参数相同元素的新排序集合，按元素自然顺序排序。
 * 4) 一个带单个SortedSet类型参数的构造器，创建包含与输入排序集合相同元素且保持相同排序的新排序集合。
 * 由于接口无法包含构造器，此建议无法强制执行。
 * @author liyibo
 * @date 2026-02-25 12:36
 */
public interface SortedSet<E> extends Set<E> {

    /**
     * 返回对本集合中的元素进行排序的Comparator，如果本集合使用元素的自然排序，则返回null。
     */
    Comparator<? super E> comparator();

    /**
     * 返回本集合中元素范围从fromElement（包含）到toElement（不包含）的子集视图（fromElement == toElement则返回空集合）。
     * 返回的子集由本集合提供支持，因此对子集的修改将反映在本集合中，反之亦然，该子集支持本集合所支持的所有可选集合运算。
     * 当尝试插入超出该范围的元素时，返回的集合将抛出IllegalArgumentException。
     */
    SortedSet<E> subSet(E fromElement, E toElement);

    SortedSet<E> headSet(E toElement);

    SortedSet<E> tailSet(E fromElement);

    /**
     * 返回当前集合中的第一个（最小）的元素
     */
    E first();

    E last();

    @Override
    default Spliterator<E> spliterator() {
        return new Spliterators.IteratorSpliterator<E>(
                this, Spliterator.DISTINCT | Spliterator.SORTED | Spliterator.ORDERED) {
            @Override
            public Comparator<? super E> getComparator() {
                return SortedSet.this.comparator();
            }
        };
    }
}

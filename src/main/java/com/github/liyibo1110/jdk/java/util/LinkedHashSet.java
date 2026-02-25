package com.github.liyibo1110.jdk.java.util;

import java.io.Serializable;
import java.util.Spliterator;
import java.util.Spliterators;

/**
 * 实现Set接口的Hash表和链表方案，具有可预测的迭代顺序。
 * 此实现与HashSet的区别在于：它维护一条贯穿所有条目的双向链表。该链表定义了迭代顺序，即元素被插入集合时的顺序（插入顺序）。
 * 需注意：若元素被重新插入集合，其插入顺序不受影响。（当元素e被重新插入集合s时，需满足：在调用s.add(e)之前，s.contains(e) 立即返回true。）
 *
 * 该实现既避免了HashSet提供的未定义且通常混乱的排序，又未增加TreeSet的额外开销。它可用于生成与原始集合顺序相同的副本，且不受原始集合实现方式限制。
 *
 * 当模块接收集合输入、复制该集合，并后续返回取决于副本顺序的结果时，此技术尤为实用（客户端通常期望返回结果与输入顺序一致）。
 *
 * 该类提供所有可选集合操作，并允许包含空元素。
 * 与HashSet类似，当哈希函数能将元素合理分散到桶中时，其基本操作（添加、包含检测和移除）可实现常数时间性能。
 * 由于维护链表的额外开销，其性能通常略低于HashSet，但存在一个例外：遍历LinkedHashSet所需时间与集合大小成正比，与容量无关。
 * 遍历HashSet的开销更大，其时间复杂度与容量成正比。
 *
 * 链式哈希集合有两个影响性能的参数：初始容量和负载因子。它们的定义与HashSet完全一致。
 * 但需注意，相较于HashSet，本类选择过高初始容量的惩罚较轻，因为其迭代时间不受容量影响。
 *
 * 初看这个类会发现根本没有实现任何维护key顺序的代码，原因在于这里的功能是在LinkedHashMap中，
 * 在LinkedHashSet的构造方法里，最终会调用HashSet的特殊构造方法，那里面实际用的Map就是LinkedHashMap，就是这样了。
 * @author liyibo
 * @date 2026-02-25 19:40
 */
public class LinkedHashSet<E> extends HashSet<E> implements Set<E>, Cloneable, Serializable {

    @java.io.Serial
    private static final long serialVersionUID = -2851667679971038690L;

    public LinkedHashSet(int initialCapacity, float loadFactor) {
        super(initialCapacity, loadFactor, true);
    }

    public LinkedHashSet(int initialCapacity) {
        super(initialCapacity, .75f, true);
    }

    public LinkedHashSet() {
        super(16, .75f, true);
    }

    public LinkedHashSet(Collection<? extends E> c) {
        super(Math.max(2*c.size(), 11), .75f, true);
        addAll(c);
    }

    @Override
    public Spliterator<E> spliterator() {
        return Spliterators.spliterator(this, Spliterator.DISTINCT | Spliterator.ORDERED);
    }
}

package com.github.liyibo1110.jdk.java.util;

import java.util.Collection;

/**
 * Queue是一种用于在处理前暂存元素的集合，除基本Collection操作外，Queue还提供额外的插入、提取和检查操作。
 * 这些方法均存在两种形式：
 * 1、在操作失败时抛出异常。
 * 2、在操作失败时返回特殊值（null或false）。
 * 插入操作的后一种形式专为容量受限的Queue实现设计，在大多数实现中，插入操作不会失败。
 *
 * Queue通常（并非必然）以先进先出（FIFO）方式排序元素。
 * 例外情况包括优先级Queue -- 其根据提供的比较器或元素的自然顺序排序元素，以及后进先出Queue（或栈），这类Queue按后进先出（LIFO）原则排序元素。
 * 无论采用何种排序规则，Queue头部始终是调用remove或者poll方法时将被移除的元素，在FIFO的Queue中，所有新元素均插入队尾。
 * 其他Queue类型可能采用不同插入规则，每个Queue实现都必须明确其排序属性。
 *
 * offer方法在可能时插入元素，否则返回false，这与Collection的add方法不同 -- 后者仅通过抛出非检查异常才能失败。
 * offer专为失败属于常态（而非异常）的场景设计，例如固定容量（有界）队列。
 *
 * remove和poll方法用于移除并返回Queue头部元素，具体移除哪个元素取决于Queue的排序策略，该策略因实现而异。
 * 两者的区别仅在于Queue为空的行为，remove会抛出异常，而poll返回null。
 *
 * element和peek方法返回队列头部而不会移除它。
 *
 * Queue接口并未定义并发编程中常见的阻塞队列方法。这些用于等待元素出现或空间释放的方法，由继承自该接口的BlockingQueue接口定义。
 * Queue实现通过不定义基于元素的equals和hashCode方法，而是继承Object类的基于身份的版本，因为对于元素相同但排序属性不同的队列，基于元素的相等性定义未必明确。
 * @author liyibo
 * @date 2026-02-24 00:35
 */
public interface Queue<E> extends Collection<E> {

    /**
     * 成功返回true，如果空间不够则抛出IllegalStateException。
     */
    boolean add(E e);

    /**
     * 成功返回true，如果空间不够则返回false。
     */
    boolean offer(E e);

    /**
     * 检索并移除Queue的头部元素，若Queue为空，则抛异常。
     */
    E remove();

    /**
     * 检索并移除Queue的头部元素，若Queue为空，则返回false。
     */
    E poll();

    /**
     * 检索并返回Queue的头部元素，若Queue为空，则抛异常（不会消耗元素）。
     */
    E element();

    /**
     * 检索并返回Queue的头部元素，若Queue为空，则返回null（不会消耗元素）。
     */
    E peek();
}

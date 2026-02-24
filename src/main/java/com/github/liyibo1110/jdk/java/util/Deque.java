package com.github.liyibo1110.jdk.java.util;

import java.util.Queue;

/**
 * 一种支持在两端插入和删除元素的线性集合，名称是“双端队列”的缩写，发音为deck。
 * 大多数Deque实现对元素数量没有固定限制，但该接口同时支持容量受限的双端队列和无固定大小限制的双端队列。
 * 该接口定义了访问Deque元素的方法，提供插入、移除以及检查元素的功能，每种操作方法均存在两种形式：（和Queue一样，不写了）
 *
 * 此接口继承自Queue，但Deque作为队列使用时，将呈现FIFO的行为。
 * Deque也可用作后进先出（LIFO）栈，应优先使用此接口而非旧版的Stack类，当Deque作为栈使用时，元素将从首部进行push和pop。
 *
 * 请注意，当Deque作为队列或者栈使用时，peek方法同样有效，无论哪种情况，元素都从Deque的开头提取。
 * 该接口提供两种移除内部元素的方法：removeFirstOccurrence和removeLastOccurrence。
 * 与List接口不同，该接口不支持通过索引访问元素。
 *
 * 虽然Deque实现并非严格禁止插入null元素，但强烈建议采取此限制措施，对于运行null元素的Deque实现，强烈建议用户避免利用插入null的功能。
 * 这是因为null被多种方法用作特殊返回值，用于指示Deque为空。
 *
 * Deque实现在通常不定义基于元素的equals和hashCode方法，而是继承自Object类的基于身份的版本。
 * @author liyibo
 * @date 2026-02-24 00:51
 */
public interface Deque<E> extends Queue<E> {

    void addFirst(E e);
    void addLast(E e);

    boolean offerFirst(E e);
    boolean offerLast(E e);

    E removeFirst();
    E removeLast();

    E pollFirst();
    E pollLast();

    E getFirst();
    E getLast();

    E peekFirst();
    E peekLast();

    /**
     * 从Deque中移除首个指定元素，若不存在，则保持不变。
     */
    boolean removeFirstOccurrence(Object o);
    boolean removeLastOccurrence(Object o);

    // *** Queue methods ***

    /**
     * 插入Deque的队尾
     */
    boolean add(E e);
    boolean offer(E e);

    E remove();
    E poll();

    E element();
    E peek();

    boolean addAll(Collection<? extends E> c);

    // *** Stack methods ***

    void push(E e);
    E pop();

    // *** Collection methods ***

    boolean remove(Object o);

    boolean contains(Object o);

    int size();

    java.util.Iterator<E> iterator();

    Iterator<E> descendingIterator();
}

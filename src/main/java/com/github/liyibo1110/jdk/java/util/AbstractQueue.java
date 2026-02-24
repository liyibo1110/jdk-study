package com.github.liyibo1110.jdk.java.util;

import java.util.NoSuchElementException;

/**
 * 提供了Queue操作的骨架实现，当基础实现不允许null元素时，本类的实现方案适用：
 * add、remove和element方法分别基于offer、poll和peek方法，但通过抛异常而非返回false或null来表示失败。
 *
 * 继承本类的Queue实现至少需要定义以下方法：
 * Queue.offer（禁止插入null元素）
 * Queue.peek
 * Queue.poll
 * Collection.size
 * Collection.iterator
 * 通常还会重写其他方法，若无法满足上述要求，请考虑继承AbstractCollection。
 * @author liyibo
 * @date 2026-02-24 01:05
 */
public abstract class AbstractQueue<E> extends AbstractCollection<E> implements Queue<E> {

    protected AbstractQueue() {}

    public boolean add(E e) {
        if(offer(e))
            return true;
        else
            throw new IllegalStateException("Queue full");
    }

    public E remove() {
        E x = poll();
        if(x != null)
            return x;
        else
            throw new NoSuchElementException();
    }

    public E element() {
        E x = peek();
        if(x != null)
            return x;
        else
            throw new NoSuchElementException();
    }

    public void clear() {
        while (poll() != null)
            ;
    }

    public boolean addAll(Collection<? extends E> c) {
        if(c == null)
            throw new NullPointerException();
        if(c == this)
            throw new IllegalArgumentException();
        boolean modified = false;
        for(E e : c) {
            if(add(e))
                modified = true;
        }
        return modified;
    }
}

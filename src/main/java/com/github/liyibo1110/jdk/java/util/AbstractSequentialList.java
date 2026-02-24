package com.github.liyibo1110.jdk.java.util;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 该类为List接口提供了一个骨架实现，旨在最大限度地减少基于“顺序访问”数据存储（如链表）实现该接口所需的工作量。
 * 对于随机访问数据（如数组），应优先使用AbstractList类而非本类。
 *
 * 本类与AbstractList区别在于：它在ListIterator基础上实现了“随机访问”方法。
 *
 * 要实现List功能，开发人员只需继承本类并为ListIterator和size方法提供实现。
 * 对于不可修改的List，开发人员只需要实现ListIterator的hasNext、next、hasPrevious、previous和index方法。
 * 对于可修改的List，开发人员还应该实现ListIterator的set方法。
 * 对于可变长度的List，还需要实现ListIterator的remove和add方法。
 *
 * 根据 Collection 接口规范的建议，程序员通常应提供无参数构造器和集合构造器。
 * @author liyibo
 * @date 2026-02-24 01:12
 */
public abstract class AbstractSequentialList<E> extends AbstractList<E> {

    protected AbstractSequentialList() {}

    public E get(int index) {
        try {
            return listIterator(index).next();
        } catch (NoSuchElementException exc) {
            throw new IndexOutOfBoundsException("Index: "+index);
        }
    }

    public E set(int index, E element) {
        try {
            ListIterator<E> e = listIterator(index);
            E oldVal = e.next();
            e.set(element);
            return oldVal;
        } catch (NoSuchElementException exc) {
            throw new IndexOutOfBoundsException("Index: "+index);
        }
    }

    public void add(int index, E element) {
        try {
            listIterator(index).add(element);
        } catch (NoSuchElementException exc) {
            throw new IndexOutOfBoundsException("Index: "+index);
        }
    }

    public E remove(int index) {
        try {
            ListIterator<E> e = listIterator(index);
            E outCast = e.next();
            e.remove();
            return outCast;
        } catch (NoSuchElementException exc) {
            throw new IndexOutOfBoundsException("Index: "+index);
        }
    }

    // Bulk Operations

    public boolean addAll(int index, Collection<? extends E> c) {
        try {
            boolean modified = false;
            ListIterator<E> e1 = listIterator(index);
            for(E e : c) {
                e1.add(e);
                modified = true;
            }
            return modified;
        } catch (NoSuchElementException exc) {
            throw new IndexOutOfBoundsException("Index: "+index);
        }
    }

    // Iterators

    public Iterator<E> iterator() {
        return listIterator();
    }

    public abstract ListIterator<E> listIterator(int index);
}

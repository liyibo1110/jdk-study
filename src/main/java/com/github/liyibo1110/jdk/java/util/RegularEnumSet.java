package com.github.liyibo1110.jdk.java.util;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * EnumSet的常用实现，特定类型枚举常量小于64个就会用这个实现，特点是只用一个long来存储所有元素即可。
 * @author liyibo
 * @date 2026-03-26 11:41
 */
class RegularEnumSet<E extends Enum<E>> extends EnumSet<E> {
    @java.io.Serial
    private static final long serialVersionUID = 3411599620347842686L;

    /** 底层存储 */
    private long elements = 0L;

    RegularEnumSet(Class<E>elementType, Enum<?>[] universe) {
        super(elementType, universe);
    }

    void addRange(E from, E to) {
        elements = (-1L >>>  (from.ordinal() - to.ordinal() - 1)) << from.ordinal();
    }

    void addAll() {
        if(universe.length != 0)
            elements = -1L >>> -universe.length;
    }

    void complement() {
        if(universe.length != 0) {
            elements = ~elements;
            elements &= -1L >>> -universe.length;  // Mask unused bits
        }
    }

    public Iterator<E> iterator() {
        return new EnumSetIterator<>();
    }

    private class EnumSetIterator<E extends Enum<E>> implements Iterator<E> {
        long unseen;

        long lastReturned = 0;

        EnumSetIterator() {
            unseen = elements;
        }

        public boolean hasNext() {
            return unseen != 0;
        }

        public E next() {
            if(unseen == 0)
                throw new NoSuchElementException();
            lastReturned = unseen & -unseen;
            unseen -= lastReturned;
            return (E)universe[Long.numberOfTrailingZeros(lastReturned)];
        }

        public void remove() {
            if(lastReturned == 0)
                throw new IllegalStateException();
            elements &= ~lastReturned;
            lastReturned = 0;
        }
    }

    public int size() {
        return Long.bitCount(elements);
    }

    public boolean isEmpty() {
        return elements == 0;
    }

    public boolean contains(Object e) {
        if(e == null)
            return false;
        Class<?> eClass = e.getClass();
        if(eClass != elementType && eClass.getSuperclass() != elementType)
            return false;
        return (elements & (1L << ((Enum<?>)e).ordinal())) != 0;
    }

    // Modification Operations

    public boolean add(E e) {
        typeCheck(e);
        long oldElements = elements;
        elements |= (1L << ((Enum<?>)e).ordinal()); // 使用的是ordinal
        return elements != oldElements;
    }

    public boolean remove(Object e) {
        if(e == null)
            return false;
        Class<?> eClass = e.getClass();
        if(eClass != elementType && eClass.getSuperclass() != elementType)
            return false;

        long oldElements = elements;
        elements &= ~(1L << ((Enum<?>)e).ordinal());
        return elements != oldElements;
    }

    public boolean containsAll(Collection<?> c) {
        if(!(c instanceof RegularEnumSet<?> es))
            return super.containsAll(c);
        if(es.elementType != elementType)
            return es.isEmpty();
        return (es.elements & ~elements) == 0;
    }

    public boolean addAll(Collection<? extends E> c) {
        if(!(c instanceof RegularEnumSet<?> es))
            return super.addAll(c);

        if(es.elementType != elementType) {
            if(es.isEmpty())
                return false;
            else
                throw new ClassCastException(es.elementType + " != " + elementType);
        }

        long oldElements = elements;
        elements |= es.elements;
        return elements != oldElements;
    }

    public boolean removeAll(Collection<?> c) {
        if(!(c instanceof RegularEnumSet<?> es))
            return super.removeAll(c);

        if(es.elementType != elementType)
            return false;

        long oldElements = elements;
        elements &= ~es.elements;
        return elements != oldElements;
    }

    public boolean retainAll(Collection<?> c) {
        if(!(c instanceof RegularEnumSet<?> es))
            return super.retainAll(c);

        if(es.elementType != elementType) {
            boolean changed = (elements != 0);
            elements = 0;
            return changed;
        }

        long oldElements = elements;
        elements &= es.elements;
        return elements != oldElements;
    }

    public void clear() {
        elements = 0;
    }

    public boolean equals(Object o) {
        if(!(o instanceof RegularEnumSet<?> es))
            return super.equals(o);
        if(es.elementType != elementType)
            return elements == 0 && es.elements == 0;
        return es.elements == elements;
    }
}

package com.github.liyibo1110.jdk.java.util.concurrent;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 内置了ConcurrentSkipListMap，基本都是委托其操作。
 * @author liyibo
 * @date 2026-03-20 17:47
 */
public class ConcurrentSkipListSet<E> extends AbstractSet<E> implements NavigableSet<E>, Cloneable, Serializable {
    private static final long serialVersionUID = -2479143111061671589L;

    private final ConcurrentNavigableMap<E, Object> m;

    public ConcurrentSkipListSet() {
        m = new ConcurrentSkipListMap<>();
    }

    public ConcurrentSkipListSet(Comparator<? super E> comparator) {
        m = new ConcurrentSkipListMap<>(comparator);
    }

    public ConcurrentSkipListSet(Collection<? extends E> c) {
        m = new ConcurrentSkipListMap<>();
        addAll(c);
    }

    public ConcurrentSkipListSet(SortedSet<E> s) {
        m = new ConcurrentSkipListMap<>(s.comparator());
        addAll(s);
    }

    ConcurrentSkipListSet(ConcurrentNavigableMap<E,Object> m) {
        this.m = m;
    }

    public ConcurrentSkipListSet<E> clone() {
        try {
            @SuppressWarnings("unchecked")
            ConcurrentSkipListSet<E> clone = (ConcurrentSkipListSet<E>) super.clone();
            clone.setMap(new ConcurrentSkipListMap<E,Object>(m));
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new InternalError();
        }
    }

    /* ---------------- Set operations -------------- */

    public int size() {
        return m.size();
    }

    public boolean isEmpty() {
        return m.isEmpty();
    }

    public boolean contains(Object o) {
        return m.containsKey(o);
    }

    public boolean add(E e) {
        return m.putIfAbsent(e, Boolean.TRUE) == null;
    }

    public boolean remove(Object o) {
        return m.remove(o, Boolean.TRUE);
    }

    public void clear() {
        m.clear();
    }

    public Iterator<E> iterator() {
        return m.navigableKeySet().iterator();
    }

    public Iterator<E> descendingIterator() {
        return m.descendingKeySet().iterator();
    }

    /* ---------------- AbstractSet Overrides -------------- */

    public boolean equals(Object o) {
        // Override AbstractSet version to avoid calling size()
        if(o == this)
            return true;
        if(!(o instanceof Set))
            return false;
        Collection<?> c = (Collection<?>) o;
        try {
            return containsAll(c) && c.containsAll(this);
        } catch (ClassCastException | NullPointerException unused) {
            return false;
        }
    }

    public boolean removeAll(Collection<?> c) {
        // Override AbstractSet version to avoid unnecessary call to size()
        boolean modified = false;
        for(Object e : c)
            if(remove(e))
                modified = true;
        return modified;
    }

    /* ---------------- Relational operations -------------- */

    public E lower(E e) {
        return m.lowerKey(e);
    }

    public E floor(E e) {
        return m.floorKey(e);
    }

    public E ceiling(E e) {
        return m.ceilingKey(e);
    }

    public E higher(E e) {
        return m.higherKey(e);
    }

    public E pollFirst() {
        Map.Entry<E,Object> e = m.pollFirstEntry();
        return (e == null) ? null : e.getKey();
    }

    public E pollLast() {
        Map.Entry<E,Object> e = m.pollLastEntry();
        return (e == null) ? null : e.getKey();
    }

    /* ---------------- SortedSet operations -------------- */

    public Comparator<? super E> comparator() {
        return m.comparator();
    }

    public E first() {
        return m.firstKey();
    }

    public E last() {
        return m.lastKey();
    }

    public NavigableSet<E> subSet(E fromElement, boolean fromInclusive,
                                  E toElement, boolean toInclusive) {
        return new ConcurrentSkipListSet<>(m.subMap(fromElement, fromInclusive, toElement, toInclusive));
    }

    public NavigableSet<E> headSet(E toElement, boolean inclusive) {
        return new ConcurrentSkipListSet<>(m.headMap(toElement, inclusive));
    }

    public NavigableSet<E> tailSet(E fromElement, boolean inclusive) {
        return new ConcurrentSkipListSet<E>(m.tailMap(fromElement, inclusive));
    }

    public NavigableSet<E> subSet(E fromElement, E toElement) {
        return subSet(fromElement, true, toElement, false);
    }

    public NavigableSet<E> headSet(E toElement) {
        return headSet(toElement, false);
    }

    public NavigableSet<E> tailSet(E fromElement) {
        return tailSet(fromElement, true);
    }

    public NavigableSet<E> descendingSet() {
        return new ConcurrentSkipListSet<>(m.descendingMap());
    }

    public Spliterator<E> spliterator() {
        return (m instanceof ConcurrentSkipListMap)
                ? ((ConcurrentSkipListMap<E,?>)m).keySpliterator()
                : ((ConcurrentSkipListMap.SubMap<E,?>)m).new SubMapKeyIterator();
    }

    private void setMap(ConcurrentNavigableMap<E,Object> map) {
        @SuppressWarnings("removal")
        Field mapField = java.security.AccessController.doPrivileged(
                (java.security.PrivilegedAction<Field>) () -> {
                    try {
                        Field f = ConcurrentSkipListSet.class.getDeclaredField("m");
                        f.setAccessible(true);
                        return f;
                    } catch (ReflectiveOperationException e) {
                        throw new Error(e);
                    }});
        try {
            mapField.set(this, map);
        } catch (IllegalAccessException e) {
            throw new Error(e);
        }
    }
}

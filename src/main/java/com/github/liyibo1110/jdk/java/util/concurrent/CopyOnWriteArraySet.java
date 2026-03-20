package com.github.liyibo1110.jdk.java.util.concurrent;

import com.github.liyibo1110.jdk.java.util.AbstractList;

import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 内置了CopyOnWriteArrayList，基本都是委托其操作。
 * @author liyibo
 * @date 2026-03-20 17:58
 */
public class CopyOnWriteArraySet<E> extends AbstractSet<E> implements Serializable {
    private static final long serialVersionUID = 5457747651344034263L;

    private final CopyOnWriteArrayList<E> al;

    public CopyOnWriteArraySet() {
        al = new CopyOnWriteArrayList<>();
    }

    public CopyOnWriteArraySet(Collection<? extends E> c) {
        if(c.getClass() == CopyOnWriteArraySet.class) {
            @SuppressWarnings("unchecked") CopyOnWriteArraySet<E> cc = (CopyOnWriteArraySet<E>)c;
            al = new CopyOnWriteArrayList<>(cc.al);
        }else {
            al = new CopyOnWriteArrayList<>();
            al.addAllAbsent(c);
        }
    }

    public int size() {
        return al.size();
    }

    public boolean isEmpty() {
        return al.isEmpty();
    }

    public boolean contains(Object o) {
        return al.contains(o);
    }

    public Object[] toArray() {
        return al.toArray();
    }

    public <T> T[] toArray(T[] a) {
        return al.toArray(a);
    }

    public void clear() {
        al.clear();
    }

    public boolean remove(Object o) {
        return al.remove(o);
    }

    public boolean add(E e) {
        return al.addIfAbsent(e);
    }

    public boolean containsAll(Collection<?> c) {
        return (c instanceof Set) ? compareSets(al.getArray(), (Set<?>) c) >= 0 : al.containsAll(c);
    }

    private static int compareSets(Object[] snapshot, Set<?> set) {
        // Uses O(n^2) algorithm, that is only appropriate for small
        // sets, which CopyOnWriteArraySets should be.
        //
        // Optimize up to O(n) if the two sets share a long common prefix,
        // as might happen if one set was created as a copy of the other set.

        final int len = snapshot.length;
        // Mark matched elements to avoid re-checking
        final boolean[] matched = new boolean[len];

        // j is the largest int with matched[i] true for { i | 0 <= i < j }
        int j = 0;
        outer: for (Object x : set) {
            for (int i = j; i < len; i++) {
                if (!matched[i] && Objects.equals(x, snapshot[i])) {
                    matched[i] = true;
                    if (i == j)
                        do { j++; } while (j < len && matched[j]);
                    continue outer;
                }
            }
            return -1;
        }
        return (j == len) ? 0 : 1;
    }

    public boolean addAll(Collection<? extends E> c) {
        return al.addAllAbsent(c) > 0;
    }

    public boolean removeAll(Collection<?> c) {
        return al.removeAll(c);
    }

    public boolean retainAll(Collection<?> c) {
        return al.retainAll(c);
    }

    public Iterator<E> iterator() {
        return al.iterator();
    }

    public boolean equals(Object o) {
        return (o == this) || ((o instanceof Set) && compareSets(al.getArray(), (Set<?>) o) == 0);
    }

    public boolean removeIf(Predicate<? super E> filter) {
        return al.removeIf(filter);
    }

    public void forEach(Consumer<? super E> action) {
        al.forEach(action);
    }

    public Spliterator<E> spliterator() {
        return Spliterators.spliterator(al.getArray(), Spliterator.IMMUTABLE | Spliterator.DISTINCT);
    }
}

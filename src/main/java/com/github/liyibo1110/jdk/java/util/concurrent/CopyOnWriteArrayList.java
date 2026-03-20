package com.github.liyibo1110.jdk.java.util.concurrent;

import jdk.internal.access.SharedSecrets;

import java.io.Serializable;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 典型的以空间换时间的方式实现了并发安全，方法实现基本和ArrayList没什么差别。
 * 读数据：直接读数组本身就可以了。
 * 写数据：首先要加锁成功，然后把原来数组复制一份，在新数组里面进行修改，最后直接覆盖旧数组，最后释放锁。
 * 所以读操作可以并发，写操作需要抢锁（用synchronized就够了），然后代价就是需要复制数组。
 *
 * 适用场景：
 * 1、读多写少（最好是读远大于写）。
 * 2、数据总量不大（复制数组成本很高）。
 * 3、需要稳定遍历。
 * @author liyibo
 * @date 2026-03-19 13:19
 */
public class CopyOnWriteArrayList<E> implements List<E>, RandomAccess, Cloneable, Serializable {
    private static final long serialVersionUID = 8673264195747942595L;

    /** 修改数据要上的锁 */
    final transient Object lock = new Object();

    private transient volatile Object[] array;

    final Object[] getArray() {
        return array;
    }

    final void setArray(Object[] a) {
        array = a;
    }

    public CopyOnWriteArrayList() {
        setArray(new Object[0]);
    }

    public CopyOnWriteArrayList(Collection<? extends E> c) {
        Object[] es;
        if(c.getClass() == CopyOnWriteArrayList.class) {
            es= ((CopyOnWriteArrayList<?>)c).getArray();
        }else {
            es = c.toArray();
            if(c.getClass() != java.util.ArrayList.class) {
                es = Arrays.copyOf(es, es.length, Object[].class);
            }
        }
        setArray(es);
    }

    public CopyOnWriteArrayList(E[] toCopyIn) {
        setArray(Arrays.copyOf(toCopyIn, toCopyIn.length, Object[].class));
    }

    public int size() {
        return getArray().length;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    private static int indexOfRange(Object o, Object[] es, int from, int to) {
        if(o == null) {
            for(int i = from; i < to; i++)
                if(es[i] == null)
                    return i;
        }else {
            for(int i = from; i < to; i++)
                if(o.equals(es[i]))
                    return i;
        }
        return -1;
    }

    private static int lastIndexOfRange(Object o, Object[] es, int from, int to) {
        if (o == null) {
            for(int i = to - 1; i >= from; i--)
                if (es[i] == null)
                    return i;
        }else {
            for(int i = to - 1; i >= from; i--)
                if(o.equals(es[i]))
                    return i;
        }
        return -1;
    }

    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    public int indexOf(Object o) {
        Object[] es = getArray();
        return indexOfRange(o, es, 0, es.length);
    }

    public int indexOf(E e, int index) {
        Object[] es = getArray();
        return indexOfRange(e, es, index, es.length);
    }

    public int lastIndexOf(Object o) {
        Object[] es = getArray();
        return lastIndexOfRange(o, es, 0, es.length);
    }

    public int lastIndexOf(E e, int index) {
        Object[] es = getArray();
        return lastIndexOfRange(e, es, 0, index + 1);
    }

    public Object clone() {
        try {
            @SuppressWarnings("unchecked")
            CopyOnWriteArrayList<E> clone = (CopyOnWriteArrayList<E>) super.clone();
            clone.resetLock();
            // Unlike in readObject, here we cannot visibility-piggyback on the
            // volatile write in setArray().
            VarHandle.releaseFence();
            return clone;
        } catch (CloneNotSupportedException e) {
            // this shouldn't happen, since we are Cloneable
            throw new InternalError();
        }
    }

    public Object[] toArray() {
        return getArray().clone();
    }

    public <T> T[] toArray(T[] a) {
        Object[] es = getArray();
        int len = es.length;
        if(a.length < len)
            return (T[]) Arrays.copyOf(es, len, a.getClass());
        else{
            System.arraycopy(es, 0, a, 0, len);
            if(a.length > len)
                a[len] = null;
            return a;
        }
    }

    // Positional Access Operations

    static <E> E elementAt(Object[] a, int index) {
        return (E) a[index];
    }

    static String outOfBounds(int index, int size) {
        return "Index: " + index + ", Size: " + size;
    }

    public E get(int index) {
        return elementAt(getArray(), index);
    }

    /**
     * 写数据要加锁
     */
    public E set(int index, E element) {
        synchronized(lock) {
            Object[] es = getArray();
            E oldValue = elementAt(es, index);
            if(oldValue != element) {
                es = es.clone();
                es[index] = element;
            }
            setArray(es);
            return oldValue;
        }
    }

    /**
     * 因为会复制一次数组，所以直接长度会加1，不需要做什么动态扩容。
     */
    public boolean add(E e) {
        synchronized(lock) {
            Object[] es = getArray();
            int len = es.length;
            es = Arrays.copyOf(es, len + 1);
            es[len] = e;
            setArray(es);
            return true;
        }
    }

    /**
     * 增加了向右移动数据的额外操作。
     */
    public void add(int index, E element) {
        synchronized(lock) {
            Object[] es = getArray();
            int len = es.length;
            if(index > len || index < 0)
                throw new IndexOutOfBoundsException(outOfBounds(index, len));
            Object[] newElements;
            int numMoved = len - index;
            if(numMoved == 0)
                newElements = Arrays.copyOf(es, len + 1);
            else {
                newElements = new Object[len + 1];
                System.arraycopy(es, 0, newElements, 0, index);
                System.arraycopy(es, index, newElements, index + 1, numMoved);
            }
            newElements[index] = element;
            setArray(newElements);
        }
    }

    /**
     * 一样要移动数据，只是方向不同。
     */
    public E remove(int index) {
        synchronized(lock) {
            Object[] es = getArray();
            int len = es.length;
            E oldValue = elementAt(es, index);
            int numMoved = len - index - 1;
            Object[] newElements;
            if(numMoved == 0)
                newElements = Arrays.copyOf(es, len - 1);
            else{
                newElements = new Object[len - 1];
                System.arraycopy(es, 0, newElements, 0, index);
                System.arraycopy(es, index + 1, newElements, index, numMoved);
            }
            setArray(newElements);
            return oldValue;
        }
    }

    public boolean remove(Object o) {
        Object[] snapshot = getArray();
        int index = indexOfRange(o, snapshot, 0, snapshot.length);
        return index >= 0 && remove(o, snapshot, index);
    }

    private boolean remove(Object o, Object[] snapshot, int index) {
        synchronized(lock) {
            Object[] current = getArray();
            int len = current.length;
            if(snapshot != current) findIndex: {
                int prefix = Math.min(index, len);
                for(int i = 0; i < prefix; i++) {
                    if(current[i] != snapshot[i] && Objects.equals(o, current[i])) {
                        index = i;
                        break findIndex;
                    }
                }
                if(index >= len)
                    return false;
                if(current[index] == o)
                    break findIndex;
                index = indexOfRange(o, current, index, len);
                if(index < 0)
                    return false;
            }
            Object[] newElements = new Object[len - 1];
            System.arraycopy(current, 0, newElements, 0, index);
            System.arraycopy(current, index + 1, newElements, index, len - index - 1);
            setArray(newElements);
            return true;
        }
    }

    void removeRange(int fromIndex, int toIndex) {
        synchronized(lock) {
            Object[] es = getArray();
            int len = es.length;

            if(fromIndex < 0 || toIndex > len || toIndex < fromIndex)
                throw new IndexOutOfBoundsException();
            int newlen = len - (toIndex - fromIndex);
            int numMoved = len - toIndex;
            if(numMoved == 0)
                setArray(Arrays.copyOf(es, newlen));
            else{
                Object[] newElements = new Object[newlen];
                System.arraycopy(es, 0, newElements, 0, fromIndex);
                System.arraycopy(es, toIndex, newElements, fromIndex, numMoved);
                setArray(newElements);
            }
        }
    }

    public boolean addIfAbsent(E e) {
        Object[] snapshot = getArray();
        return indexOfRange(e, snapshot, 0, snapshot.length) < 0 && addIfAbsent(e, snapshot);
    }

    private boolean addIfAbsent(E e, Object[] snapshot) {
        synchronized(lock) {
            Object[] current = getArray();
            int len = current.length;
            if(snapshot != current) {
                // Optimize for lost race to another addXXX operation
                int common = Math.min(snapshot.length, len);
                for(int i = 0; i < common; i++)
                    if(current[i] != snapshot[i] && Objects.equals(e, current[i]))
                        return false;
                if(indexOfRange(e, current, common, len) >= 0)
                    return false;
            }
            Object[] newElements = Arrays.copyOf(current, len + 1);
            newElements[len] = e;
            setArray(newElements);
            return true;
        }
    }

    public boolean containsAll(Collection<?> c) {
        Object[] es = getArray();
        int len = es.length;
        for(Object e : c) {
            if(indexOfRange(e, es, 0, len) < 0)
                return false;
        }
        return true;
    }

    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> c.contains(e));
    }

    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> !c.contains(e));
    }

    public int addAllAbsent(Collection<? extends E> c) {
        Object[] cs = c.toArray();
        if(c.getClass() != ArrayList.class) {
            cs = cs.clone();
        }
        if(cs.length == 0)
            return 0;
        synchronized (lock) {
            Object[] es = getArray();
            int len = es.length;
            int added = 0;
            // uniquify and compact elements in cs
            for(int i = 0; i < cs.length; ++i) {
                Object e = cs[i];
                if(indexOfRange(e, es, 0, len) < 0 && indexOfRange(e, cs, 0, added) < 0)
                    cs[added++] = e;
            }
            if(added > 0) {
                Object[] newElements = Arrays.copyOf(es, len + added);
                System.arraycopy(cs, 0, newElements, len, added);
                setArray(newElements);
            }
            return added;
        }
    }

    public void clear() {
        synchronized (lock) {
            setArray(new Object[0]);
        }
    }

    public boolean addAll(Collection<? extends E> c) {
        Object[] cs = (c.getClass() == CopyOnWriteArrayList.class)
                ? ((CopyOnWriteArrayList<?>)c).getArray()
                : c.toArray();
        if(cs.length == 0)
            return false;
        synchronized (lock) {
            Object[] es = getArray();
            int len = es.length;
            Object[] newElements;
            if(len == 0 && (c.getClass() == CopyOnWriteArrayList.class || c.getClass() == ArrayList.class)) {
                newElements = cs;
            }else {
                newElements = Arrays.copyOf(es, len + cs.length);
                System.arraycopy(cs, 0, newElements, len, cs.length);
            }
            setArray(newElements);
            return true;
        }
    }

    public boolean addAll(int index, Collection<? extends E> c) {
        Object[] cs = c.toArray();
        synchronized(lock) {
            Object[] es = getArray();
            int len = es.length;
            if(index > len || index < 0)
                throw new IndexOutOfBoundsException(outOfBounds(index, len));
            if(cs.length == 0)
                return false;
            int numMoved = len - index;
            Object[] newElements;
            if(numMoved == 0)
                newElements = Arrays.copyOf(es, len + cs.length);
            else {
                newElements = new Object[len + cs.length];
                System.arraycopy(es, 0, newElements, 0, index);
                System.arraycopy(es, index, newElements, index + cs.length, numMoved);
            }
            System.arraycopy(cs, 0, newElements, index, cs.length);
            setArray(newElements);
            return true;
        }
    }

    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        for(Object x : getArray()) {
            @SuppressWarnings("unchecked") E e = (E) x;
            action.accept(e);
        }
    }

    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        return bulkRemove(filter);
    }

    // A tiny bit set implementation

    private static long[] nBits(int n) {
        return new long[((n - 1) >> 6) + 1];
    }
    private static void setBit(long[] bits, int i) {
        bits[i >> 6] |= 1L << i;
    }
    private static boolean isClear(long[] bits, int i) {
        return (bits[i >> 6] & (1L << i)) == 0;
    }

    private boolean bulkRemove(Predicate<? super E> filter) {
        synchronized(lock) {
            return bulkRemove(filter, 0, getArray().length);
        }
    }

    boolean bulkRemove(Predicate<? super E> filter, int i, int end) {
        // assert Thread.holdsLock(lock);
        final Object[] es = getArray();
        // Optimize for initial run of survivors
        for(; i < end && !filter.test(elementAt(es, i)); i++)
            ;
        if(i < end) {
            final int beg = i;
            final long[] deathRow = nBits(end - beg);
            int deleted = 1;
            deathRow[0] = 1L;   // set bit 0
            for(i = beg + 1; i < end; i++)
                if(filter.test(elementAt(es, i))) {
                    setBit(deathRow, i - beg);
                    deleted++;
                }
            // Did filter reentrantly modify the list?
            if(es != getArray())
                throw new ConcurrentModificationException();
            final Object[] newElts = Arrays.copyOf(es, es.length - deleted);
            int w = beg;
            for(i = beg; i < end; i++)
                if(isClear(deathRow, i - beg))
                    newElts[w++] = es[i];
            System.arraycopy(es, i, newElts, w, es.length - i);
            setArray(newElts);
            return true;
        }else {
            if(es != getArray())
                throw new ConcurrentModificationException();
            return false;
        }
    }

    public void replaceAll(UnaryOperator<E> operator) {
        synchronized(lock) {
            replaceAllRange(operator, 0, getArray().length);
        }
    }

    void replaceAllRange(UnaryOperator<E> operator, int i, int end) {
        // assert Thread.holdsLock(lock);
        Objects.requireNonNull(operator);
        final Object[] es = getArray().clone();
        for(; i < end; i++)
            es[i] = operator.apply(elementAt(es, i));
        setArray(es);
    }

    public void sort(Comparator<? super E> c) {
        synchronized(lock) {
            sortRange(c, 0, getArray().length);
        }
    }

    void sortRange(Comparator<? super E> c, int i, int end) {
        // assert Thread.holdsLock(lock);
        final Object[] es = getArray().clone();
        Arrays.sort(es, i, end, (Comparator<Object>)c);
        setArray(es);
    }

    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        s.defaultWriteObject();

        Object[] es = getArray();
        // Write out array length
        s.writeInt(es.length);

        // Write out all elements in the proper order.
        for(Object element : es)
            s.writeObject(element);
    }

    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {

        s.defaultReadObject();

        // bind to new lock
        resetLock();

        // Read in array length and allocate array
        int len = s.readInt();
        SharedSecrets.getJavaObjectInputStreamAccess().checkArray(s, Object[].class, len);
        Object[] es = new Object[len];

        // Read in all elements in the proper order.
        for(int i = 0; i < len; i++)
            es[i] = s.readObject();
        setArray(es);
    }

    public String toString() {
        return Arrays.toString(getArray());
    }

    public boolean equals(Object o) {
        if(o == this)
            return true;
        if(!(o instanceof List))
            return false;

        List<?> list = (List<?>)o;
        Iterator<?> it = list.iterator();
        for(Object element : getArray())
            if(!it.hasNext() || !Objects.equals(element, it.next()))
                return false;
        return !it.hasNext();
    }

    private static int hashCodeOfRange(Object[] es, int from, int to) {
        int hashCode = 1;
        for(int i = from; i < to; i++) {
            Object x = es[i];
            hashCode = 31 * hashCode + (x == null ? 0 : x.hashCode());
        }
        return hashCode;
    }

    public int hashCode() {
        Object[] es = getArray();
        return hashCodeOfRange(es, 0, es.length);
    }

    public Iterator<E> iterator() {
        return new COWIterator<E>(getArray(), 0);
    }

    public ListIterator<E> listIterator() {
        return new COWIterator<E>(getArray(), 0);
    }

    public ListIterator<E> listIterator(int index) {
        Object[] es = getArray();
        int len = es.length;
        if(index < 0 || index > len)
            throw new IndexOutOfBoundsException(outOfBounds(index, len));

        return new COWIterator<E>(es, index);
    }

    public Spliterator<E> spliterator() {
        return Spliterators.spliterator(getArray(), Spliterator.IMMUTABLE | Spliterator.ORDERED);
    }

    static final class COWIterator<E> implements ListIterator<E> {
        private final Object[] snapshot;

        private int cursor;

        COWIterator(Object[] es, int initialCursor) {
            cursor = initialCursor;
            snapshot = es;
        }

        public boolean hasNext() {
            return cursor < snapshot.length;
        }

        public boolean hasPrevious() {
            return cursor > 0;
        }

        public E next() {
            if(! hasNext())
                throw new NoSuchElementException();
            return (E)snapshot[cursor++];
        }

        public E previous() {
            if(! hasPrevious())
                throw new NoSuchElementException();
            return (E)snapshot[--cursor];
        }

        public int nextIndex() {
            return cursor;
        }

        public int previousIndex() {
            return cursor - 1;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }

        public void set(E e) {
            throw new UnsupportedOperationException();
        }

        public void add(E e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            final int size = snapshot.length;
            int i = cursor;
            cursor = size;
            for(; i < size; i++)
                action.accept(elementAt(snapshot, i));
        }
    }

    public List<E> subList(int fromIndex, int toIndex) {
        synchronized(lock) {
            Object[] es = getArray();
            int len = es.length;
            int size = toIndex - fromIndex;
            if(fromIndex < 0 || toIndex > len || size < 0)
                throw new IndexOutOfBoundsException();
            return new COWSubList(es, fromIndex, size);
        }
    }

    private class COWSubList implements List<E>, RandomAccess {
        private final int offset;
        private int size;
        private Object[] expectedArray;

        COWSubList(Object[] es, int offset, int size) {
            // assert Thread.holdsLock(lock);
            expectedArray = es;
            this.offset = offset;
            this.size = size;
        }

        private void checkForComodification() {
            // assert Thread.holdsLock(lock);
            if(getArray() != expectedArray)
                throw new ConcurrentModificationException();
        }

        private Object[] getArrayChecked() {
            // assert Thread.holdsLock(lock);
            Object[] a = getArray();
            if(a != expectedArray)
                throw new ConcurrentModificationException();
            return a;
        }

        private void rangeCheck(int index) {
            // assert Thread.holdsLock(lock);
            if(index < 0 || index >= size)
                throw new IndexOutOfBoundsException(outOfBounds(index, size));
        }

        private void rangeCheckForAdd(int index) {
            // assert Thread.holdsLock(lock);
            if(index < 0 || index > size)
                throw new IndexOutOfBoundsException(outOfBounds(index, size));
        }

        public Object[] toArray() {
            final Object[] es;
            final int offset;
            final int size;
            synchronized(lock) {
                es = getArrayChecked();
                offset = this.offset;
                size = this.size;
            }
            return Arrays.copyOfRange(es, offset, offset + size);
        }

        public <T> T[] toArray(T[] a) {
            final Object[] es;
            final int offset;
            final int size;
            synchronized(lock) {
                es = getArrayChecked();
                offset = this.offset;
                size = this.size;
            }
            if(a.length < size)
                return (T[]) Arrays.copyOfRange(es, offset, offset + size, a.getClass());
            else{
                System.arraycopy(es, offset, a, 0, size);
                if(a.length > size)
                    a[size] = null;
                return a;
            }
        }

        public int indexOf(Object o) {
            final Object[] es;
            final int offset;
            final int size;
            synchronized(lock) {
                es = getArrayChecked();
                offset = this.offset;
                size = this.size;
            }
            int i = indexOfRange(o, es, offset, offset + size);
            return (i == -1) ? -1 : i - offset;
        }

        public int lastIndexOf(Object o) {
            final Object[] es;
            final int offset;
            final int size;
            synchronized(lock) {
                es = getArrayChecked();
                offset = this.offset;
                size = this.size;
            }
            int i = lastIndexOfRange(o, es, offset, offset + size);
            return (i == -1) ? -1 : i - offset;
        }

        public boolean contains(Object o) {
            return indexOf(o) >= 0;
        }

        public boolean containsAll(Collection<?> c) {
            final Object[] es;
            final int offset;
            final int size;
            synchronized(lock) {
                es = getArrayChecked();
                offset = this.offset;
                size = this.size;
            }
            for(Object o : c)
                if(indexOfRange(o, es, offset, offset + size) < 0)
                    return false;
            return true;
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        public String toString() {
            return Arrays.toString(toArray());
        }

        public int hashCode() {
            final Object[] es;
            final int offset;
            final int size;
            synchronized(lock) {
                es = getArrayChecked();
                offset = this.offset;
                size = this.size;
            }
            return hashCodeOfRange(es, offset, offset + size);
        }

        public boolean equals(Object o) {
            if(o == this)
                return true;
            if(!(o instanceof List))
                return false;
            Iterator<?> it = ((List<?>)o).iterator();

            final Object[] es;
            final int offset;
            final int size;
            synchronized(lock) {
                es = getArrayChecked();
                offset = this.offset;
                size = this.size;
            }

            for(int i = offset, end = offset + size; i < end; i++)
                if(!it.hasNext() || !Objects.equals(es[i], it.next()))
                    return false;
            return !it.hasNext();
        }

        public E set(int index, E element) {
            synchronized(lock) {
                rangeCheck(index);
                checkForComodification();
                E x = CopyOnWriteArrayList.this.set(offset + index, element);
                expectedArray = getArray();
                return x;
            }
        }

        public E get(int index) {
            synchronized(lock) {
                rangeCheck(index);
                checkForComodification();
                return CopyOnWriteArrayList.this.get(offset + index);
            }
        }

        public int size() {
            synchronized(lock) {
                checkForComodification();
                return size;
            }
        }

        public boolean add(E element) {
            synchronized(lock) {
                checkForComodification();
                CopyOnWriteArrayList.this.add(offset + size, element);
                expectedArray = getArray();
                size++;
            }
            return true;
        }

        public void add(int index, E element) {
            synchronized(lock) {
                checkForComodification();
                rangeCheckForAdd(index);
                CopyOnWriteArrayList.this.add(offset + index, element);
                expectedArray = getArray();
                size++;
            }
        }

        public boolean addAll(Collection<? extends E> c) {
            synchronized(lock) {
                final Object[] oldArray = getArrayChecked();
                boolean modified = CopyOnWriteArrayList.this.addAll(offset + size, c);
                size += (expectedArray = getArray()).length - oldArray.length;
                return modified;
            }
        }

        public boolean addAll(int index, Collection<? extends E> c) {
            synchronized(lock) {
                rangeCheckForAdd(index);
                final Object[] oldArray = getArrayChecked();
                boolean modified = CopyOnWriteArrayList.this.addAll(offset + index, c);
                size += (expectedArray = getArray()).length - oldArray.length;
                return modified;
            }
        }

        public void clear() {
            synchronized(lock) {
                checkForComodification();
                removeRange(offset, offset + size);
                expectedArray = getArray();
                size = 0;
            }
        }

        public E remove(int index) {
            synchronized(lock) {
                rangeCheck(index);
                checkForComodification();
                E result = CopyOnWriteArrayList.this.remove(offset + index);
                expectedArray = getArray();
                size--;
                return result;
            }
        }

        public boolean remove(Object o) {
            synchronized(lock) {
                checkForComodification();
                int index = indexOf(o);
                if(index == -1)
                    return false;
                remove(index);
                return true;
            }
        }

        public Iterator<E> iterator() {
            return listIterator(0);
        }

        public ListIterator<E> listIterator() {
            return listIterator(0);
        }

        public ListIterator<E> listIterator(int index) {
            synchronized(lock) {
                checkForComodification();
                rangeCheckForAdd(index);
                return new COWSubListIterator<E>(CopyOnWriteArrayList.this, index, offset, size);
            }
        }

        public List<E> subList(int fromIndex, int toIndex) {
            synchronized(lock) {
                checkForComodification();
                if(fromIndex < 0 || toIndex > size || fromIndex > toIndex)
                    throw new IndexOutOfBoundsException();
                return new COWSubList(expectedArray, fromIndex + offset, toIndex - fromIndex);
            }
        }

        public void forEach(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            int i, end; final Object[] es;
            synchronized(lock) {
                es = getArrayChecked();
                i = offset;
                end = i + size;
            }
            for(; i < end; i++)
                action.accept(elementAt(es, i));
        }

        public void replaceAll(UnaryOperator<E> operator) {
            synchronized(lock) {
                checkForComodification();
                replaceAllRange(operator, offset, offset + size);
                expectedArray = getArray();
            }
        }

        public void sort(Comparator<? super E> c) {
            synchronized(lock) {
                checkForComodification();
                sortRange(c, offset, offset + size);
                expectedArray = getArray();
            }
        }

        public boolean removeAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return bulkRemove(e -> c.contains(e));
        }

        public boolean retainAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return bulkRemove(e -> !c.contains(e));
        }

        public boolean removeIf(Predicate<? super E> filter) {
            Objects.requireNonNull(filter);
            return bulkRemove(filter);
        }

        private boolean bulkRemove(Predicate<? super E> filter) {
            synchronized (lock) {
                final Object[] oldArray = getArrayChecked();
                boolean modified = CopyOnWriteArrayList.this.bulkRemove(filter, offset, offset + size);
                size += (expectedArray = getArray()).length - oldArray.length;
                return modified;
            }
        }

        public Spliterator<E> spliterator() {
            synchronized (lock) {
                return Spliterators.spliterator(
                        getArrayChecked(), offset, offset + size, Spliterator.IMMUTABLE | Spliterator.ORDERED);
            }
        }
    }

    private static class COWSubListIterator<E> implements ListIterator<E> {
        private final ListIterator<E> it;
        private final int offset;
        private final int size;

        COWSubListIterator(List<E> l, int index, int offset, int size) {
            this.offset = offset;
            this.size = size;
            it = l.listIterator(index + offset);
        }

        public boolean hasNext() {
            return nextIndex() < size;
        }

        public E next() {
            if(hasNext())
                return it.next();
            else
                throw new NoSuchElementException();
        }

        public boolean hasPrevious() {
            return previousIndex() >= 0;
        }

        public E previous() {
            if(hasPrevious())
                return it.previous();
            else
                throw new NoSuchElementException();
        }

        public int nextIndex() {
            return it.nextIndex() - offset;
        }

        public int previousIndex() {
            return it.previousIndex() - offset;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }

        public void set(E e) {
            throw new UnsupportedOperationException();
        }

        public void add(E e) {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            while(hasNext())
                action.accept(it.next());
        }
    }

    /** Initializes the lock; for use when deserializing or cloning. */
    private void resetLock() {
        @SuppressWarnings("removal")
        Field lockField = java.security.AccessController.doPrivileged(
                (java.security.PrivilegedAction<Field>) () -> {
                    try {
                        Field f = CopyOnWriteArrayList.class.getDeclaredField("lock");
                        f.setAccessible(true);
                        return f;
                    } catch (ReflectiveOperationException e) {
                        throw new Error(e);
                    }});
        try {
            lockField.set(this, new Object());
        } catch (IllegalAccessException e) {
            throw new Error(e);
        }
    }
}
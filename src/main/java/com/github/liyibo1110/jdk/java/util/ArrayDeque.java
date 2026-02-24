package com.github.liyibo1110.jdk.java.util;

import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 可调整大小数组实现的Deque接口，数组双端队列无容量限制，会根据使用动态扩展。
 * 此类不具备线程安全性，在缺乏外部同步机制时无法支持多线程并发访问。
 * 禁止使用null元素，当作为栈使用，本类性能可能优于Stack，作为队列使用时则优于LinkedList。
 * 多数ArrayDeque操作具有摊销常数时间复杂度，例外情况包括：remove、removeFirstOccurrence、removeLastOccurrence、contains、迭代器、remove() 以及批量操作，这些操作均以线性时间运行。
 *
 * 本类迭代器方法返回的迭代器具有快速失败特性：若迭代器创建后，通过除迭代器自身remove方法外的任何方式修改双端队，迭代器通常会抛出并发修改异常。
 * 因此，面对并发修改时，迭代器会快速干净地失败，而非在未来某个未知的时刻冒着出现任意、非确定性行为的风险。
 *
 * 请注意，迭代器的快速失败行为无法得到保证——因为在存在非同步并发修改的情况下，通常不可能做出任何硬性保证。
 * 快速失败迭代器仅在尽最大努力的基础上抛出 ConcurrentModificationException。
 * 因此，若将程序正确性建立在此异常之上则存在缺陷：迭代器的快速失败行为仅应用于检测错误。
 *
 * 本类以及迭代器实现了Collection和Iterator接口的所有可选方法。
 * @author liyibo
 * @date 2026-02-24 12:21
 */
public class ArrayDeque<E> extends AbstractCollection<E> implements Deque<E>, Cloneable, Serializable {

    /**
     * 底层数组，始终至少包含一个空槽位（位于队尾）。
     */
    transient Object[] elements;

    /**
     * 头部元素的索引（即由remove或pop方法移除的元素），或任意整数0 <= head < elements.length，若双端队列为空则等于tail。
     */
    transient int head;

    /**
     * 下个元素将被添加到队列尾部的索引（通过addLast、add或push实现），元素尾部始终为null。
     */
    transient int tail;

    /** 数组最大容量，某些虚拟机会在数组中预留若干头部字，尝试分配更大数组可能导致内存不足错误。 */
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * 将此队列的容量至少增加指定的数值。
     * @param needed 所需的最低额外容量，必须为正数。
     */
    private void grow(int needed) {
        final int oldCapacity = elements.length;
        int newCapacity;
        // Double capacity if small; else grow by 50%
        int jump = (oldCapacity < 64) ? (oldCapacity + 2) : (oldCapacity >> 1); // 小数组扩2倍，大数组扩1.5倍
        if(jump < needed || (newCapacity = (oldCapacity + jump)) - MAX_ARRAY_SIZE > 0)
            newCapacity = newCapacity(needed, jump);
        final Object[] es = elements = Arrays.copyOf(elements, newCapacity);
        // Exceptionally, here tail == head needs to be disambiguated
        if(tail < head || (tail == head && es[head] != null)) { // 数据重排
            // wrap around; slide first leg forward to end of array
            int newSpace = newCapacity - oldCapacity;
            System.arraycopy(es, head, es, head + newSpace, oldCapacity - head);
            for(int i = head, to = (head += newSpace); i < to; i++)
                es[i] = null;
        }
    }

    /**
     * 边缘条件下的容量计算，特别是溢出情况。
     */
    private int newCapacity(int needed, int jump) {
        final int oldCapacity = elements.length, minCapacity;
        if((minCapacity = oldCapacity + needed) - MAX_ARRAY_SIZE > 0) {
            if(minCapacity < 0)
                throw new IllegalStateException("Sorry, deque too big");
            return Integer.MAX_VALUE;
        }
        if(needed > jump)
            return minCapacity;
        return (oldCapacity + jump - MAX_ARRAY_SIZE < 0)
                ? oldCapacity + jump
                : MAX_ARRAY_SIZE;
    }

    /**
     * 构造一个初始容量足以容纳16个元素的空数组双端队列
     */
    public ArrayDeque() {
        elements = new Object[16 + 1];
    }

    public ArrayDeque(int numElements) {
        elements = new Object[(numElements < 1) ? 1 :
                        (numElements == Integer.MAX_VALUE) ? Integer.MAX_VALUE : numElements + 1];
    }

    public ArrayDeque(Collection<? extends E> c) {
        this(c.size());
        copyElements(c);
    }

    /**
     * 循环递增变量i，取模数值，先决条件与后置条件： 0 <= i < 模数值。
     */
    static final int inc(int i, int modulus) {
        if(++i >= modulus)
            i = 0;
        return i;
    }

    /**
     * 循环递减计数器i，取模运算符表示模数，先决条件与后置条件： 0 <= i < 模数值。
     */
    static final int dec(int i, int modulus) {
        if(--i < 0)
            i = modulus - 1;
        return i;
    }

    static final int inc(int i, int distance, int modulus) {
        if ((i += distance) - modulus >= 0) i -= modulus;
        return i;
    }

    /**
     * 计算i和j之间的环形距离
     */
    static final int sub(int i, int j, int modulus) {
        if ((i -= j) < 0) i += modulus;
        return i;
    }

    static final <E> E elementAt(Object[] es, int i) {
        return (E) es[i];
    }

    static final <E> E nonNullElementAt(Object[] es, int i) {
        @SuppressWarnings("unchecked") E e = (E) es[i];
        if(e == null)
            throw new ConcurrentModificationException();
        return e;
    }

    public void addFirst(E e) {
        if(e == null)
            throw new NullPointerException();
        final Object[] es = elements;
        es[head = dec(head, es.length)] = e;    // head向前（左）移动1格
        if(head == tail)    // 数组是否满了
            grow(1);
    }

    public void addLast(E e) {
        if(e == null)
            throw new NullPointerException();
        final Object[] es = elements;
        es[tail] = e;
        if(head == (tail = inc(tail, es.length)))
            grow(1);
    }

    public boolean addAll(Collection<? extends E> c) {
        final int s, needed;
        if((needed = (s = size()) + c.size() + 1 - elements.length) > 0)
            grow(needed);
        copyElements(c);
        return size() > s;
    }

    private void copyElements(Collection<? extends E> c) {
        c.forEach(this::addLast);
    }

    public boolean offerFirst(E e) {
        addFirst(e);
        return true;
    }

    public boolean offerLast(E e) {
        addLast(e);
        return true;
    }

    public E removeFirst() {
        E e = pollFirst();
        if(e == null)
            throw new NoSuchElementException();
        return e;
    }

    public E removeLast() {
        E e = pollLast();
        if(e == null)
            throw new NoSuchElementException();
        return e;
    }

    public E pollFirst() {
        final Object[] es;
        final int h;
        E e = elementAt(es = elements, h = head);
        if(e != null) {
            es[h] = null;
            head = inc(h, es.length);
        }
        return e;
    }

    public E pollLast() {
        final Object[] es;
        final int t;
        E e = elementAt(es = elements, t = dec(tail, es.length));
        if(e != null)
            es[tail = t] = null;
        return e;
    }

    public E getFirst() {
        E e = elementAt(elements, head);
        if(e == null)
            throw new NoSuchElementException();
        return e;
    }

    public E getLast() {
        final Object[] es = elements;
        E e = elementAt(es, dec(tail, es.length));
        if(e == null)
            throw new NoSuchElementException();
        return e;
    }

    public E peekFirst() {
        return elementAt(elements, head);
    }

    public E peekLast() {
        final Object[] es;
        return elementAt(es = elements, dec(tail, es.length));
    }

    public boolean removeFirstOccurrence(Object o) {
        if(o != null) {
            final Object[] es = elements;
            for(int i = head, end = tail, to = (i <= end) ? end : es.length;
                    ; i = 0, to = end) {
                for(; i < to; i++)
                    if(o.equals(es[i])) {
                        delete(i);
                        return true;
                    }
                if(to == end)
                    break;
            }
        }
        return false;
    }

    public boolean removeLastOccurrence(Object o) {
        if(o != null) {
            final Object[] es = elements;
            for(int i = tail, end = head, to = (i >= end) ? end : 0;
                    ; i = es.length, to = end) {
                for(i--; i > to - 1; i--)
                    if (o.equals(es[i])) {
                        delete(i);
                        return true;
                    }
                if(to == end)
                    break;
            }
        }
        return false;
    }

    // *** Queue methods ***

    public boolean add(E e) {
        addLast(e);
        return true;
    }

    public boolean offer(E e) {
        return offerLast(e);
    }

    public E remove() {
        return removeFirst();
    }

    public E poll() {
        return pollFirst();
    }

    public E element() {
        return getFirst();
    }

    public E peek() {
        return peekFirst();
    }

    // *** Stack methods ***

    public void push(E e) {
        addFirst(e);
    }

    public E pop() {
        return removeFirst();
    }

    boolean delete(int i) {
        final Object[] es = elements;
        final int capacity = es.length;
        final int h, t;
        // number of elements before to-be-deleted elt
        final int front = sub(i, h = head, capacity);
        // number of elements after to-be-deleted elt
        final int back = sub(t = tail, i, capacity) - 1;
        if(front < back) {
            // move front elements forwards
            if(h <= i) {
                System.arraycopy(es, h, es, h + 1, front);
            }else { // Wrap around
                System.arraycopy(es, 0, es, 1, i);
                es[0] = es[capacity - 1];
                System.arraycopy(es, h, es, h + 1, front - (i + 1));
            }
            es[h] = null;
            head = inc(h, capacity);
            return false;
        } else {
            // move back elements backwards
            tail = dec(t, capacity);
            if(i <= tail) {
                System.arraycopy(es, i + 1, es, i, back);
            }else { // Wrap around
                System.arraycopy(es, i + 1, es, i, capacity - (i + 1));
                es[capacity - 1] = es[0];
                System.arraycopy(es, 1, es, 0, t - 1);
            }
            es[tail] = null;
            return true;
        }
    }

    // *** Collection Methods ***

    public int size() {
        return sub(tail, head, elements.length);
    }

    public boolean isEmpty() {
        return head == tail;
    }

    public Iterator<E> iterator() {
        return new DeqIterator();
    }

    public Iterator<E> descendingIterator() {
        return new DescendingIterator();
    }

    private class DeqIterator implements Iterator<E> {
        int cursor;
        int remaining = size();
        int lastRet = -1;

        DeqIterator() {
            cursor = head;
        }

        public final boolean hasNext() {
            return remaining > 0;
        }

        public E next() {
            if(remaining <= 0)
                throw new NoSuchElementException();
            final Object[] es = elements;
            E e = nonNullElementAt(es, cursor);
            cursor = inc(lastRet = cursor, es.length);
            remaining--;
            return e;
        }

        void postDelete(boolean leftShifted) {
            if(leftShifted)
                cursor = dec(cursor, elements.length);
        }

        public final void remove() {
            if(lastRet < 0)
                throw new IllegalStateException();
            postDelete(delete(lastRet));
            lastRet = -1;
        }

        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            int r;
            if((r = remaining) <= 0)
                return;
            remaining = 0;
            final Object[] es = elements;
            if(es[cursor] == null || sub(tail, cursor, es.length) != r)
                throw new ConcurrentModificationException();
            for(int i = cursor, end = tail, to = (i <= end) ? end : es.length;
                    ; i = 0, to = end) {
                for(; i < to; i++)
                    action.accept(elementAt(es, i));
                if(to == end) {
                    if(end != tail)
                        throw new ConcurrentModificationException();
                    lastRet = dec(end, es.length);
                    break;
                }
            }
        }
    }

    private class DescendingIterator extends DeqIterator {
        DescendingIterator() {
            cursor = dec(tail, elements.length);
        }

        public final E next() {
            if(remaining <= 0)
                throw new NoSuchElementException();
            final Object[] es = elements;
            E e = nonNullElementAt(es, cursor);
            cursor = dec(lastRet = cursor, es.length);
            remaining--;
            return e;
        }

        void postDelete(boolean leftShifted) {
            if(!leftShifted)
                cursor = inc(cursor, elements.length);
        }

        public final void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            int r;
            if((r = remaining) <= 0)
                return;
            remaining = 0;
            final Object[] es = elements;
            if(es[cursor] == null || sub(cursor, head, es.length) + 1 != r)
                throw new ConcurrentModificationException();
            for(int i = cursor, end = head, to = (i >= end) ? end : 0;
                    ; i = es.length - 1, to = end) {
                // hotspot generates faster code than for: i >= to !
                for(; i > to - 1; i--)
                    action.accept(elementAt(es, i));
                if(to == end) {
                    if(end != head)
                        throw new ConcurrentModificationException();
                    lastRet = end;
                    break;
                }
            }
        }
    }

    public Spliterator<E> spliterator() {
        return new DeqSpliterator();
    }

    final class DeqSpliterator implements Spliterator<E> {
        private int fence;      // -1 until first use
        private int cursor;     // current index, modified on traverse/split

        /** Constructs late-binding spliterator over all elements. */
        DeqSpliterator() {
            this.fence = -1;
        }

        /** Constructs spliterator over the given range. */
        DeqSpliterator(int origin, int fence) {
            // assert 0 <= origin && origin < elements.length;
            // assert 0 <= fence && fence < elements.length;
            this.cursor = origin;
            this.fence = fence;
        }

        /** Ensures late-binding initialization; then returns fence. */
        private int getFence() { // force initialization
            int t;
            if ((t = fence) < 0) {
                t = fence = tail;
                cursor = head;
            }
            return t;
        }

        public DeqSpliterator trySplit() {
            final Object[] es = elements;
            final int i, n;
            return ((n = sub(getFence(), i = cursor, es.length) >> 1) <= 0)
                    ? null
                    : new DeqSpliterator(i, cursor = inc(i, n, es.length));
        }

        public void forEachRemaining(Consumer<? super E> action) {
            if (action == null)
                throw new NullPointerException();
            final int end = getFence(), cursor = this.cursor;
            final Object[] es = elements;
            if (cursor != end) {
                this.cursor = end;
                // null check at both ends of range is sufficient
                if (es[cursor] == null || es[dec(end, es.length)] == null)
                    throw new ConcurrentModificationException();
                for (int i = cursor, to = (i <= end) ? end : es.length;
                        ; i = 0, to = end) {
                    for (; i < to; i++)
                        action.accept(elementAt(es, i));
                    if (to == end) break;
                }
            }
        }

        public boolean tryAdvance(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            final Object[] es = elements;
            if (fence < 0) { fence = tail; cursor = head; } // late-binding
            final int i;
            if ((i = cursor) == fence)
                return false;
            E e = nonNullElementAt(es, i);
            cursor = inc(i, es.length);
            action.accept(e);
            return true;
        }

        public long estimateSize() {
            return sub(getFence(), cursor, elements.length);
        }

        public int characteristics() {
            return Spliterator.NONNULL
                    | Spliterator.ORDERED
                    | Spliterator.SIZED
                    | Spliterator.SUBSIZED;
        }
    }

    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        final Object[] es = elements;
        for (int i = head, end = tail, to = (i <= end) ? end : es.length;
                ; i = 0, to = end) {
            for (; i < to; i++)
                action.accept(elementAt(es, i));
            if (to == end) {
                if (end != tail) throw new ConcurrentModificationException();
                break;
            }
        }
    }

    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        return bulkRemove(filter);
    }

    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> c.contains(e));
    }

    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> !c.contains(e));
    }

    private boolean bulkRemove(Predicate<? super E> filter) {
        final Object[] es = elements;
        // Optimize for initial run of survivors
        for (int i = head, end = tail, to = (i <= end) ? end : es.length;
                ; i = 0, to = end) {
            for (; i < to; i++)
                if (filter.test(elementAt(es, i)))
                    return bulkRemoveModified(filter, i);
            if (to == end) {
                if (end != tail) throw new ConcurrentModificationException();
                break;
            }
        }
        return false;
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

    private boolean bulkRemoveModified(
            Predicate<? super E> filter, final int beg) {
        final Object[] es = elements;
        final int capacity = es.length;
        final int end = tail;
        final long[] deathRow = nBits(sub(end, beg, capacity));
        deathRow[0] = 1L;   // set bit 0
        for (int i = beg + 1, to = (i <= end) ? end : es.length, k = beg;
                ; i = 0, to = end, k -= capacity) {
            for (; i < to; i++)
                if (filter.test(elementAt(es, i)))
                    setBit(deathRow, i - k);
            if (to == end) break;
        }
        // a two-finger traversal, with hare i reading, tortoise w writing
        int w = beg;
        for (int i = beg + 1, to = (i <= end) ? end : es.length, k = beg;
                ; w = 0) { // w rejoins i on second leg
            // In this loop, i and w are on the same leg, with i > w
            for (; i < to; i++)
                if (isClear(deathRow, i - k))
                    es[w++] = es[i];
            if (to == end) break;
            // In this loop, w is on the first leg, i on the second
            for (i = 0, to = end, k -= capacity; i < to && w < capacity; i++)
                if (isClear(deathRow, i - k))
                    es[w++] = es[i];
            if (i >= to) {
                if (w == capacity) w = 0; // "corner" case
                break;
            }
        }
        if (end != tail) throw new ConcurrentModificationException();
        circularClear(es, tail = w, end);
        return true;
    }

    public boolean contains(Object o) {
        if (o != null) {
            final Object[] es = elements;
            for (int i = head, end = tail, to = (i <= end) ? end : es.length;
                    ; i = 0, to = end) {
                for (; i < to; i++)
                    if (o.equals(es[i]))
                        return true;
                if (to == end) break;
            }
        }
        return false;
    }

    public boolean remove(Object o) {
        return removeFirstOccurrence(o);
    }

    public void clear() {
        circularClear(elements, head, tail);
        head = tail = 0;
    }

    private static void circularClear(Object[] es, int i, int end) {
        // assert 0 <= i && i < es.length;
        // assert 0 <= end && end < es.length;
        for(int to = (i <= end) ? end : es.length;
                ; i = 0, to = end) {
            for(; i < to; i++) es[i] = null;
            if(to == end)
                break;
        }
    }

    public Object[] toArray() {
        return toArray(Object[].class);
    }

    private <T> T[] toArray(Class<T[]> klazz) {
        final Object[] es = elements;
        final T[] a;
        final int head = this.head, tail = this.tail, end;
        if((end = tail + ((head <= tail) ? 0 : es.length)) >= 0) {
            // Uses null extension feature of copyOfRange
            a = Arrays.copyOfRange(es, head, end, klazz);
        }else {
            // integer overflow!
            a = Arrays.copyOfRange(es, 0, end - head, klazz);
            System.arraycopy(es, head, a, 0, es.length - head);
        }
        if(end != tail)
            System.arraycopy(es, 0, a, es.length - head, tail);
        return a;
    }

    public <T> T[] toArray(T[] a) {
        final int size;
        if((size = size()) > a.length)
            return toArray((Class<T[]>) a.getClass());
        final Object[] es = elements;
        for(int i = head, j = 0, len = Math.min(size, es.length - i);
                ; i = 0, len = tail) {
            System.arraycopy(es, i, a, j, len);
            if((j += len) == size)
                break;
        }
        if(size < a.length)
            a[size] = null;
        return a;
    }

    // *** Object methods ***

    public ArrayDeque<E> clone() {
        try {
            @SuppressWarnings("unchecked")
            ArrayDeque<E> result = (ArrayDeque<E>)super.clone();
            result.elements = Arrays.copyOf(elements, elements.length);
            return result;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }

    @java.io.Serial
    private static final long serialVersionUID = 2340985798034038923L;

    @java.io.Serial
    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        s.defaultWriteObject();

        // Write out size
        s.writeInt(size());

        // Write out elements in order.
        final Object[] es = elements;
        for(int i = head, end = tail, to = (i <= end) ? end : es.length;
                ; i = 0, to = end) {
            for(; i < to; i++)
                s.writeObject(es[i]);
            if (to == end)
                break;
        }
    }

    @java.io.Serial
    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();

        // Read in size and allocate array
        int size = s.readInt();
        SharedSecrets.getJavaObjectInputStreamAccess().checkArray(s, Object[].class, size + 1);
        elements = new Object[size + 1];
        this.tail = size;

        // Read in all elements in the proper order.
        for(int i = 0; i < size; i++)
            elements[i] = s.readObject();
    }

    /** debugging */
    void checkInvariants() {
        // Use head and tail fields with empty slot at tail strategy.
        // head == tail disambiguates to "empty".
        try {
            int capacity = elements.length;
            // assert 0 <= head && head < capacity;
            // assert 0 <= tail && tail < capacity;
            // assert capacity > 0;
            // assert size() < capacity;
            // assert head == tail || elements[head] != null;
            // assert elements[tail] == null;
            // assert head == tail || elements[dec(tail, capacity)] != null;
        } catch (Throwable t) {
            System.err.printf("head=%d tail=%d capacity=%d%n", head, tail, elements.length);
            System.err.printf("elements=%s%n", Arrays.toString(elements));
            throw t;
        }
    }
}

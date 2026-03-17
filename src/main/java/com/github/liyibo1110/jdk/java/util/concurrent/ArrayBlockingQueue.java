package com.github.liyibo1110.jdk.java.util.concurrent;

import java.io.Serializable;
import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于数组的有界阻塞队列，使用了单锁 + 两个条件变量实现生产者/消费者模型，关键特性为：
 * 1、容量固定：构造时必须提供容量大小，且不能扩容扩容。
 * 队列满：put()会阻塞。
 * 队列空：take()会阻塞。
 * 2、底层是个循环数组（ring buffer），数组会被循环使用，通过两个指针来维护：
 * takeIndex：下一个取元素的位置。
 * putIndex：下一个放元素的位置。
 * 3、只有一把锁（ReentrantLock），因此注意put和take会竞争同一把锁，因此ArrayBlockingQueue结构更简单，但锁竞争更高。
 * 4、两个Condition：
 * notEmpty：队列非空，当队列已空，take则会在notEmpty上面await。
 * notFull：队列未满，当队列已满，put则会在notFull上面await。
 * 5、支持公平锁，因为锁用的是ReentrantLock，代价是性能下降。
 * @author liyibo
 * @date 2026-03-16 17:48
 */
public class ArrayBlockingQueue<E> extends AbstractQueue implements BlockingQueue<E>, Serializable {
    private static final long serialVersionUID = -817911632652898426L;

    /** 底层循环数组 */
    final Object[] items;

    /** 下一个取元素的位置 */
    int takeIndex;

    /** 下一个放元素的位置 */
    int putIndex;

    /** 当前元素数量，这个要注意它是用来区分：队列空还是队列满，因为两种情况都是takeIndex == putIndex，没有count则无法分辨 */
    int count;

    final ReentrantLock lock;

    /** 队列有元素 */
    private final Condition notEmpty;

    /** 队列有空位 */
    private final Condition notFull;

    /**
     * 注意iterator需要修正自己的index
     */
    transient Itrs itrs;

    // Internal helper methods

    /**
     * 给定的下标i，先递增，再和给定的modulus取模。
     */
    static final int inc(int i, int modulus) {
        if(++i >= modulus)
            i = 0;
        return i;
    }

    /**
     * 给定的下标i，先递减，再和给定的modulus取模。
     */
    static final int dec(int i, int modulus) {
        if(--i < 0)
            i = modulus - 1;
        return i;
    }

    final E itemAt(int i) {
        return (E) items[i];
    }

    static <E> E itemAt(Object[] items, int i) {
        return (E) items[i];
    }

    /**
     * 注意获取了锁才能调用这个内部方法，因此内部都是线程安全的代码。
     */
    private void enqueue(E e) {
        // assert lock.isHeldByCurrentThread();
        // assert lock.getHoldCount() == 1;
        // assert items[putIndex] == null;
        final Object[] items = this.items;
        items[putIndex] = e;
        if(++putIndex == items.length)  // 到头了就回到下标0
            putIndex = 0;
        count++;
        notEmpty.signal();
    }

    /**
     * 注意获取了锁才能调用这个内部方法，因此内部都是线程安全的代码。
     */
    private E dequeue() {
        // assert lock.isHeldByCurrentThread();
        // assert lock.getHoldCount() == 1;
        // assert items[takeIndex] != null;
        final Object[] items = this.items;
        E e = (E) items[takeIndex];
        items[takeIndex] = null;
        if(++takeIndex == items.length) // 到头了就回到下标0
            takeIndex = 0;
        count--;
        if(itrs != null)
            itrs.elementDequeued();
        notFull.signal();
        return e;
    }

    void removeAt(final int removeIndex) {
        // assert lock.isHeldByCurrentThread();
        // assert lock.getHoldCount() == 1;
        // assert items[removeIndex] != null;
        // assert removeIndex >= 0 && removeIndex < items.length;
        final Object[] items = this.items;
        if(removeIndex == takeIndex) {  // 要移除的如果正好的下一个要返回的，即头元素，那就不用再遍历找了，相当于dequeue方法
            items[takeIndex] = null;
            if(++takeIndex == items.length)
                takeIndex = 0;
            count--;
            if(itrs != null)
                itrs.elementDequeued();
        }else { // 要移除的在队列中间，需要遍历，同时要把后面的元素都往前移动一格，来填补删除的空洞，并更新putIndex
            /**
             * 难点，循环做了3件事：
             * 1、从removeIndex作为起点。
             * 2、不断把后一个元素复制到前一个位置。
             * 3、直到移动到putIndex之前。
             */
            for(int i = removeIndex, putIndex = this.putIndex; ;) {
                int pred = i;   // i就是当前要被移动的元素的位置，pred就是当前需要填补的位置（hole position）
                if(++i == items.length) // 将i移动到下一个元素
                    i = 0;
                /**
                 * 判断是否到达了putIndex，注意putIndex位置一定是null（因为它永远指向下一个要插入的位置），说明到队尾了。
                 * 这时候要把最后一个位置清空，并更新putIndex，最后退出。
                 */
                if(i == putIndex) {
                    items[pred] = null;
                    this.putIndex = pred;
                    break;
                }
                /**
                 * 走到这里说明还没有找到队尾，要把当前元素复制到前一个位置
                 */
                items[pred] = items[i];
            }
            count--;
            if(itrs != null)
                itrs.removedAt(removeIndex);
        }
        notFull.signal();
    }

    public ArrayBlockingQueue(int capacity) {
        this(capacity, false);
    }

    public ArrayBlockingQueue(int capacity, boolean fair) {
        if(capacity <= 0)
            throw new IllegalArgumentException();
        this.items = new Object[capacity];
        lock = new ReentrantLock(fair);
        notEmpty = lock.newCondition();
        notFull =  lock.newCondition();
    }

    public ArrayBlockingQueue(int capacity, boolean fair, Collection<? extends E> c) {
        this(capacity, fair);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Object[] items = this.items;
            int i = 0;
            try {
                // 遍历添加到items里（注意是从下标0开始直接添加），越界了就直接抛异常
                for(E e : c)
                    items[i++] = Objects.requireNonNull(e);
            } catch (ArrayIndexOutOfBoundsException ex) {
                throw new IllegalArgumentException();
            }
            count = i;
            putIndex = (i == capacity) ? 0 : i;
        } finally {
            lock.unlock();
        }
    }

    public boolean add(E e) {
        return super.add(e);
    }

    public boolean offer(E e) {
        Objects.requireNonNull(e);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if(count == items.length) {
                return false;
            }else {
                enqueue(e);
                return true;
            }
        } finally {
            lock.unlock();
        }
    }

    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while(count == items.length)
                notFull.wait();
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while(count == items.length) {
                if(nanos <= 0L)
                    return false;
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count == 0 ? null : dequeue();
        } finally {
            lock.unlock();
        }
    }

    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while(count == 0)
                notEmpty.await();
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while(count == 0) {
                if(nanos <= 0L)
                    return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    public E peek() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return itemAt(takeIndex);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    public int remainingCapacity() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return items.length - count;
        } finally {
            lock.unlock();
        }
    }

    public boolean remove(Object o) {
        if (o == null)
            return false;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if(count > 0) {
                final Object[] items = this.items;
                /**
                 * 不是遍历整个数组，而是遍历当前有效队列的区间，可能是连续的，也可能被数组尾部切成了两段。
                 * 1、[A, B, C, B, null, null]这种，直接扫就行。
                 * 2、[D, E, null, A, B, C]这种，实际要分成2轮扫描：[3, 6)和[0, 2)。
                 * 外层这个for就是干这个的：
                 * i：当前扫描起点
                 * end：当前队列尾后位置，也就是有效区间的结束边界。
                 * to：当前这一段扫描的结束位置。
                 * 分2种情况：
                 * 1、takeIndex < putIndex：说明有效区间是连续的，直接从i扫描到end即可。
                 * 2、takeIndex >= putIndex：说明队列发生了环绕，第一段先扫到数组末尾，之后再从0扫到putIndex
                 * 注意for的最后一部分，如果第一段扫完，如果还要第二轮，就把i设为0，to设为putIndex再来一轮。
                 */
                for(int i = takeIndex, end = putIndex, to = (i < end) ? end : items.length; ; i = 0, to = end) {
                    /**
                     * 内层在[i, to)中逐个比较
                     */
                    for(; i < to; i++)
                        if(o.equals(items[i])) {
                            removeAt(i);
                            return true;
                        }
                    if(to == end) // 不再扫描，要么扫一轮，要么扫两轮
                        break;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 和remove方法逻辑是一样的
     */
    public boolean contains(Object o) {
        if(o == null)
            return false;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if(count > 0) {
                final Object[] items = this.items;
                for(int i = takeIndex, end = putIndex, to = (i < end) ? end : items.length; ; i = 0, to = end) {
                    for(; i < to; i++)
                        if(o.equals(items[i]))
                            return true;
                    if (to == end)
                        break;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    public Object[] toArray() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Object[] items = this.items;
            final int end = takeIndex + count;
            final Object[] a = Arrays.copyOfRange(items, takeIndex, end);   // 如果有环绕，这一步会先复制到数组尾部
            if(end != putIndex) // 如果有环绕，这里会继续从数组头部复制到putIndex
                System.arraycopy(items, 0, a, items.length - takeIndex, putIndex);
        } finally {
            lock.unlock();
        }
    }

    public <T> T[] toArray(T[] a) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Object[] items = this.items;
            final int count = this.count;
            final int firstLeg = Math.min(items.length - takeIndex, count);
            if(a.length < count) {
                a = (T[]) Arrays.copyOfRange(items, takeIndex, takeIndex + count, a.getClass());
            }else {
                System.arraycopy(items, takeIndex, a, 0, firstLeg);
                if(a.length > count)
                    a[count] = null;
            }
            if(firstLeg < count)
                System.arraycopy(items, 0, a, firstLeg, putIndex);
            return a;
        } finally {
            lock.unlock();
        }
    }

    public String toString() {
        return Helpers.collectionToString(this);
    }

    public void clear() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int k;
            if((k = count) > 0) {
                circularClear(items, takeIndex, putIndex);  // 清空数组
                takeIndex = putIndex;   // 两个index指向同一个位置
                count = 0;
                if(itrs != null)
                    itrs.queueIsEmpty();
                for(; k > 0 && lock.hasWaiters(notFull); k--)
                    notFull.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 和remove、contain的扫描方式相同，把所有的有效元素全改成null
     */
    private static void circularClear(Object[] items, int i, int end) {
        // assert 0 <= i && i < items.length;
        // assert 0 <= end && end < items.length;
        for(int to = (i < end) ? end : items.length; ; i = 0, to = end) {
            for(; i < to; i++)
                items[i] = null;
            if(to == end)
                break;
        }
    }
}

package com.github.liyibo1110.jdk.java.util.concurrent;

import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
                /**
                 * 最多唤醒k个等待put的线程（没有用signalAll唤醒全部）
                 */
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

    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    public int drainTo(Collection<? super E> c, int maxElements) {
        Objects.requireNonNull(c);
        if(c == this)
            throw new IllegalArgumentException();
        if(maxElements <= 0)
            return 0;
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = Math.min(maxElements, count);
            int take = takeIndex;
            int i = 0;
            try {
                while(i < n) {
                    E e = (E)items[take];
                    c.add(e);
                    items[take] = null;
                    if(++take == items.length)  // 到头了就指向数组开头
                        take = 0;
                    i++;
                }
                return n;
            } finally { // 后续清理
                if(i > 0) {
                    count -= i;
                    takeIndex = take;
                    if(itrs != null) {
                        if(count == 0)
                            itrs.queueIsEmpty();
                        else if(i > take)
                            itrs.takeIndexWrapped();
                    }
                    for(; i > 0 && lock.hasWaiters(notFull); i--)
                        notFull.signal();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public Iterator<E> iterator() {
        return new Itr();
    }

    /**
     * 所有活跃的迭代器的协调器，负责：
     * 1、感知队列结构变化。
     * 2、把变化通知给所有迭代器。
     * 3、清理失效的迭代器。
     */
    class Itrs {
        private class Node extends WeakReference<Itr> {
            Node next;

            Node(Itr iterator, Node next) {
                super(iterator);
                this.next = next;
            }
        }

        /** takeIndex绕回0的总次数，注意各个迭代器用的cycles，用的就是这个字段 */
        int cycles;

        /** 迭代器链表头 */
        private Node head;

        /** 用来分段扫描链表的指针，避免每次都要从表头扫 */
        private Node sweeper;

        /** 控制扫链表要走多少步 */
        private static final int SHORT_SWEEP_PROBES = 4;
        private static final int LONG_SWEEP_PROBES = 16;

        Itrs(Itr initial) {
            register(initial);
        }

        /**
         * 非常复杂，作用是：清理链表中无效的迭代器，无效指的是：
         * 1、iter == null：说明被GC回收了。
         * 2、iter.isDetached() == true：不再进行跟踪了。
         */
        void doSomeSweeping(boolean tryHarder) {
            // assert lock.isHeldByCurrentThread();
            // assert head != null;

            /**
             * 控制扫描力度，普通扫4次，强力扫16次。
             * 1、新建迭代器：普通。
             * 2、删除迭代器：强力。
             **/
            int probes = tryHarder ? LONG_SWEEP_PROBES : SHORT_SWEEP_PROBES;
            Node o; // p的前驱
            Node p; // 当前正在检查的节点
            final Node sweeper = this.sweeper;  // sweeper作用是：记录上次扫到了哪里，避免每次都要从头扫
            boolean passedGo;   // 限制本次扫描最多只绕链表一整圈

            /**
             * 没有sweeper，说明是第一次清理，只能从头开始扫
             */
            if(sweeper == null) {
                o = null;
                p = head;
                passedGo = true;    // 这次本来是从head开始的，所以不允许绕回head再扫了
            }else {
                o = sweeper;
                p = o.next;
                passedGo = false;   // 这次是从中间开始的，允许绕回head再扫
            }

            /**
             * 开始扫描固定步数，每次循环做：
             * 1、处理走到链表尾部的情况。
             * 2、取出当前节点的iter。
             * 3、判断当前节点是不是stale。
             * 4、如果是旧删除，不是继续往后。
             */
            for(; probes > 0; probes--) {
                /**
                 * 说明已经扫描链表尾了。
                 */
                if(p == null) {
                    if(passedGo)   // 之前已经绕回head了，直接退出
                        break;
                    // 到链表尾了，但还没绕回head，标记passedGo后继续从head扫描
                    o = null;
                    p = head;
                    passedGo = true;
                }
                // 开始处理特定node的iter
                final Itr it = p.get();
                final Node next = p.next;
                if(it == null || it.isDetached()) { // 需要清理
                    probes = LONG_SWEEP_PROBES; // 优化操作，加大扫描力度，已经发现垃圾了，要加强扫描，注意是改成继续16次
                    // unlink p
                    p.clear();
                    p.next = null;
                    if(o == null) { // 说明p是头节点
                        head = next;
                        if(next == null) {  // 如果链表全空了，直接清理itrs
                            itrs = null;
                            return;
                        }
                    }else { // p不是头节点，就把p的下一个给前驱的next
                        o.next = next;
                    }
                }else {
                    o = p;
                }
                p = next;
            }

            this.sweeper = (p == null) ? null : o;  // 最终返回扫描后的sweeper
        }

        /**
         * 后来的会成为head（即头插）
         * @param itr
         */
        void register(Itr itr) {
            // assert lock.isHeldByCurrentThread();
            head = new Node(itr, head);
        }

        void takeIndexWrapped() {
            // assert lock.isHeldByCurrentThread();
            cycles++;   // 圈数加1
            for(Node o = null, p = head; p != null;) {
                final Itr it = p.get();
                final Node next = p.next;
                if(it == null || it.takeIndexWrapped()) {   // 如果迭代器为null，或者已经落后超过一整圈了，则放弃
                    // unlink p
                    // assert it == null || it.isDetached();
                    p.clear();
                    p.next = null;
                    if(o == null)
                        head = next;
                    else
                        o.next = next;
                }else {
                    o = p;
                }
                p = next;
            }
            if(head == null)   // no more iterators to track
                itrs = null;
        }

        /**
         * 对每个迭代器执行removedIndex，相当于广播 + 顺便清理垃圾迭代器。
         */
        void removedAt(int removedIndex) {
            for(Node o = null, p = head; p != null;) {
                final Itr it = p.get();
                final Node next = p.next;
                if(it == null || it.removedAt(removedIndex)) {
                    // unlink p
                    // assert it == null || it.isDetached();
                    p.clear();
                    p.next = null;
                    if (o == null)
                        head = next;
                    else
                        o.next = next;
                }else {
                    o = p;
                }
                p = next;
            }
            if(head == null)   // no more iterators to track
                itrs = null;
        }

        void queueIsEmpty() {
            // assert lock.isHeldByCurrentThread();
            for(Node p = head; p != null; p = p.next) {
                Itr it = p.get();
                if(it != null) {
                    p.clear();
                    it.shutdown();
                }
            }
            head = null;
            itrs = null;
        }

        /**
         * 当调用dequeue会调用这个回调
         */
        void elementDequeued() {
            // assert lock.isHeldByCurrentThread();
            if(count == 0)  // 队列本身为空
                queueIsEmpty();
            else if(takeIndex == 0) // takeIndex回到了0，发生环绕
                takeIndexWrapped();
        }
    }

    /**
     * 挺复杂的一个迭代器实现，之所以复杂，主要面对的问题是：在一个会并发修改的环形数组上，如何提供尽量正确的迭代器。
     * 这个迭代器基于弱一致性，这意味着：
     * 允许迭代过程中并发修改，不会抛ConcurrentModificationException，但要尽量保证不乱、不重复、不越界。
     *
     * 要解决的三大问题：
     * 1、元素被take()移除：即头部变化。
     * 2、元素被removeAt()删除：后面的元素会往前面移动。
     * 3、数组会发生环绕：takeIndex会回到0。
     *
     * 运行模式：
     * 1、初始时：基于当前队列状态建立一个遍历起点。
     * 2、每次操作前：通过incorporateDequeues()来修正位置。
     * 3、如果发现自己跟不上了：调用detach()放弃精准跟踪。
     */
    private class Itr implements Iterator<E> {

        /** 下一次要去尝试获取的位置，即扫描指针 */
        private int cursor;

        /** 下一次要返回的元素值（提前缓存） */
        private E nextItem;

        /** nextItem对应的index */
        private int nextIndex;

        /** 上一次next()返回的元素值，用于detached操作 */
        private E lastItem;

        /** 上一次next()返回的元素位置，用于remove操作 */
        private int lastRet;

        /**
         * 一致性校验核心，非常重要的字段。
         * 上一次操作时的takeIndex
         */
        private int prevTakeIndex;

        /**
         * 一致性校验核心，非常重要的字段。
         * 环绕次数（来自itrs.cycles），和上面的prevTakeIndex组合使用，
         * 用来计算：自上次访问以来，发生了多少次dequeue操作。
         */
        private int prevCycles;

        /** 没有值 / 结束 */
        private static final int NONE = -1;

        /** 这个位置的元素已经被其它线程移除了 */
        private static final int REMOVED = -2;

        /** 迭代器已经脱离队列跟踪（很重要的一个特殊状态） */
        private static final int DETACHED = -3;

        Itr() {
            lastRet = NONE;
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                if(count == 0) {    // 队列没元素
                    // assert itrs == null;
                    cursor = NONE;
                    nextIndex = NONE;
                    prevTakeIndex = DETACHED;
                }else {
                    final int takeIndex = ArrayBlockingQueue.this.takeIndex;
                    prevTakeIndex = takeIndex;
                    nextItem = itemAt(nextIndex = takeIndex);
                    cursor = incCursor(takeIndex);  // 直接指向下一个元素了
                    if(itrs == null) {
                        itrs = new Itrs(this);
                    }else {
                        itrs.register(this);
                        itrs.doSomeSweeping(false);
                    }
                    prevCycles = itrs.cycles;
                }
                // assert takeIndex >= 0;
                // assert prevTakeIndex == takeIndex;
                // assert nextIndex >= 0;
                // assert nextItem != null;
            } finally {
                lock.unlock();
            }
        }

        boolean isDetached() {
            // assert lock.isHeldByCurrentThread();
            return prevTakeIndex < 0;
        }

        private int incCursor(int index) {
            // assert lock.isHeldByCurrentThread();
            if(++index == items.length)
                index = 0;
            if(index == putIndex)   // 迭代结束
                index = NONE;
            return index;
        }

        /**
         * 判断给定index的元素，是否已经被dequeue了。
         */
        private boolean invalidated(int index, int prevTakeIndex, long dequeues, int length) {
            if(index < 0)
                return false;
            int distance = index - prevTakeIndex;   // prevTakeIndex到index的距离
            if(distance < 0)    // 环绕了，即index在prevTakeIndex的左边
                distance += length;
            return dequeues > distance; // 为true说明index的元素已经没有了，已经队头已经走过了我
        }

        /**
         * 整个Itr最重要的方法：计算从上次操作到现在，队列中发生了多少次dequeue，公式为：
         * long dequeues = (cycles - prevCycles) * len + (takeIndex - prevTakeIndex)
         * 然后判断cursor、nextIndex、lastRet是否失效了。
         */
        private void incorporateDequeues() {
            // assert lock.isHeldByCurrentThread();
            // assert itrs != null;
            // assert !isDetached();
            // assert count > 0;
            final int cycles = itrs.cycles;
            final int takeIndex = ArrayBlockingQueue.this.takeIndex;
            final int prevCycles = this.prevCycles;
            final int prevTakeIndex = this.prevTakeIndex;

            if(cycles != prevCycles || takeIndex != prevTakeIndex) {
                final int len = items.length;
                long dequeues = (long)(cycles - prevCycles) * len + (takeIndex - prevTakeIndex);
                // 检查下标是否生效

                // 上一次返回的元素，如果被删，标记为REMOVED
                if(invalidated(lastRet, prevTakeIndex, dequeues, len))
                    lastRet = REMOVED;
                // 下一次要返回的元素，如果被删，标记为REMOVED
                if(invalidated(nextIndex, prevTakeIndex, dequeues, len))
                    nextIndex = REMOVED;
                // 下一次扫描的位置，如果被删，则直接跳到当前队头，即重新从队头开始扫描
                if(invalidated(cursor, prevTakeIndex, dequeues, len))
                    cursor = takeIndex;

                // 记录的所有位置都没用了，放弃跟踪
                if(cursor < 0 && nextIndex < 0 && lastRet < 0)
                    detach();
                else{   // 否则更新基准，为下一次计算做准备
                    this.prevCycles = cycles;
                    this.prevTakeIndex = takeIndex;
                }
            }
        }

        private void detach() {
            // Switch to detached mode
            // assert lock.isHeldByCurrentThread();
            // assert cursor == NONE;
            // assert nextIndex < 0;
            // assert lastRet < 0 || nextItem == null;
            // assert lastRet < 0 ^ lastItem != null;
            if(prevTakeIndex >= 0) {
                // assert itrs != null;
                prevTakeIndex = DETACHED;
                // try to unlink from itrs (but not too hard)
                itrs.doSomeSweeping(true);
            }
        }

        public boolean hasNext() {
            // 先走fast-path，不确定时才调用noNext走加锁确认路径，只要nextItem不为空，就一定还有剩余元素
            if(nextItem != null)
                return true;
            // 到这里要么真的没有元素了，要么因为并发导致状态不确定，需要额外处理
            noNext();
            return false;
        }

        /**
         * 最终确认以及收尾处理，只有当nextItem为null才会进来。
         * noNext的最终效果是：
         * 1、已经结束（hasNext = false）
         * 2、但仍然允许remove来删除最后一个元素。
         * 3、并且这个删除是安全的。
         * 可以想象成：我已经走到队列末尾了，但我要把最后一个元素单独保存下来，以防用户还要remove。
         */
        private void noNext() {
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                // assert cursor == NONE;
                // assert nextIndex == NONE;
                if(!isDetached()) { // 是否还在跟踪队列，如果不跟踪了，就直接结束方法，否则进入
                    incorporateDequeues();  // 里面会尝试把lastRet改成REMOVED（负数）
                    /**
                     * 涉及细节流程了，假定以下操作场景：
                     * 1、刚调用了next，并且返回的是最后一个元素，此时nextItem为null，所以hasNext会返回false。
                     * 2、如果又调用了remove，根据迭代器规范，应该删除最后next返回的那个元素。
                     * 3、但是可能并发了dequeue/removeAt之类的操作，使得lastRet在此时失效。
                     * 因此会先保存最后一个元素的值，即使后面位置变了，也能靠value来判断，并且不再跟踪队列变化，
                     * detach之后，迭代器不再依赖index字段，而是只依赖lastItem的值了。
                     */
                    if(lastRet >= 0) {
                        lastItem = itemAt(lastRet);
                        // assert lastItem != null;
                        detach();
                    }
                }
                // assert isDetached();
                // assert lastRet < 0 ^ lastItem != null;
            } finally {
                lock.unlock();
            }
        }

        public E next() {
            final E e = nextItem;
            if(e == null)
                throw new NoSuchElementException();
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                /** 做获取后的nextItem移动 */
                if(!isDetached())
                    incorporateDequeues();
                // assert nextIndex != NONE;
                // assert lastItem == null;
                lastRet = nextIndex;
                final int cursor = this.cursor;
                if(cursor >= 0) {
                    nextItem = itemAt(nextIndex = cursor);  // 预先加载下一个元素
                    // assert nextItem != null;
                    this.cursor = incCursor(cursor);
                }else {
                    nextIndex = NONE;
                    nextItem = null;
                    if(lastRet == REMOVED)
                        detach();
                }
            } finally {
                lock.unlock();
            }
            return e;
        }

        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                // 先直接处理nextItem里面现有的元素（因为是预加载）
                final E e = nextItem;
                if(e == null)
                    return;
                if(!isDetached())
                    incorporateDequeues();
                action.accept(e);
                if(isDetached() || cursor < 0)
                    return;
                // 再遍历后面的元素
                final Object[] items = ArrayBlockingQueue.this.items;
                for(int i = cursor, end = putIndex, to = (i < end) ? end : items.length; ; i = 0, to = end) {
                    for(; i < to; i++)
                        action.accept(itemAt(items, i));
                    if (to == end)
                        break;
                }
            } finally {
                // 遍历完，迭代器就没用了，开始清理
                cursor = nextIndex = lastRet = NONE;
                nextItem = lastItem = null;
                detach();
                lock.unlock();
            }
        }

        public void remove() {
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            // assert lock.getHoldCount() == 1;
            try {
                if(!isDetached())
                    incorporateDequeues();
                final int lastRet = this.lastRet;
                this.lastRet = NONE;
                if(lastRet >= 0) {
                    if(!isDetached())
                        removeAt(lastRet);  // 这个方法才是最重要的
                    else {  // 进入这个说明要删除的是当前最后一个元素了（参照noNext方法的对应逻辑）
                        final E lastItem = this.lastItem;
                        // assert lastItem != null;
                        this.lastItem = null;
                        if(itemAt(lastRet) == lastItem)
                            removeAt(lastRet);
                    }
                }else if(lastRet == NONE)
                    throw new IllegalStateException();

                if(cursor < 0 && nextIndex < 0)
                    detach();
            } finally {
                lock.unlock();
                // assert lastRet == NONE;
                // assert lastItem == null;
            }
        }

        void shutdown() {
            cursor = NONE;
            if(nextIndex >= 0)
                nextIndex = REMOVED;
            if(lastRet >= 0) {
                lastRet = REMOVED;
                lastItem = null;
            }
            prevTakeIndex = DETACHED;
        }

        /**
         * 和invalidated方法差不多，计算：
         * 从prevTakeIndex出发，沿着环形数组走到index的距离，参数length是数组的总长度。
         */
        private int distance(int index, int prevTakeIndex, int length) {
            int distance = index - prevTakeIndex;
            if (distance < 0)
                distance += length;
            return distance;
        }

        /**
         * 比较难的方法，因为处理的不是头部出队，而是中间某个位置被删掉后，迭代器保存的那些下标要怎么修正。
         * 方法内部会把删除位置之后的元素整体往前移动一格，所以会发生两类影响：
         * 1、如果迭代器保存的位置，正好就是被删的位置：这个位置的元素就没了。
         * 2、如果迭代器保存的位置，在被删位置之后：对应的元素还在，但是它的数组下标要整体减1。
         * 因此要根据removedIndex参数，来修正cursor、nextIndex和lastRet。
         */
        boolean removedAt(int removedIndex) {
            // assert lock.isHeldByCurrentThread();
            if(isDetached())
                return true;    // 注意返回true的语义是：这个迭代器可以从Itrs链表中解绑了

            final int takeIndex = ArrayBlockingQueue.this.takeIndex;
            final int prevTakeIndex = this.prevTakeIndex;
            final int len = items.length;
            /**
             * 从prevTakeIndex出发，走到removedIndex，一共要多少步，
             * 不能直接用distance的原因在于：removedIndex是当前时刻的数组下标，而prevTakeIndex是迭代器上次操作时记住的队头。
             * 这中间takeIndex可能前进了，甚至可以绕了一圈回来了，所以不能用简单的distance。
             * 公式本身就是：完成绕圈数 * len + 当前圈内偏移差
             */
            final int removedDistance =
                    len * (itrs.cycles - this.prevCycles + ((removedIndex < takeIndex) ? 1 : 0)) + (removedIndex - prevTakeIndex);
            // assert itrs.cycles - this.prevCycles >= 0;
            // assert itrs.cycles - this.prevCycles <= 1;
            // assert removedDistance > 0;
            // assert removedIndex != takeIndex;

            /**
             * 调整cursor
             */
            int cursor = this.cursor;
            if(cursor >= 0) {
                /**
                 * x表示cursor的位置，相对于旧队头prevTakeIndex的逻辑距离，
                 * 然后和removedDistance比较，就是在比：cursor在被删除位置的前面/后面/本身
                 */
                int x = distance(cursor, prevTakeIndex, len);
                if(x == removedDistance) {
                    /**
                     * cursor正好指向被删位置。
                     * 要判断是因为cursor不是已经缓存好的下一个元素，它只是未来的扫描位置。
                     * 如果正好是putIndex，说明后面可以扫描的元素了，直接清理cursor。
                     * 如果不是putIndex，这个删除的空位会被后面元素给补上，所以不用动cursor。
                     */
                    if(cursor == putIndex)
                        this.cursor = cursor = NONE;
                }else if(x > removedDistance) {
                    /**
                     * cursor在被删除位置的后面。
                     * 删除会导致后面元素整体左移一格，所以cursor也要往前挪一格。
                     */
                    // assert cursor != prevTakeIndex;
                    this.cursor = cursor = dec(cursor, len);
                }
            }

            /**
             * 调整lastRet，比cursor处理要简单一些
             */
            int lastRet = this.lastRet;
            if(lastRet >= 0) {
                int x = distance(lastRet, prevTakeIndex, len);
                if(x == removedDistance)    // 上次返回的元素，正好被删了，直接标记成REMOVED即可
                    this.lastRet = lastRet = REMOVED;
                else if (x > removedDistance)   // lastRet在被删位置之后，所以要左移
                    this.lastRet = lastRet = dec(lastRet, len);
            }
            /**
             * 调整nextIndex，和lastRet是一样的逻辑
             */
            int nextIndex = this.nextIndex;
            if (nextIndex >= 0) {
                int x = distance(nextIndex, prevTakeIndex, len);
                if (x == removedDistance)
                    this.nextIndex = nextIndex = REMOVED;
                else if (x > removedDistance)
                    this.nextIndex = nextIndex = dec(nextIndex, len);
            }

            /**
             * 如果经过上面的处理，三个关键位置全都无效了，则进入detached状态并返回true来通知Itrs。
             */
            if(cursor < 0 && nextIndex < 0 && lastRet < 0) {
                this.prevTakeIndex = DETACHED;
                return true;
            }

            return false;
        }

        boolean takeIndexWrapped() {
            // assert lock.isHeldByCurrentThread();
            if(isDetached())
                return true;
            if(itrs.cycles - prevCycles > 1) {
                // All the elements that existed at the time of the last
                // operation are gone, so abandon further iteration.
                shutdown();
                return true;
            }
            return false;
        }
    }

    public Spliterator<E> spliterator() {
        return Spliterators.spliterator(this, (Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.CONCURRENT));
    }

    /**
     * 还是扫两轮
     */
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if(count > 0) {
                final Object[] items = this.items;
                for(int i = takeIndex, end = putIndex, to = (i < end) ? end : items.length; ; i = 0, to = end) {
                    for(; i < to; i++)
                        action.accept(itemAt(items, i));
                    if(to == end)
                        break;
                }
            }
        } finally {
            lock.unlock();
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
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if(itrs == null) {  // 注意这个只在没有活动迭代器时才能运行remove
                if(count > 0) {
                    final Object[] items = this.items;
                    // Optimize for initial run of survivors
                    for(int i = takeIndex, end = putIndex, to = (i < end) ? end : items.length; ; i = 0, to = end) {
                        for(; i < to; i++)
                            if(filter.test(itemAt(items, i)))   // 找到第一个符合删除条件的元素，再进入bulkRemoveModified
                                return bulkRemoveModified(filter, i);
                        if(to == end)
                            break;
                    }
                }
                return false;
            }
        } finally {
            lock.unlock();
        }
        // Active iterators are too hairy!
        // Punting (for now) to the slow n^2 algorithm ...
        return super.removeIf(filter);
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

    private int distanceNonEmpty(int i, int j) {
        if((j -= i) <= 0)
            j += items.length;
        return j;
    }

    /**
     * 这个方法之所以非常复杂，原因在于参数是个Predicate，里面的代码也可以参与读写队列的，因此流程要分为三个部分：
     * 1、先标记哪些元素要删（mark阶段）。
     * 2、统一压缩数组（compact阶段）
     * 3、清尾 + 更新结构
     * 参数bag表示：第一个要删除的位置
     */
    private boolean bulkRemoveModified(Predicate<? super E> filter, final int beg) {
        final Object[] es = items;
        final int capacity = items.length;
        final int end = putIndex;
        /**
         * 第一阶段：deathRow标记了哪些位置的元素要删除（是个bitset，1表示要删除，0则保留）
         */
        final long[] deathRow = nBits(distanceNonEmpty(beg, putIndex));
        deathRow[0] = 1L;   // beg这个位置的元素，一定要删（bulkRemove已经判断过了）
        /**
         * 遍历队列所有元素，满足filter就标记为待删除（不会动数组本身）
         */
        for(int i = beg + 1, to = (i <= end) ? end : es.length, k = beg; ; i = 0, to = end, k -= capacity) {
            for(; i < to; i++)
                if(filter.test(itemAt(es, i)))
                    setBit(deathRow, i - k);    // deathRow是连续数组，es是环形数组，要把环形index映射成从beg开始的线性offset
            if(to == end)
                break;
        }

        /**
         * 第二阶段：双指针压缩数组，把保留的元素往前移动，覆盖掉要删除的元素。
         * i：快指针（用来读）
         * w：慢指针（用来写）
         */
        // a two-finger traversal, with hare i reading, tortoise w writing
        int w = beg;
        /**
         * 第一层循环：如果特定元素不删除，则写到w的位置，然后w++
         */
        for(int i = beg + 1, to = (i <= end) ? end : es.length, k = beg; ; w = 0) { // w rejoins i on second leg
            // In this loop, i and w are on the same leg, with i > w
            for(; i < to; i++)
                if(isClear(deathRow, i - k))
                    es[w++] = es[i];    // 用i的元素，覆盖w的元素
            if(to == end)
                break;
            // In this loop, w is on the first leg, i on the second
            /**
             * 第二层循环，用来处理环绕
             */
            for(i = 0, to = end, k -= capacity; i < to && w < capacity; i++)
                if(isClear(deathRow, i - k))
                    es[w++] = es[i];
            if(i >= to) {
                if(w == capacity)   // 写指针w回绕
                    w = 0; // "corner" case
                break;
            }
        }
        /**
         * 第三阶段：更新count，更新putIndex指向新的尾部位置，最后清理尾部（把后面没用的元素改成null）
         */
        count -= distanceNonEmpty(w, end);
        circularClear(es, putIndex = w, end);
        return true;
    }

    /** debugging */
    void checkInvariants() {
        // meta-assertions
        // assert lock.isHeldByCurrentThread();
        if (!invariantsSatisfied()) {
            String detail = String.format(
                    "takeIndex=%d putIndex=%d count=%d capacity=%d items=%s",
                    takeIndex, putIndex, count, items.length,
                    Arrays.toString(items));
            System.err.println(detail);
            throw new AssertionError(detail);
        }
    }

    private boolean invariantsSatisfied() {
        // Unlike ArrayDeque, we have a count field but no spare slot.
        // We prefer ArrayDeque's strategy (and the names of its fields!),
        // but our field layout is baked into the serial form, and so is
        // too annoying to change.
        //
        // putIndex == takeIndex must be disambiguated by checking count.
        int capacity = items.length;
        return capacity > 0
                && items.getClass() == Object[].class
                && (takeIndex | putIndex | count) >= 0
                && takeIndex <  capacity
                && putIndex  <  capacity
                && count     <= capacity
                && (putIndex - takeIndex - count) % capacity == 0
                && (count == 0 || items[takeIndex] != null)
                && (count == capacity || items[putIndex] == null)
                && (count == 0 || items[dec(putIndex, capacity)] != null);
    }

    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        // Read in items array and various fields
        s.defaultReadObject();
        if(!invariantsSatisfied())
            throw new java.io.InvalidObjectException("invariants violated");
    }
}

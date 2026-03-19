package com.github.liyibo1110.jdk.java.util.concurrent;

import java.io.Serializable;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Helpers;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 基于链表的无界阻塞队列，特点是有两把锁分别控制读和写，比ArrayBlockingQueue要简单。
 * @author liyibo
 * @date 2026-03-18 17:03
 */
public class LinkedBlockingQueue<E> extends AbstractQueue implements BlockingQueue<E>, Serializable {
    private static final long serialVersionUID = -6903933977591709194L;

    static class Node<E> {
        E item;

        Node<E> next;

        Node(E x) {
            item = x;
        }
    }

    private final int capacity;

    private final AtomicInteger count = new AtomicInteger();

    /** 要注意head.item永远都是null（哨兵节点），真正的数据是从head.next开始的 */
    transient Node<E> head;

    private transient Node<E> last;

    /** take、poll方法需要使用的锁 */
    private final ReentrantLock takeLock = new ReentrantLock();
    private final Condition notEmpty = takeLock.newCondition();

    /** put、offer方法需要使用的锁 */
    private final ReentrantLock putLock = new ReentrantLock();
    private final Condition notFull = putLock.newCondition();

    private void signalNotEmpty() {
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
    }

    private void signalNotFull() {
        final ReentrantLock putLock = this.putLock;
        putLock.lock();
        try {
            notFull.signal();
        } finally {
            putLock.unlock();
        }
    }

    private void enqueue(Node<E> node) {
        // assert putLock.isHeldByCurrentThread();
        // assert last.next == null;
        last.next = node;
        last = last.next;   // 修改last的指向
    }

    private E dequeue() {
        // assert takeLock.isHeldByCurrentThread();
        // assert head.item == null;
        Node<E> h = head;   // dummy
        Node<E> first = h.next; // 这才是真正要移除的头
        /** 这一步需要理解，功能是断开链表引用：原来是h -> first，现在是h -> h，即h不再指向链表了，作用是帮助GC清理h */
        h.next = h;
        head = first;   // 数据头给dummy，后面会清理
        E e = first.item;
        first.item = null;  // 清理，变成真正的dummy，也是为了GC
        return e;
    }

    void fullyLock() {
        putLock.lock();
        takeLock.lock();
    }

    void fullyUnlock() {
        takeLock.unlock();
        putLock.unlock();
    }

    /**
     * 默认就是无界链表
     */
    public LinkedBlockingQueue() {
        this(Integer.MAX_VALUE);
    }

    public LinkedBlockingQueue(int capacity) {
        if(capacity <= 0)
            throw new IllegalArgumentException();
        this.capacity = capacity;
        last = head = new Node<>(null); // dummy node
    }

    /**
     * 还是无界链表
     */
    public LinkedBlockingQueue(Collection<? extends E> c) {
        this(Integer.MAX_VALUE);
        final ReentrantLock putLock = this.putLock;
        putLock.lock();
        try {
            int n = 0;
            for(E e : c) {
                if(e == null)
                    throw new NullPointerException();
                if(n == capacity)
                    throw new IllegalStateException("Queue full");
                enqueue(new Node<>(e));
                ++n;
            }
            count.set(n);
        } finally {
            putLock.unlock();
        }
    }

    public int size() {
        return count.get();
    }

    public int remainingCapacity() {
        return capacity - count.get();
    }

    public void put(E e) throws InterruptedException {
        if(e == null)
            throw new NullPointerException();
        final int c;
        final Node<E> node = new Node<>(e);
        final ReentrantLock putLock = this.putLock;
        final AtomicInteger count = this.count;
        putLock.lockInterruptibly();
        try {
            while(count.get() == capacity)  // 队列满了就要阻塞等signal
                notFull.await();
            enqueue(node);
            c = count.getAndIncrement();    // 原来的count
            if(c + 1 < capacity)    // 顺便检测剩余空间并触发可以写的signal
                notFull.signal();
        } finally {
            putLock.unlock();
        }
        if(c == 0)  // 为0，说明put之前是空的，顺便触发可以读的signal
            signalNotEmpty();
    }

    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if(e == null)
            throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        final int c;
        final ReentrantLock putLock = this.putLock;
        final AtomicInteger count = this.count;
        putLock.lockInterruptibly();
        try {
            while(count.get() == capacity) {
                if(nanos <= 0L)
                    return false;
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(new Node<>(e));
            c = count.getAndIncrement();
            if(c + 1 < capacity)
                notFull.signal();
        } finally {
            putLock.unlock();
        }
        if(c == 0)
            signalNotEmpty();
        return true;
    }

    public boolean offer(E e) {
        if(e == null)
            throw new NullPointerException();
        final AtomicInteger count = this.count;
        if(count.get() == capacity)
            return false;
        final int c;
        final Node<E> node = new Node<>(e);
        final ReentrantLock putLock = this.putLock;
        putLock.lock();
        try {
            if(count.get() == capacity)
                return false;
            enqueue(node);
            c = count.getAndIncrement();
            if(c + 1 < capacity)
                notFull.signal();
        } finally {
            putLock.unlock();
        }
        if(c == 0)
            signalNotEmpty();
        return true;
    }

    public E take() throws InterruptedException {
        final E x;
        final int c;
        final AtomicInteger count = this.count;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lockInterruptibly();
        try {
            while(count.get() == 0)
                notEmpty.await();
            x = dequeue();
            c = count.getAndDecrement();
            if(c > 1)
                notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
        if(c == capacity)
            signalNotFull();
        return x;
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        final E x;
        final int c;
        long nanos = unit.toNanos(timeout);
        final AtomicInteger count = this.count;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lockInterruptibly();
        try {
            while(count.get() == 0) {
                if(nanos <= 0L)
                    return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            x = dequeue();
            c = count.getAndDecrement();
            if(c > 1)
                notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
        if(c == capacity)
            signalNotFull();
        return x;
    }

    public E poll() {
        final AtomicInteger count = this.count;
        if(count.get() == 0)
            return null;
        final E x;
        final int c;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            if(count.get() == 0)
                return null;
            x = dequeue();
            c = count.getAndDecrement();
            if(c > 1)
                notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
        if(c == capacity)
            signalNotFull();
        return x;
    }

    public E peek() {
        final AtomicInteger count = this.count;
        if(count.get() == 0)
            return null;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            return (count.get() > 0) ? head.next.item : null;
        } finally {
            takeLock.unlock();
        }
    }

    /**
     * 将p移出
     */
    void unlink(Node<E> p, Node<E> pred) {
        // assert putLock.isHeldByCurrentThread();
        // assert takeLock.isHeldByCurrentThread();
        p.item = null;
        pred.next = p.next;
        if(last == p)   // 如果是p是尾节点
            last = pred;
        if(count.getAndDecrement() == capacity) // 如果之前队列是满的，则通知可以写
            notFull.signal();
    }

    public boolean remove(Object o) {
        if(o == null)
            return false;
        fullyLock();
        try {
            // 遍历链表
            for(Node<E> pred = head, p = pred.next; p != null; pred = p, p = p.next) {
                if(o.equals(p.item)) {
                    unlink(p, pred);
                    return true;
                }
            }
            return false;
        } finally {
            fullyUnlock();
        }
    }

    public boolean contains(Object o) {
        if (o == null) return false;
        fullyLock();
        try {
            for (Node<E> p = head.next; p != null; p = p.next)
                if(o.equals(p.item))
                    return true;
            return false;
        } finally {
            fullyUnlock();
        }
    }

    public Object[] toArray() {
        fullyLock();
        try {
            int size = count.get();
            Object[] a = new Object[size];
            int k = 0;
            for(Node<E> p = head.next; p != null; p = p.next)
                a[k++] = p.item;
            return a;
        } finally {
            fullyUnlock();
        }
    }

    public <T> T[] toArray(T[] a) {
        fullyLock();
        try {
            int size = count.get();
            if (a.length < size)
                a = (T[])java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
            int k = 0;
            for(Node<E> p = head.next; p != null; p = p.next)
                a[k++] = (T)p.item;
            if (a.length > k)
                a[k] = null;
            return a;
        } finally {
            fullyUnlock();
        }
    }

    public String toString() {
        return Helpers.collectionToString(this);
    }

    public void clear() {
        fullyLock();
        try {
            for(Node<E> p, h = head; (p = h.next) != null; h = p) {
                h.next = h;
                p.item = null;
            }
            head = last;
            // assert head.item == null && head.next == null;
            if(count.getAndSet(0) == capacity)
                notFull.signal();
        } finally {
            fullyUnlock();
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
        boolean signalNotFull = false;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            int n = Math.min(maxElements, count.get());
            // count.get provides visibility to first n Nodes
            Node<E> h = head;
            int i = 0;
            try {
                while (i < n) {
                    Node<E> p = h.next;
                    c.add(p.item);
                    p.item = null;
                    h.next = h;
                    h = p;
                    ++i;
                }
                return n;
            } finally {
                // Restore invariants even if c.add() threw
                if (i > 0) {
                    // assert h.item == null;
                    head = h;
                    signalNotFull = (count.getAndAdd(-i) == capacity);
                }
            }
        } finally {
            takeLock.unlock();
            if(signalNotFull)
                signalNotFull();
        }
    }

    /**
     * 在遍历链表时，如果发现节点被特殊处理了（即自己的next下指向自己），就跳回头部继续找下一个有效节点。
     * 这里对应的其实是dequeue方法里h.next = h;这一句，旧的head会变成这样，假如迭代器刚好访问到了，会形成死循环。
     * 然后是跳回头部这个操作可行，是因为迭代器语义就是弱一致性，允许跳过元素、允许看到元素、允许顺序不完全严格。
     *
     * succ就是安全版本的获取p.next的方法。
     */
    Node<E> succ(Node<E> p) {
        if(p == (p = p.next))
            p = head.next;
        return p;
    }

    public Iterator<E> iterator() {
        return new Itr();
    }

    private class Itr implements Iterator<E> {
        private Node<E> next;

        private E nextItem;

        private Node<E> lastRet;

        /** remove中会用到的字段，表示：上一次找到的前驱节点，因为LinkedBlockingQueue是单向链表，要删除p，则必须要找p的pred */
        private Node<E> ancestor;

        Itr() {
            fullyLock();
            try {
                if((next = head.next) != null)
                    nextItem = next.item;
            } finally {
                fullyUnlock();
            }
        }

        public boolean hasNext() {
            return next != null;
        }

        public E next() {
            Node<E> p;
            if ((p = next) == null)
                throw new NoSuchElementException();
            lastRet = p;
            E x = nextItem;
            /** 获取了当前元素，开始指向下一个元素 */
            fullyLock();
            try {
                E e = null;
                /**
                 * 重要流程：从当前节点往后找，找到第一个item != null的节点即可，
                 * 但是中间如果找到异常节点，用succ来修正
                 */
                for(p = p.next; p != null && (e = p.item) == null; )
                    p = succ(p);
                next = p;
                nextItem = e;
            } finally {
                fullyUnlock();
            }
            return x;
        }

        /**
         * 复杂之处在于：会分批（64个）从队列里复制数据，然后解锁，最后再执行消费，目的是减少锁持有时间
         */
        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            Node<E> p;
            if((p = next) == null)  // 没有下一个元素了
                return;
            lastRet = p;
            next = null;    // 直接取消next，不能再用next()了
            final int batchSize = 64;
            Object[] es = null; // 临时存储用，相当于buffer
            int n;
            int len = 1;    // 第一条一定有，就是nextItem
            /**
             * 每轮最多处理64条，目的是不会一直持有锁
             */
            do {
                fullyLock();
                try {
                    if(es == null) {    // 第一批
                        /**
                         * 先扫一遍，算出这批最多能拿多少个
                         */
                        p = p.next; // 注意p直接指向了下一个node，因为原来的next本来就在nextItem了，会写死加入
                        for(Node<E> q = p; q != null; q = succ(q)) {
                            if(q.item != null && ++len == batchSize)
                                break;
                        }
                        es = new Object[len];
                        es[0] = nextItem;
                        nextItem = null;    // nextItem字段也没用了
                        n = 1;
                    }else {
                        n = 0;
                    }
                    /**
                     * 将特定范围的数据复制到es的剩余位置
                     */
                    for(; p != null && n < len; p = succ(p)) {
                        if((es[n] = p.item) != null) {
                            lastRet = p;
                            n++;
                        }
                    }
                } finally {
                    fullyUnlock();
                }

                /**
                 * 开始对这一批数据进行accept
                 */
                for(int i = 0; i < n; i++) {
                    E e = (E)es[i];
                    action.accept(e);
                }
            } while(n > 0 && p != null);
        }

        /**
         * 因为涉及到了动链表本身了，所以多了两点复杂性：
         * 1、必须找到前驱节点。
         * 2、必须保证并发下仍然安全。
         * 功能：删除上一次next()返回的节点，并通过找到前驱节点并执行unlink。
         */
        public void remove() {
            Node<E> p = lastRet;    // lastRet就是上一次next()的返回值
            if(p == null)
                throw new IllegalStateException();
            lastRet = null; // 重要，防止重复调用remove，要符合迭代器接口的语义
            fullyLock();
            try {
                if(p.item != null) {
                    if(ancestor == null)    // 初始化ancestor
                        ancestor = head;
                    ancestor = findPred(p, ancestor);
                    unlink(p, ancestor);
                }
            } finally {
                fullyUnlock();
            }
        }
    }

    private final class LBQSpliterator implements Spliterator<E> {
        static final int MAX_BATCH = 1 << 25;  // max batch array size;
        java.util.concurrent.LinkedBlockingQueue.Node<E> current;    // current node; null until initialized
        int batch;          // batch size for splits
        boolean exhausted;  // true when no more nodes
        long est = size();  // size estimate

        LBQSpliterator() {}

        public long estimateSize() { return est; }

        public Spliterator<E> trySplit() {
            java.util.concurrent.LinkedBlockingQueue.Node<E> h;
            if (!exhausted &&
                    ((h = current) != null || (h = head.next) != null)
                    && h.next != null) {
                int n = batch = Math.min(batch + 1, MAX_BATCH);
                Object[] a = new Object[n];
                int i = 0;
                java.util.concurrent.LinkedBlockingQueue.Node<E> p = current;
                fullyLock();
                try {
                    if (p != null || (p = head.next) != null)
                        for (; p != null && i < n; p = succ(p))
                            if ((a[i] = p.item) != null)
                                i++;
                } finally {
                    fullyUnlock();
                }
                if ((current = p) == null) {
                    est = 0L;
                    exhausted = true;
                }
                else if ((est -= i) < 0L)
                    est = 0L;
                if (i > 0)
                    return Spliterators.spliterator
                            (a, 0, i, (Spliterator.ORDERED |
                                    Spliterator.NONNULL |
                                    Spliterator.CONCURRENT));
            }
            return null;
        }

        public boolean tryAdvance(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            if (!exhausted) {
                E e = null;
                fullyLock();
                try {
                    java.util.concurrent.LinkedBlockingQueue.Node<E> p;
                    if ((p = current) != null || (p = head.next) != null)
                        do {
                            e = p.item;
                            p = succ(p);
                        } while (e == null && p != null);
                    if ((current = p) == null)
                        exhausted = true;
                } finally {
                    fullyUnlock();
                }
                if (e != null) {
                    action.accept(e);
                    return true;
                }
            }
            return false;
        }

        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            if (!exhausted) {
                exhausted = true;
                java.util.concurrent.LinkedBlockingQueue.Node<E> p = current;
                current = null;
                forEachFrom(action, p);
            }
        }

        public int characteristics() {
            return (Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.CONCURRENT);
        }
    }

    public Spliterator<E> spliterator() {
        return new LBQSpliterator();
    }

    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        forEachFrom(action, null);
    }

    /**
     * 和迭代器里面的forEachRemaining基本是一样的逻辑
     */
    void forEachFrom(Consumer<? super E> action, Node<E> p) {
        // Extract batches of elements while holding the lock; then
        // run the action on the elements while not
        final int batchSize = 64;       // max number of elements per batch
        Object[] es = null;             // container for batch of elements
        int n, len = 0;
        do {
            fullyLock();
            try {
                if (es == null) {
                    if (p == null) p = head.next;
                    for (Node<E> q = p; q != null; q = succ(q))
                        if (q.item != null && ++len == batchSize)
                            break;
                    es = new Object[len];
                }
                for (n = 0; p != null && n < len; p = succ(p))
                    if ((es[n] = p.item) != null)
                        n++;
            } finally {
                fullyUnlock();
            }
            for (int i = 0; i < n; i++) {
                @SuppressWarnings("unchecked") E e = (E) es[i];
                action.accept(e);
            }
        } while (n > 0 && p != null);
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

    /**
     * 找出p的前驱，ancestor是上一轮找到的前驱（目的是不每次从head开始遍历）
     */
    Node<E> findPred(Node<E> p, Node<E> ancestor) {
        if(ancestor.item == null)
            ancestor = head;
        for(Node<E> q; (q = ancestor.next) != p; )
            ancestor = q;
        return ancestor;
    }

    private boolean bulkRemove(Predicate<? super E> filter) {
        boolean removed = false;
        Node<E> p = null, ancestor = head;
        Node<E>[] nodes = null;
        int n, len = 0;
        do {
            // 1. Extract batch of up to 64 elements while holding the lock.
            fullyLock();
            try {
                if (nodes == null) {  // first batch; initialize
                    p = head.next;
                    for (Node<E> q = p; q != null; q = succ(q))
                        if (q.item != null && ++len == 64)
                            break;
                    nodes = (Node<E>[]) new Node<?>[len];
                }
                for (n = 0; p != null && n < len; p = succ(p))
                    nodes[n++] = p;
            } finally {
                fullyUnlock();
            }

            // 2. Run the filter on the elements while lock is free.
            long deathRow = 0L;       // "bitset" of size 64
            for (int i = 0; i < n; i++) {
                final E e;
                if ((e = nodes[i].item) != null && filter.test(e))
                    deathRow |= 1L << i;
            }

            // 3. Remove any filtered elements while holding the lock.
            if (deathRow != 0) {
                fullyLock();
                try {
                    for (int i = 0; i < n; i++) {
                        final Node<E> q;
                        if ((deathRow & (1L << i)) != 0L
                                && (q = nodes[i]).item != null) {
                            ancestor = findPred(q, ancestor);
                            unlink(q, ancestor);
                            removed = true;
                        }
                        nodes[i] = null; // help GC
                    }
                } finally {
                    fullyUnlock();
                }
            }
        } while (n > 0 && p != null);
        return removed;
    }

    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        fullyLock();
        try {
            // Write out any hidden stuff, plus capacity
            s.defaultWriteObject();

            // Write out all elements in the proper order.
            for (Node<E> p = head.next; p != null; p = p.next)
                s.writeObject(p.item);

            // Use trailing null as sentinel
            s.writeObject(null);
        } finally {
            fullyUnlock();
        }
    }

    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        // Read in capacity, and any hidden stuff
        s.defaultReadObject();

        count.set(0);
        last = head = new Node<>(null);

        // Read in all elements and place in queue
        for (;;) {
            @SuppressWarnings("unchecked")
            E item = (E)s.readObject();
            if (item == null)
                break;
            add(item);
        }
    }
}

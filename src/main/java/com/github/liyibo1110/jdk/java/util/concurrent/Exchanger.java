package com.github.liyibo1110.jdk.java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * 主要作用：两个线程在某个同步点交换数据：生产者 - 消费者双缓冲，特点是：
 * 1、数据必须成对出现：一个线程调用exchange会阻塞，必须等另一个线程调用exchange。
 * 2、不是队列，本身不存数据：没有缓存，也没有中间存储。
 * 本质是一个rendezvous（汇合点）
 *
 * 两种运行模式：
 * 低竞争（slot）：
 * 线程A -> 放入slot -> 等待
 * 线程B -> 看到slot -> CAS获取 -> 设置node的match -> unpark线程A
 * 高竞争（arena）：
 * 线程根据probe找一个index，在arena[index]中交换，如果冲突则重新probe，类似LongAdder或ThreadLocalRandom的分散竞争策略。
 *
 * 这个组件简单学习的内部特性、调用路径和思路，一些具体方法并没有逐行学习。
 * @author liyibo
 * @date 2026-03-19 14:18
 */
public class Exchanger<V> {

    private static final int ASHIFT = 5;

    private static final int MMASK = 0xff;

    private static final int SEQ = MMASK + 1;

    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    static final int FULL = (NCPU >= (MMASK << 1)) ? MMASK : NCPU >>> 1;

    private static final int SPINS = 1 << 10;

    private static final Object NULL_ITEM = new Object();

    private static final Object TIMED_OUT = new Object();

    /**
     * 交换数据的节点
     */
    static final class Node {

        /**
         * 下面这几个字段都是用来控制诸如：
         * 1、arena是否启用。
         * 2、arena大小。
         * 3、线程选择哪个槽位
         * 本质是自适应扩容：slot -> arena
         */
        int index;
        int bound;
        int collides;
        int hash;

        /** 自己的数据 */
        Object item;

        /** 对方的数据 */
        volatile Object match;

        /** 挂起的线程 */
        volatile Thread parked;
    }

    static final class Participant extends ThreadLocal<Node> {
        public Node initialValue() {
            return new Node();
        }
    }

    private final Participant participant;

    /**
     * 对应slot，是多槽位数组：用于高并发场景（多个线程），原因在于：如果很多线程抢一个slot，会产生严重竞争，所以扩展成多个交换点，
     * 类似arena[0]、arena[1]、arena[2]，线程会根据hash/probe来选择槽位。
     */
    private volatile Node[] arena;

    /**
     * 单槽位：用于低竞争场景（只有两个线程），流程是：
     * 线程A -> 放入slot -> 进入等待
     * 线程B -> 发现slot -> 交换数据 -> 唤醒A
     * 以上是fast-path
     */
    private volatile Node slot;

    private volatile int bound;

    private final Object arenaExchange(Object item, boolean timed, long ns) {
        Node[] a = arena;
        int alen = a.length;
        Node p = participant.get();
        for (int i = p.index;;) {                      // access slot at i
            int b, m, c;
            int j = (i << ASHIFT) + ((1 << ASHIFT) - 1);
            if (j < 0 || j >= alen)
                j = alen - 1;
            Node q = (Node)AA.getAcquire(a, j);
            if (q != null && AA.compareAndSet(a, j, q, null)) {
                Object v = q.item;                     // release
                q.match = item;
                Thread w = q.parked;
                if (w != null)
                    LockSupport.unpark(w);
                return v;
            }
            else if (i <= (m = (b = bound) & MMASK) && q == null) {
                p.item = item;                         // offer
                if (AA.compareAndSet(a, j, null, p)) {
                    long end = (timed && m == 0) ? System.nanoTime() + ns : 0L;
                    Thread t = Thread.currentThread(); // wait
                    for (int h = p.hash, spins = SPINS;;) {
                        Object v = p.match;
                        if (v != null) {
                            MATCH.setRelease(p, null);
                            p.item = null;             // clear for next use
                            p.hash = h;
                            return v;
                        }
                        else if (spins > 0) {
                            h ^= h << 1; h ^= h >>> 3; h ^= h << 10; // xorshift
                            if (h == 0)                // initialize hash
                                h = SPINS | (int)t.getId();
                            else if (h < 0 &&          // approx 50% true
                                    (--spins & ((SPINS >>> 1) - 1)) == 0)
                                Thread.yield();        // two yields per wait
                        }
                        else if (AA.getAcquire(a, j) != p)
                            spins = SPINS;       // releaser hasn't set match yet
                        else if (!t.isInterrupted() && m == 0 &&
                                (!timed ||
                                        (ns = end - System.nanoTime()) > 0L)) {
                            p.parked = t;              // minimize window
                            if (AA.getAcquire(a, j) == p) {
                                if (ns == 0L)
                                    LockSupport.park(this);
                                else
                                    LockSupport.parkNanos(this, ns);
                            }
                            p.parked = null;
                        }
                        else if (AA.getAcquire(a, j) == p &&
                                AA.compareAndSet(a, j, p, null)) {
                            if (m != 0)                // try to shrink
                                BOUND.compareAndSet(this, b, b + SEQ - 1);
                            p.item = null;
                            p.hash = h;
                            i = p.index >>>= 1;        // descend
                            if (Thread.interrupted())
                                return null;
                            if (timed && m == 0 && ns <= 0L)
                                return TIMED_OUT;
                            break;                     // expired; restart
                        }
                    }
                }
                else
                    p.item = null;                     // clear offer
            }
            else {
                if (p.bound != b) {                    // stale; reset
                    p.bound = b;
                    p.collides = 0;
                    i = (i != m || m == 0) ? m : m - 1;
                }
                else if ((c = p.collides) < m || m == FULL ||
                        !BOUND.compareAndSet(this, b, b + SEQ + 1)) {
                    p.collides = c + 1;
                    i = (i == 0) ? m : i - 1;          // cyclically traverse
                }
                else
                    i = m + 1;                         // grow
                p.index = i;
            }
        }
    }

    private final Object slotExchange(Object item, boolean timed, long ns) {
        Node p = participant.get();
        Thread t = Thread.currentThread();
        if (t.isInterrupted()) // preserve interrupt status so caller can recheck
            return null;

        for (Node q;;) {
            if ((q = slot) != null) {
                if (SLOT.compareAndSet(this, q, null)) {
                    Object v = q.item;
                    q.match = item;
                    Thread w = q.parked;
                    if (w != null)
                        LockSupport.unpark(w);
                    return v;
                }
                // create arena on contention, but continue until slot null
                if (NCPU > 1 && bound == 0 &&
                        BOUND.compareAndSet(this, 0, SEQ))
                    arena = new Node[(FULL + 2) << ASHIFT];
            }
            else if (arena != null)
                return null; // caller must reroute to arenaExchange
            else {
                p.item = item;
                if (SLOT.compareAndSet(this, null, p))
                    break;
                p.item = null;
            }
        }

        // await release
        int h = p.hash;
        long end = timed ? System.nanoTime() + ns : 0L;
        int spins = (NCPU > 1) ? SPINS : 1;
        Object v;
        while ((v = p.match) == null) {
            if (spins > 0) {
                h ^= h << 1; h ^= h >>> 3; h ^= h << 10;
                if (h == 0)
                    h = SPINS | (int)t.getId();
                else if (h < 0 && (--spins & ((SPINS >>> 1) - 1)) == 0)
                    Thread.yield();
            }
            else if (slot != p)
                spins = SPINS;
            else if (!t.isInterrupted() && arena == null &&
                    (!timed || (ns = end - System.nanoTime()) > 0L)) {
                p.parked = t;
                if (slot == p) {
                    if (ns == 0L)
                        LockSupport.park(this);
                    else
                        LockSupport.parkNanos(this, ns);
                }
                p.parked = null;
            }
            else if (SLOT.compareAndSet(this, p, null)) {
                v = timed && ns <= 0L && !t.isInterrupted() ? TIMED_OUT : null;
                break;
            }
        }
        MATCH.setRelease(p, null);
        p.item = null;
        p.hash = h;
        return v;
    }

    public Exchanger() {
        participant = new Participant();
    }

    public V exchange(V x) throws InterruptedException {
        Object v;
        Node[] a;
        Object item = (x == null) ? NULL_ITEM : x;
        /**
         * 1、arena是否存在？
         * 是：直接走arenaExchange
         * 否：先走slotExchange
         * 2、slotExchange是否成功？
         * 是：返回
         * 否：走arenaExchange
         * 3、arenaExchange是否成功？
         * 是：返回
         * 否：抛出异常
         * 4、任意时刻线程被中断，则抛异常。
         *
         * 还要注意2个内部方法返回的null，并不代表数据是null，只是一个失败信号，并不是交换的结果。
         */
        if(((a = arena) != null || (v = slotExchange(item, false, 0L)) == null)
                &&
             (Thread.interrupted() || (v = arenaExchange(item, false, 0L)) == null)) {
            throw new InterruptedException();
        }
        return (v == NULL_ITEM) ? null : (V)v;
    }

    public V exchange(V x, long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        Object v;
        Object item = (x == null) ? NULL_ITEM : x;
        long ns = unit.toNanos(timeout);
        if ((arena != null || (v = slotExchange(item, true, ns)) == null)
                &&
             (Thread.interrupted() || (v = arenaExchange(item, true, ns)) == null))
            throw new InterruptedException();
        if(v == TIMED_OUT)
            throw new TimeoutException();
        return (v == NULL_ITEM) ? null : (V)v;
    }

    // VarHandle mechanics
    private static final VarHandle BOUND;
    private static final VarHandle SLOT;
    private static final VarHandle MATCH;
    private static final VarHandle AA;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            BOUND = l.findVarHandle(java.util.concurrent.Exchanger.class, "bound", int.class);
            SLOT = l.findVarHandle(java.util.concurrent.Exchanger.class, "slot", java.util.concurrent.Exchanger.Node.class);
            MATCH = l.findVarHandle(java.util.concurrent.Exchanger.Node.class, "match", Object.class);
            AA = MethodHandles.arrayElementVarHandle(java.util.concurrent.Exchanger.Node[].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

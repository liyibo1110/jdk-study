package com.github.liyibo1110.jdk.java.util.concurrent.locks;

import sun.misc.Unsafe;

import java.io.Serializable;

/**
 * ReentrantReadWriteLock的进阶版，完全抛弃了AQS，自己实现的同步机制。
 * 在之前的读锁和写锁模式下，又新增了一种乐观读模式，即先读数据，再检查期间有没有写操作，如果没有则读结果有效，否则重新获取读锁再读。
 *
 * 设计目标：
 * 1、ReentrantReadWriteLock的问题在于，获取读锁需要CAS + ThreadLocal，在读多的场景下仍然有成本，因此StampedLock优化方向是：读操作尽量不加锁。
 * 所以引入了OptimisticRead，其中Stamp = 版本号，每次锁操作都会返回一个stamp。
 * 2、StampedLock不是可重入锁，连续调用WriteLock方法会导致死锁，这样做的目的是减少复杂度以及提高性能。
 * 3、支持锁转换：即读锁 -> 写锁，写锁 -> 读锁
 * 4、stamp机制：每次获取锁后会返回，但释放锁时必须传回。
 *
 * 缺点：
 * 1、不可重入。
 * 2、API比较复杂。
 * 3、容易误用，例如使用用忘记validate。
 * @author liyibo
 * @date 2026-03-14 17:52
 */
public class StampedLock implements Serializable {
    private static final long serialVersionUID = -6001602636862214147L;

    /** The number of bits to use for reader count before overflowing */
    private static final int LG_READERS = 7; // 127 readers

    // Values for lock state and stamp operations
    private static final long RUNIT = 1L;

    /** 写锁位 */
    private static final long WBIT  = 1L << LG_READERS;

    /** 读锁计数掩码 */
    private static final long RBITS = WBIT - 1L;
    private static final long RFULL = RBITS - 1L;

    /** 锁状态部分 */
    private static final long ABITS = RBITS | WBIT;
    private static final long SBITS = ~RBITS; // note overlap with ABITS
    // not writing and conservatively non-overflowing
    private static final long RSAFE = ~(3L << (LG_READERS - 1));

    /** 初始版本号 */
    private static final long ORIGIN = WBIT << 1;
    private static final long INTERRUPTED = 1L;

    static final int WAITING   = 1;
    static final int CANCELLED = 0x80000000;

    /**
     * 类似AQS里面的Node
     */
    abstract static class Node {
        volatile Node prev;
        volatile Node next;
        Thread waiter;
        volatile int status;

        final boolean casPrev(Node c, Node v) {  // for cleanQueue
            return U.weakCompareAndSetReference(this, PREV, c, v);
        }

        final boolean casNext(Node c, Node v) {  // for cleanQueue
            return U.weakCompareAndSetReference(this, NEXT, c, v);
        }

        final int getAndUnsetStatus(int v) {     // for signalling
            return U.getAndBitwiseAndInt(this, STATUS, ~v);
        }

        final void setPrevRelaxed(Node p) {      // for off-queue assignment
            U.putReference(this, PREV, p);
        }

        final void setStatusRelaxed(int s) {     // for off-queue assignment
            U.putInt(this, STATUS, s);
        }

        final void clearStatus() {               // for reducing unneeded signals
            U.putIntOpaque(this, STATUS, 0);
        }

        private static final long STATUS = U.objectFieldOffset(Node.class, "status");
        private static final long NEXT = U.objectFieldOffset(Node.class, "next");
        private static final long PREV = U.objectFieldOffset(Node.class, "prev");
    }

    static final class WriterNode extends Node {

    }

    static final class ReaderNode extends Node {
        volatile ReaderNode cowaiters;

        final boolean casCowaiters(ReaderNode c, ReaderNode v) {
            return U.weakCompareAndSetReference(this, COWAITERS, c, v);
        }

        final void setCowaitersRelaxed(ReaderNode p) {
            U.putReference(this, COWAITERS, p);
        }

        private static final long COWAITERS = U.objectFieldOffset(ReaderNode.class, "cowaiters");
    }

    private transient volatile Node head;

    private transient volatile Node tail;

    // views
    transient ReadLockView readLockView;
    transient WriteLockView writeLockView;
    transient ReadWriteLockView readWriteLockView;

    /**
     * 64位的锁状态标记，同时保存了3种信息：
     * 低7位：写锁
     * 中间位：读锁
     * 高位：版本号
     * 每次有写操作完成，版本号就会发生变化。
     */
    private transient volatile long state;

    /** 读锁超过计数范围时使用 */
    private transient int readerOverflow;

    public StampedLock() {
        state = ORIGIN;
    }

    // internal lock methods

    private boolean casState(long expect, long update) {
        return U.compareAndSetLong(this, STATE, expect, update);
    }


    /**
     * 乐观读
     */
    public long tryOptimisticRead() {
        long s;
        /**
         * state & WBIT == 0表示没有写锁，s & SBITS的结果就是当前的版本号。
         * 因此下面的意义是：如果没有写锁就返回当前版本号，否则返回0。
         */
        return (((s = state) & WBIT) == 0L) ? (s & SBITS) : 0L;
    }

    /**
     * 一般会在tryOptimisticRead成功时，从资源中读取的数据后，要调用这个方法，来验证在刚才读取过程中，是否已经被改写了，
     * 如果validate返回false，说明之前读的已是脏数据，要通过再次获取读锁的方式来重新获取数据。
     */
    public boolean validate(long stamp) {
        U.loadFence();
        return (stamp & SBITS) == (state & SBITS);  // 就是检查给定的stamp和state里面的stamp是否还一致
    }


    // status monitoring methods

    // views

    // view classes

    // overflow handling methods

    // release methods

    // Cancellation support

    // Unsafe
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long STATE = U.objectFieldOffset(StampedLock.class, "state");
    private static final long HEAD = U.objectFieldOffset(StampedLock.class, "head");
    private static final long TAIL = U.objectFieldOffset(StampedLock.class, "tail");

    static {
        Class<?> ensureLoaded = LockSupport.class;
    }
}

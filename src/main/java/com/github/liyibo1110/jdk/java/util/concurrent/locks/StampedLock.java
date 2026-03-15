package com.github.liyibo1110.jdk.java.util.concurrent.locks;

import sun.misc.Unsafe;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

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

    /** 所有锁的状态位（写锁 + 读锁） */
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

    private long tryAcquireWrite() {
        long s;
        long nextState;
        /**
         * state & ABITS意思是：当前是否存在读锁或者写锁，0代表没有任何锁，这时获取写锁的前提条件。
         * s | WBIT意思是：设置写锁。
         */
        if(((s = state) & ABITS) == 0L && casState(s, nextState = s | WBIT)) {
            /**
             * storeStoreFence防止写操作被CPU重排序，更准确地说是：
             * 保证写锁获取之后，之前的写操作不会被重排序到后面，
             * 这时写锁语义必须保证的可见性规则。
             * StampedLock的实现没有像AQS那样使用大量的volatile和LockSupport，而使用了Unsafe + 内存屏障来精细控制CPU内存顺序。
             */
            U.storeStoreFence();
            return nextState;
        }
        return 0L;
    }

    /**
     * 1、如果没有写锁且读锁计数没满，则cas增加读锁。
     * 2、如果有写锁，则失败。
     * 3、如果读锁计数满了，则使用overflow机制。
     * @return
     */
    private long tryAcquireRead() {
        // 因为state可能在并发下变化，所以需要不断尝试
        for(long s, m, nextState; ; ) {
            /**
             * s就是完整的state。
             * m只保留锁相关的位，不包括版本号。
             */
            if((m = (s = state) & ABITS) < RFULL) { // 读锁计数没有满
                if(casState(s, nextState = s + RUNIT))  // 读锁次数+1
                    return nextState;   // 进入说明获取到读锁了
            }else if(m == WBIT) { // 有没有写锁
                return 0L;
            }else if((nextState = tryIncReaderOverflow(s)) != 0L) { // 读锁计数满了
                /**
                 * 因为state只给了读锁7bit的计数，也就是最大值为127。
                 * 如果超过了，前127个读锁存在state，而更多的读锁存在readerOverflow变量里，相当于两级计数。
                 */
                return nextState;
            }
        }
    }

    private static long unlockWriteState(long s) {
        /**
         * 注意这个s += WBIT，同时干了2件事：
         * 1、清除了写锁位。
         * 2、更新了版本号。
         * 后面判断等于0，是溢出保护，因为版本号会一直增加，如果溢出结果就是0，所以这时要重置为ORIGIN（初始版本号）
         */
        return ((s += WBIT) == 0L) ? ORIGIN : s;
    }

    private long releaseWrite(long s) {
        // 因为释放的是写锁，所以要先更新state里面的stamp
        long nextState = state = unlockWriteState(s);
        signalNext(head);
        return nextState;
    }

    public long writeLock() {
        /**
         * fast-path + slow-path组合
         * 1、读取当前的state（忽略锁位，即只保留了版本号部分的位）。
         * 2、尝试cas设置写锁。
         * 3、如果成功，直接返回stamp，否则进入acquireWrite。
         */
        long s = U.getLongOpaque(this, STATE) & ~ABITS;
        long nextState;
        if(casState(s, nextState = s | WBIT)) {
            U.storeStoreFence();
            return nextState;
        }
        return acquireWrite(false, false, 0L);
    }

    public long tryWriteLock() {
        return tryAcquireWrite();
    }

    public long tryWriteLock(long time, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(time);
        if(!Thread.interrupted()) {
            long nextState;
            if((nextState = tryAcquireWrite()) != 0L)
                return nextState;
            if(nanos <= 0L)
                return 0L;
            nextState = acquireWrite(true, true, System.nanoTime() + nanos);
            if(nextState != INTERRUPTED)
                return nextState;
        }
        throw new InterruptedException();
    }

    public long writeLockInterruptibly() throws InterruptedException {
        long nextState;
        if (!Thread.interrupted() && ((nextState = tryAcquireWrite()) != 0L || (nextState = acquireWrite(true, false, 0L)) != INTERRUPTED))
            return nextState;
        throw new InterruptedException();
    }

    /**
     * 和writeLock类似，也是fast-path + slow-path组合。
     */
    public long readLock() {
        // unconditionally optimistically try non-overflow case once
        long s = U.getLongOpaque(this, STATE) & RSAFE, nextState;
        if(casState(s, nextState = s + RUNIT))
            return nextState;
        else
            return acquireRead(false, false, 0L);
    }

    public long tryReadLock() {
        return tryAcquireRead();
    }

    public long tryReadLock(long time, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(time);
        if(!Thread.interrupted()) {
            long nextState;
            if(tail == head && (nextState = tryAcquireRead()) != 0L)
                return nextState;
            if(nanos <= 0L)
                return 0L;
            nextState = acquireRead(true, true, System.nanoTime() + nanos);
            if(nextState != INTERRUPTED)
                return nextState;
        }
        throw new InterruptedException();
    }

    public long readLockInterruptibly() throws InterruptedException {
        long nextState;
        if(!Thread.interrupted() && ((nextState = tryAcquireRead()) != 0L || (nextState = acquireRead(true, false, 0L)) != INTERRUPTED))
            return nextState;
        throw new InterruptedException();
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

    public void unlockWrite(long stamp) {
        // 检查stamp是不是没变，以及stamp是否是写锁（读锁、写锁和乐观读，它们三者的stamp都是不同的）
        if(state != stamp || (stamp & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        releaseWrite(stamp);
    }

    public void unlockRead(long stamp) {
        long s;
        long m;
        if((stamp & RBITS) != 0L) { // 确认持有读锁
            /** stamp要和state的stamp是一致的 */
            while(((s = state) & SBITS) == (stamp & SBITS) && ((m = s & RBITS) != 0L)) {
                if(m < RFULL) { // state里面的读锁计数没有满，直接处理state即可
                    if(casState(s, s - RUNIT)) {
                        /**
                         * 如果m等于1，说明当前只有1个读锁，并且在casState已经释放了，所以现在没有读锁了，所以尝试唤醒写线程。
                         * 注意这里其实是个优化，让最后一个读线程进行唤醒，不优化就是：只要有读线程锁释放，就尝试唤醒。
                         */
                        if(m == RUNIT)  //
                            signalNext(head);
                        return;
                    }
                }else if(tryDecReaderOverflow(s) != 0L) // state里面的读锁计数满了，直接处理readerOverflow变量的计数
                    return;
            }
        }
        throw new IllegalMonitorStateException();
    }

    public void unlock(long stamp) {
        if((stamp & WBIT) != 0L)    // 优先unlock写线程
            unlockWrite(stamp);
        else
            unlockRead(stamp);
    }

    /**
     * 三种使用场景：
     * 1、当前是乐观读stamp：意思就是当前没有任何锁，可以直接转成写锁。
     * 2、当前是写锁stamp：直接原样返回。
     * 3、当前是读锁stamp：如果当前只剩你这一个读锁了，则可以升级成写锁，否则失败。
     */
    public long tryConvertToWriteLock(long stamp) {
        /**
         * 传入的stamp所代表的锁模式，有三种情况
         * 1、a == 0：乐观读，即没有读和写锁位。
         * 2、a == RUNIT：有读锁标记。
         * 3、a == WBIT：有写锁标记。
         */
        long a = stamp & ABITS; //
        long m;
        long s;
        long nextState;
        while(((s = state) & SBITS) == (stamp & SBITS)) {   // 检查stamp和state的stamp是否一致
            if((m = s & ABITS) == 0L) { // 乐观读stamp
                if(a != 0L) // 状态对不上
                    break;
                if(casState(s, nextState = s | WBIT)) { // 升级写锁
                    U.storeStoreFence();
                    return nextState;
                }
            }else if(m == WBIT) {   // 写锁stamp
                if (a != m)
                    break;
                return stamp;   // 直接返回即可
            }else if(m == RUNIT && a != 0L) {   // 读锁stamp
                if(casState(s, nextState = s - RUNIT + WBIT))
                    return nextState;
            }else
                break;
        }
        return 0L;
    }

    public long tryConvertToReadLock(long stamp) {
        long a;
        long s;
        long nextState;
        while(((s = state) & SBITS) == (stamp & SBITS)) {
            if((a = stamp & ABITS) >= WBIT) {
                if(s != stamp) // write stamp
                    break;
                nextState = state = unlockWriteState(s) + RUNIT;
                signalNext(head);
                return nextState;
            }else if(a == 0L) { // optimistic read stamp
                if((s & ABITS) < RFULL) {
                    if(casState(s, nextState = s + RUNIT))
                        return nextState;
                }else if ((nextState = tryIncReaderOverflow(s)) != 0L)
                    return nextState;
            }else { // already a read stamp
                if((s & ABITS) == 0L)
                    break;
                return stamp;
            }
        }
        return 0L;
    }

    public long tryConvertToOptimisticRead(long stamp) {
        long a;
        long m;
        long s;
        long nextState;
        while(((s = state) & SBITS) == (stamp & SBITS)) {
            if((a = stamp & ABITS) >= WBIT) {
                if(s != stamp)   // write stamp
                    break;
                return releaseWrite(s);
            }else if (a == 0L) { // already an optimistic read stamp
                return stamp;
            }else if ((m = s & ABITS) == 0L) { // invalid read stamp
                break;
            }else if (m < RFULL) {
                if(casState(s, nextState = s - RUNIT)) {
                    if(m == RUNIT)
                        signalNext(head);
                    return nextState & SBITS;
                }
            }else if((nextState = tryDecReaderOverflow(s)) != 0L)
                return nextState & SBITS;
        }
        return 0L;
    }

    public boolean tryUnlockWrite() {
        long s;
        if(((s = state) & WBIT) != 0L) {    // 如果有写锁
            releaseWrite(s);
            return true;
        }
        return false;
    }

    public boolean tryUnlockRead() {
        long s, m;
        while((m = (s = state) & ABITS) != 0L && m < WBIT) {
            if(m < RFULL) {
                if(casState(s, s - RUNIT)) {
                    if(m == RUNIT)
                        signalNext(head);
                    return true;
                }
            }else if (tryDecReaderOverflow(s) != 0L)
                return true;
        }
        return false;
    }

    // status monitoring methods

    private int getReadLockCount(long s) {
        long readers;
        if((readers = s & RBITS) >= RFULL)
            readers = RFULL + readerOverflow;
        return (int)readers;
    }

    public boolean isWriteLocked() {
        return (state & WBIT) != 0L;
    }

    public boolean isReadLocked() {
        return (state & RBITS) != 0L;
    }

    public static boolean isWriteLockStamp(long stamp) {
        return (stamp & ABITS) == WBIT;
    }

    public static boolean isReadLockStamp(long stamp) {
        return (stamp & RBITS) != 0L;
    }

    public static boolean isLockStamp(long stamp) {
        return (stamp & ABITS) != 0L;
    }

    public static boolean isOptimisticReadStamp(long stamp) {
        return (stamp & ABITS) == 0L && stamp != 0L;
    }

    public int getReadLockCount() {
        return getReadLockCount(state);
    }

    public String toString() {
        long s = state;
        return super.toString() +
                ((s & ABITS) == 0L ? "[Unlocked]" :
                        (s & WBIT) != 0L ? "[Write-locked]" :
                                "[Read-locks:" + getReadLockCount(s) + "]");
    }

    // views

    public Lock asReadLock() {
        ReadLockView v;
        if((v = readLockView) != null)
            return v;
        return readLockView = new ReadLockView();
    }

    public Lock asWriteLock() {
        WriteLockView v;
        if((v = writeLockView) != null)
            return v;
        return writeLockView = new WriteLockView();
    }

    public ReadWriteLock asReadWriteLock() {
        ReadWriteLockView v;
        if((v = readWriteLockView) != null)
            return v;
        return readWriteLockView = new ReadWriteLockView();
    }

    // view classes

    /**
     * 缩水版视图，只提供了读锁获取和释放
     */
    final class ReadLockView implements Lock {
        public void lock() {
            readLock();
        }

        public void lockInterruptibly() throws InterruptedException {
            readLockInterruptibly();
        }

        public boolean tryLock() {
            return tryReadLock() != 0L;
        }

        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return tryReadLock(time, unit) != 0L;
        }

        public void unlock() {
            unstampedUnlockRead();
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 缩水版视图，只提供了写锁获取和释放
     */
    final class WriteLockView implements Lock {

        public void lock() {
            writeLock();
        }

        public void lockInterruptibly() throws InterruptedException {
            writeLockInterruptibly();
        }
        public boolean tryLock() {
            return tryWriteLock() != 0L;
        }

        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return tryWriteLock(time, unit) != 0L;
        }

        public void unlock() {
            unstampedUnlockWrite();
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    final class ReadWriteLockView implements ReadWriteLock {

        public Lock readLock() {
            return asReadLock();
        }
        public Lock writeLock() {
            return asWriteLock();
        }
    }

    /**
     * 释放写锁，因为StampedLock的释放方法必须带stamp参数，但是视图版本没有这个参数，只能重新实现一个特供版本
     */
    final void unstampedUnlockWrite() {
        long s;
        if(((s = state) & WBIT) == 0L)  // state必须有写锁
            throw new IllegalMonitorStateException();
        releaseWrite(s);
    }

    /**
     * 释放读锁，因为StampedLock的释放方法必须带stamp参数，但是视图版本没有这个参数，只能重新实现一个特供版本
     */
    final void unstampedUnlockRead() {
        long s; // state
        long m; // 读锁计数
        while((m = (s = state) & RBITS) > 0L) { // state必须有读锁
            if(m < RFULL) { // 读锁计数没有溢出state，直接减少state读锁计数即可
                if(casState(s, s - RUNIT)) {
                    if(m == RUNIT)  // 自己释放的是最后一个读锁
                        signalNext(head);
                }
            }else if(tryDecReaderOverflow(s) != 0L)
                return;
        }
        throw new IllegalMonitorStateException();
    }

    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        state = ORIGIN; // reset to unlocked state
    }

    // overflow handling methods

    /**
     * state已经读锁计数满了，才会调用这个方法继续累加读锁计数。
     */
    private long tryIncReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        if((s & ABITS) != RFULL)
            Thread.onSpinWait();
        else if(casState(s, s | RBITS)) {
            ++readerOverflow;
            return state = s;
        }
        return 0L;
    }

    private long tryDecReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        if((s & ABITS) != RFULL)
            Thread.onSpinWait();
        else if(casState(s, s | RBITS)) {
            int r; long nextState;
            if((r = readerOverflow) > 0) {
                readerOverflow = r - 1;
                nextState = s;
            }else
                nextState = s - RUNIT;
            return state = nextState;
        }
        return 0L;
    }

    // release methods

    static final void signalNext(Node h) {
        Node s;
        if(h != null && (s = h.next) != null && s.status > 0) {
            s.getAndUnsetStatus(WAITING);
            LockSupport.unpark(s.waiter);
        }
    }

    private static void signalCowaiters(ReaderNode node) {
        if(node != null) {
            for(ReaderNode c; (c = node.cowaiters) != null; ) {
                if(node.casCowaiters(c, c.cowaiters))
                    LockSupport.unpark(c.waiter);
            }
        }
    }

    // queue link methods

    private boolean casTail(Node c, Node v) {
        return U.compareAndSetReference(this, TAIL, c, v);
    }

    /**
     * 初始化queue，给head和tail关联dummy node。
     */
    private void tryInitializeHead() {
        Node h = new WriterNode();
        if(U.compareAndSetReference(this, HEAD, null, h))
            tail = h;
    }

    /**
     * 排队获取写锁的核心方法，需要注意queue并不是严格的FIFO，而是写优先 + 读批量。
     * 总体流程：拿不到写锁时，线程进入queue，在检查前驱 -> 尝试抢锁 -> 入队 -> 自旋 -> 挂起 -> 被唤醒后重试这个循环里，直到成功、超时或中断。
     */
    private long acquireWrite(boolean interruptible, boolean timed, long time) {
        byte spins = 0; // 当前轮还能自旋多少次
        byte postSpins = 0; // 每次park醒来后，下一轮给多少次的自旋数
        boolean interrupted = false;    // 等待期间是否被中断过
        boolean first = false;  // 当前node是否已经排到了队首，即前驱是否是head
        WriterNode node = null; // 当前线程对应的等待node
        Node pred = null;   // 当前node的前驱
        for(long s, nextState; ; ) {
            /**
             * 1. 如果我已经在队列里，先检查前驱和队列位置
             * 2. 如果我现在有资格抢锁，就尝试 CAS 抢写锁
             * 3. 还没有节点就创建节点
             * 4. 还没入队就尝试入队
             * 5. 如果已经在队首附近，先小自旋
             * 6. 设置自己为 WAITING
             * 7. park 挂起，等待被唤醒
             * 8. 醒来后继续下一轮
             */

            /**
             * 如果我已经有node了，并且我前面还有node，那先看pred是否正常。
             * 注意这个判断最后尝试给first赋值了，关系到下一段if的判断结果。
             */
            if(!first && (pred = (node == null) ? null : node.prev) != null && !(first = (head == pred))) {
                if(pred.status < 0) {   // 前驱不正常，则触发清理然后重来
                    cleanQueue();
                    continue;
                }else if(pred.prev == null) {   // 前驱还未稳定，重来
                    Thread.onSpinWait();
                    continue;
                }
            }
            /**
             * 检查是否可以抢锁了，前提是同时满足以下三个条件：
             * 1、当前线程还没有入队，或者当前线程已经排到最前面了。
             * 2、当前没有写锁或者读锁。
             * 3、1和2都满足，则执行抢锁。
             * 以下是状态机
             */
            if((first || pred == null) && ((s = state) & ABITS) == 0L && casState(s, nextState = s | WBIT)) {
                U.storeStoreFence();
                /**
                 * 抢到写锁了，有两种可能：
                 * 1、直接进来抢的，线程不在队列里面
                 * 2、线程在队列里，排到了最前面然后自己抢到的，要清理对应node
                 */
                if(first) {
                    node.prev = null;
                    head = node;
                    pred.next = null;
                    pred.waiter = null;
                    if(interrupted)
                        Thread.currentThread().interrupt();
                }
                return nextState;
            }else if(node == null) {    // 没抢到锁，并且是新来的线程，还没入队过，就初始化对应node
                node = new WriterNode();
            }else if(pred == null) {    // 当前线程已经是node了，但是还没有入队，进入入队的流程
                Node t = tail;
                node.setPrevRelaxed(t);
                if(t == null)
                    tryInitializeHead();
                else if(!casTail(t, node))
                    node.setPrevRelaxed(null);
                else
                    t.next = node;
            }else if(first && spins != 0) { // 如果当前线程已经排到最前面了，而且还有自旋次数，就自旋而不是park
                --spins;
                Thread.onSpinWait();
            }else if(node.status == 0) {
                if(node.waiter == null)
                    node.waiter = Thread.currentThread();
                node.status = WAITING;
            }else { // 继续挂起等待
                long nanos;
                /**
                 * 计算下一轮的自旋次数。
                 * 作用是：每次从park醒来后，给一个递增的小自旋预算，例如1、3、7、15。
                 * 目的是让刚醒来的队首线程，有更大机会直接接到锁（因为自旋过程中就相当于不参与抢锁了）
                 */
                spins = postSpins = (byte)((postSpins << 1) | 1);
                if(!timed)  // 永久park
                    LockSupport.park(this);
                else if((nanos = time - System.nanoTime()) > 0L)    // 限时park
                    LockSupport.parkNanos(this, nanos);
                else    // 限时已到
                    break;
                // 到这里说明park醒来了，要清理状态，不能再是WAITING了，要参与抢锁了
                node.clearStatus();
                /**
                 * 处理中断：
                 * 1、记录等待期间是否被中断过。
                 * 2、如果当前方法要求可中断，就退出循环，后面走cancel逻辑。
                 * 3、如果不可中断，就继续抢锁，只是把中断信息记录以下。
                 */
                if((interrupted |= Thread.interrupted()) && interruptible)
                    break;
            }
        }
        /**
         * 到这里说明没有成功拿到锁，因为：
         * 1、超时。
         * 2、可中断模式下被中断了。
         * 需要：
         * 1、取消当前node。
         * 2、清理queue关系。
         * 3、必要时唤醒后继node。
         * 4、返回失败结果或特殊stamp。
         */
        return cancelAcquire(node, interrupted);
    }

    private long acquireRead(boolean interruptible, boolean timed, long time) {
        boolean interrupted = false;
        ReaderNode node = null;

        while(true) {
            ReaderNode leader;
            long nextState;
            Node tailPred = null;
            Node t = tail;
            /**
             * 如果queue是空的，则直接尝试读锁，即fast-path。
             */
            if((t == null || (tailPred = t.prev) == null) && (nextState = tryAcquireRead()) != 0L) {
                return nextState;
            }else if(t == null) {   // queue还没初始化
                tryInitializeHead();
            }else if(tailPred == null || !(t instanceof ReaderNode)) {
                /**
                 * 比较复杂的一段逻辑，作用是：
                 * 如果当前queue的尾节点不是ReaderNode，那么当前线程不能cowait，只能自己创建一个ReaderNode并查到queue尾部，成为新leader。
                 * 上面最重要的判断是t instanceof ReaderNode，如果不满足，则说明当前没有可供依附的reader leader，
                 * 所以只能自己入queue，并成为新的leader。
                 */
                if(node == null)    // 创建ReaderNode
                    node = new ReaderNode();
                if(tail == t) { // 并发保护，再次确认tail是否还是刚才的t
                    // 将node插入队尾
                    node.setPrevRelaxed(t);
                    if(casTail(t, node)) {
                        t.next = node;
                        break;  // node成为了leader node，跳出while循环进入第二阶段排队逻辑
                    }
                    node.setPrevRelaxed(null);
                }
            }else if((leader = (ReaderNode)t) == tail) {
                /**
                 * 直接对应上面的分支，这里是tail就是leader，直接尝试拼车，不会单独在queue里排队
                 */
                for(boolean attached = false; ; ) {
                    if(leader.status < 0 || leader.prev == null)    // leader是否还有效
                        break;
                    else if(node == null)   // node还未初始化
                        node = new ReaderNode();
                    else if(node.waiter == null)
                        node.waiter = Thread.currentThread();
                    else if(!attached) {    // 尝试将node挂在leader的cowaiters后面
                        ReaderNode c = leader.cowaiters;
                        node.setCowaitersRelaxed(c);
                        attached = leader.casCowaiters(c, node);
                        if(!attached)
                            node.setCowaitersRelaxed(null);
                    }else { // 挂好了，进入阻塞
                        long nanos = 0L;
                        if(!timed)
                            LockSupport.park(this);
                        else if((nanos = time - System.nanoTime()) > 0L)
                            LockSupport.parkNanos(this, nanos);
                        interrupted |= Thread.interrupted();
                        if((interrupted && interruptible) || (timed && nanos <= 0L))
                            return cancelCowaiter(node, leader, interrupted);
                    }
                }
                // 到这里说明要么leader不再有效，要么中断或超时
                if(node != null)
                    node.waiter = null;
                long ns = tryAcquireRead(); // 重新尝试抢锁
                signalCowaiters(leader);
                if(interrupted)
                    Thread.currentThread().interrupt();
                if(ns != 0L)
                    return ns;
                else
                    node = null;
            }
        }

        // 到这里说明当前node是一个leader，下面的排队等待代码和acquireWrite对应的代码基本是一样的
        byte spins = 0;
        byte postSpins = 0;
        boolean first = false;
        Node pred = null;
        for(long nextState; ; ) {
            if(!first && (pred = node.prev) != null && !(first = (head == pred))) {
                if(pred.status < 0) {
                    cleanQueue();           // predecessor cancelled
                    continue;
                }else if (pred.prev == null) {
                    Thread.onSpinWait();    // ensure serialization
                    continue;
                }
            }
            if((first || pred == null) && (nextState = tryAcquireRead()) != 0L) {
                if(first) {
                    node.prev = null;
                    head = node;
                    pred.next = null;
                    node.waiter = null;
                }
                signalCowaiters(node);
                if(interrupted)
                    Thread.currentThread().interrupt();
                return nextState;
            }else if(first && spins != 0) {
                --spins;
                Thread.onSpinWait();
            }else if(node.status == 0) {
                if(node.waiter == null)
                    node.waiter = Thread.currentThread();
                node.status = WAITING;
            }else {
                long nanos;
                spins = postSpins = (byte)((postSpins << 1) | 1);
                if(!timed)
                    LockSupport.park(this);
                else if((nanos = time - System.nanoTime()) > 0L)
                    LockSupport.parkNanos(this, nanos);
                else
                    break;
                node.clearStatus();
                if((interrupted |= Thread.interrupted()) && interruptible)
                    break;
            }
        }
        return cancelAcquire(node, interrupted);
    }

    // Cancellation support

    /**
     * 和AQS基本是类似的，还是三兄弟模式来清理有问题的node，不再学习了。
     */
    private void cleanQueue() {
        while(true) {
            for (Node q = tail, s = null, p, n;;) { // (p, q, s) triples
                if (q == null || (p = q.prev) == null)
                    return;                      // end of list
                if (s == null ? tail != q : (s.prev != q || s.status < 0))
                    break;                       // inconsistent
                if (q.status < 0) {              // cancelled
                    if ((s == null ? casTail(q, p) : s.casPrev(q, p)) &&
                            q.prev == p) {
                        p.casNext(q, s);         // OK if fails
                        if (p.prev == null)
                            signalNext(p);
                    }
                    break;
                }
                if ((n = p.next) != q) {         // help finish
                    if (n != null && q.prev == p && q.status >= 0) {
                        p.casNext(n, q);
                        if (p.prev == null)
                            signalNext(p);
                    }
                    break;
                }
                s = q;
                q = q.prev;
            }
        }
    }

    /**
     * 把当前reader节点从leader的cowait链表中删除。
     */
    private void unlinkCowaiter(ReaderNode node, ReaderNode leader) {
        if(leader != null) {
            while(leader.prev != null && leader.status >= 0) {  // leader是否仍然有效
                for(ReaderNode p = leader, q; ; p = q) {    // 遍历cowait链，p是前驱，q是当前
                    if((q = p.cowaiters) == null)   // 注意p.cowaiters其实相当于p.next
                        return;
                    if(q == node) {
                        p.casCowaiters(q, q.cowaiters);
                        break;  // 即使CAS成功了，还要回到while重新检查，因为可能发生并发修改
                    }
                }
            }
        }
    }

    private long cancelAcquire(Node node, boolean interrupted) {
        if(node != null) {  // 清理node
            node.waiter = null;
            node.status = CANCELLED;
            cleanQueue();
            if(node instanceof ReaderNode)
                signalCowaiters((ReaderNode)node);
        }
        // 如果是可中断模式，并且确实线程被中断了，则返回负值，否则返回0
        return (interrupted || Thread.interrupted()) ? INTERRUPTED : 0L;
    }

    private long cancelCowaiter(ReaderNode node, ReaderNode leader, boolean interrupted) {
        if(node != null) {
            node.waiter = null;
            node.status = CANCELLED;
            unlinkCowaiter(node, leader);
        }
        return (interrupted || Thread.interrupted()) ? INTERRUPTED : 0L;
    }

    // Unsafe
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long STATE = U.objectFieldOffset(StampedLock.class, "state");
    private static final long HEAD = U.objectFieldOffset(StampedLock.class, "head");
    private static final long TAIL = U.objectFieldOffset(StampedLock.class, "tail");

    static {
        Class<?> ensureLoaded = LockSupport.class;
    }
}

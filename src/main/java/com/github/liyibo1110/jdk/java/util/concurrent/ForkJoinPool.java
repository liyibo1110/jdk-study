package com.github.liyibo1110.jdk.java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.locks.LockSupport;

/**
 * 这个组件属于最终boss级别的，不应该硬学，目标是能回答以下五个问题就够了：
 * 1、任务是怎么提交进来的？
 * 2、线程是怎么拿任务执行的？
 * 3、work-stealing是怎么发生的？
 * 4、阻塞时为什么要补偿线程？
 * 5、commonPool是怎么工作的？
 * @author liyibo
 * @date 2026-03-23 15:20
 */
public class ForkJoinPool extends AbstractExecutorService {

    // Static utilities

    // Nested classes

    public interface ForkJoinWorkerThreadFactory {
        ForkJoinWorkerThread newThread(ForkJoinPool pool);
    }

    static final class DefaultForkJoinWorkerThreadFactory implements ForkJoinWorkerThreadFactory {
        // ACC for access to the factory
        @SuppressWarnings("removal")
        private static final AccessControlContext ACC = contextWithPermissions(
                new RuntimePermission("getClassLoader"),
                new RuntimePermission("setContextClassLoader"));
        @SuppressWarnings("removal")
        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return AccessController.doPrivileged(
                    new PrivilegedAction<>() {
                        public ForkJoinWorkerThread run() {
                            return new ForkJoinWorkerThread(null, pool, true, false);
                        }},
                    ACC);
        }
    }

    static final class DefaultCommonPoolForkJoinWorkerThreadFactory implements ForkJoinWorkerThreadFactory {
        @SuppressWarnings("removal")
        private static final AccessControlContext ACC = contextWithPermissions(
                modifyThreadPermission,
                new RuntimePermission("enableContextClassLoaderOverride"),
                new RuntimePermission("modifyThreadGroup"),
                new RuntimePermission("getClassLoader"),
                new RuntimePermission("setContextClassLoader"));

        @SuppressWarnings("removal")
        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return AccessController.doPrivileged(
                    new PrivilegedAction<>() {
                        public ForkJoinWorkerThread run() {
                            return System.getSecurityManager() == null ?
                                    new ForkJoinWorkerThread(null, pool, true, true):
                                    new ForkJoinWorkerThread.InnocuousForkJoinWorkerThread(pool); }},
                    ACC);
        }
    }

    // Constants shared across ForkJoinPool and WorkQueue
    // Bounds
    static final int SWIDTH       = 16;            // width of short
    static final int SMASK        = 0xffff;        // short bits == max index
    static final int MAX_CAP      = 0x7fff;        // max #workers - 1

    // Masks and units for WorkQueue.phase and ctl sp subfield
    static final int UNSIGNALLED  = 1 << 31;       // must be negative
    static final int SS_SEQ       = 1 << 16;       // version count

    // Mode bits and sentinels, some also used in WorkQueue fields
    static final int FIFO         = 1 << 16;       // fifo queue or access mode
    static final int SRC          = 1 << 17;       // set for valid queue ids
    static final int INNOCUOUS    = 1 << 18;       // set for Innocuous workers
    static final int QUIET        = 1 << 19;       // quiescing phase or source
    static final int SHUTDOWN     = 1 << 24;
    static final int TERMINATED   = 1 << 25;
    static final int STOP         = 1 << 31;       // must be negative
    static final int UNCOMPENSATE = 1 << 16;       // tryCompensate return

    static final int INITIAL_QUEUE_CAPACITY = 1 << 8;

    /**
     * 线程私有的双端队列：注意是每个线程都有自己的workQueue，所以FJP里面是一堆WorkQueue（每个线程一个），而不是就一个
     */
    static final class WorkQueue {

        volatile int phase;

        int stackPred;

        int config;

        int base;

        ForkJoinTask<?>[] array;

        final ForkJoinWorkerThread owner;

        // Support for atomic operations
        private static final VarHandle QA; // for array slots
        private static final VarHandle SOURCE;
        private static final VarHandle BASE;
        static final ForkJoinTask<?> getSlot(ForkJoinTask<?>[] a, int i) {
            return (ForkJoinTask<?>)QA.getAcquire(a, i);
        }
        static final ForkJoinTask<?> getAndClearSlot(ForkJoinTask<?>[] a, int i) {
            return (ForkJoinTask<?>)QA.getAndSet(a, i, null);
        }
        static final void setSlotVolatile(ForkJoinTask<?>[] a, int i, ForkJoinTask<?> v) {
            QA.setVolatile(a, i, v);
        }
        static final boolean casSlotToNull(ForkJoinTask<?>[] a, int i, ForkJoinTask<?> c) {
            return QA.compareAndSet(a, i, c, null);
        }
        final boolean tryLock() {
            return SOURCE.compareAndSet(this, 0, 1);
        }
        final void setBaseOpaque(int b) {
            BASE.setOpaque(this, b);
        }

        WorkQueue(ForkJoinWorkerThread owner, boolean isInnocuous) {
            this.config = (isInnocuous) ? INNOCUOUS : 0;
            this.owner = owner;
        }

        /**
         * Constructor used for external queues.
         */
        WorkQueue(int config) {
            array = new ForkJoinTask<?>[INITIAL_QUEUE_CAPACITY];
            this.config = config;
            owner = null;
            phase = -1;
        }

        final int getPoolIndex() {
            return (config & 0xffff) >>> 1; // ignore odd/even tag bit
        }

        final int queueSize() {
            VarHandle.acquireFence(); // ensure fresh reads by external callers
            int n = top - base;
            return (n < 0) ? 0 : n;   // ignore transient negative
        }

        final boolean isEmpty() {
            return !((source != 0 && owner == null) || top - base > 0);
        }

        final void push(ForkJoinTask<?> task, ForkJoinPool pool) {

        }
    }

    // static fields (initialized in static initializer below)

    // static configuration constants

    // Lower and upper word masks

    // Instance fields

    // Support for atomic operations

    private static final VarHandle CTL;
    private static final VarHandle MODE;
    private static final VarHandle THREADIDS;
    private static final VarHandle POOLIDS;
    private boolean compareAndSetCtl(long c, long v) {
        return CTL.compareAndSet(this, c, v);
    }
    private long compareAndExchangeCtl(long c, long v) {
        return (long)CTL.compareAndExchange(this, c, v);
    }
    private long getAndAddCtl(long v) {
        return (long)CTL.getAndAdd(this, v);
    }
    private int getAndBitwiseOrMode(int v) {
        return (int)MODE.getAndBitwiseOr(this, v);
    }
    private int getAndAddThreadIds(int x) {
        return (int)THREADIDS.getAndAdd(this, x);
    }
    private static int getAndAddPoolIds(int x) {
        return (int)POOLIDS.getAndAdd(x);
    }

    // Creating, registering and deregistering workers

    // Utilities used by ForkJoinTask

    // Termination

    // Exported methods

    // Constructors

    // helper method for commonPool constructor

    // Execution methods

    // AbstractExecutorService methods

    // Task to hold results from InvokeAnyTasks

    // Variant of AdaptedInterruptibleCallable with results in InvokeAnyRoot

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            CTL = l.findVarHandle(java.util.concurrent.ForkJoinPool.class, "ctl", long.class);
            MODE = l.findVarHandle(java.util.concurrent.ForkJoinPool.class, "mode", int.class);
            THREADIDS = l.findVarHandle(java.util.concurrent.ForkJoinPool.class, "threadIds", int.class);
            POOLIDS = l.findStaticVarHandle(java.util.concurrent.ForkJoinPool.class, "poolIds", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }

        // Reduce the risk of rare disastrous classloading in first call to
        // LockSupport.park: https://bugs.openjdk.java.net/browse/JDK-8074773
        Class<?> ensureLoaded = LockSupport.class;

        int commonMaxSpares = DEFAULT_COMMON_MAX_SPARES;
        try {
            String p = System.getProperty("java.util.concurrent.ForkJoinPool.common.maximumSpares");
            if (p != null)
                commonMaxSpares = Integer.parseInt(p);
        } catch (Exception ignore) {}
        COMMON_MAX_SPARES = commonMaxSpares;

        defaultForkJoinWorkerThreadFactory = new java.util.concurrent.ForkJoinPool.DefaultForkJoinWorkerThreadFactory();
        modifyThreadPermission = new RuntimePermission("modifyThread");
        @SuppressWarnings("removal")
        java.util.concurrent.ForkJoinPool tmp = AccessController.doPrivileged(new PrivilegedAction<>() {
            public java.util.concurrent.ForkJoinPool run() {
                return new java.util.concurrent.ForkJoinPool((byte)0);
            }
        });
        common = tmp;

        COMMON_PARALLELISM = Math.max(common.mode & SMASK, 1);
    }
}

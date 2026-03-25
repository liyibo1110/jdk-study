package com.github.liyibo1110.jdk.java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.Permission;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

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

    private static void checkPermission() {
        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if(security != null)
            security.checkPermission(modifyThreadPermission);
    }

    @SuppressWarnings("removal")
    static AccessControlContext contextWithPermissions(Permission... perms) {
        Permissions permissions = new Permissions();
        for(Permission perm : perms)
            permissions.add(perm);
        return new AccessControlContext(new ProtectionDomain[] { new ProtectionDomain(null, permissions) });
    }

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

        /**
         * 调度控制字段之一：标记队列是否在工作。
         * >=0：活跃
         * <0：不活跃
         * pool会用它判断：要不要唤醒线程、要不要扫描这个队列。
         */
        volatile int phase;

        /**
         * 调度控制字段之二：在pool的ctl栈中做链接（调度用），属于FJP内部线程管理结构的一部分。
         */
        int stackPred;

        /**
         * 调度控制字段之三：是个位标志集合，包含了：
         * 1、队列index
         * 2、FIFO/LIFO模式
         * 3、是否innocuous
         * 功能是决定这个队列的行为模式。
         */
        int config;

        /** 队头指针，让其他worker来偷任务，是个队列 */
        int base;

        /**
         * 环形数组
         * [A, B, C, D]
         *  ↑        ↑
         * base     top
         * 自己往top放，同时从top拿。
         * 别人从base取。
         */
        ForkJoinTask<?>[] array;

        /**
         * 队列属于哪个线程：
         * 1、为null：外部线程提交队列。
         * 2、不为null：某worker的私有队列。
         * 因此要知道FJP不仅有worker队列，还有外部提交专属队列。
         */
        final ForkJoinWorkerThread owner;

        /** 队尾指针，自己从这里取任务，是个栈 */
        int top;

        /**
         * 多用途字段：
         * 1、作为锁：0|1
         * 2、标记来源
         * 3、控制共享队列
         * 可以理解为一个轻量级状态 + 锁的复用字段。
         */
        volatile int source;

        /**
         * 对应的worker偷了多少个任务，用于：
         * 1、负载均衡
         * 2、调度策略
         */
        int nsteals;

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

        /**
         * owner线程把一个新任务压到自己的队列尾（top）
         */
        final void push(ForkJoinTask<?> task, ForkJoinPool pool) {
            ForkJoinTask<?>[] a = array;
            int s = top++;  // s保存旧的top值，s就是放task的位置
            int d = s - base;   // 之前队列里大概有多少个task
            int cap;
            int m;
            if(a != null && pool != null && (cap = a.length) > 0) {
                setSlotVolatile(a, (m = cap - 1) & s, task);    // 把task放到s这个位置
                if(d == m)  // 数组满了要扩容
                    growArray();
                /**
                 * 必要时唤醒线程，分两种情况：
                 * 1、d == m：说明刚刚扩容了，队列状态有明显变化，可能需要唤醒别的worker来干活。
                 * 2、a[m & (s - 1)] == null：插入前队列可能是空的，这里是在看前一个位置是不是空，大概判断：
                 * 这次push之前，队列是不是接近空队列的状态，如果之前空，现在来了新任务，要让池里可能休眠的线程起来干活。
                 */
                if (d == m || a[m & (s - 1)] == null)
                    pool.signalWork(); // signal if was empty or resized
            }
        }

        /**
         * 和上面的push类似，在持有submission queue锁的前提下，把任务压到这个共享队列，并解锁。
         * 注意在submissionQueue方法里调用的tryLock，
         */
        final boolean lockedPush(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a = array;
            int s = top++;
            int d = s - base;
            int cap;
            int m;
            if(a != null && (cap = a.length) > 0) {
                a[(m = cap - 1) & s] = task;
                if(d == m)
                    growArray();
                source = 0; // 重要：解锁指的是把这个source改成0，解锁不需要CAS操作
                if(d == m || a[m & (s - 1)] == null)
                    return true;
            }
            return false;
        }

        final void growArray() {
            ForkJoinTask<?>[] oldArray = array, newArray;
            int s = top - 1;
            int oldCap;
            int newCap;
            if(oldArray != null && (oldCap = oldArray.length) > 0 && (newCap = oldCap << 1) > 0) { // skip if disabled
                try {
                    newArray = new ForkJoinTask<?>[newCap];
                } catch (Throwable ex) {
                    top = s;
                    if(owner == null)
                        source = 0; // unlock
                    throw new RejectedExecutionException("Queue capacity exceeded");
                }
                int newMask = newCap - 1, oldMask = oldCap - 1;
                for(int k = oldCap; k > 0; --k, --s) {
                    ForkJoinTask<?> x;        // poll old, push to new
                    if((x = getAndClearSlot(oldArray, s & oldMask)) == null)
                        break;                // others already taken
                    newArray[s & newMask] = x;
                }
                VarHandle.releaseFence();     // fill before publish
                array = newArray;
            }
        }

        // Variants of pop

        /**
         * owner从自己队列尾取出一个task
         */
        private ForkJoinTask<?> pop() {
            ForkJoinTask<?> t = null;
            int s = top;    // 记录当前top位置
            int cap;
            ForkJoinTask<?>[] a;
            /**
             * base != s--：判断队列是否为空，之后s - 1，因此s现在变成了最后一个task的位置
             */
            if((a = array) != null && (cap = a.length) > 0 && base != s-- && (t = getAndClearSlot(a, (cap - 1) & s)) != null)
                top = s;    // task取出后，重置top位置
            return t;
        }

        final boolean tryUnpush(ForkJoinTask<?> task) {
            int s = top, cap; ForkJoinTask<?>[] a;
            if((a = array) != null && (cap = a.length) > 0 && base != s-- && casSlotToNull(a, (cap - 1) & s, task)) {
                top = s;
                return true;
            }
            return false;
        }

        final boolean externalTryUnpush(ForkJoinTask<?> task) {
            boolean taken = false;
            for(;;) {
                int s = top, cap, k; ForkJoinTask<?>[] a;
                if((a = array) == null || (cap = a.length) <= 0 || a[k = (cap - 1) & (s - 1)] != task)
                    break;
                if(tryLock()) {
                    if(top == s && array == a) {
                        if(taken = casSlotToNull(a, k, task)) {
                            top = s - 1;
                            source = 0;
                            break;
                        }
                    }
                    source = 0; // release lock for retry
                }
                Thread.yield(); // trylock failure
            }
            return taken;
        }

        final boolean tryRemove(ForkJoinTask<?> task, boolean owned) {
            boolean taken = false;
            int p = top, cap;
            ForkJoinTask<?>[] a;
            ForkJoinTask<?> t;
            if((a = array) != null && task != null && (cap = a.length) > 0) {
                int m = cap - 1, s = p - 1, d = p - base;
                for(int i = s, k; d > 0; --i, --d) {
                    if((t = a[k = i & m]) == task) {
                        if(owned || tryLock()) {
                            if((owned || (array == a && top == p)) && (taken = casSlotToNull(a, k, t))) {
                                for(int j = i; j != s; ) // shift down
                                    a[j & m] = getAndClearSlot(a, ++j & m);
                                top = s;
                            }
                            if(!owned)
                                source = 0;
                        }
                        break;
                    }
                }
            }
            return taken;
        }

        // variants of poll

        /**
         * 从队头base位置尝试拿走一个task，注意这不是owner自己操作的，而是：
         * 1、别的worker来偷任务。
         * 2、某些帮助执行逻辑来取头部任务。
         */
        final ForkJoinTask<?> tryPoll() {
            int cap;
            int b;  // base
            int k;  // 映射后的物理索引
            ForkJoinTask<?>[] a;
            if((a = array) != null && (cap = a.length) > 0) {
                /**
                 * 读出base槽位的task
                 */
                ForkJoinTask<?> t = getSlot(a, k = (cap - 1) & (b = base));
                /**
                 * 确认base没有变，即检查：刚才看到的base还是当前的base，如果不是，则放弃操作，否则调用casSlotToNull清空槽位。
                 */
                if(base == b++ && t != null && casSlotToNull(a, k, t)) {
                    setBaseOpaque(b);   // 成功获取了task后，推进base值
                    return t;
                }
            }
            return null;
        }

        /**
         * 从当前队列里取下一个本地任务，主要支持两种模式：
         * 1、默认：从top拿，也就是LIFO。
         * 2、FIFO：从base拿。
         * 这个队列在当前给定的配置下，下一份本地任务该从哪头获取。
         */
        final ForkJoinTask<?> nextLocalTask(int cfg) {
            ForkJoinTask<?> t = null;
            int s = top;
            int cap;
            ForkJoinTask<?>[] a;
            if((a = array) != null && (cap = a.length) > 0) {
                for(int b, d;;) {
                    if((d = s - (b = base)) <= 0)   // 判断队列里还有没有任务了，没有就退出
                        break;
                    /**
                     * 决定走LIFO还是FIFO：
                     * 1、只剩一个任务了。
                     * 2、配置里不是FIFO
                     */
                    if(d == 1 || (cfg & FIFO) == 0) {
                        if((t = getAndClearSlot(a, --s & (cap - 1))) != null)   // LIFO路径，从top拿
                            top = s;
                        break;
                    }
                    if((t = getAndClearSlot(a, b++ & (cap - 1))) != null) { // FIFO路径，从base拿
                        setBaseOpaque(b);
                        break;
                    }
                }
            }
            return t;
        }

        final ForkJoinTask<?> nextLocalTask() {
            return nextLocalTask(config);
        }

        final ForkJoinTask<?> peek() {
            VarHandle.acquireFence();
            int cap;
            ForkJoinTask<?>[] a;
            return((a = array) != null && (cap = a.length) > 0)
                    ? a[(cap - 1) & ((config & FIFO) != 0 ? base : top - 1)]
                    : null;
        }

        // specialized execution methods

        /**
         * worker已经拿到了task，接下来怎么一边执行它，一边把本地队列和被偷来的队列都顺手清理一下。
         * 重点提示：worker不是执行task就完了，还可能负责把相关任务都运行：
         * 先执行手头这个任务，然后不断尝试执行本地任务，如果本地没了，再继续从传入的队列里poll一些任务继续干
         */
        final void topLevelExec(ForkJoinTask<?> task, WorkQueue q) {
            int cfg = config;
            int nstolen = 1;    // 初始化偷取计数，为1是假定当前这个task就是偷来的（不精确）
            /**
             * 只要手里有任务就会执行，注意是循环，说明worker可能会执行很多个task
             */
            while(task != null) {
                task.doExec();
                /**
                 * 优先继续拿本地的任务（nextLocalTask），如果为空了，则尝试从q里面偷任务
                 */
                if((task = nextLocalTask(cfg)) == null && q != null && (task = q.tryPoll()) != null)
                    ++nstolen;  // 只要拿到task了，则增加计数
            }
            nsteals += nstolen; // 覆盖计数字段
            source = 0;
            if ((cfg & INNOCUOUS) != 0)
                ThreadLocalRandom.eraseThreadLocals(Thread.currentThread());
        }

        final int helpComplete(ForkJoinTask<?> task, boolean owned, int limit) {
            int status = 0, cap, k, p, s;
            ForkJoinTask<?>[] a;
            ForkJoinTask<?> t;
            while (task != null && (status = task.status) >= 0 &&
                    (a = array) != null && (cap = a.length) > 0 &&
                    (t = a[k = (cap - 1) & (s = (p = top) - 1)])
                            instanceof CountedCompleter) {
                CountedCompleter<?> f = (CountedCompleter<?>)t;
                boolean taken = false;
                for (;;) {     // exec if root task is a completer of t
                    if (f == task) {
                        if (owned) {
                            if ((taken = casSlotToNull(a, k, t)))
                                top = s;
                        }
                        else if (tryLock()) {
                            if (top == p && array == a && (taken = casSlotToNull(a, k, t)))
                                top = s;
                            source = 0;
                        }
                        if (taken)
                            t.doExec();
                        else if (!owned)
                            Thread.yield(); // tryLock failure
                        break;
                    }
                    else if ((f = f.completer) == null)
                        break;
                }
                if (taken && limit != 0 && --limit == 0)
                    break;
            }
            return status;
        }

        final void helpAsyncBlocker(ManagedBlocker blocker) {
            int cap, b, d, k;
            ForkJoinTask<?>[] a;
            ForkJoinTask<?> t;
            while (blocker != null && (d = top - (b = base)) > 0 &&
                    (a = array) != null && (cap = a.length) > 0 &&
                    (((t = getSlot(a, k = (cap - 1) & b)) == null && d > 1) ||
                            t instanceof CompletableFuture.AsynchronousCompletionTask) &&
                    !blocker.isReleasable()) {
                if (t != null && base == b++ && casSlotToNull(a, k, t)) {
                    setBaseOpaque(b);
                    t.doExec();
                }
            }
        }

        // misc

        private static AccessControlContext INNOCUOUS_ACC;

        @SuppressWarnings("removal")
        final void initializeInnocuousWorker() {
            AccessControlContext acc; // racy construction OK
            if((acc = INNOCUOUS_ACC) == null)
                INNOCUOUS_ACC = acc = new AccessControlContext(new ProtectionDomain[] { new ProtectionDomain(null, null) });
            Thread t = Thread.currentThread();
            ThreadLocalRandom.setInheritedAccessControlContext(t, acc);
            ThreadLocalRandom.eraseThreadLocals(t);
        }

        final boolean isApparentlyUnblocked() {
            Thread wt; Thread.State s;
            return ((wt = owner) != null &&
                    (s = wt.getState()) != Thread.State.BLOCKED &&
                    s != Thread.State.WAITING &&
                    s != Thread.State.TIMED_WAITING);
        }

        static {
            try {
                QA = MethodHandles.arrayElementVarHandle(ForkJoinTask[].class);
                MethodHandles.Lookup l = MethodHandles.lookup();
                SOURCE = l.findVarHandle(WorkQueue.class, "source", int.class);
                BASE = l.findVarHandle(WorkQueue.class, "base", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    // static fields (initialized in static initializer below)

    public static final ForkJoinWorkerThreadFactory defaultForkJoinWorkerThreadFactory;

    static final RuntimePermission modifyThreadPermission;

    static final ForkJoinPool common;

    static final int COMMON_PARALLELISM;

    private static final int COMMON_MAX_SPARES;

    private static volatile int poolIds;

    // static configuration constants

    private static final long DEFAULT_KEEPALIVE = 60_000L;

    private static final long TIMEOUT_SLOP = 20L;

    private static final int DEFAULT_COMMON_MAX_SPARES = 256;

    // Lower and upper word masks
    private static final long SP_MASK    = 0xffffffffL;
    private static final long UC_MASK    = ~SP_MASK;

    // Release counts
    private static final int  RC_SHIFT   = 48;
    private static final long RC_UNIT    = 0x0001L << RC_SHIFT;
    private static final long RC_MASK    = 0xffffL << RC_SHIFT;

    // Total counts
    private static final int  TC_SHIFT   = 32;
    private static final long TC_UNIT    = 0x0001L << TC_SHIFT;
    private static final long TC_MASK    = 0xffffL << TC_SHIFT;
    private static final long ADD_WORKER = 0x0001L << (TC_SHIFT + 15); // sign

    // Instance fields

    final long keepAlive;                // milliseconds before dropping if idle
    volatile long stealCount;            // collects worker nsteals
    int scanRover;                       // advances across pollScan calls
    volatile int threadIds;              // for worker thread names
    final int bounds;                    // min, max threads packed as shorts
    volatile int mode;                   // parallelism, runstate, queue mode
    WorkQueue[] queues;                  // main registry
    final ReentrantLock registrationLock;
    Condition termination;               // lazily constructed
    final String workerNamePrefix;       // null for common pool
    final ForkJoinWorkerThreadFactory factory;
    final Thread.UncaughtExceptionHandler ueh;  // per-worker UEH
    final Predicate<? super ForkJoinPool> saturate;

    volatile long ctl;

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

    /**
     * 依照pool的线程工厂，创建并启动一个新的ForkJoinWorkerThread
     */
    private boolean createWorker() {
        ForkJoinWorkerThreadFactory fac = factory;
        Throwable ex = null;
        ForkJoinWorkerThread wt = null;
        try {
            /**
             * 工厂存在就创建worker thread（注意传入了FJP实例）
             */
            if(fac != null && (wt = fac.newThread(this)) != null) {
                wt.start(); // 启动worker线程，让它自己跑自己的生命周期，本方法退出
                return true;
            }
        } catch (Throwable rex) {
            ex = rex;
        }
        deregisterWorker(wt, ex);   // 创建线程失败了，要走注销方法
        return false;
    }

    final String nextWorkerThreadName() {
        String prefix = workerNamePrefix;
        int tid = getAndAddThreadIds(1) + 1;
        if(prefix == null) // commonPool has no prefix
            prefix = "ForkJoinPool.commonPool-worker-";
        return prefix.concat(Integer.toString(tid));
    }

    /**
     * 给worker上户口，初始化一个worker对应的workQueue，并把它注册到FJP的队列数组中。
     */
    final void registerWorker(WorkQueue w) {
        /**
         * 阶段一：准备随机种子和锁：
         * 1、初始槽位选择。
         * 2、runWorker的随机扫描种子起点
         */
        ReentrantLock lock = registrationLock;
        ThreadLocalRandom.localInit();
        int seed = ThreadLocalRandom.getProbe();
        if(w != null && lock != null) {
            /**
             * 阶段二：计算modebits，初始化array
             * 1、确定这个队列的行为模式：FIFO合并worker自身的config
             * 2、给workQueue分配初始任务数组。
             * 3、把seed暂存在w.stackPred
             */
            int modebits = (mode & FIFO) | w.config;
            w.array = new ForkJoinTask<?>[INITIAL_QUEUE_CAPACITY];
            w.stackPred = seed;                         // stash for runWorker
            /**
             * 阶段三：必要时初始化innocuous worker
             */
            if((modebits & INNOCUOUS) != 0)
                w.initializeInnocuousWorker();
            /**
             * 阶段四：先猜一个初始槽位id：worker队列优先会放到奇数槽位。
             * 重要信息：FJP的queues[]通常用奇数位存worker的队列，偶数位存外部/共享提交的队列。
             */
            int id = (seed << 1) | 1;                   // initial index guess
            /**
             * 阶段五：加注册锁，开始真正挂接
             */
            lock.lock();
            try {
                WorkQueue[] qs; int n;                  // find queue index
                /**
                 * 阶段六：在queues[]里找空槽位。
                 */
                if((qs = queues) != null && (n = qs.length) > 0) {
                    int k = n, m = n - 1;
                    for(; qs[id &= m] != null && k > 0; id -= 2, k -= 2);
                    /**
                     * 阶段七：奇数位满了，就扩容。
                     */
                    if(k == 0)
                        id = n | 1;                     // resize below
                    /**
                     * 阶段八：正式发布config/phase，到这里workQueue有个正式身份了。
                     */
                    w.phase = w.config = id | modebits; // now publishable

                    /**
                     * 阶段九：如果数组够大，直接放进去。
                     */
                    if(id < n)
                        qs[id] = w;
                    /**
                     * 阶段十：如果不够大就扩容。
                     */
                    else {                              // expand array
                        int an = n << 1, am = an - 1;
                        WorkQueue[] as = new WorkQueue[an];
                        as[id & am] = w;
                        for(int j = 1; j < n; j += 2)
                            as[j] = qs[j];
                        for(int j = 0; j < n; j += 2) {
                            WorkQueue q;
                            if ((q = qs[j]) != null)    // shared queues may move
                                as[q.config & am] = q;
                        }
                        VarHandle.releaseFence();       // fill before publish
                        queues = as;
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 把一个worker从FJP中移除，汇总统计，清理剩余任务，并在必要时触发补位。
     */
    final void deregisterWorker(ForkJoinWorkerThread wt, Throwable ex) {
        ReentrantLock lock = registrationLock;
        /**
         * 阶段一：拿到worker对应的workQueue。
         * 说明createWorker失败时，传来的wt可能是null，deregisterWorker需要能容忍半初始化状态。
         */
        WorkQueue w = null;
        int cfg = 0;
        if(wt != null && (w = wt.workQueue) != null && lock != null) {
            WorkQueue[] qs; int n, i;
            /**
             * 阶段二：保存config和偷取计数
             */
            cfg = w.config;
            long ns = w.nsteals & 0xffffffffL;
            /**
             * 阶段三：在注册锁保护下，把自己从queues[]摘掉
             */
            lock.lock();                             // remove index from array
            if ((qs = queues) != null && (n = qs.length) > 0 && qs[i = cfg & (n - 1)] == w)
                qs[i] = null;
            stealCount += ns;                        // accumulate steals
            lock.unlock();
            /**
             * 阶段四：更新ctl里面的线程计数。
             */
            long c = ctl;
            if((cfg & QUIET) == 0) // unless self-signalled, decrement counts
                do {} while (c != (c = compareAndExchangeCtl(
                        c, ((RC_MASK & (c - RC_UNIT)) | (TC_MASK & (c - TC_UNIT)) | (SP_MASK & c)))));
            else if((int)c == 0)                    // was dropped on timeout
                cfg = 0;                             // suppress signal if last
            /**
             * 阶段五：把自己队列的残留任务取消掉。
             */
            for(ForkJoinTask<?> t; (t = w.pop()) != null; )
                ForkJoinTask.cancelIgnoringExceptions(t); // cancel tasks
        }

        /**
         * 阶段六：如果pool还没终止，且这个worker原本是有效source，可能要补一个新的。
         * worker退出后，pool会尝试维持自身运行能力。
         */
        if(!tryTerminate(false, false) && w != null && (cfg & SRC) != 0)
            signalWork();                            // possibly replace worker
        /**
         * 阶段七：如果之前有异常，就重新抛出。
         * 说明不是简单地吞异常，而是先清理，再把异常重新抛出：先收尾，后上抛。
         */
        if(ex != null)
            ForkJoinTask.rethrow(ex);
    }

    /**
     * 当发现有活可干的时候，尝试让系统多一个可运行的worker，有两种方式：
     * 1、叫醒一个已经空闲的worker。
     * 2、如果没有空闲的worker并且允许扩容，则新建worker。
     * ctl是一个全局调度控制字，里面记载着空闲的worker、活跃/总线程计数等信息，
     * signalWork的工作，就是围绕这个ctl做决策。
     */
    final void signalWork() {
        for(long c = ctl; c < 0L;) {
            int sp, i; WorkQueue[] qs; WorkQueue v;
            /**
             * 判读是否没有空闲的worker了
             */
            if((sp = (int)c & ~UNSIGNALLED) == 0) {
                if((c & ADD_WORKER) == 0L)  // 不允许再新增worker了，只能退出
                    break;
                /**
                 * compareAndExchangeCtl先更新计数，然后新建一个worker
                 */
                if(c == (c = compareAndExchangeCtl(c, ((RC_MASK & (c + RC_UNIT)) | (TC_MASK & (c + TC_UNIT)))))) {
                    createWorker();
                    break;
                }
            }else if((qs = queues) == null)
                break;                                // unstarted/terminated
            else if (qs.length <= (i = sp & SMASK))
                break;                                // terminated
            else if ((v = qs[i]) == null)
                break;                                // terminating
            else {  // 有空闲则worker则尝试唤醒
                long nc = (v.stackPred & SP_MASK) | (UC_MASK & (c + RC_UNIT));
                Thread vt = v.owner;
                if(c == (c = compareAndExchangeCtl(c, nc))) {
                    v.phase = sp;
                    LockSupport.unpark(vt);           // release idle worker
                    break;
                }
            }
        }
    }

    /**
     * worker线程启动后，不断随机扫描别人的队列找任务，找到就执行，找不到就尝试休眠，队列有任务了再被唤醒干活
     * @param w
     */
    final void runWorker(WorkQueue w) {
        /**
         * 保护性判断，pool是否处于可运行状态，以及worker对应的队列是否已存在
         */
        if(mode >= 0 && w != null) {           // skip on failed init
            w.config |= SRC;    // 把这个队列标记为合法source，意思就是队列可以被当作task的来源队列了
            int r = w.stackPred;    // 用来做伪随机扫描，目的是：避免重复传播
            int src = 0;    // 上一次的任务来源，目的是：追踪本轮是从哪个队列里偷的task
            do {
                /**
                 * 基于xorshift的伪随机更新，意思是：
                 * 每轮扫描前，都要把随机种子变一下，避免总是从固定位置开始扫，目的是分散worker的扫描起始位置
                 * scan结果小于0，说明没活干，会进入后面的awaitWork，结果等于0，说明线程虽然休眠过，但现在还应该继续工作循环。
                 * awaitWork返回非0值，意味着这个worker应该退出了，整个方法结束。
                 */
                r ^= r << 13; r ^= r >>> 17; r ^= r << 5;
            }while ((src = scan(w, src, r)) >= 0 || (src = awaitWork(w)) == 0);
        }
    }

    /**
     * 随机扫描所有队列，尝试从某个队列的base偷一个任务。
     * @param prevSrc
     * @param r 起始扫描位置
     */
    private int scan(WorkQueue w, int prevSrc, int r) {
        WorkQueue[] qs = queues;    // 注意是拿了整个池子的队列数组
        int n = (w == null || qs == null) ? 0 : qs.length;
        /**
         * step：每次跳多远，| 1操作目的是让步长是奇数，因为数组长度通常是2的幂，奇数步长更容易遍历所有槽位，不会只撞到部分下标。
         * 总之这个循环就是：以伪随机但可覆盖全表的方式扫描所有队列，而不是从0到n-1。
         */
        for(int step = (r >>> 16) | 1, i = n; i > 0; --i, r += step) {
            int j, cap, b; WorkQueue q; ForkJoinTask<?>[] a;
            /**
             * 定位到某个队列q，并检查队列是否存在、数组是否存在、容量是否有效。
             */
            if((q = qs[j = r & (n - 1)]) != null && // poll at qs[j].array[k]
                    (a = q.array) != null && (cap = a.length) > 0) {
                /**
                 * 为偷任务做准备：
                 * b：q.base
                 * k：base对应的物理槽位
                 * t：当前队头task
                 * src：当前来源标识
                 */
                int k = (cap - 1) & (b = q.base), nextBase = b + 1;
                int nextIndex = (cap - 1) & nextBase, src = j | SRC;
                ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                if(q.base != b) // 并发保护，别人并发改了q.base就放弃本轮
                    return prevSrc;
                /**
                 * 核心抢任务逻辑，如果队头有任务，就CAS抢它
                 */
                else if(t != null && WorkQueue.casSlotToNull(a, k, t)) {
                    /**
                     * 成功后，把q.base推进到nextBase，即成功偷走了这个队头任务
                     */
                    q.base = nextBase;
                    /**
                     * 必要时传播唤醒：
                     * 1、把当前worker的source设成这次任务来源的src。
                     * 2、如果这次来源和上次来源不同，并且被偷那个队列里nextBase后面还有任务，那就signalWork()
                     * 意思就是：我刚从这个队列里偷了一个任务，而且它后面还有任务，我就顺手通知系统这里还有任务，可以再叫别人来。
                     * 因此scan方法不仅负责偷任务，还承担了活跃传播的职责。
                     */
                    ForkJoinTask<?> next = a[nextIndex];
                    if((w.source = src) != prevSrc && next != null)
                        signalWork();           // propagate
                    /**
                     * 执行偷到的任务链，注意不是偷到了就方法结束了，而是执行t，并且用topLevelExec连续执行一串任务（尽量多干活）
                     */
                    w.topLevelExec(t, q);
                    return src;
                    /**
                     * 当前槽位没偷成，不代表这个队列是空（竞争、过渡），
                     * 返回要返回prevSrc，意思是：不判断全局无活，先重新来一轮
                     */
                }else if (a[nextIndex] != null)
                    return prevSrc;
            }
        }
        /**
         * 整轮扫描都没task，判断queues是否在扫描期间变了，说明列表可能扩容了，返回prevSrc让外层继续。
         * 如果queues没变就返回-1，告诉runWorker这轮没扫到任务，可以去awaitWork了。
         */
        return (queues != qs) ? prevSrc: -1;    // possibly resized
    }

    /**
     * worker没活干的挂起自己的操作
     */
    private int awaitWork(WorkQueue w) {
        /**
         * 阶段一：基本判断 + 更新phase
         */
        if (w == null)
            return -1;                       // already terminated
        /**
         * phase是worker当前等待轮次/版本的标记：
         * 1、phase递增，相当于推进了
         * 2、标记成UNSIGNALLED：准备进入未被唤醒的等待状态了
         * 每次worker进入idle，不是简单复用一个状态，而是：进入第n次等待，好处是：
         * 1、避免旧唤醒信号和新等待状态混淆。
         * 2、保证signal/await是按轮次配对的。
         */
        int phase = (w.phase + SS_SEQ) & ~UNSIGNALLED;
        w.phase = phase | UNSIGNALLED;       // advance phase

        /**
         * 阶段二：把自己挂到ctl的idle栈
         */
        long prevCtl = ctl, c;               // enqueue
        do {
            w.stackPred = (int)prevCtl;
            c = ((prevCtl - RC_UNIT) & UC_MASK) | (phase & SP_MASK);
        } while (prevCtl != (prevCtl = compareAndExchangeCtl(prevCtl, c)));

        /**
         * 阶段三：休眠之前再检查一遍，是不是真的要休眠
         */
        Thread.interrupted();                // clear status
        LockSupport.setCurrentBlocker(this); // prepare to block (exit also OK)
        long deadline = 0L;                  // nonzero if possibly quiescent
        int ac = (int)(c >> RC_SHIFT), md;
        if((md = mode) < 0)                 // pool is terminating
            return -1;
        else if ((md & SMASK) + ac <= 0) {
            /**
             * 阶段四：quiescent前再扫描一次：
             * 本来躺下了，突然发现有事，起来继续干活。
             */
            boolean checkTermination = (md & SHUTDOWN) != 0;
            if((deadline = System.currentTimeMillis() + keepAlive) == 0L)
                deadline = 1L;               // avoid zero
            WorkQueue[] qs = queues;         // check for racing submission
            int n = (qs == null) ? 0 : qs.length;
            for(int i = 0; i < n; i += 2) {
                WorkQueue q; ForkJoinTask<?>[] a; int cap, b;
                if(ctl != c) {              // already signalled
                    checkTermination = false;
                    break;
                }else if ((q = qs[i]) != null &&
                        (a = q.array) != null && (cap = a.length) > 0 &&
                        ((b = q.base) != q.top || a[(cap - 1) & b] != null ||
                                q.source != 0)) {
                    if (compareAndSetCtl(c, prevCtl))
                        w.phase = phase;     // self-signal
                    checkTermination = false;
                    break;
                }
            }
            /**
             * 如果正在shutdown而且真的quiescent了，推进终止直接退出。
             */
            if(checkTermination && tryTerminate(false, false))
                return -1;                   // trigger quiescent termination
        }

        /**
         * 阶段五：真正的等待循环
         */
        for (boolean alt = false;;) {        // await activation or termination
            if(w.phase >= 0)    // 自己被signalWork又激活了，退出等待
                break;
            else if(mode < 0)   // pool在终止，直接退出
                return -1;
            else if((c = ctl) == prevCtl)   // 如果ctl还没变，就先自旋
                Thread.onSpinWait();         // signal in progress
            else if(!(alt = !alt))     // park前后穿插检查，目的是清理中断等状态，避免park协议被污染
                Thread.interrupted();
            else if(deadline == 0L) // 无限park
                LockSupport.park();
            else if(deadline - System.currentTimeMillis() > TIMEOUT_SLOP)
                LockSupport.parkUntil(deadline);    // park一段时间
            /**
             * 空闲太久了，尝试从池里注销自己
             */
            else if (((int)c & SMASK) == (w.config & SMASK) && compareAndSetCtl(c, ((UC_MASK & (c - TC_UNIT)) | (prevCtl & SP_MASK)))) {
                w.config |= QUIET;           // sentinel for deregisterWorker
                return -1;                   // drop on timeout
            }else if ((deadline += keepAlive) == 0L)    // 回收不成功，延长deadline再来一轮
                deadline = 1L;               // not at head; restart timer
        }
        return 0;   // 返回0表示worker没有终止，成功地从等待状态重新被激活了，runWorker可以继续下一轮scan了
    }

    // Utilities used by ForkJoinTask

    final boolean canStop() {
        outer: for (long oldSum = 0L;;) { // repeat until stable
            int md; WorkQueue[] qs;  long c;
            if((qs = queues) == null || ((md = mode) & STOP) != 0)
                return true;
            if((md & SMASK) + (int)((c = ctl) >> RC_SHIFT) > 0)
                break;
            long checkSum = c;
            for(int i = 1; i < qs.length; i += 2) { // scan submitters
                WorkQueue q; ForkJoinTask<?>[] a; int s = 0, cap;
                if((q = qs[i]) != null && (a = q.array) != null && (cap = a.length) > 0 &&
                        ((s = q.top) != q.base || a[(cap - 1) & s] != null || q.source != 0))
                    break outer;
                checkSum += (((long)i) << 32) ^ s;
            }
            if(oldSum == (oldSum = checkSum) && queues == qs)
                return true;
        }
        return (mode & STOP) != 0; // recheck mode on false return
    }

    /**
     * 如果某个worker线程暂时不能继续干活了，FJP怎么避免并行度下降的问题，就是所谓的补偿机制。
     * tryCompensate处理的情况是：worker线程还没退出，也不是idle状态，而是占着worker名额没干活：
     * 1、join等待其他任务。
     * 2、managedBlock进入阻塞。
     * 3、某些同步等待让当前worker暂时停住。
     * 这也是和传统线程池最大的不同，会补一个活跃能力出来
     *
     * 补偿方法：
     * 1、先唤醒一个idle worker。
     * 2、不行就减少活跃计数，把当前阻塞线程从active里扣掉。
     * 3、还不够就扩容，新增worker
     *
     * 这个方法特别复杂，整理一下完整行为：
     * 1、当前某个worker要进入阻塞/等待，pool需要考虑补偿。
     * 2、先看并行度是不是0；是的话根本无法补。
     * 3、再看idle栈里有没有worker，有的话优先唤醒一个。
     * 4、没有idle worker，就看active能不能安全减1，可以就先把当前阻塞线程从active里扣掉。
     * 5、还不够的话，再看总线程数是否允许扩容，允许就新建一个worker。
     * 6、如果已经到上限，就走饱和策略，策略不接受则抛异常。
     *
     * 要学习的核心思想：
     * 1、补偿不等于一定要新建线程：唤醒idle -> 扣减active -> 新建worker。
     * 2、活跃计数和总线程数是两个不同的维度：
     * - active：当前有效劳动力。
     * - total：池里总共多少worker。
     * 当前线程阻塞时，可能只是active要减，不一定马上total要变。
     * 3、补偿是为了维持有效并行度：不要因为某个worker阻塞了，导致整个分治计算卡住。
     * 4、UNCOMPENATE表示这次补偿已登记，后续要归还。
     * 5、FJP比普通线程池要高级的地方：
     * - 普通线程池不管：某些线程虽然没死，但它因为join/block暂时不能推进任务，该怎么办。
     * - FJP专门为这类：并行分治中的阻塞塌陷，设计了补偿机制。
     */
    private int tryCompensate(long c) {
        /**
         * 阶段一：取出关键池状态
         */
        Predicate<? super ForkJoinPool> sat;
        int md = mode, b = bounds;
        // counts are signed; centered at parallelism level == 0
        int minActive = (short)(b & SMASK), // 允许的最小活跃并行度下界
                maxTotal  = b >>> SWIDTH,   // 总线程数允许达到的上限
                active    = (int)(c >> RC_SHIFT),   // 当前ctl里记录的活跃worker计数
                total     = (short)(c >>> TC_SHIFT),    // 当前总worker数量相关计数
                sp        = (int)c & ~UNSIGNALLED;  // idle worker栈顶标识
        /**
         * 阶段二：parallelism为0，直接不能补
         */
        if ((md & SMASK) == 0)
            return 0;                  // cannot compensate if parallelism zero
        /**
         * 阶段三：优先处理total >= 0的正常区间
         */
        else if(total >= 0) {
            if(sp != 0) {                        // activate idle worker
                WorkQueue[] qs; int n; WorkQueue v;
                if((qs = queues) != null && (n = qs.length) > 0 && (v = qs[sp & (n - 1)]) != null) {
                    Thread vt = v.owner;
                    long nc = ((long)v.stackPred & SP_MASK) | (UC_MASK & c);
                    if(compareAndSetCtl(c, nc)) {
                        v.phase = sp;
                        LockSupport.unpark(vt);
                        return UNCOMPENSATE;
                    }
                }
                return -1;                        // retry
            }else if(active > minActive) {        // reduce parallelism
                long nc = ((RC_MASK & (c - RC_UNIT)) | (~RC_MASK & c));
                return compareAndSetCtl(c, nc) ? UNCOMPENSATE : -1;
            }
        }
        if(total < maxTotal) {                   // expand pool
            long nc = ((c + TC_UNIT) & TC_MASK) | (c & ~TC_MASK);
            return (!compareAndSetCtl(c, nc) ? -1 : !createWorker() ? 0 : UNCOMPENSATE);
        /**
         * 阶段四：再也不能扩了，进入饱和/拒绝逻辑
         */
        }else if (!compareAndSetCtl(c, c))         // validate
            return -1;
        else if((sat = saturate) != null && sat.test(this))
            return 0;
        else
            throw new RejectedExecutionException("Thread limit exceeded replacing blocked worker");
    }

    final void uncompensate() {
        getAndAddCtl(RC_UNIT);
    }

    final int helpJoin(ForkJoinTask<?> task, WorkQueue w, boolean canHelp) {
        int s = 0;
        if (task != null && w != null) {
            int wsrc = w.source, wid = w.config & SMASK, r = wid + 2;
            boolean scan = true;
            long c = 0L;                          // track ctl stability
            outer: for (;;) {
                if ((s = task.status) < 0)
                    break;
                else if (scan = !scan) {          // previous scan was empty
                    if (mode < 0)
                        ForkJoinTask.cancelIgnoringExceptions(task);
                    else if (c == (c = ctl) && (s = tryCompensate(c)) >= 0)
                        break;                    // block
                }
                else if (canHelp) {               // scan for subtasks
                    WorkQueue[] qs = queues;
                    int n = (qs == null) ? 0 : qs.length, m = n - 1;
                    for (int i = n; i > 0; i -= 2, r += 2) {
                        int j; WorkQueue q, x, y; ForkJoinTask<?>[] a;
                        if ((q = qs[j = r & m]) != null) {
                            int sq = q.source & SMASK, cap, b;
                            if ((a = q.array) != null && (cap = a.length) > 0) {
                                int k = (cap - 1) & (b = q.base);
                                int nextBase = b + 1, src = j | SRC, sx;
                                ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                                boolean eligible = sq == wid ||
                                        ((x = qs[sq & m]) != null &&   // indirect
                                                ((sx = (x.source & SMASK)) == wid ||
                                                        ((y = qs[sx & m]) != null && // 2-indirect
                                                                (y.source & SMASK) == wid)));
                                if ((s = task.status) < 0)
                                    break outer;
                                else if ((q.source & SMASK) != sq ||
                                        q.base != b)
                                    scan = true;          // inconsistent
                                else if (t == null)
                                    scan |= (a[nextBase & (cap - 1)] != null ||
                                            q.top != b); // lagging
                                else if (eligible) {
                                    if (WorkQueue.casSlotToNull(a, k, t)) {
                                        q.base = nextBase;
                                        w.source = src;
                                        t.doExec();
                                        w.source = wsrc;
                                    }
                                    scan = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        return s;
    }

    final int helpComplete(ForkJoinTask<?> task, WorkQueue w, boolean owned) {
        int s = 0;
        if (task != null && w != null) {
            int r = w.config;
            boolean scan = true, locals = true;
            long c = 0L;
            outer: for (;;) {
                if (locals) {                     // try locals before scanning
                    if ((s = w.helpComplete(task, owned, 0)) < 0)
                        break;
                    locals = false;
                }
                else if ((s = task.status) < 0)
                    break;
                else if (scan = !scan) {
                    if (c == (c = ctl))
                        break;
                }else {                            // scan for subtasks
                    WorkQueue[] qs = queues;
                    int n = (qs == null) ? 0 : qs.length;
                    for (int i = n; i > 0; --i, ++r) {
                        int j, cap, b;
                        WorkQueue q;
                        ForkJoinTask<?>[] a;
                        boolean eligible = false;
                        if ((q = qs[j = r & (n - 1)]) != null &&
                                (a = q.array) != null && (cap = a.length) > 0) {
                            int k = (cap - 1) & (b = q.base), nextBase = b + 1;
                            ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                            if (t instanceof CountedCompleter) {
                                CountedCompleter<?> f = (CountedCompleter<?>)t;
                                do {} while (!(eligible = (f == task)) &&
                                        (f = f.completer) != null);
                            }
                            if ((s = task.status) < 0)
                                break outer;
                            else if (q.base != b)
                                scan = true;       // inconsistent
                            else if (t == null)
                                scan |= (a[nextBase & (cap - 1)] != null ||
                                        q.top != b);
                            else if (eligible) {
                                if (WorkQueue.casSlotToNull(a, k, t)) {
                                    q.setBaseOpaque(nextBase);
                                    t.doExec();
                                    locals = true;
                                }
                                scan = true;
                                break;
                            }
                        }
                    }
                }
            }
        }
        return s;
    }

    private ForkJoinTask<?> pollScan(boolean submissionsOnly) {
        VarHandle.acquireFence();
        int r = scanRover += 0x61c88647; // Weyl increment; raciness OK
        if (submissionsOnly)             // even indices only
            r &= ~1;
        int step = (submissionsOnly) ? 2 : 1;
        WorkQueue[] qs; int n;
        while ((qs = queues) != null && (n = qs.length) > 0) {
            boolean scan = false;
            for (int i = 0; i < n; i += step) {
                int j, cap, b; WorkQueue q; ForkJoinTask<?>[] a;
                if ((q = qs[j = (n - 1) & (r + i)]) != null &&
                        (a = q.array) != null && (cap = a.length) > 0) {
                    int k = (cap - 1) & (b = q.base), nextBase = b + 1;
                    ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                    if (q.base != b)
                        scan = true;
                    else if (t == null)
                        scan |= (q.top != b || a[nextBase & (cap - 1)] != null);
                    else if (!WorkQueue.casSlotToNull(a, k, t))
                        scan = true;
                    else {
                        q.setBaseOpaque(nextBase);
                        return t;
                    }
                }
            }
            if (!scan && queues == qs)
                break;
        }
        return null;
    }

    final int helpQuiescePool(WorkQueue w, long nanos, boolean interruptible) {
        if (w == null)
            return 0;
        long startTime = System.nanoTime(), parkTime = 0L;
        int prevSrc = w.source, wsrc = prevSrc, cfg = w.config, r = cfg + 1;
        for (boolean active = true, locals = true;;) {
            boolean busy = false, scan = false;
            if (locals) {  // run local tasks before (re)polling
                locals = false;
                for (ForkJoinTask<?> u; (u = w.nextLocalTask(cfg)) != null;)
                    u.doExec();
            }
            WorkQueue[] qs = queues;
            int n = (qs == null) ? 0 : qs.length;
            for (int i = n; i > 0; --i, ++r) {
                int j, b, cap;
                WorkQueue q;
                ForkJoinTask<?>[] a;
                if ((q = qs[j = (n - 1) & r]) != null && q != w &&
                        (a = q.array) != null && (cap = a.length) > 0) {
                    int k = (cap - 1) & (b = q.base);
                    int nextBase = b + 1, src = j | SRC;
                    ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                    if (q.base != b)
                        busy = scan = true;
                    else if (t != null) {
                        busy = scan = true;
                        if (!active) {    // increment before taking
                            active = true;
                            getAndAddCtl(RC_UNIT);
                        }
                        if (WorkQueue.casSlotToNull(a, k, t)) {
                            q.base = nextBase;
                            w.source = src;
                            t.doExec();
                            w.source = wsrc = prevSrc;
                            locals = true;
                        }
                        break;
                    }
                    else if (!busy) {
                        if (q.top != b || a[nextBase & (cap - 1)] != null)
                            busy = scan = true;
                        else if (q.source != QUIET && q.phase >= 0)
                            busy = true;
                    }
                }
            }
            VarHandle.acquireFence();
            if (!scan && queues == qs) {
                boolean interrupted;
                if (!busy) {
                    w.source = prevSrc;
                    if (!active)
                        getAndAddCtl(RC_UNIT);
                    return 1;
                }
                if (wsrc != QUIET)
                    w.source = wsrc = QUIET;
                if (active) {                 // decrement
                    active = false;
                    parkTime = 0L;
                    getAndAddCtl(RC_MASK & -RC_UNIT);
                }
                else if (parkTime == 0L) {
                    parkTime = 1L << 10; // initially about 1 usec
                    Thread.yield();
                }
                else if ((interrupted = interruptible && Thread.interrupted()) ||
                        System.nanoTime() - startTime > nanos) {
                    getAndAddCtl(RC_UNIT);
                    return interrupted ? -1 : 0;
                }
                else {
                    LockSupport.parkNanos(this, parkTime);
                    if (parkTime < nanos >>> 8 && parkTime < 1L << 20)
                        parkTime <<= 1;  // max sleep approx 1 sec or 1% nanos
                }
            }
        }
    }

    final int externalHelpQuiescePool(long nanos, boolean interruptible) {
        for (long startTime = System.nanoTime(), parkTime = 0L;;) {
            ForkJoinTask<?> t;
            if ((t = pollScan(false)) != null) {
                t.doExec();
                parkTime = 0L;
            }
            else if (canStop())
                return 1;
            else if (parkTime == 0L) {
                parkTime = 1L << 10;
                Thread.yield();
            }
            else if ((System.nanoTime() - startTime) > nanos)
                return 0;
            else if (interruptible && Thread.interrupted())
                return -1;
            else {
                LockSupport.parkNanos(this, parkTime);
                if (parkTime < nanos >>> 8 && parkTime < 1L << 20)
                    parkTime <<= 1;
            }
        }
    }

    final ForkJoinTask<?> nextTaskFor(WorkQueue w) {
        ForkJoinTask<?> t;
        if (w == null || (t = w.nextLocalTask(w.config)) == null)
            t = pollScan(false);
        return t;
    }

    // External operations

    /**
     * 外部线程应该把task投到哪一个external/shared queue，如果没有，就尝试直接创建一个。
     */
    final WorkQueue submissionQueue() {
        /**
         * 阶段一：拿现成的probe，没有就初始化，用来决定它倾向于命中哪个submission queue，目的是：不要让所有线程都挤在同一个队列。
         */
        int r;
        if((r = ThreadLocalRandom.getProbe()) == 0) {
            ThreadLocalRandom.localInit();           // initialize caller's probe
            r = ThreadLocalRandom.getProbe();
        }
        /**
         * 阶段二：从一个偶数开始尝试，即submission queue放在queues[]的偶数槽位。
         */
        for(int id = r << 1;;) {                    // even indices only
            int md = mode, n, i;
            WorkQueue q;
            ReentrantLock lock;
            WorkQueue[] qs = queues;
            /**
             * 阶段三：如果池关闭或者结构无效，返回null
             */
            if((md & SHUTDOWN) != 0 || qs == null || (n = qs.length) <= 0)
                return null;
            /**
             * 阶段四：目标偶数槽位为空，尝试新建external queue
             */
            else if((q = qs[i = (n - 1) & id]) == null) {
                if((lock = registrationLock) != null) {
                    WorkQueue w = new WorkQueue(id | SRC);
                    lock.lock();                    // install under lock
                    if(qs[i] == null)
                        qs[i] = w;                  // else lost race; discard
                    lock.unlock();
                }
            /**
             * 阶段五：如果槽位上已经有queue，但拿不到锁，换一个probe再试。
             */
            }else if(!q.tryLock())                  // move and restart
                id = (r = ThreadLocalRandom.advanceProbe(r)) << 1;
            /**
             * 阶段六：拿到了锁，就返回这个queue。
             */
            else
                return q;
        }
    }

    /**
     * 给外部线程找一个可用submission queue，把task压进去，必要时唤醒worker
     * 设计意图：
     * 1、外部线程不直接碰worker的私有队列，而是走共享的submission queue。
     * 2、提交成功后，不负责自己执行，而是通过signalWork把工作交给worker系统。
     * 即只负责投递，不负责消费。
     */
    final void externalPush(ForkJoinTask<?> task) {
        WorkQueue q;
        /**
         * 调用submissionQueue找出一个合适的shared queue，返回null则说明：
         * 1、FJP已经shutdown了。
         * 2、结构不可用。
         * 3、提交入口被关闭了。
         */
        if((q = submissionQueue()) == null)
            throw new RejectedExecutionException(); // shutdown or disabled
        /**
         * 注意调用的是lockedPush，说明：external queue不是某个owner独占的，所以外部线程写入时要先加锁。
         */
        else if(q.lockedPush(task))
            signalWork();   // 入队不只塞任务，还可以触发调度器活跃化
    }

    /**
     * 判断这次提交到底属于内部worker还是真正的外部提交，然后走对应路径，相当于只是个路由方法
     */
    private <T> ForkJoinTask<T> externalSubmit(ForkJoinTask<T> task) {
        Thread t;
        ForkJoinWorkerThread wt;
        WorkQueue q;
        if(task == null)
            throw new NullPointerException();
        if(((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) &&
                (q = (wt = (ForkJoinWorkerThread)t).workQueue) != null &&
                wt.pool == this)
            q.push(task, this); // worker走这个push
        else
            externalPush(task); // 外部线程提交的走这个externalPush
        return task;
    }

    /**
     * 返回一个外部线程对应的公共池队列，该线程可能曾提交过公共池任务（探测值为非零），未提交过，则返回 null。
     */
    static WorkQueue commonQueue() {
        ForkJoinPool p;
        WorkQueue[] qs;
        int r = ThreadLocalRandom.getProbe(), n;
        return ((p = common) != null && (qs = p.queues) != null && (n = qs.length) > 0 && r != 0)
                ? qs[(n - 1) & (r << 1)]
                : null;
    }

    /**
     * 返回外部线程的队列（如果存在的话）。
     */
    final WorkQueue externalQueue() {
        WorkQueue[] qs;
        int r = ThreadLocalRandom.getProbe(), n;
        return ((qs = queues) != null && (n = qs.length) > 0 && r != 0) ?
                qs[(n - 1) & (r << 1)] :
                null;
    }

    static void helpAsyncBlocker(Executor e, ManagedBlocker blocker) {
        WorkQueue w = null; Thread t; ForkJoinWorkerThread wt;
        if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) {
            if ((wt = (ForkJoinWorkerThread)t).pool == e)
                w = wt.workQueue;
        }
        else if (e instanceof ForkJoinPool)
            w = ((ForkJoinPool)e).externalQueue();
        if (w != null)
            w.helpAsyncBlocker(blocker);
    }

    static int getSurplusQueuedTaskCount() {
        Thread t;
        ForkJoinWorkerThread wt;
        ForkJoinPool pool;
        WorkQueue q;
        if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) &&
                (pool = (wt = (ForkJoinWorkerThread)t).pool) != null &&
                (q = wt.workQueue) != null) {
            int p = pool.mode & SMASK;
            int a = p + (int)(pool.ctl >> RC_SHIFT);
            int n = q.top - q.base;
            return n - (a > (p >>>= 1) ? 0 :
                    a > (p >>>= 1) ? 1 :
                            a > (p >>>= 1) ? 2 :
                                    a > (p >>>= 1) ? 4 :
                                            8);
        }
        return 0;
    }

    // Termination

    private boolean tryTerminate(boolean now, boolean enable) {
        int md; // try to set SHUTDOWN, then STOP, then help terminate
        if(((md = mode) & SHUTDOWN) == 0) {
            if (!enable)
                return false;
            md = getAndBitwiseOrMode(SHUTDOWN);
        }
        if((md & STOP) == 0) {
            if (!now && !canStop())
                return false;
            md = getAndBitwiseOrMode(STOP);
        }
        for(boolean rescan = true;;) { // repeat until no changes
            boolean changed = false;
            for(ForkJoinTask<?> t; (t = pollScan(false)) != null; ) {
                changed = true;
                ForkJoinTask.cancelIgnoringExceptions(t); // help cancel
            }
            WorkQueue[] qs;
            int n;
            WorkQueue q;
            Thread thread;
            if((qs = queues) != null && (n = qs.length) > 0) {
                for(int j = 1; j < n; j += 2) { // unblock other workers
                    if((q = qs[j]) != null && (thread = q.owner) != null && !thread.isInterrupted()) {
                        changed = true;
                        try {
                            thread.interrupt();
                        } catch (Throwable ignore) {

                        }
                    }
                }
            }
            ReentrantLock lock; Condition cond; // signal when no workers
            if(((md = mode) & TERMINATED) == 0 &&
                    (md & SMASK) + (short)(ctl >>> TC_SHIFT) <= 0 &&
                    (getAndBitwiseOrMode(TERMINATED) & TERMINATED) == 0 &&
                    (lock = registrationLock) != null) {
                lock.lock();
                if((cond = termination) != null)
                    cond.signalAll();
                lock.unlock();
            }
            if(changed)
                rescan = true;
            else if(rescan)
                rescan = false;
            else
                break;
        }
        return true;
    }

    // Exported methods

    // Constructors

    public ForkJoinPool() {
        this(Math.min(MAX_CAP, Runtime.getRuntime().availableProcessors()),
                defaultForkJoinWorkerThreadFactory, null, false,
                0, MAX_CAP, 1, null, DEFAULT_KEEPALIVE, TimeUnit.MILLISECONDS);
    }

    public ForkJoinPool(int parallelism) {
        this(parallelism, defaultForkJoinWorkerThreadFactory, null, false,
                0, MAX_CAP, 1, null, DEFAULT_KEEPALIVE, TimeUnit.MILLISECONDS);
    }

    public ForkJoinPool(int parallelism,
                        ForkJoinWorkerThreadFactory factory,
                        Thread.UncaughtExceptionHandler handler,
                        boolean asyncMode) {
        this(parallelism, factory, handler, asyncMode,
                0, MAX_CAP, 1, null, DEFAULT_KEEPALIVE, TimeUnit.MILLISECONDS);
    }

    public ForkJoinPool(int parallelism,
                        ForkJoinWorkerThreadFactory factory,
                        Thread.UncaughtExceptionHandler handler,
                        boolean asyncMode,
                        int corePoolSize,
                        int maximumPoolSize,
                        int minimumRunnable,
                        Predicate<? super ForkJoinPool> saturate,
                        long keepAliveTime,
                        TimeUnit unit) {
        checkPermission();
        int p = parallelism;
        if(p <= 0 || p > MAX_CAP || p > maximumPoolSize || keepAliveTime <= 0L)
            throw new IllegalArgumentException();
        if(factory == null || unit == null)
            throw new NullPointerException();
        this.factory = factory;
        this.ueh = handler;
        this.saturate = saturate;
        this.keepAlive = Math.max(unit.toMillis(keepAliveTime), TIMEOUT_SLOP);
        int size = 1 << (33 - Integer.numberOfLeadingZeros(p - 1));
        int corep = Math.min(Math.max(corePoolSize, p), MAX_CAP);
        int maxSpares = Math.min(maximumPoolSize, MAX_CAP) - p;
        int minAvail = Math.min(Math.max(minimumRunnable, 0), MAX_CAP);
        this.bounds = ((minAvail - p) & SMASK) | (maxSpares << SWIDTH);
        this.mode = p | (asyncMode ? FIFO : 0);
        this.ctl = ((((long)(-corep) << TC_SHIFT) & TC_MASK) |
                (((long)(-p)     << RC_SHIFT) & RC_MASK));
        this.registrationLock = new ReentrantLock();
        this.queues = new ForkJoinPool.WorkQueue[size];
        String pid = Integer.toString(getAndAddPoolIds(1) + 1);
        this.workerNamePrefix = "ForkJoinPool-" + pid + "-worker-";
    }

    // helper method for commonPool constructor

    private static Object newInstanceFromSystemProperty(String property) throws ReflectiveOperationException {
        String className = System.getProperty(property);
        return (className == null)
                ? null
                : ClassLoader.getSystemClassLoader().loadClass(className).getConstructor().newInstance();
    }

    private ForkJoinPool(byte forCommonPoolOnly) {
        int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ForkJoinWorkerThreadFactory fac = null;
        Thread.UncaughtExceptionHandler handler = null;
        try {  // ignore exceptions in accessing/parsing properties
            fac = (ForkJoinWorkerThreadFactory) newInstanceFromSystemProperty(
                    "java.util.concurrent.ForkJoinPool.common.threadFactory");
            handler = (Thread.UncaughtExceptionHandler)newInstanceFromSystemProperty(
                    "java.util.concurrent.ForkJoinPool.common.exceptionHandler");
            String pp = System.getProperty
                    ("java.util.concurrent.ForkJoinPool.common.parallelism");
            if (pp != null)
                parallelism = Integer.parseInt(pp);
        } catch (Exception ignore) {

        }
        this.ueh = handler;
        this.keepAlive = DEFAULT_KEEPALIVE;
        this.saturate = null;
        this.workerNamePrefix = null;
        int p = Math.min(Math.max(parallelism, 0), MAX_CAP), size;
        this.mode = p;
        if(p > 0) {
            size = 1 << (33 - Integer.numberOfLeadingZeros(p - 1));
            this.bounds = ((1 - p) & SMASK) | (COMMON_MAX_SPARES << SWIDTH);
            this.ctl = ((((long)(-p) << TC_SHIFT) & TC_MASK) |
                    (((long)(-p) << RC_SHIFT) & RC_MASK));
        }else {  // zero min, max, spare counts, 1 slot
            size = 1;
            this.bounds = 0;
            this.ctl = 0L;
        }
        this.factory = (fac != null) ? fac : new DefaultCommonPoolForkJoinWorkerThreadFactory();
        this.queues = new WorkQueue[size];
        this.registrationLock = new ReentrantLock();
    }

    public static ForkJoinPool commonPool() {
        // assert common != null : "static init error";
        return common;
    }

    // Execution methods

    public <T> T invoke(ForkJoinTask<T> task) {
        externalSubmit(task);
        return task.joinForPoolInvoke(this);
    }

    public void execute(ForkJoinTask<?> task) {
        externalSubmit(task);
    }

    // AbstractExecutorService methods

    public void execute(Runnable task) {
        externalSubmit((task instanceof ForkJoinTask<?>)
                ? (ForkJoinTask<Void>) task // avoid re-wrap
                : new ForkJoinTask.RunnableExecuteAction(task));
    }

    public <T> ForkJoinTask<T> submit(ForkJoinTask<T> task) {
        return externalSubmit(task);
    }

    @Override
    public <T> ForkJoinTask<T> submit(Callable<T> task) {
        return externalSubmit(new ForkJoinTask.AdaptedCallable<>(task));
    }

    @Override
    public <T> ForkJoinTask<T> submit(Runnable task, T result) {
        return externalSubmit(new ForkJoinTask.AdaptedRunnable<>(task, result));
    }

    @Override
    @SuppressWarnings("unchecked")
    public ForkJoinTask<?> submit(Runnable task) {
        return externalSubmit((task instanceof ForkJoinTask<?>)
                ? (ForkJoinTask<Void>) task // avoid re-wrap
                : new ForkJoinTask.AdaptedRunnableAction(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());
        try {
            for(Callable<T> t : tasks) {
                ForkJoinTask<T> f = new ForkJoinTask.AdaptedInterruptibleCallable<T>(t);
                futures.add(f);
                externalSubmit(f);
            }
            for(int i = futures.size() - 1; i >= 0; --i)
                ((ForkJoinTask<?>)futures.get(i)).awaitPoolInvoke(this);
            return futures;
        } catch (Throwable t) {
            for(Future<T> e : futures)
                ForkJoinTask.cancelIgnoringExceptions(e);
            throw t;
        }
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());
        try {
            for(Callable<T> t : tasks) {
                ForkJoinTask<T> f = new ForkJoinTask.AdaptedInterruptibleCallable<>(t);
                futures.add(f);
                externalSubmit(f);
            }
            long startTime = System.nanoTime(), ns = nanos;
            boolean timedOut = (ns < 0L);
            for(int i = futures.size() - 1; i >= 0; --i) {
                Future<T> f = futures.get(i);
                if(!f.isDone()) {
                    if(timedOut)
                        ForkJoinTask.cancelIgnoringExceptions(f);
                    else {
                        ((ForkJoinTask<T>)f).awaitPoolInvoke(this, ns);
                        if((ns = nanos - (System.nanoTime() - startTime)) < 0L) {
                            timedOut = true;
                            ForkJoinTask.cancelIgnoringExceptions(f);
                        }
                    }
                }
            }
            return futures;
        } catch (Throwable t) {
            for(Future<T> e : futures)
                ForkJoinTask.cancelIgnoringExceptions(e);
            throw t;
        }
    }

    // Task to hold results from InvokeAnyTasks

    static final class InvokeAnyRoot<E> extends ForkJoinTask<E> {
        private static final long serialVersionUID = 2838392045355241008L;

        @SuppressWarnings("serial") // Conditionally serializable
        volatile E result;

        final AtomicInteger count;  // in case all throw

        final ForkJoinPool pool;    // to check shutdown while collecting

        InvokeAnyRoot(int n, ForkJoinPool p) {
            pool = p;
            count = new AtomicInteger(n);
        }

        final void tryComplete(Callable<E> c) { // called by InvokeAnyTasks
            Throwable ex = null;
            boolean failed;
            if(c == null || Thread.interrupted() || (pool != null && pool.mode < 0))
                failed = true;
            else if (isDone())
                failed = false;
            else {
                try {
                    complete(c.call());
                    failed = false;
                } catch (Throwable tx) {
                    ex = tx;
                    failed = true;
                }
            }
            if ((pool != null && pool.mode < 0) || (failed && count.getAndDecrement() <= 1))
                trySetThrown(ex != null ? ex : new CancellationException());
        }

        public final boolean exec() {
            return false;   // never forked
        }

        public final E getRawResult() {
            return result;
        }
        public final void setRawResult(E v) {
            result = v;
        }
    }

    // Variant of AdaptedInterruptibleCallable with results in InvokeAnyRoot

    static final class InvokeAnyTask<E> extends ForkJoinTask<E> {
        private static final long serialVersionUID = 2838392045355241008L;
        final InvokeAnyRoot<E> root;
        @SuppressWarnings("serial") // Conditionally serializable
        final Callable<E> callable;
        transient volatile Thread runner;
        InvokeAnyTask(InvokeAnyRoot<E> root, Callable<E> callable) {
            this.root = root;
            this.callable = callable;
        }
        public final boolean exec() {
            Thread.interrupted();
            runner = Thread.currentThread();
            root.tryComplete(callable);
            runner = null;
            Thread.interrupted();
            return true;
        }
        public final boolean cancel(boolean mayInterruptIfRunning) {
            Thread t;
            boolean stat = super.cancel(false);
            if (mayInterruptIfRunning && (t = runner) != null) {
                try {
                    t.interrupt();
                } catch (Throwable ignore) {

                }
            }
            return stat;
        }
        public final void setRawResult(E v) {} // unused
        public final E getRawResult() {
            return null;
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        int n = tasks.size();
        if(n <= 0)
            throw new IllegalArgumentException();
        InvokeAnyRoot<T> root = new InvokeAnyRoot<>(n, this);
        ArrayList<InvokeAnyTask<T>> fs = new ArrayList<>(n);
        try {
            for(Callable<T> c : tasks) {
                if(c == null)
                    throw new NullPointerException();
                InvokeAnyTask<T> f = new InvokeAnyTask<T>(root, c);
                fs.add(f);
                externalSubmit(f);
                if(root.isDone())
                    break;
            }
            return root.getForPoolInvoke(this);
        } finally {
            for (ForkJoinPool.InvokeAnyTask<T> f : fs)
                ForkJoinTask.cancelIgnoringExceptions(f);
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        int n = tasks.size();
        if(n <= 0)
            throw new IllegalArgumentException();
        InvokeAnyRoot<T> root = new InvokeAnyRoot<>(n, this);
        ArrayList<InvokeAnyTask<T>> fs = new ArrayList<>(n);
        try {
            for(Callable<T> c : tasks) {
                if(c == null)
                    throw new NullPointerException();
                InvokeAnyTask<T> f = new InvokeAnyTask<>(root, c);
                fs.add(f);
                externalSubmit(f);
                if(root.isDone())
                    break;
            }
            return root.getForPoolInvoke(this, nanos);
        } finally {
            for(InvokeAnyTask<T> f : fs)
                ForkJoinTask.cancelIgnoringExceptions(f);
        }
    }

    public ForkJoinWorkerThreadFactory getFactory() {
        return factory;
    }

    public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return ueh;
    }

    public int getParallelism() {
        int par = mode & SMASK;
        return (par > 0) ? par : 1;
    }

    public static int getCommonPoolParallelism() {
        return COMMON_PARALLELISM;
    }

    public int getPoolSize() {
        return ((mode & SMASK) + (short)(ctl >>> TC_SHIFT));
    }

    public boolean getAsyncMode() {
        return (mode & FIFO) != 0;
    }

    public int getRunningThreadCount() {
        VarHandle.acquireFence();
        WorkQueue[] qs;
        WorkQueue q;
        int rc = 0;
        if((qs = queues) != null) {
            for(int i = 1; i < qs.length; i += 2) {
                if((q = qs[i]) != null && q.isApparentlyUnblocked())
                    ++rc;
            }
        }
        return rc;
    }

    public int getActiveThreadCount() {
        int r = (mode & SMASK) + (int)(ctl >> RC_SHIFT);
        return (r <= 0) ? 0 : r; // suppress momentarily negative values
    }

    public boolean isQuiescent() {
        return canStop();
    }

    public long getStealCount() {
        long count = stealCount;
        WorkQueue[] qs;
        WorkQueue q;
        if((qs = queues) != null) {
            for(int i = 1; i < qs.length; i += 2) {
                if((q = qs[i]) != null)
                    count += (long)q.nsteals & 0xffffffffL;
            }
        }
        return count;
    }

    public long getQueuedTaskCount() {
        VarHandle.acquireFence();
        WorkQueue[] qs;
        WorkQueue q;
        int count = 0;
        if((qs = queues) != null) {
            for(int i = 1; i < qs.length; i += 2) {
                if((q = qs[i]) != null)
                    count += q.queueSize();
            }
        }
        return count;
    }

    public int getQueuedSubmissionCount() {
        VarHandle.acquireFence();
        WorkQueue[] qs;
        WorkQueue q;
        int count = 0;
        if((qs = queues) != null) {
            for(int i = 0; i < qs.length; i += 2) {
                if((q = qs[i]) != null)
                    count += q.queueSize();
            }
        }
        return count;
    }

    public boolean hasQueuedSubmissions() {
        VarHandle.acquireFence();
        WorkQueue[] qs;
        WorkQueue q;
        if((qs = queues) != null) {
            for(int i = 0; i < qs.length; i += 2) {
                if((q = qs[i]) != null && !q.isEmpty())
                    return true;
            }
        }
        return false;
    }

    protected ForkJoinTask<?> pollSubmission() {
        return pollScan(true);
    }

    protected int drainTasksTo(Collection<? super ForkJoinTask<?>> c) {
        int count = 0;
        for(ForkJoinTask<?> t; (t = pollScan(false)) != null; ) {
            c.add(t);
            ++count;
        }
        return count;
    }

    public String toString() {
        // Use a single pass through queues to collect counts
        int md = mode; // read volatile fields first
        long c = ctl;
        long st = stealCount;
        long qt = 0L, ss = 0L; int rc = 0;
        WorkQueue[] qs;
        WorkQueue q;
        if((qs = queues) != null) {
            for(int i = 0; i < qs.length; ++i) {
                if((q = qs[i]) != null) {
                    int size = q.queueSize();
                    if((i & 1) == 0)
                        ss += size;
                    else{
                        qt += size;
                        st += (long)q.nsteals & 0xffffffffL;
                        if(q.isApparentlyUnblocked())
                            ++rc;
                    }
                }
            }
        }

        int pc = (md & SMASK);
        int tc = pc + (short)(c >>> TC_SHIFT);
        int ac = pc + (int)(c >> RC_SHIFT);
        if (ac < 0) // ignore transient negative
            ac = 0;
        String level = ((md & TERMINATED) != 0 ? "Terminated" :
                (md & STOP)       != 0 ? "Terminating" :
                        (md & SHUTDOWN)   != 0 ? "Shutting down" :
                                "Running");
        return super.toString() +
                "[" + level +
                ", parallelism = " + pc +
                ", size = " + tc +
                ", active = " + ac +
                ", running = " + rc +
                ", steals = " + st +
                ", tasks = " + qt +
                ", submissions = " + ss +
                "]";
    }

    public void shutdown() {
        checkPermission();
        if(this != common)
            tryTerminate(false, true);
    }

    public List<Runnable> shutdownNow() {
        checkPermission();
        if(this != common)
            tryTerminate(true, true);
        return Collections.emptyList();
    }

    public boolean isTerminated() {
        return (mode & TERMINATED) != 0;
    }

    public boolean isTerminating() {
        return (mode & (STOP | TERMINATED)) == STOP;
    }

    public boolean isShutdown() {
        return (mode & SHUTDOWN) != 0;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        ReentrantLock lock; Condition cond;
        long nanos = unit.toNanos(timeout);
        boolean terminated = false;
        if (this == common) {
            Thread t;
            ForkJoinWorkerThread wt;
            int q;
            if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread &&
                    (wt = (ForkJoinWorkerThread)t).pool == this)
                q = helpQuiescePool(wt.workQueue, nanos, true);
            else
                q = externalHelpQuiescePool(nanos, true);
            if (q < 0)
                throw new InterruptedException();
        }else if (!(terminated = ((mode & TERMINATED) != 0)) &&
                (lock = registrationLock) != null) {
            lock.lock();
            try {
                if ((cond = termination) == null)
                    termination = cond = lock.newCondition();
                while (!(terminated = ((mode & TERMINATED) != 0)) && nanos > 0L)
                    nanos = cond.awaitNanos(nanos);
            } finally {
                lock.unlock();
            }
        }
        return terminated;
    }

    public boolean awaitQuiescence(long timeout, TimeUnit unit) {
        Thread t;
        ForkJoinWorkerThread wt;
        int q;
        long nanos = unit.toNanos(timeout);
        if((t = Thread.currentThread()) instanceof ForkJoinWorkerThread && (wt = (ForkJoinWorkerThread)t).pool == this)
            q = helpQuiescePool(wt.workQueue, nanos, false);
        else
            q = externalHelpQuiescePool(nanos, false);
        return (q > 0);
    }

    /**
     * 重点接口：我知道接下来的这段代码会阻塞，但我先正式告诉FJP一下，然后FJP就可以：
     * 1、提前判断这个worker要暂时失去工作能力。
     * 2、调用tryCompensate。
     * 3、必要时唤醒别的idle worker，或者补新的worker。
     * 因此ManagedBlocker本质是：让不可避免的阻塞变成可管理、可补偿的阻塞
     */
    public static interface ManagedBlocker {
        /**
         * Possibly blocks the current thread, for example waiting for
         * a lock or condition.
         *
         * @return {@code true} if no additional blocking is necessary
         * (i.e., if isReleasable would return true)
         * @throws InterruptedException if interrupted while waiting
         * (the method is not required to do so, but is allowed to)
         */
        boolean block() throws InterruptedException;

        /**
         * Returns {@code true} if blocking is unnecessary.
         * @return {@code true} if blocking is unnecessary
         */
        boolean isReleasable();
    }

    public static void managedBlock(ManagedBlocker blocker) throws InterruptedException {
        Thread t;
        ForkJoinPool p;
        if((t = Thread.currentThread()) instanceof ForkJoinWorkerThread && (p = ((ForkJoinWorkerThread)t).pool) != null)
            p.compensatedBlock(blocker);
        else
            unmanagedBlock(blocker);
    }

    private void compensatedBlock(ManagedBlocker blocker) throws InterruptedException {
        if(blocker == null)
            throw new NullPointerException();
        for(;;) {
            int comp;
            boolean done;
            long c = ctl;
            if(blocker.isReleasable())
                break;
            if((comp = tryCompensate(c)) >= 0) {
                long post = (comp == 0) ? 0L : RC_UNIT;
                try {
                    done = blocker.block();
                } finally {
                    getAndAddCtl(post);
                }
                if (done)
                    break;
            }
        }
    }

    private static void unmanagedBlock(java.util.concurrent.ForkJoinPool.ManagedBlocker blocker) throws InterruptedException {
        if(blocker == null)
            throw new NullPointerException();
        do {} while (!blocker.isReleasable() && !blocker.block());
    }

    // AbstractExecutorService.newTaskFor overrides rely on
    // undocumented fact that ForkJoinTask.adapt returns ForkJoinTasks
    // that also implement RunnableFuture.

    @Override
    protected <T> java.util.concurrent.RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new ForkJoinTask.AdaptedRunnable<>(runnable, value);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new ForkJoinTask.AdaptedCallable<>(callable);
    }

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

        defaultForkJoinWorkerThreadFactory = new DefaultForkJoinWorkerThreadFactory();
        modifyThreadPermission = new RuntimePermission("modifyThread");
        @SuppressWarnings("removal")
        ForkJoinPool tmp = AccessController.doPrivileged(new PrivilegedAction<>() {
            public ForkJoinPool run() {
                return new ForkJoinPool((byte)0);
            }
        });
        common = tmp;

        COMMON_PARALLELISM = Math.max(common.mode & SMASK, 1);
    }
}

package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ExecutorService通过使用可能多个线程池中的一个来执行每个提交的任务，通常使用Executors工厂方法进行配置。
 * 线程池解决两个不同问题：
 * 1、通过降低每项任务的调用开销，在执行大量异步任务时通常能提升性能。
 * 2、提供了一种约束和管理资源（包括线程）的手段，用于控制执行任务集合时消耗的资源。
 * 每个线程池executor还会维护基础统计数据，例如已完成任务的数量。
 *
 * 为满足广泛场景的应用需求，该类提供了众多可调整参数及扩展钩子。
 * 但强烈建议使用更便捷的Executors工厂方法：
 * 1、Executors.newCachedThreadPool（无界线程池，支持自动回收线程）。
 * 2、Executors.newFixedThreadPool（固定大小的线程池）
 * 3、Executors.newSingleThreadExecutor（单后台线程）
 * 这些方法已针对最常见的使用场景预配置了参数，如需手动配置和调优奔雷，请遵循以下指南：
 *
 * Core and maximum pool sizes：
 * executor会根据corePoolSize和maximumPoolSize设定的边界自动调整线程池大小。
 * 在当execute(Runnable)提交新任务时，如果运行中的线程数少于corePoolSize，即使存在空闲工作线程，也会创建新线程处理请求。
 * 如果运行中的线程数少于maximumPoolSize，仅当队列已满时才会创建新线程处理请求。
 * 将core和maximum设为相同值，可创建固定大小的线程池。
 * 若将maximum设为Integer.MAX_VALUE，则允许池容纳任意数量的并发任务。
 * 通常core和maximum仅在构造时设定，但也可以通过setCorePoolSize和setMaximumPoolSize动态调整。
 *
 * On-demand construction：
 * 默认情况下，即使核心线程也仅在新任务到达时才初始创建并启动，但也可以通过prestartCoreThread和prestartAllCoreThreads方法动态覆盖此行为。
 * 若使用非空队列构建线程池，建议预启动线程。
 *
 * Creating new threads：
 * 新线程通过线程工厂创建，若未另行指定，则使用Executors.defaultThreadFactory。
 * 该工厂创建的所有线程均属于同一个线程组，具有相同的NORM_PRIORITY优先级且为非守护线程状态。
 * 通过提供不同的线程工厂，可修改线程名称、线程组、优先级、守护状态等属性。
 * 若线程工厂在创建线程时调用newThread返回null，executor将持续运行但可能无法执行任务。
 * 线程应具备modifyThread运行时权限，若工作线程或其他使用该线程池的线程未持有此权限，服务性能可能下降，
 * 配置变更可能无法及时生效，且关闭中的线程池可能处于可终止但未完成终止的状态。
 *
 * Keep-alive times：
 * 如果线程池当前拥有的线程数超过核心线程池大小，且闲置时间超过keepAliveTime，则超额线程将被终止。
 * 这为在线程池未被积极使用时降低资源消耗提供了机制，若后续池活动增加，将自动创建新线程。
 * 该参数可通过setKeepAliveTime方法动态调整，使用Long.MAX_VALUE作为作为TimeUnit.NANOSECONDS值，可有效禁止闲置线程在关闭前终止。
 * 默认情况下，保持活动策略仅在线程数超过核心池大小时生效，但只要keepAliveTime值不为零，即可通过allowCoreThreadTimeOut(boolean)方法将此超时策略应用于核心线程。
 *
 * Queuing：
 * 任何阻塞队列均可用于传输和暂存提交的任务，该队列的使用与线程池规模存在交互关系：
 * 1、若运行中的线程池少于核心线程池大小，executor始终优先创建新线程而非入队。
 * 2、若运行中的线程数达到或超过核心线程池大小，executor始终优先入队请求而非创建新线程。
 * 3、若请求无法入队，则创建新线程，除非此操作将导致最大线程池大小被超出，此时任务将被拒绝。
 *
 * 队列处理主要有三种策略：
 * 1、直接传递：工作队列的默认优选方法是使用SynchronousQueue，它能将任务直接传递给线程而无需额外持有。
 * 此时若无可用线程立即执行任务，排队操作将失败并创建新线程，该策略可避免处理存在内部依赖的请求集时发生锁死。
 * 直接传递通常需要设置无上限的最大线程池大小，以避免拒绝新提交任务，但当命令平均到达速度持续超过处理速度，将导致线程数量无限增长的风险。
 *
 * 2、无界队列：例如LinkedBlockingQueue，当核心线程所有线程均忙时，新任务将进入队列等待，因此实际创建的线程数永远不会超过核心线程池规模（maximum值将失去作用）。
 * 当每个任务完全独立且互不影响时（如web服务器），此模式较为适用。
 * 虽然这种队列机制能有效平滑瞬时请求峰值，但当命令平均到达速度持续超时处理能力时，仍可能导致工作队列无限增长。
 *
 * 3、有界队列：例如ArrayBlockingQueue，配合有限的maximum值可防止资源耗尽，但调优难度更高。
 * 队列大小与最大池大小存在权衡关系：使用大队列和小池能最小化CPU占用、操作系统资源及上下文切换开销，但可能导致吞吐量人为偏低。
 * 若任务频繁阻塞（例如受I/O限制），系统可能为更多线程分配调度时间。
 * 使用小队列通常需要更大的池容量，这虽然能提高CPU利用率，但可能产生不可接受的调度开销，同样会降低吞吐量。
 *
 * Rejected tasks：
 * 当executor已关闭，或者同时设置了最大线程数和工作队列容量的有限边界且处于饱和状态时，提交至execute(Runnable)方法的新任务将被拒绝。
 * 这两种情况下，execute方法都会调用其RejectedExecutionHandler的rejectedExecution(Runnable, ThreadPoolExecutor)方法，
 * 系统提供了四种预定义的处理策略：
 * 1、在默认的ThreadPoolExecutor.AbortPolicy策略中，executor在拒绝任务时会抛出运行时异常RejectedExecutionException。
 * 2、在ThreadPoolExecutor.CallerRunsPolicy策略中，调用execute方法的线程将自行执行任务，这提供了一种简单的反馈控制机制，能够降低新任务的提交速率。
 * 3、在ThreadPoolExecutor.DiscardPolicy中，无法执行的任务会直接被丢弃，该策略仅适用于极少数任务完成结果无需依赖的特殊场景。
 * 4、在ThreadPoolExecutor.DiscardOldestPolicy中，若executor未关闭，则会丢弃工作队列首任务并重试执行（可能再次失败，导致循环重试），
 * 该策略极少适用，几乎所有情况下，都应该同时取消任务以触发等待其完成的组件抛出异常，并记录失败日志，具体操作可参考ThreadPoolExecutor.DiscardOldestPolicy文档说明。
 *
 * Hook methods：
 * 该类提供了protected的方法beforeExecute(Thread, Runnable)和afterExecute(Runnable,Throwable)，它们分别在每个任务执行前后被调用。
 * 这些方法可用于操作执行环境，例如重新初始化线程局部变量、收集统计信息或添加日志条目。
 * 此外，可重写terminated方法来执行在executor完全终止后需要进行的特殊处理。
 * 如果钩子、回调或阻塞队列方法抛出异常，内部工作线程可能随之失败、突然终止并被替换。
 *
 * Queue maintenance：
 * getQueue()方法允许访问工作队列，用于监控和调试目的，强烈不建议将此方法用于其他任何用途。
 * 当大量队列任务被取消时，可使用提供的两个方法remove(Runnable)和purge来协助回收存储空间。
 *
 * Reclamation：
 * 在程序中不再被引用的线程池，且没有剩余线程时，可被回收而无需显式关闭。
 * 可通过设置适当的keepAliveTime、将core设为0或设置allowCoreThreadTimeOut(boolean)来配置线程池，使其允许所有未使用的线程最终终止。
 *
 * @author liyibo
 * @date 2026-02-20 21:00
 */
public class ThreadPoolExecutor extends AbstractExecutorService {
    /**
     * 主池控制状态ctl是一个原子整数，封装了两个概念字段：workerCount（表示有效线程）和runState（表示运行状态，如运行中、关闭中等）。
     * 为将其封装为单个int类型，我们限制workerCount最大值为(2^29)-1（约5亿）而非理论可达的(2^31)-1（约20亿）。
     * 若未来出现问题，可将该变量改为AtomicLong类型，并调整下文的移位/掩码常量，但在实际需要出现前，使用int能使代码更简洁高效。
     * workerCount代表已获准启动且未被禁止停止的工作线程数量，该值可能与实际活跃线程池存在短暂偏差，例如线程工厂创建失败时，或退出线程在终止前仍执行收尾操作时。
     * 用户可见的池容量即为当前工作者集大小。
     *
     * 运行状态提供了核心声明周期控制，取值如下：
     * RUNNING：接受新任务并处理队列任务。
     * SHUTDOWN：不接受新任务，但处理队列中任务。
     * STOP：不接受新任务，也不处理队列任务，并中断进行中的任务。
     * TIDYING：所有任务已终止，workerCount为零，进入TIDYING状态的线程将执行terminated()钩子方法。
     * TERMINATED：terminated()已完成。
     * 这些值的数值顺序至关重要，已便进行有序比较。
     * 运行状态随时间单调递增，但不必遍历所有状态（状态转换略，这里翻译的有点乱了）。
     */
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    private static final int COUNT_BITS = Integer.SIZE - 3;
    private static final int COUNT_MASK = (1 << COUNT_BITS) - 1;

    private static final int RUNNING    = -1 << COUNT_BITS;
    private static final int SHUTDOWN   =  0 << COUNT_BITS;
    private static final int STOP       =  1 << COUNT_BITS;
    private static final int TIDYING    =  2 << COUNT_BITS;
    private static final int TERMINATED =  3 << COUNT_BITS;

    private static int runStateOf(int c)     { return c & ~COUNT_MASK; }
    private static int workerCountOf(int c)  { return c & COUNT_MASK; }
    private static int ctlOf(int rs, int wc) { return rs | wc; }

    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    private void decrementWorkerCount() {
        ctl.addAndGet(-1);
    }

    /**
     * 用于存储任务并传递给工作现成的队列。
     * 我们不要求workQueue.poll()返回null时必然意味着workQueue.isEmpty()为true，
     * 因此仅依赖isEmpty判断队列是否为空（例如在决定是否从SHUTDOWN状态转到TIDYING状态时必须如此）。
     * 此设计兼容特殊用途的队列（如延迟队列），允许poll()在延迟到期前返回null，即使后续可能返回非null值。
     */
    private final BlockingQueue<Runnable> workQueue;

    /**
     * 对工作者集访问及相关账目记录实施锁定，虽然可采用某种并发集合，但实践证明使用锁更为可取。
     * 原因之一在于此举可串行化中断闲置工作者的处理，从而避免不必要的中断风暴，尤其在系统关闭期间，否则退出线程会并发中断尚未中断的线程。
     * 此方案还简化了最大池大小等相关统计记录的维护，在关闭和立即关闭操作中，我们同样持有主锁，以确保在分别检查中断权限和执行中断操作时，工作者集保持稳定状态。
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * 包含池中所有工作线程的集合，仅在持有mainLock时访问。
     */
    private final HashSet<Worker> workers = new HashSet<>();

    /**
     * 等待条件以支持awaitTermination。
     */
    private final Condition termination = mainLock.newCondition();

    /**
     * 记录达到的最大池大小，仅在持有mainLock时访问。
     */
    private int largestPoolSize;

    /**
     * 已完成任务的计数器，仅在工作线程终止时更新（因为没有了，要记录之前的值），仅在持有mainLock时访问。
     */
    private long completedTaskCount;

    /**
     * 新线程工厂，所有线程均通过此工厂创建（使用addWorker方法）。
     * 调用方必须做好addWorker失败的准备，这可能反映系统或用户策略对线程数量的限制。
     * 尽管不视为错误，但创建线程失败可能导致新任务被拒绝或现有任务滞留在队列中。
     * 我们进一步确保即使在创建线程时可能抛出OutOfMemoryError等错误时，也能保持线程池的不变性。
     * 由于Thread.start方法需要分配本机栈空间，此类错误较为常见，用户需执行干净的池关闭操作进行清理。
     * 此时系统通常仍保留足够内存供清理代码完成，不会再次触发OutOfMemoryError。
     */
    private volatile ThreadFactory threadFactory;

    /**
     * 处理程序在执行过程中饱和和关闭时被调用。
     */
    private volatile RejectedExecutionHandler handler;

    /**
     * 空闲线程等待任务的超时时间（单位是纳秒）。
     * 当活跃线程数超过核心线程池大小，或启动allowCoreThreadTimeOut时，线程将使用此超时时间，否则将无限期等待新任务。
     */
    private volatile long keepAliveTime;

    /**
     * 默认值为false，核心线程即使处于空闲状态也会保持存活。
     * 如果为true，核心线程将使用keepAliveTime超时等待任务。
     */
    private volatile boolean allowCoreThreadTimeOut;

    private volatile int corePoolSize;

    private volatile int maximumPoolSize;

    private static final RejectedExecutionHandler defaultHandler = new AbortPolicy();

    private static final RuntimePermission shutdownPerm = new RuntimePermission("modifyThread");

    private final class Worker extends AbstractQueuedSynchronizer implements Runnable {
        private static final long serialVersionUID = 6138294804551838833L;
        final Thread thread;
        Runnable firstTask;

        /** 该worker完成的任务数 */
        volatile long completedTasks;

        Worker(Runnable firstTask) {
            /**
             * AQS的方法，这里线程刚启动但还没进入run loop之前，先抑制interrupt，
             * 进入runWorker后立刻unlock，把它变成可变interrupt状态。
             */
            setState(-1);
            this.firstTask = firstTask;
            this.thread = getThreadFactory().newThread(this);
        }

        @Override
        public void run() {
            runWorker(this);
        }

        /**
         * 是否锁定中
         */
        protected boolean isHeldExclusively() {
            /**
             * state值为0表示未锁定状态，state值为1表示已锁定状态
             */
            return getState() != 0;
        }

        /**
         * 尝试获取锁
         */
        protected boolean tryAcquire(int unused) {
            if(compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        /**
         * 尝试释放锁
         */
        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        public void lock() {
            acquire(1);
        }

        public boolean tryLock() {
            return tryAcquire(1);
        }

        public void unlock() {
            release(1);
        }

        public boolean isLocked() {
            return isHeldExclusively();
        }

        void interruptIfStarted() {
            Thread t;
            if(getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                    // nothing to do
                }
            }
        }
    }

    /**
     * 将运行状态转换至指定目标，若当前状态已达到或超过指定目标，则保持不变。
     */
    private void advanceRunState(int targetState) {
        // assert targetState == SHUTDOWN || targetState == STOP;
        for (;;) {
            int c = ctl.get();
            if (runStateAtLeast(c, targetState) || ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
                break;
        }
    }

    /**
     * 当满足以下任一条件时，状态将转成TERMINATED：
     * 1、SHUTDOWN状态且线程池与队列为空。
     * 2、STOP状态且线程池为空。
     * 若满足终止条件，但workerCount不为零，则中断空闲worker以确保关闭信号传播。
     * 在任何可能导致终止的操作之后（如减少worker数量或关闭期间从队列remove任务），则必须调用此方法。
     * 该方法是默认包权限，以便于ScheduledThreadPoolExecutor来共享访问。
     */
    final void tryTerminate() {
        for(; ;) {
            int c = ctl.get();
            // 但凡满足这些都不能切换到TERMINATED
            if(isRunning(c) || runStateAtLeast(c, TIDYING) || (runStateLessThan(c, STOP) && !workQueue.isEmpty()))
                return;
            if(workerCountOf(c) != 0) {
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            // 开始切换至TERMINATED状态
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                if(ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        terminated();
                    } finally {
                        ctl.set(ctlOf(TERMINATED, 0));
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
        }
    }

    private void checkShutdownAccess() {
        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if(security != null) {
            security.checkPermission(shutdownPerm);
            for(Worker w : workers)
                security.checkAccess(w.thread);
        }
    }

    private void interruptWorkers() {
        for(Worker w : workers)
            w.interruptIfStarted();
    }

    /**
     * 中断可能正在空闲等待的任务（通过未被锁定来识别）的线程，以便它们能够检查终止或配置更改。
     */
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for(Worker w : workers) {
                Thread t = w.thread;
                if(!t.isInterrupted() && w.tryLock()) {
                    try {
                        t.interrupt();
                    } catch (SecurityException ignore) {

                    } finally {
                        w.unlock();
                    }
                }
                if(onlyOne)
                    break;
            }
        } finally {
            mainLock.unlock();
        }
    }

    private void interruptIdleWorkers() {
        interruptIdleWorkers(false);
    }

    private static final boolean ONLY_ONE = true;

    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    /**
     * 钩子函数
     */
    void onShutdown() {}

    /**
     * 将任务队列清空到新列表中，通常使用drainTo方法。
     * 但如果队列是延迟队列或其他可能导致poll或drainTo无法移除某些元素的队列类型，则会逐个删除这些元素。
     */
    private List<Runnable> drainQueue() {
        BlockingQueue<Runnable> q = workQueue;
        ArrayList<Runnable> taskList = new ArrayList<>();
        q.drainTo(taskList);
        if(!q.isEmpty()) {
            for(Runnable r : q.toArray(new Runnable[0])) {
                if(q.remove(r))
                    taskList.add(r);
            }
        }
        return taskList;
    }

    /**
     * 检查当前线程池状态和给定限制（core和maximum）是否允许增加新worker线程。
     * 如果允许，则相应调整worker线程数量，若条件允许，则创建并启动新worker线程，使其首次执行firstTask任务。
     * 若线程池处于停止状态或具备关闭条件，本方法返回false，当线程工厂创建线程失败同样返回false。
     * 若因线程工厂返回null或异常（通常是Thread.start()中的OutOfMemoryError）导致创建失败，则执行干净回滚。
     *
     * 负责解决一个最重要的问题：在高并发+多状态下，如何安全地增加一个worker。
     * 要做的事：
     * 1、判断当前是否允许创建新线程（runState + 队列状态）
     * 2、CAS增加workerCount。
     * 3、创建Worker对象，加入workers集合，启动线程。
     * 上面任何一步失败都要干净回滚。
     * @param firstTask 新线程应首先执行的任务（若无则为null），当线程数少于core大小时，或队列已满时，会通过初始任务创建worker线程以跳过队列操作。
     *                  初始空闲线程通常通过prestartCoreThread创建，或用于替换即将终止的worker线程。
     * @param core 如果为true，则使用corePoolSize为上限，否则使用maximumPoolSize
     */
    private boolean addWorker(Runnable firstTask, boolean core) {
        /**
         * 外层retry，为了处理：
         * 1、runState发生了变化（RUNNING -> SHUTDOWN）。
         * 2、ctl被其他线程修改了。
         */
        retry:
        /**
         * 外层循环，为了处理：runState变化导致策略变化。
         */
        for(int c = ctl.get(); ;) {
            /**
             * 1、STOP状态不允许add。
             * 2、SHUTDOWN状态，同时是firstTask（SHUTDOWN不接受新任务，只能消化队列里的旧任务）。
             * 3、SHUTDOWN状态，同时workQueue没有任务了（add了也没用了，因为要进入STOP了）。
             */
            if(runStateAtLeast(c, SHUTDOWN) &&  // 线程池处于停止状态或者具体关闭条件
                    (runStateAtLeast(c, STOP) || firstTask != null || workQueue.isEmpty()))
                return false;
            for(; ;) {  // 增加worker，内层循环只是为了处理：workCount竞争失败
                if(workerCountOf(c) >= ((core ? corePoolSize : maximumPoolSize) & COUNT_MASK))  // worker已超出最大限制
                    return false;
                if(compareAndIncrementWorkerCount(c))
                    break retry;    // 跳出最外层的for循环
                c = ctl.get();  // 到这里说明增加worker数量失败了
                if(runStateAtLeast(c, SHUTDOWN))
                    continue retry;
            }
        }

        // 成功新增了workerCount，开始创建Worker和新线程
        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker w = null;
        try {
            w = new Worker(firstTask);
            final Thread t = w.thread;
            if(t != null) {
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    int c = ctl.get();  // 总是要再次获取状态，就是为了防止有并发调用shutdown之类的方法
                    /**
                     * 1、RUNNING可以新增worker
                     * 2、SHUTDOWN但是firstTask为null（补线程）
                     */
                    if(isRunning(c) || (runStateLessThan(c, STOP) && firstTask == null)) {
                        if(t.getState() != Thread.State.NEW)
                            throw new IllegalStateException();
                        workers.add(w);
                        workerAdded = true;
                        int s = workers.size();
                        if(s > largestPoolSize)
                            largestPoolSize = s;
                    }
                } finally {
                    mainLock.unlock();
                }
                if(workerAdded) {
                    t.start();  // 在这里启动了刚建立的新线程
                    workerStarted = true;
                }
            }
        } finally {
            if(!workerStarted)
                addWorkerFailed(w); // 清理
        }
        return workerStarted;
    }

    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if(w != null)
                workers.remove(w);
            decrementWorkerCount();
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 为即将终止的线程，执行清理和账目处理，仅从worker线程中调用。
     * 除非设置了completedAbruptly，否则默认认为workerCount已调整已适应退出情况。
     * 该方法将线程移除工作线程集，并在以下任一情况下可能终止线程池或替换该线程：
     * 1、因用户任务异常导致退出。
     * 2、运行中的线程数少于corePoolSize。
     * 3、队列非空但无可用线程。
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        // 如果是异常退出，需要补减（如果是正常退出，会在之前的getTask里面就减过了）。
        if(completedAbruptly)
            decrementWorkerCount();

        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            completedTaskCount += w.completedTasks; // 汇总
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }

        /**
         * 每次有work退出，都要尝试触发终止，因为shutdown状态下，只要满足：
         * 1、workQueue空。
         * 2、workerCount变为0.
         * 就可以进入TIDYING/TERMINATED状态了
         */
        tryTerminate();

        int c = ctl.get();

        /**
         * 是否需要补线程：
         * STOP以上（STOP/TIDYING/TERMINATED）不再补线程。
         * 有两种情况需要补线程：
         * 1、异常死亡（completedAbruptly=true），会直接补，保证线程池吞吐稳定
         * 2、正常退出（completedAbruptly=false），需要检查是否低于最小保活线程数min（如果队列还有任务，最小为1，因为需要必须留一个干活的）
         * 如果当前workerCount >= min，则不需要补
         * 这段流程是线程池维持核心线程 + 保证队列可被消费的关键逻辑。
         */
        if (runStateLessThan(c, STOP)) {
            if(!completedAbruptly) {
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                if(min == 0 && !workQueue.isEmpty())
                    min = 1;
                if(workerCountOf(c) >= min)
                    return;
            }
            addWorker(null, false);
        }
    }

    /**
     * 根据当前配置，对任务执行阻塞或定时等待，如果因以下任一情况导致该worker必须退出，则返回null：
     * 1、worker数量超过最大池大小（因调用了setMaximumPoolSize导致）。
     * 2、线程池已停止。
     * 3、线程池已关闭且队列为空。
     * 4、该worker在等待任务时超时，且超时worker在定时等待前后均可能被终止（即满足allowCoreThreadTimeOut || workerCount > corePoolSize条件），
     * 同时若队列非空且该worker并非池中的最后一个线程。
     *
     * 通俗地说：从队列里取任务 + 线程回收策略 + shutdown协议，三者合并在一个循环里完成。
     */
    private Runnable getTask() {
        boolean timedOut = false;   // 调用poll()是否超时

        for(;;) {
            int c = ctl.get();
            /**
             * SHUTDOWN：不接新任务，但还要把队列任务跑完，所以只有workQueue.isEmpty了才能退出
             * STOP：不接新任务，不跑队列，还要interrupt正在跑的
             */
            if(runStateAtLeast(c, SHUTDOWN) && (runStateAtLeast(c, STOP) || workQueue.isEmpty())) {
                decrementWorkerCount(); // 在addWorker里面增加的数字，在这里减回来
                return null;
            }

            int wc = workerCountOf(c);

            // 线程需要回收的判断（开启了allowCoreThreadTimeOut，或者线程总数超过core了）
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

            /**
             * 1、maximum被调小了。
             * 2、上一次poll超时了，说明这个线程空闲超过keepAliveTime了。
             * 3、避免队列不空但把最后一个线程给干掉了。
             */
            if((wc > maximumPoolSize || (timed && timedOut)) && (wc > 1 || workQueue.isEmpty())) {
                if(compareAndDecrementWorkerCount(c))
                    return null;
                continue;
            }

            try {
                Runnable r = timed
                        ? workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS)
                        : workQueue.take();
                if(r != null)
                    return r;
                timedOut = true;
            } catch (InterruptedException retry) {
                timedOut = false;
            }
        }
    }

    /**
     * 主工作线程运行循环，该循环反复从队列获取任务并执行，同时处理以下情况：
     * 1、可能存在初始任务，此时无需获取首个任务，否则只要线程池运行，任务均通过getTask获取。
     * 若该方法返回null，则因线程池状态或配置参数变更导致工作线程退出，
     * 其他退出情况源于外部代码抛出异常，此时completedAbruptly为true，通常会触发processWorkerExit替换该线程。
     * 2、执行任务前需获取锁，以防止任务运行期间其他池中断，随后确保除非池正在停止，否则该线程不会被设置中断。
     * 3、每次任务执行前都会调用beforeExecute方法，该方法可能抛异常，此时我们将直接终止线程（通过设置completedAbruptly为true来中断循环），而不处理该任务。
     * 4、假设beforeExecute正常完成，我们将执行任务并捕获异常转发至afterExecute，我们分别处理RuntimeException、Error（规范保证这两类异常会被捕获）和任意Throwable异常。
     * 由于无法在Runnable.run内部重抛Throwable异常，我们将其包装为Error异常传递至线程的UncaughtExceptionHandler。任何抛出的异常都会保守地导致线程终止。
     * 任务执行完成后调用afterExecute方法，该方法同样可能抛出异常并导致线程终止。
     *
     * 个人注：类似模板方法模式
     */
    final void runWorker(Worker w) {
        Thread wt = Thread.currentThread();
        Runnable task = w.firstTask;
        w.firstTask = null;
        w.unlock(); // 参照Worker构造方法里面的对应注释，这里是把worker变成可以被interrupt的状态
        /**
         * 是给processWorkerExit用来判断：是不是用户任务/钩子导致的非正常退出。
         * 默认就是true，后面跑完while循环才会改成false
         */
        boolean completedAbruptly = true;
        try {
            while(task != null || (task = getTask()) != null) {
                w.lock();   // 加锁

                /**
                 * 比较复杂的一段判断：
                 * 1、如果线程池已经STOP，则要求正在跑任务的线程也要尽量中断，所以要调用interrupt方法。
                 * 2、如果线程池不是STOP，要清除中断标记（通过Thread.interrupted方法）
                 * 3、最后的STOP是防御并发竞态的，如果刚清除了中断，线程池就进入STOP了，也会进入if里面来重新打开中断标记
                 */
                if ((runStateAtLeast(ctl.get(), STOP) || (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP)))
                        && !wt.isInterrupted())
                    wt.interrupt();
                try {
                    this.beforeExecute(wt, task);   // 注意这里面（用户的方法）如果抛出异常，completedAbruptly值依然为true。
                    try {
                        task.run(); // 而这里面如果抛出异常，会进入catch，最后再throw。
                        this.afterExecute(task, null);
                    } catch (Throwable t) {
                        this.afterExecute(task, t); // 这里（用户的方法）如果抛出异常，会覆盖run的异常。
                        throw t;
                    }
                } finally {
                    task = null;    // 强制下一轮从getTask方法拿
                    w.completedTasks++;
                    w.unlock();
                }
            }
            completedAbruptly = false;
        } finally { // worker正常会一直循环，到这里了说明worker要退出了（池状态变化或线程策略导致的）
            processWorkerExit(w, completedAbruptly);
        }
    }

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
                              long keepAliveTime, TimeUnit unit,
                              BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Executors.defaultThreadFactory(), defaultHandler);
    }

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
                              long keepAliveTime, TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, defaultHandler);
    }

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
                              long keepAliveTime, TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Executors.defaultThreadFactory(), handler);
    }

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
                              long keepAliveTime, TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory,
                              RejectedExecutionHandler handler) {
        if(corePoolSize < 0 || maximumPoolSize <= 0 || maximumPoolSize < corePoolSize || keepAliveTime < 0)
            throw new IllegalArgumentException();
        if(workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    @Override
    public void execute(Runnable command) {
        if(command == null)
            throw new NullPointerException();
        /**
         * 2、若任务成功入队，仍需复核：
         * 是否应新增线程（因上次检查后现有线程已终止），或任务队列自进入本方法后是否已关闭。
         * 故需重新检查状态，必要时：
         * 若队列停止则回滚入队操作，若无可用线程则启动新线程。
         *
         * 3、若任务无法入队，则尝试创建新线程，若创建失败，则表明线程池已关闭或饱和，此时应拒绝该任务。
         */

        /**
         * 1、若运行中的线程数少于核心线程大小，则尝试：
         * 启动新线程并将其作为首个任务执行给定的命令。
         * 调用addWorker时会原子性地检查运行状态和线程数量，通过返回false来防止在不应添加线程时触发虚假警报。
         */
        int c = ctl.get();
        if(workerCountOf(c) < corePoolSize) {
            if(addWorker(command, true))    // 成功启动新的core线程，则可以返回了
                return;
            c = ctl.get();
        }
        // 到这里说明core线程已经满了，任务要先入队
        if(isRunning(c) && workQueue.offer(command)) {
            // 入队成功
            int recheck = ctl.get();
            if(!isRunning(recheck) && remove(command))
                reject(command);
            else if(workerCountOf(recheck) == 0)
                addWorker(null, false);
        }else if(!addWorker(command, false)) {
            reject(command);
        }
    }

    /**
     * 启动有序关闭流程，此过程中将执行先前提交的任务，但不再接受新任务。
     * 若系统已处于关闭状态，调用此方法不会产生额外效果。
     * 此方法不会等待先前提交的任务完成执行，如需等待，请使用awaitTermination方法。
     */
    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            checkShutdownAccess();
            advanceRunState(SHUTDOWN);
            interruptIdleWorkers();
            onShutdown();   // 回调钩子
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
    }

    /**
     * 尝试停止所有正在执行的任务，暂停等待任务的处理，并返回待执行的任务列表，方法返回后，这些任务将从任务队列中清空移除。
     * 此方法不会等待先前提交的任务完成执行，如需等待，请使用awaitTermination方法。
     * 除尽最大努力尝试停止处理正在执行的任务外，不作任何保证。本实现通过Thread.interrupt中断任务，任何未能响应中断的任务可能永远不会终止。
     */
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            checkShutdownAccess();
            advanceRunState(STOP);
            interruptWorkers();
            tasks = drainQueue();
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
        return tasks;
    }

    public boolean isShutdown() {
        return runStateAtLeast(ctl.get(), SHUTDOWN);
    }

    boolean isStopped() {
        return runStateAtLeast(ctl.get(), STOP);
    }

    /**
     * 如果该executor在shutdown或shutdownNow后，处于终止过程中，但尚未完全终止，则返回true。
     * 此方法有助于调试，在关机后足够长时间内仍返回true，可能表明提交的任务忽略或抑制了中断，导致executor无法正常终止。
     */
    public boolean isTerminating() {
        int c = ctl.get();
        return runStateAtLeast(c, SHUTDOWN) && runStateLessThan(c, TERMINATED);
    }

    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(), TERMINATED);
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            while(runStateAtLeast(ctl.get(), TERMINATED)) {
                if(nanos <= 0L)
                    return false;
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            mainLock.unlock();
        }
    }


    @Deprecated(since="9")
    protected void finalize() {}

    public void setThreadFactory(ThreadFactory threadFactory) {
        if(threadFactory == null)
            throw new NullPointerException();
        this.threadFactory = threadFactory;
    }

    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if(handler == null)
            throw new NullPointerException();
        this.handler = handler;
    }

    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    /**
     * 设置core线程数，此设置将覆盖构造方法中设定的值。
     * 若新值小于当前值，则现有超额线程将在下次闲置时终止。
     * 若新值大于当前值，则系统将在需要时启动新线程以执行队列中的任务。
     */
    public void setCorePoolSize(int corePoolSize) {
        if(corePoolSize < 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        if (workerCountOf(ctl.get()) > corePoolSize)
            interruptIdleWorkers();
        else if(delta > 0) { // 如果调大了，则从队列中放相应数量的任务启动执行
            int k = Math.min(delta, workQueue.size());
            while(k-- > 0 && addWorker(null, true)) {
                if(workQueue.isEmpty())
                    break;
            }
        }
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    /**
     * 启动一个core线程，使其处于空闲状态等待任务。
     * 这将覆盖默认策略 -- 仅在新任务执行时启动core线程，如果所有core线程都启动了，方法将返回false
     */
    public boolean prestartCoreThread() {
        return workerCountOf(ctl.get()) < corePoolSize && addWorker(null, true);
    }

    /**
     * 与prestartCoreThread相同，但确保即使core线程池大小为0，至少也有一个线程会被启动。
     */
    void ensurePrestart() {
        int wc = workerCountOf(ctl.get());
        if(wc < corePoolSize)
            addWorker(null, true);
        else if(wc == 0)    // corePoolSize为0
            addWorker(null, false);
    }

    /**
     * 启动所有的core线程，使其处于空闲状态等待任务，这覆盖了默认策略 -- 仅在新任务时才启动core线程。
     */
    public int prestartAllCoreThreads() {
        int n = 0;
        while(this.addWorker(null, true))
            ++n;
        return n;
    }

    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    /**
     * 设置core线程的生存策略：若在keepAliveTime内未收到任务，core线程是否会超时终止，并在收到新任务时被替换。
     * 当值为false时，core线程不会因缺乏传入任务而终止。
     * 当值为true时，非core线程适用的keepAlive策略同样适用于core线程。
     * 为避免持续的线程替换，设置为true时keepAliveTime必须大于0，该方法通常应在线程池被积极使用前调用。
     */
    public void allowCoreThreadTimeOut(boolean value) {
        if(value && keepAliveTime <= 0)
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            if(value)
                interruptIdleWorkers(); // 如果由false变成true了，则可能要提前移除一些闲置线程
        }
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        if(maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
        if(workerCountOf(ctl.get()) > maximumPoolSize)  // 如果maximumPoolSize变小了，则可能要提前移除一些闲置线程
            interruptIdleWorkers();
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setKeepAliveTime(long time, TimeUnit unit) {
        if(time < 0)
            throw new IllegalArgumentException();
        if(time == 0 && allowsCoreThreadTimeOut())
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        long keepAliveTime = unit.toNanos(time);
        long delta = keepAliveTime - this.keepAliveTime;
        this.keepAliveTime = keepAliveTime;
        if(delta < 0)   // 如果keepAliveTime变小了，则可能要提前移除一些闲置线程
            interruptIdleWorkers();
    }

    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS);
    }

    /* User-level queue utilities */

    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate();
        return removed;
    }

    /**
     * 尝试从工作队列中移除所有已被取消的Future任务，此方法可作为存储回收操作，对功能性没有其他影响。
     * 已取消的任务永远不会执行，但可能在工作队列中累积，直到工作线程主动移除它们。
     * 调用此方法则会立即尝试移除这些任务，然而当存在其他线程干扰时，此方法可能无法成功移除任务。
     */
    public void purge() {
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> iter = q.iterator();
            while(iter.hasNext()) {
                Runnable r = iter.next();
                if(r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    iter.remove();
            }
        } catch (ConcurrentModificationException fallThrough) {
            for(Object r : q.toArray()) {
                if(r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    q.remove(r);
            }
        }
        tryTerminate();
    }

    public int getPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return runStateAtLeast(ctl.get(), TIDYING) ? 0 : workers.size();
        } finally {
            mainLock.unlock();
        }
    }

    public int getActiveCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            int n = 0;
            for(Worker w : workers)
                if(w.isLocked())
                    ++n;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    public int getLargestPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        } finally {
            mainLock.unlock();
        }
    }

    public long getTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for(Worker w : workers) {
                n += w.completedTasks;
                if (w.isLocked())
                    ++n;
            }
            return n + workQueue.size();
        } finally {
            mainLock.unlock();
        }
    }

    public long getCompletedTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for(Worker w : workers)
                n += w.completedTasks;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    @Override
    public String toString() {
        long ncompleted;
        int nworkers, nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for(Worker w : workers) {
                ncompleted += w.completedTasks;
                if (w.isLocked())
                    ++nactive;
            }
        } finally {
            mainLock.unlock();
        }

        int c = ctl.get();
        String runState = isRunning(c)
                ? "Running"
                :  runStateAtLeast(c, TERMINATED) ? "Terminated" : "Shutting down";
        return super.toString() +
                "[" + runState +
                ", pool size = " + nworkers +
                ", active threads = " + nactive +
                ", queued tasks = " + workQueue.size() +
                ", completed tasks = " + ncompleted +
                "]";
    }

    /**
     * 给子类实现的preExecute钩子
     */
    protected void beforeExecute(Thread t, Runnable r) {}

    /**
     * 给子类实现的postExecute钩子
     */
    protected void afterExecute(Runnable r, Throwable t) {}

    /**
     * 给子类实现的terminate钩子
     */
    protected void terminated() {}

    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        public CallerRunsPolicy() {}

        public void rejectedExecution(Runnable r, java.util.concurrent.ThreadPoolExecutor executor) {
            if(!executor.isShutdown())
                r.run();
        }
    }

    public static class AbortPolicy implements RejectedExecutionHandler {
        public AbortPolicy() {}

        public void rejectedExecution(Runnable r, java.util.concurrent.ThreadPoolExecutor executor) {
            throw new RejectedExecutionException("Task " + r.toString() + " rejected from " + executor.toString());
        }
    }

    public static class DiscardPolicy implements RejectedExecutionHandler {
        public DiscardPolicy() {}

        @Override
        public void rejectedExecution(Runnable r, java.util.concurrent.ThreadPoolExecutor executor) {
            // 什么也不需要做就可以了
        }
    }

    /**
     * 该处理器会丢弃最旧的未处理请求，然后重试执行。
     */
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        public DiscardOldestPolicy() {}

        @Override
        public void rejectedExecution(Runnable r, java.util.concurrent.ThreadPoolExecutor executor) {
            if(!executor.isShutdown()) {
                executor.getQueue().poll();
                executor.execute(r);
            }
        }
    }
}

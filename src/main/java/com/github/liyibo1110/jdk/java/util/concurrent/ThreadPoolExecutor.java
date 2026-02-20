package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.HashSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
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
     * 已完成任务的计数器，仅在工作线程终止时更新，仅在持有mainLock时访问。
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
}

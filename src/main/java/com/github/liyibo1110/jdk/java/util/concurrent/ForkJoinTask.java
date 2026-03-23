package com.github.liyibo1110.jdk.java.util.concurrent;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.Callable;
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 一个为了可拆分、可窃取、可协作完成，而设计的特殊Future。
 *
 * 和之前出现过的FutureTask很不一样，FutureTask的中心思想是：
 * 包住一个异步计算，等线程池执行完，等别人调用get获取计算结果。
 * 1、一个任务。
 * 2、一次执行。
 * 3、结果计算出来后就结束。
 * 4、主要面对普通线程池ExecutorService。
 * 重点是：
 * 1、任务执行。
 * 2、结果存储。
 * 3、等待唤醒。
 * 4、cancel/interrupt语义。
 *
 * 而ForkJoinTask的中心思想是：
 * 这个任务不只是等着被执行，它还可以被继续拆分，被当前线程直接帮忙执行，被别的worker窃取执行，甚至以树状方式协同完成。
 * 它本质上并不是普通的异步结果容器，而是：
 * 1、调度器可以识别的任务单元。
 * 2、支持fork/join协作的状态机。
 * 3、支持轻量等待与完成传播的Future抽象。
 *
 * 对比FutureTask更像：
 * 1、一个可以被ForkJoinPool理解的任务节点。
 * 2、可能有父子关系/递归拆分关系。
 * 3、可以被worker线程自己执行，也可以被别的worker偷走。
 * 4、等待时不是纯阻塞，常常会顺手帮着干活。
 * 5、更强调吞吐和协作，而不是传统阻塞等待。
 *
 * FutureTask是：线程池提交任务的抽象。
 * ForkJoinTask是：工作窃取计算图中的任务节点抽象。
 *
 * 与ForkJoinPool的关系：ForkJoinPool负责调度，ForkJoinTask负责表达任务。
 * Pool要解决的主要问题：
 * 1、worker怎么取任务。
 * 2、怎么偷任务。
 * 3、怎么补偿阻塞。
 * 4、怎么维护队列和活跃度。
 * Task要解决的问题：
 * 1、这个任务有没有完成。
 * 2、任务计算结果是什么。
 * 3、异常是什么。
 * 4、join时该怎么等。
 * 5、exec是该怎么跑。
 * 6、如何适配不同子类：RecursiveTask / RecursiveAction / CountedCompleter
 *
 * ForkJoinTask的本质抽象：fork + join
 * 1、fork不是新建线程，而是把当前任务压到某个worker的双端队列里，等待后续执行或被窃取。也就是fork像是：把任务发布到ForkJoinPool的语境里。
 * 2、join不是Future.get()，而是等待这个fork出去的任务完成，如果合适，当前线程可能自己去帮忙执行相关任务，而不是傻等。
 *
 * 为什么设计成Future接口的实现：因为从用户使用视角，依然要回答如下问题：
 * 1、任务计算完了吗？
 * 2、有结果了吗？
 * 3、抛异常了吗？
 * 4、任务能取消吗？
 * 5、我能等任务完成吗？
 * @author liyibo
 * @date 2026-03-22 19:07
 */
public abstract class ForkJoinTask<V> implements Future<V>, Serializable {

    /**
     * 承担了两种角色的数据结构，一物多用：
     * 1、等待者链表节点：thread != null，ex == null。
     * - aux指向一个等待者节点链表。
     * - 链表里每个节点记录了一个等待线程。
     * - 节点上通常是thread != null, ex == null。
     * 这时Aux表示：谁在等这个任务。
     *
     * 2、异常头节点/异常占位节点：ex != null
     * - aux头节点保存了异常对象ex。
     * - 这个节点不再是普通等待节点了。
     * - 代表：这个任务异常完成了，并且异常对象在这里。
     *
     * 核心思想：用一个轻量的辅助链表头aux，在不同阶段复用成不同语义。
     * 当aux.ex != null时，这个aux头节点的语义已经从等待链表头，切换成了异常记录头。
     */
    static final class Aux {
        final Thread thread;
        final Throwable ex;
        Aux next;

        Aux(Thread thread, Throwable ex) {
            this.thread = thread;
            this.ex = ex;
        }

        boolean casNext(Aux c, Aux v) {
            return NEXT.compareAndSet(this, c, v);
        }

        private static final VarHandle NEXT;
        static {
            try {
                NEXT = MethodHandles.lookup().findVarHandle(Aux.class, "next", Aux.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    private static final int DONE         = 1 << 31; // must be negative
    private static final int ABNORMAL     = 1 << 16;
    private static final int THROWN       = 1 << 17;
    private static final int SMASK        = 0xffff;  // short bits for tags
    private static final int UNCOMPENSATE = 1 << 16; // helpJoin return sentinel

    /**
     * 核心字段，整个ForkJoinTask本质就是围绕status运转，它记录任务当前的生命周期状态，比如：
     * 1、未完成
     * 2、正常完成
     * 3、异常完成
     * 4、取消
     * 5、等待者信号位
     * 注意是个类似：完成类型 + 等待信号等信息的组合状态，而不是单一状态值。
     */
    volatile int status;

    private transient volatile Aux aux;

    // Support for atomic operations
    private static final VarHandle STATUS;
    private static final VarHandle AUX;
    private int getAndBitwiseOrStatus(int v) {
        return (int)STATUS.getAndBitwiseOr(this, v);
    }
    private boolean casStatus(int c, int v) {
        return STATUS.compareAndSet(this, c, v);
    }
    private boolean casAux(Aux c, Aux v) {
        return AUX.compareAndSet(this, c, v);
    }

    /**
     * 如果当前任务上挂着等待者链表，就把整条等待链条摘下来，然后逐个unpark等待线程，即：
     * 1、任务已经完成了。
     * 2、要叫醒所有等这个任务的线程。
     */
    private void signalWaiters() {
        /**
         * 只要当前aux不为空，并且头节点不是异常节点，就尝试把它当等待者链表来处理
         */
        for(Aux a; (a = aux) != null && a.ex == null; ) {
            /**
             * 尝试把整个链表头摘掉：CAS当前aux，从链表头原子替换成null，如果成功就表示：
             * 1、当前线程拿到了整条等待链表的处理权。
             * 2、后面可以安心遍历并唤醒所有等待线程。
             * 3、其他线程不会在看到这条等待链表了
             * 设计思路：先整体摘链，再逐个唤醒。
             */
            if(casAux(a, null)) {
                /**
                 * 遍历链表，逐个unpark
                 */
                for(Thread t; a != null; a = a.next) {
                    /** 注意没有唤醒自己 */
                    if((t = a.thread) != Thread.currentThread() && t != null)
                        LockSupport.unpark(t);
                }
                break;
            }
        }
    }

    /**
     * 将任务设置为正常完成态，并做完成后的唤醒/清理
     */
    private int setDone() {
        int s = getAndBitwiseOrStatus(DONE) | DONE; // 给status原子性标记DONE位，并把更新后的结果返回
        signalWaiters();    // 任务完成要唤醒等待者
        return s;
    }

    private int trySetCancelled() {
        int s;
        do {

        }while ((s = status) >= 0 && !casStatus(s, s |= (DONE | ABNORMAL)));
        signalWaiters();
        return s;
    }

    /**
     * 尝试把当前任务设置为异常完成，并把异常对象登记起来。
     * 相当于异常路径下的setDone()
     */
    final int trySetThrown(Throwable ex) {
        /**
         * 创建异常头节点，注意这个不再是等待节点了。
         */
        Aux h = new Aux(Thread.currentThread(), ex);
        Aux p = null;
        boolean installed = false;
        int s;
        /**
         * 只要任务还未完成，就尝试安装异常节点并设置异常状态。
         */
        while((s = status) >= 0) {
            Aux a;
            /**
             * 尝试把aux替换成异常头节点。
             * 如果异常头节点还没安装成功，并且当前aux为空或表示等待者链表头，那就尝试用CAS把aux替换成异常头节点h。
             */
            if(!installed && ((a = aux) == null || a.ex == null) && (installed = casAux(a, h)))
                /**
                 * 原来的a可能是一整条等待者链表头，现在CAS成功后：
                 * 1、aux已经不再指向等待链表，而是指向异常头h。
                 * 2、原来的等待链表已经被摘下来了。
                 * 要把被替换下来的旧等待者链表保存下来，后面还要负责唤醒这些等待线程。
                 */
                p = a;
            /**
             * 如果异常头安装好了，就尝试设置异常完成状态。
             * 注意异常完成不是简单的DONE，而是DONE + ABNORMAL + THROWN。
             */
            if(installed && casStatus(s, s |= (DONE | ABNORMAL | THROWN)))
                break;
        }
        // 最后唤醒原来等待链表上的线程
        for(; p != null; p = p.next)
            LockSupport.unpark(p.thread);
        return s;
    }

    int trySetException(Throwable ex) {
        return trySetThrown(ex);
    }

    public ForkJoinTask() {}

    static boolean isExceptionalStatus(int s) {  // needed by subclasses
        return (s & THROWN) != 0;
    }

    /**
     * 是一个执行包装器。
     * 如果当前任务还没完成，就调用子类的exec()去实际执行。
     * 如果执行抛了异常，就把任务设置为异常完成。
     * 如果执行成功且任务已完成，就把任务设置为正常完成，最后返回最新状态。
     */
    final int doExec() {
        int s;
        boolean completed;  // 这次执行后，任务是否已经完成
        if((s = status) >= 0) { // 任务未完成
            try {
                completed = exec(); // 调用子类实现来完成
            } catch (Throwable t) {
                s = trySetException(t);
                completed = false;
            }
            if(completed)
                s = setDone();
        }
        return s;
    }

    /**
     * 等待当前task完成，并尽可能在等待前或等待过程中帮助它推进执行，只有是在没法推进时才park阻塞。
     * @param pool 自己是在哪个pool环境里等，判断我是不是这个pool内部的worker。
     * @param ran 当前任务是不是已经被当前路径尝试执行过了，防止重复执行当前任务。
     * @param interruptible 这次等待是否允许被中断给打断，如果true则中断会导致等待异常结束，否则中断只被记录，最终恢复中断标志。
     * @param timed 这次等待是不是带超时限制
     */
    private int awaitDone(ForkJoinPool pool, boolean ran, boolean interruptible, boolean timed, long nanos) {
        ForkJoinPool p;

        /** 当前等待线程，是不是目标pool的内部worker */
        boolean internal;
        int s;
        Thread t;

        /**
         * 当前线程对应的工作队列 / 外部队列，有了这个队列，后面才有机会：
         * 1、尝试从队列移除任务。
         * 2、尝试帮忙完成任务。
         * 3、参与join协作。
         */
        ForkJoinPool.WorkQueue q = null;
        /**
         * 阶段一：识别当前线程身份，确定等待环境，解决三个问题：
         * 1、当前线程是谁？是worker还是普通外部线程。
         * 2、当前线程属于哪个pool？worker的话取wt的pool，外部线程默认拿FJP.common
         * 3、当前线程有没有一个可参与协作的队列q？如果是worker，并且传入的pool就是它自己的pool，那q = wt.workQueue，
         * 如果是外部线程，并且等待目标pool就是commonPool，那q = p.externalQueue()
         */
        if((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) {
            ForkJoinWorkerThread wt = (ForkJoinWorkerThread) t;
            p = wt.pool;
            if(pool == null)
                pool = p;
            if(internal = (pool == p))
                q = wt.workQueue;
        }else {
            internal = false;
            p = ForkJoinPool.common;
            if(pool == null)
                pool = p;
            if(pool == p && p != null)
                q = p.externalQueue();
        }
        /**
         * 阶段二：处理中断、已完成、超时边界这些快速返回路径
         */
        if(interruptible && Thread.interrupted())
            return ABNORMAL;
        if((s = status) < 0)
            return s;
        long deadline = 0L;
        if(timed) {
            if(nanos <= 0L)
                return 0;   // 注意如果超时，方法则返回0
            else if((deadline = nanos + System.nanoTime()) == 0L)
                deadline = 1L;
        }
        /**
         * 阶段三：先尝试协作推进，而不是立刻阻塞（核心阶段，也是ForkJoin的特色代码）
         * 如果当前线程有队列、有池环境，那么在真正阻塞之前，先尽量帮忙推进当前任务或相关任务。
         */
        boolean uncompensate = false;   // 在某些等待协助场景里，pool可能为了维持并行度做过补偿，等等待结束后，要把补偿还回去
        if(q != null && p != null) {
            /**
             * 非超时等待：可以尽量帮.
             * 超时等待：通常不想无限帮下去，但如果池没有并行度，也还是可以帮。
             */
            boolean canHelp = !timed || (p.mode & SMASK) == 0;
            if(canHelp) {
                /**
                 * 如果是是CountedCompleter，就专门帮complete。
                 * 如果当前任务是CountedCompleter，那它有自己特殊的完成传播机制，就调用pool的helpComplete去协助它完成。
                 * 1、CountedCompleter不是普通：等子任务join模型。
                 * 2、更偏向完成计数与传播。
                 * 3、所以等待时用专门的complete协助路径
                 */
                if((this instanceof CountedCompleter) && (s = p.helpComplete(this, q, internal)) < 0)
                    return s;
                /**
                 * 如果任务还没执行过，尝试把它从队列里拿出来，当前线程自己来执行
                 * 条件1：!ran：表示当前任务还没在本路径里执行过。
                 * 条件2：尝试把任务从队列里拿掉：
                 * 外部线程路径：!internal && q.externalTryUnpush(this)：如果是外部线程，尝试从外部队列把当前任务弹回来。
                 * 内部线程路径：q.tryRemove(this, internal)：尝试从当前worker的工作队列中把这个任务移除出来。
                 * 条件3：如果拿出来成功，则自己来执行：既然任务还在队列里，还没被别人干掉，那我就直接自己把它执行了。
                 */
                if(!ran && ((!internal && q.externalTryUnpush(this)) || q.tryRemove(this, internal)) && (s = doExec()) < 0)
                    return s;
            }
            /**
             * 如果我是内部worker，就进入helpJoin。
             * 如果当前线程是目标pool的内部worker，那除了直接执行当前任务，还可以走更强的join协作路线，即帮助推进和这个join相关的工作。
             */
            if(internal) {
                if((s = p.helpJoin(this, q, canHelp)) < 0)
                    return s;
                if(s == UNCOMPENSATE)
                    uncompensate = true;
            }
        }
        /**
         * 阶段四：如果不能帮助，准备进入真正的阻塞等待循环
         */
        boolean interrupted = false;    // 在不可中断等待模式下，线程是否曾被中断过
        boolean queued = false; // 当前线程是否已经挂到任务的等待链表中了，即只能等待别人唤醒了
        boolean parked = false; // 当前线程是否已经真正park过了
        boolean fail = false;   // 是否出现了创建等待节点失败，或者pool进入失败/关闭状态等异常情况
        Aux node = null;    // 当前等待线程对应的等待节点Aux，后面会把它挂到aux链表里
        while((s = status) >= 0) {
            /**
             * 阶段五：循环内部先处理失败、取消和中断
             */
            Aux a;
            long ns;
            /**
             * 如果已经失败了，或者pool已经进入失败模式，则尝试把当前任务推进到异常完成态
             */
            if(fail || (fail = (pool != null && pool.mode < 0)))
                casStatus(s, s | (DONE | ABNORMAL));
            /**
             * 如果线程已经park过了，而且醒来后发现被中断：
             * 如果这次等待支持中断，直接异常退出。
             * 如果不支持中断，就先把被中断过记录下来，继续等。
             */
            else if (parked && Thread.interrupted()) {
                if(interruptible) {
                    s = ABNORMAL;
                    break;
                }
                interrupted = true;
            }
            /**
             * 阶段六：如果已经入队了，就真正park等待：
             * 如果我已经成功挂进等待队列了，那现在就可以park了。
             */
            else if(queued) {
                if(deadline != 0L) {
                    if((ns = deadline - System.nanoTime()) <= 0L)
                        break;
                    LockSupport.parkNanos(ns);
                }
                else
                    LockSupport.park();
                parked = true;
            }
            /**
             * 阶段七：如果还没入队，就先把自己挂到等待链表里
             */
            else if(node != null) {
                /**
                 * 已经有node了，就尝试入等待链表
                 */
                if((a = aux) != null && a.ex != null)
                    Thread.onSpinWait();     // exception in progress
                else if(queued = casAux(node.next = a, node))   // 注意又是挂到了头部，典型的Treiber stack风格的等待栈
                    LockSupport.setCurrentBlocker(this);    // 方便线程诊断工具知道它在等谁
            }
            else {
                /**
                 * 如果还没创建node，就先创建一个等待节点
                 */
                try {
                    node = new Aux(Thread.currentThread(), null);
                } catch (Throwable ex) {     // cannot create
                    fail = true;
                }
            }
        }

        /**
         * 阶段八：循环结束后的收尾清理
         */
        if(pool != null && uncompensate)    // 如果之前需要返还补偿，则执行
            pool.uncompensate();

        /**
         * 如果当前线程曾经挂入等待队列，则还要做清理
         */
        if(queued) {
            LockSupport.setCurrentBlocker(null);
            if(s >= 0) { // cancellation similar to AbstractQueuedSynchronizer
                /**
                 * 情况A：退出循环时任务还没完成，意味着：超时了、中断导致提前退出、某种取消/失败路径没成功执行完。
                 * 要把自己从等待链表里摘掉。
                 */
                outer: for(Aux a; (a = aux) != null && a.ex == null; ) {
                    for(Aux trail = null;;) {
                        Aux next = a.next;
                        if(a == node) {
                            if(trail != null)
                                trail.casNext(trail, next);
                            else if(casAux(a, next))
                                break outer; // cannot be re-encountered
                            break;           // restart
                        }else {
                            trail = a;
                            if((a = next) == null)
                                break outer;
                        }
                    }
                }
            }
            /**
             * 情况B：退出循环时任务已经完成了，做两件事：
             * 1、调用signalWaiters帮助继续唤醒或清理其它等待者。
             * 2、如果之前记录过中断，就恢复中断标志。
             */
            else {
                signalWaiters();             // help clean or signal
                if (interrupted)
                    Thread.currentThread().interrupt();
            }
        }
        return s;
    }

    static final void cancelIgnoringExceptions(Future<?> t) {
        if(t != null) {
            try {
                t.cancel(true);
            } catch (Throwable ignore) {

            }
        }
    }

    /**
     * 负责处理：异常对象怎么重新交给另外一个线程。
     * 核心作用：从aux异常头节点里取出任务的异常对象，如果当前取异常的线程不是原来抛异常的线程，
     * 则尽量构造一个同类型的新异常对象，来包装原来的异常（放到新异常的cause里面）。
     */
    private Throwable getThrowableException() {
        Throwable ex; Aux a;
        if((a = aux) == null)   // 没有aux就没有异常对象
            ex = null;
        /**
         * 是异常头，并且当前线程，并不是当初抛异常的线程，进入里面的：异常重建逻辑。
         */
        else if((ex = a.ex) != null && a.thread != Thread.currentThread()) {
            try {
                /**
                 * 在对应异常类的public构造方法里，优先找：
                 * 1、一个参数为Throwable的构造方法。
                 * 2、如果没有，再找无参数构造方法。
                 */
                Constructor<?> noArgCtor = null;
                Constructor<?> oneArgCtor = null;
                for(Constructor<?> c : ex.getClass().getConstructors()) {
                    Class<?>[] ps = c.getParameterTypes();
                    if(ps.length == 0)
                        noArgCtor = c;
                    else if(ps.length == 1 && ps[0] == Throwable.class) {
                        oneArgCtor = c;
                        break;
                    }
                }
                if(oneArgCtor != null)
                    ex = (Throwable)oneArgCtor.newInstance(ex);
                else if(noArgCtor != null) {
                    Throwable rx = (Throwable)noArgCtor.newInstance();
                    rx.initCause(ex);
                    ex = rx;
                }
            } catch (Exception ignore) {
                // 如果反射失败，则进入这里什么也不做，最终会返回原来的ex
            }
        }
        return ex;
    }

    /**
     * 把状态翻译成异常对象，注意这个只是生成异常对象，并不会throw
     */
    private Throwable getException(int s) {
        Throwable ex = null;
        /**
         * 如果正常完成则直接返回null。
         * 如果异常完成（只看ABNORMAL），则再区别（看THROWN，如果为0，说明是异常，但不是抛出异常导致的完成，通常是取消和中断）：
         * - 真的抛异常完成。
         * - 取消/其他非正常完成（只能退化成CancellationException异常）。
         */
        if((s & ABNORMAL) != 0 && ((s & THROWN) == 0 || (ex = getThrowableException()) == null))
            ex = new CancellationException();
        return ex;
    }

    /**
     * 根据状态，把对应的异常或取消情况给抛出去。
     * 这个方法针对ForkJoin的原生风格：join() / invoke()
     * 1、更直接
     * 2、更少包装
     * 3、倾向于抛原始运行时异常或Error
     * 4、不走ExecutionException这种Future风格包装
     */
    private void reportException(int s) {
        /**
         * 如果状态有THROWN，就取出真实异常，然后调用uncheckedThrow方法，否则传入null。
         */
        ForkJoinTask.uncheckedThrow((s & THROWN) != 0 ? getThrowableException() : null);
    }

    /**
     * 根据状态，把对应的异常或取消情况给抛出去
     * 这个方法针对Future的接口语义：get() / get(timeout, unit)
     * 1、可能抛InterruptedException
     * 2、可能抛TimeoutException
     * 3、可能把任务异常包装成ExecutionException
     */
    private void reportExecutionException(int s) {
        Throwable ex = null;
        if(s == ABNORMAL)
            ex = new InterruptedException();
        else if(s >= 0)
            ex = new TimeoutException();
        else if((s & THROWN) != 0 && (ex = getThrowableException()) != null)
            ex = new ExecutionException(ex);
        ForkJoinTask.uncheckedThrow(ex);
    }

    /**
     * 底层通用抛异常工具，对比uncheckedThrow，是更简单的静态包装。
     */
    static void rethrow(Throwable ex) {
        ForkJoinTask.uncheckedThrow(ex);
    }

    /**
     * 底层通用抛异常工具。
     * 没有真实异常对象，则抛出CancellationException。
     */
    static <T extends Throwable> void uncheckedThrow(Throwable t) throws T {
        if(t == null)
            t = new CancellationException();
        /**
         * join、invoke之类的方法本身并没有声明throws checked exception，但实际可能运行时会抛出，
         * 所以通过以下的泛型技巧，在运行时把异常原样重新抛出。
         * 虽然编译器以为这里抛的是某个泛型T extends Throwable，但运行时其实直接把真实的Throwable抛出去了。
         * 因此这个方法是：Task类用来裸抛任意异常的底层工具，借助泛型擦除来绕过Java对受查异常的编译器检查。
         */
        throw (T)t;
    }

    // public methods

    /**
     * 把当前这个task放进FJP的任务队列里，交给池里的调度机制去处理（并不是启动线程或立刻执行任务）
     */
    public final ForkJoinTask<V> fork() {
        Thread t;
        ForkJoinWorkerThread w;
        /**
         * fork的行为，取决于是谁（线程）在调用它。
         * 判断当前线程是不是ForkJoinWorker，因为FJP里有两类调用者：
         * 1、池内线程：也就是worker自己在执行任务的过程中，又fork了新的子任务，这是最典型最核心的场景。
         * 2、池外线程：比如主线程或者普通业务线程，直接调用了某个Task的fork，
         * 这时它不是worker，也就没有自己的工作队列，所以要走外部提交通道。
         */
        if((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
            /**
             * 取出worker自己的工作队列，把当前task压入当前worker的本地队列中（入队，不是执行）。
             */
            (w = (ForkJoinWorkerThread)t).workQueue.push(this, w.pool);
        else
            /**
             * 如果不是worker线程调用的，那就把任务提交到commonPool的外部提交通道里（公共池）。
             */
            ForkJoinPool.common.externalPush(this);
        return this;
    }

    /**
     * 等待Task计算完成，如果异常结束就把异常重新跑出来，如果正常完成就返回结果。
     */
    public final V join() {
        int s;
        /**
         * status >= 0: 代表还没结束，或者还没进入最终完成标记。
         * status < 0: 已经完成，只不过完成类型可能不同（正常|异常|取消）
         * task还没完成就进入等待流程，直到任务完成，并返回完成后的最终状态。
         */
        if((s = status) >= 0)
            s = awaitDone(null, false, false, false, 0L);
        /**
         * 任务完成后，如果状态带有ABNORMAL（被取消|执行时抛异常）标记，说明不是正常结束，要按规则把异常重新跑出来。
         */
        if((s & ABNORMAL) != 0)
            reportException(s);
        return getRawResult();
    }

    /**
     * 当前线程直接尝试执行这个任务，如果执行后任务还没完成就继续等待完成，如果完成后是异常则抛异常，否则返回结果。
     * 和join的区别是join是等别人做完，invoke是自己做，做不完再等。
     */
    public final V invoke() {
        int s;
        /**
         * 一上来在当前线程开始执行，但执行完，任务不一定完成，即执行一次，并不等于任务已经结果（如CountedCompleter任务）
         */
        if((s = doExec()) >= 0)
            /**
             * 参数pool是null：不显式指定pool，让awaitDone内部自己根据当前线程推断。
             * 参数ran是true：表示当前这条调用路径已经执行过一次doExec()了（awaitDone里面有判断，就不会再执行doExec了）。
             */
            s = awaitDone(null, true, false, false, 0L);
        if((s & ABNORMAL) != 0)
            reportException(s);
        return getRawResult();
    }

    public static void invokeAll(ForkJoinTask<?> t1, ForkJoinTask<?> t2) {
        int s1, s2;
        if(t1 == null || t2 == null)
            throw new NullPointerException();
        t2.fork();
        if((s1 = t1.doExec()) >= 0)
            s1 = t1.awaitDone(null, true, false, false, 0L);
        if((s1 & ABNORMAL) != 0) {
            cancelIgnoringExceptions(t2);
            t1.reportException(s1);
        }else if(((s2 = t2.awaitDone(null, false, false, false, 0L)) & ABNORMAL) != 0)
            t2.reportException(s2);
    }

    public static void invokeAll(java.util.concurrent.ForkJoinTask<?>... tasks) {
        Throwable ex = null;
        int last = tasks.length - 1;
        for(int i = last; i >= 0; --i) {
            ForkJoinTask<?> t;
            if((t = tasks[i]) == null) {
                ex = new NullPointerException();
                break;
            }
            if(i == 0) {
                int s;
                if((s = t.doExec()) >= 0)
                    s = t.awaitDone(null, true, false, false, 0L);
                if((s & ABNORMAL) != 0)
                    ex = t.getException(s);
                break;
            }
            t.fork();
        }
        if(ex == null) {
            for(int i = 1; i <= last; ++i) {
                ForkJoinTask<?> t;
                if((t = tasks[i]) != null) {
                    int s;
                    if((s = t.status) >= 0)
                        s = t.awaitDone(null, false, false, false, 0L);
                    if((s & ABNORMAL) != 0 && (ex = t.getException(s)) != null)
                        break;
                }
            }
        }
        if(ex != null) {
            for(int i = 1; i <= last; ++i)
                cancelIgnoringExceptions(tasks[i]);
            rethrow(ex);
        }
    }

    public static <T extends java.util.concurrent.ForkJoinTask<?>> Collection<T> invokeAll(Collection<T> tasks) {
        if(!(tasks instanceof RandomAccess) || !(tasks instanceof List<?>)) {
            invokeAll(tasks.toArray(new java.util.concurrent.ForkJoinTask<?>[0]));
            return tasks;
        }
        @SuppressWarnings("unchecked")
        List<? extends java.util.concurrent.ForkJoinTask<?>> ts =
                (List<? extends java.util.concurrent.ForkJoinTask<?>>) tasks;
        Throwable ex = null;
        int last = ts.size() - 1;  // nearly same as array version
        for(int i = last; i >= 0; --i) {
            ForkJoinTask<?> t;
            if((t = ts.get(i)) == null) {
                ex = new NullPointerException();
                break;
            }
            if(i == 0) {
                int s;
                if((s = t.doExec()) >= 0)
                    s = t.awaitDone(null, true, false, false, 0L);
                if((s & ABNORMAL) != 0)
                    ex = t.getException(s);
                break;
            }
            t.fork();
        }
        if(ex == null) {
            for(int i = 1; i <= last; ++i) {
                ForkJoinTask<?> t;
                if((t = ts.get(i)) != null) {
                    int s;
                    if((s = t.status) >= 0)
                        s = t.awaitDone(null, false, false, false, 0L);
                    if((s & ABNORMAL) != 0 && (ex = t.getException(s)) != null)
                        break;
                }
            }
        }
        if(ex != null) {
            for(int i = 1; i <= last; ++i)
                cancelIgnoringExceptions(ts.get(i));
            rethrow(ex);
        }
        return tasks;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        return (trySetCancelled() & (ABNORMAL | THROWN)) == ABNORMAL;
    }

    public final boolean isDone() {
        return status < 0;
    }

    public final boolean isCancelled() {
        return (status & (ABNORMAL | THROWN)) == ABNORMAL;
    }

    public final boolean isCompletedAbnormally() {
        return (status & ABNORMAL) != 0;
    }

    public final boolean isCompletedNormally() {
        return (status & (DONE | ABNORMAL)) == DONE;
    }

    public final Throwable getException() {
        return getException(status);
    }

    public void completeExceptionally(Throwable ex) {
        trySetException((ex instanceof RuntimeException) || (ex instanceof Error)
                ? ex : new RuntimeException(ex));
    }

    public void complete(V value) {
        try {
            setRawResult(value);
        } catch (Throwable rex) {
            trySetException(rex);
            return;
        }
        setDone();
    }

    public final void quietlyComplete() {
        setDone();
    }

    public final V get() throws InterruptedException, ExecutionException {
        int s = awaitDone(null, false, true, false, 0L);
        if((s & ABNORMAL) != 0)
            reportExecutionException(s);
        return getRawResult();
    }

    public final V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        int s = awaitDone(null, false, true, true, nanos);
        if(s >= 0 || (s & ABNORMAL) != 0)
            reportExecutionException(s);
        return getRawResult();
    }

    public final void quietlyJoin() {
        if(status >= 0)
            awaitDone(null, false, false, false, 0L);
    }

    public final void quietlyInvoke() {
        if(doExec() >= 0)
            awaitDone(null, true, false, false, 0L);
    }

    // Versions of join/get for pool.invoke* methods that use external,
    // possibly-non-commonPool submits

    final void awaitPoolInvoke(ForkJoinPool pool) {
        awaitDone(pool, false, false, false, 0L);
    }
    final void awaitPoolInvoke(ForkJoinPool pool, long nanos) {
        awaitDone(pool, false, true, true, nanos);
    }
    final V joinForPoolInvoke(ForkJoinPool pool) {
        int s = awaitDone(pool, false, false, false, 0L);
        if ((s & ABNORMAL) != 0)
            reportException(s);
        return getRawResult();
    }
    final V getForPoolInvoke(ForkJoinPool pool)
            throws InterruptedException, ExecutionException {
        int s = awaitDone(pool, false, true, false, 0L);
        if ((s & ABNORMAL) != 0)
            reportExecutionException(s);
        return getRawResult();
    }
    final V getForPoolInvoke(ForkJoinPool pool, long nanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        int s = awaitDone(pool, false, true, true, nanos);
        if (s >= 0 || (s & ABNORMAL) != 0)
            reportExecutionException(s);
        return getRawResult();
    }

    public static void helpQuiesce() {
        Thread t;
        ForkJoinWorkerThread w;
        ForkJoinPool p;
        // 如果当前线程是worker并且有自己的pool，则调用pool的helpQuiescePool方法来处理，否则调用common的externalHelpQuiescePool来处理
        if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread && (p = (w = (ForkJoinWorkerThread)t).pool) != null)
            p.helpQuiescePool(w.workQueue, Long.MAX_VALUE, false);
        else
            ForkJoinPool.common.externalHelpQuiescePool(Long.MAX_VALUE, false);
    }

    public void reinitialize() {
        aux = null;
        status = 0;
    }

    public static ForkJoinPool getPool() {
        Thread t;
        return (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
                ? ((ForkJoinWorkerThread) t).pool
                : null);
    }

    public static boolean inForkJoinPool() {
        return Thread.currentThread() instanceof ForkJoinWorkerThread;
    }

    public boolean tryUnfork() {
        Thread t;
        ForkJoinPool.WorkQueue q;
        return ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
                ? (q = ((ForkJoinWorkerThread)t).workQueue) != null && q.tryUnpush(this)
                : (q = ForkJoinPool.commonQueue()) != null && q.externalTryUnpush(this);
    }

    public static int getQueuedTaskCount() {
        Thread t; ForkJoinPool.WorkQueue q;
        if((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
            q = ((ForkJoinWorkerThread)t).workQueue;
        else
            q = ForkJoinPool.commonQueue();
        return (q == null) ? 0 : q.queueSize();
    }

    public static int getSurplusQueuedTaskCount() {
        return ForkJoinPool.getSurplusQueuedTaskCount();
    }

    // Extension methods

    public abstract V getRawResult();

    protected abstract void setRawResult(V value);

    protected abstract boolean exec();

    protected static ForkJoinTask<?> peekNextLocalTask() {
        Thread t;
        ForkJoinPool.WorkQueue q;
        if((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
            q = ((ForkJoinWorkerThread)t).workQueue;
        else
            q = ForkJoinPool.commonQueue();
        return (q == null) ? null : q.peek();
    }

    protected static ForkJoinTask<?> pollNextLocalTask() {
        Thread t;
        return (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
                ? ((ForkJoinWorkerThread)t).workQueue.nextLocalTask()
                : null);
    }

    protected static ForkJoinTask<?> pollTask() {
        Thread t;
        ForkJoinWorkerThread w;
        return (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
                ? (w = (ForkJoinWorkerThread)t).pool.nextTaskFor(w.workQueue)
                : null);
    }

    protected static ForkJoinTask<?> pollSubmission() {
        Thread t;
        return (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
                ? ((ForkJoinWorkerThread)t).pool.pollSubmission()
                : null);
    }

    // tag operations

    public final short getForkJoinTaskTag() {
        return (short)status;
    }

    public final short setForkJoinTaskTag(short newValue) {
        for(int s;;) {
            if(casStatus(s = status, (s & ~SMASK) | (newValue & SMASK)))
                return (short)s;
        }
    }

    public final boolean compareAndSetForkJoinTaskTag(short expect, short update) {
        for(int s;;) {
            if((short)(s = status) != expect)
                return false;
            if(casStatus(s, (s & ~SMASK) | (update & SMASK)))
                return true;
        }
    }

    /**
     * Runnable（带固定的返回结果） -> ForkJoinTask
     */
    static final class AdaptedRunnable<T> extends ForkJoinTask<T> implements RunnableFuture<T> {
        private static final long serialVersionUID = 5232453952276885070L;

        final Runnable runnable;
        T result;

        AdaptedRunnable(Runnable runnable, T result) {
            if(runnable == null)
                throw new NullPointerException();
            this.runnable = runnable;
            this.result = result;
        }

        @Override
        public final T getRawResult() {
            return result;
        }

        @Override
        public final void setRawResult(T v) {
            result = v;
        }

        @Override
        public final boolean exec() {
            runnable.run();
            return true;
        }

        @Override
        public final void run() {
            invoke();
        }

        @Override
        public String toString() {
            return super.toString() + "[Wrapped task = " + runnable + "]";
        }
    }

    /**
     * Runnable（不带固定的返回结果） -> ForkJoinTask
     */
    static final class AdaptedRunnableAction extends ForkJoinTask<Void> implements RunnableFuture<Void> {
        private static final long serialVersionUID = 5232453952276885070L;

        final Runnable runnable;

        AdaptedRunnableAction(Runnable runnable) {
            if(runnable == null)
                throw new NullPointerException();
            this.runnable = runnable;
        }

        @Override
        public final Void getRawResult() {
            return null;
        }

        @Override
        public final void setRawResult(Void v) {}

        @Override
        public final boolean exec() {
            runnable.run();
            return true;
        }

        @Override
        public final void run() {
            invoke();
        }

        @Override
        public String toString() {
            return super.toString() + "[Wrapped task = " + runnable + "]";
        }
    }

    static final class RunnableExecuteAction extends ForkJoinTask<Void> {
        private static final long serialVersionUID = 5232453952276885070L;

        final Runnable runnable;

        RunnableExecuteAction(Runnable runnable) {
            if (runnable == null)
                throw new NullPointerException();
            this.runnable = runnable;
        }

        @Override
        public final Void getRawResult() {
            return null;
        }

        @Override
        public final void setRawResult(Void v) {
        }

        @Override
        public final boolean exec() {
            runnable.run();
            return true;
        }

        @Override
        int trySetException(Throwable ex) {
            int s;
            Thread t;
            java.lang.Thread.UncaughtExceptionHandler h;
            if (isExceptionalStatus(s = trySetThrown(ex))
                    && (h = ((t = Thread.currentThread()).getUncaughtExceptionHandler())) != null) {
                try {
                    h.uncaughtException(t, ex);
                } catch (Throwable ignore) {

                }
            }
            return s;
        }
    }

    /**
     * Callable -> ForkJoinTask
     */
    static final class AdaptedCallable<T> extends ForkJoinTask<T> implements RunnableFuture<T> {
        private static final long serialVersionUID = 2838392045355241008L;

        final Callable<? extends T> callable;
        T result;

        AdaptedCallable(Callable<? extends T> callable) {
            if(callable == null)
                throw new NullPointerException();
            this.callable = callable;
        }

        @Override
        public final T getRawResult() {
            return result;
        }

        @Override
        public final void setRawResult(T v) {
            result = v;
        }

        @Override
        public final boolean exec() {
            try {
                result = callable.call();
                return true;
            } catch (RuntimeException rex) {
                throw rex;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public final void run() {
            invoke();
        }

        @Override
        public String toString() {
            return super.toString() + "[Wrapped task = " + callable + "]";
        }
    }

    static final class AdaptedInterruptibleCallable<T> extends ForkJoinTask<T> implements RunnableFuture<T> {
        private static final long serialVersionUID = 2838392045355241008L;

        final Callable<? extends T> callable;
        transient volatile Thread runner;
        T result;

        AdaptedInterruptibleCallable(Callable<? extends T> callable) {
            if(callable == null)
                throw new NullPointerException();
            this.callable = callable;
        }

        @Override
        public final T getRawResult() {
            return result;
        }

        @Override
        public final void setRawResult(T v) {
            result = v;
        }

        @Override
        public final boolean exec() {
            Thread.interrupted();
            runner = Thread.currentThread();
            try {
                if(!isDone()) // recheck
                    result = callable.call();
                return true;
            } catch (RuntimeException rex) {
                throw rex;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                runner = null;
                Thread.interrupted();
            }
        }

        @Override
        public final void run() {
            invoke();
        }

        @Override
        public final boolean cancel(boolean mayInterruptIfRunning) {
            Thread t;
            boolean stat = super.cancel(false);
            if(mayInterruptIfRunning && (t = runner) != null) {
                try {
                    t.interrupt();
                } catch (Throwable ignore) {

                }
            }
            return stat;
        }

        @Override
        public String toString() {
            return super.toString() + "[Wrapped task = " + callable + "]";
        }
    }

    public static ForkJoinTask<?> adapt(Runnable runnable) {
        return new AdaptedRunnableAction(runnable);
    }

    public static <T> ForkJoinTask<T> adapt(Runnable runnable, T result) {
        return new AdaptedRunnable<>(runnable, result);
    }

    public static <T> ForkJoinTask<T> adapt(Callable<? extends T> callable) {
        return new AdaptedCallable<T>(callable);
    }

    private static <T> ForkJoinTask<T> adaptInterruptible(Callable<? extends T> callable) {
        return new AdaptedInterruptibleCallable<T>(callable);
    }

    // Serialization support

    private static final long serialVersionUID = -7721805057305804111L;

    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        Aux a;
        s.defaultWriteObject();
        s.writeObject((a = aux) == null ? null : a.ex);
    }

    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        Object ex = s.readObject();
        if(ex != null)
            trySetThrown((Throwable)ex);
    }

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATUS = l.findVarHandle(ForkJoinTask.class, "status", int.class);
            AUX = l.findVarHandle(ForkJoinTask.class, "aux", Aux.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

package com.github.liyibo1110.jdk.java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * 可取消的异步计算。
 * 提供了Future的基础实现，包括了启动和取消计算、查询计算是否完成以及获取计算结果的方法。
 * 结果仅能在计算完成后获取，如果计算尚未完成，get将被阻塞。
 * 计算完成后，计算无法重启或取消（除非使用runAndReset调用计算）
 *
 * FutureTask可用于封装Callable或Runnable对象。
 * 由于FutureTask也实现了Runnable，因此可将其提交给Executor执行。
 *
 * 除作为独立类使用外，本类还提供了protected方法，在创建自定义task类时可能有所帮助。
 * @author liyibo
 * @date 2026-02-20 00:18
 */
public class FutureTask<V> implements RunnableFuture<V> {

    /**
     * 该task运行状态初始为NEW。
     * 状态仅在set、setException和cancel方法中转换为最终态。
     * 在完成过程中，状态可能呈现COMPLETING（结果设置期间）或INTERRUPTING（仅在中断运行器以响应cancel(true)时）的过渡值。
     * 从这些中间状态向最终状态的转换，采用更经济的有序/延迟写入机制，因其值具有唯一性且不可再修改。
     * 可能的状态转换路径：
     * NEW -> COMPLETING -> NORMAL NEW -> COMPLETING -> EXCEPTIONAL NEW -> CANCELLED NEW -> INTERRUPTING -> INTERRUPTED
     */
    private volatile int state;
    private static final int NEW          = 0;

    /**
     * 在set和setException方法里，会先CAS NEW -> COMPLETING，然后再写outcome，最后写NORMAL/EXCEPTIONAL。
     * 所以COMPLETING代表：
     * 1、结果正在写入outcome。
     * 2、isDone可能已经对外承诺“快完成了”。
     * 3、但outcome还未稳定到终态。
     */
    private static final int COMPLETING   = 1;
    private static final int NORMAL       = 2;
    private static final int EXCEPTIONAL  = 3;
    private static final int CANCELLED    = 4;
    private static final int INTERRUPTING = 5;
    private static final int INTERRUPTED  = 6;

    /**
     * 底层的Callable，运行后清空。
     */
    private java.util.concurrent.Callable<V> callable;

    /**
     * get方法返回的结果，或抛出的异常。
     */
    private Object outcome; // non-volatile, protected by state reads/writes

    /**
     * 运行callable的线程，在run()期间进行CAS操作。
     */
    private volatile Thread runner;

    /**
     * Treiber栈中等待的线程集合
     */
    private volatile WaitNode waiters;

    /**
     * 返回结果或抛出异常，表示任务已完成。
     * @param s state值
     */
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        if(s == NORMAL)
            return (V)x;
        if(s >= CANCELLED)  // CANCELLED/INTERRUPTING/INTERRUPTED
            throw new CancellationException();
        throw new ExecutionException((Throwable)x);
    }

    public FutureTask(Callable<V> callable) {
        if(callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;
    }

    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;
    }

    @Override
    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    @Override
    public boolean isDone() {
        return state != NEW;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        // 尝试切换状态：NEW -> INTERRUPTING / CANCELLED
        if(!(state == NEW && STATE.compareAndSet(this, NEW, mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        // 切换成功了
        try {
            if(mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if(t != null)
                        t.interrupt();  // 在这里设置了thread的中断标志位
                } finally {
                    STATE.setRelease(this, INTERRUPTED);    // 最终修改状态为INTERRUPTED
                }
            }
        } finally {
            this.finishCompletion();
        }
        return true;
    }

    /**
     * 获取结果，COMPLETING状态则阻塞等待
     */
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        if(s <= COMPLETING)
            s = awaitDone(false, 0L);
        return this.report(s);
    }

    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if(unit == null)
            throw new NullPointerException();
        int s = state;
        if(s <= COMPLETING && (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }

    /**
     * 当任务进入isDone状态时（无论是正常进入还是通过cancel进入）调用的protected方法，默认实现为空。
     * 子类可以重写以调用完成回调或执行记录工作。
     * 注意，你可以在该方法实现中查询状态以确定任务是否已被取消。
     */
    protected void done() {}

    /**
     * 直接设置结果，除非Future已被set或者cancel。
     * 当计算成功完成时，此方法由run在内部调用。
     */
    protected void set(V v) {
        if(STATE.compareAndSet(this, NEW, COMPLETING)) {    // 保证之前的状态是NEW
            outcome = v;
            STATE.setRelease(this, NORMAL); // 切换至最终状态
            this.finishCompletion();
        }
    }

    protected void setException(Throwable t) {
        if(STATE.compareAndSet(this, NEW, COMPLETING)) {
            outcome = t;
            STATE.setRelease(this, EXCEPTIONAL); // 切换至最终状态
            this.finishCompletion();
        }
    }

    @Override
    public void run() {
        // 状态不是NEW，直接返回
        if(state != NEW || !RUNNER.compareAndSet(this, null, Thread.currentThread()))
            return;
        try {
            Callable<V> c = callable;
            if(c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    result = c.call();
                    ran = true;
                } catch (Throwable t) {
                    result = null;
                    ran = false;
                    this.setException(t);
                }
                if(ran)
                    this.set(result);
            }
        } finally {
            runner = null;
            int s = state;
            if(s >= INTERRUPTING)
                this.handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * 执行计算但不设置结果，随后将Future对象重置为初始状态。
     * 若计算过程中发生异常或被取消，则重置失败。
     * 此机制专为本质上需多次执行的任务而设计。
     */
    protected boolean runAndReset() {
        if(state != NEW || !RUNNER.compareAndSet(this, null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if(c != null && s == NEW) {
                try {
                    c.call();   // 只调用，不获取结果
                    ran = true;
                } catch (Throwable t) {
                    this.setException(t);
                }
            }
        } finally {
            runner = null;
            s = state;
            if(s >= INTERRUPTING)
                this.handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * 确保任何来自可能的cancel(true)的中断，仅在任务处理运行或运行并重置状态时传递。
     */
    private void handlePossibleCancellationInterrupt(int s) {
        if(s == INTERRUPTING)
            while(state == INTERRUPTING)
                Thread.yield();
    }

    /**
     * 用于记录Treiber栈（一种用CAS修改头指针实现push/pop的无锁栈）中，等待线程的简单链表节点（其实更像一个栈）。
     * 更多详细说明请参见Phaser和SynchronousQueue等其他类。
     *
     * 这个类比较关键，因为尽管运行的Runnable只有一次，但是调用get的线程可能不止一个（就算只有一个，也是一样），即会有多个线程等待Runnable的执行完毕信号，
     * 因此当它们调用get的时候，都会在这个WaitNode里面注册等待被通知
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;

        WaitNode() {
            thread = Thread.currentThread();
        }
    }

    /**
     * 移除并通知所有等待的线程，调用done方法，并将callable置为空值
     */
    private void finishCompletion() {
        for(WaitNode q; (q = waiters) != null; ) {
            if(WAITERS.weakCompareAndSet(this, q, null)) {  // 把waiters设置成null，如果新线程再get则不会注册到waiters了
                for(; ;) {
                    Thread t = q.thread;
                    if(t != null) {
                        q.thread = null;
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    if(next == null)    // 都处理完了
                        break;
                    q.next = null;
                    q = next;
                }
                break;
            }
        }

        this.done();    // 调用done钩子，子类可以有自定义实现
        callable = null;
    }

    /**
     * 等待完成，或在中断或超时后中止。
     * 以下代码设计精妙，旨在实现以下目标（官方的翻译）：
     * - 每次调用park时，仅调用一次nanoTime。
     * - 若nanos <= 0L，即立即返回且不进行内存分配或调用nanoTime。
     * - 若nanos == Long.MIN_VALUE，则避免下溢。
     * - 若nanos == Long.MAX_VALUE且nanoTime存在非单调性且遭遇虚假唤醒，其效果不逊于短暂执行park-spin循环。
     * 设计意图：
     * 1、尽量不分配对象：如果任务很快完成，q可能根本不需要创建。
     * 2、尽量少调用nanoTime：nanoTime可能比较慢，所以只在需要timed park时调用一次/每次park前后再精算。
     * 3、入栈只做一次：queued防止重复入栈。
     */
    private int awaitDone(boolean timed, long nanos) throws InterruptedException {
        long startTime = 0L; // 0表示还没有park过
        WaitNode q = null;  // 当前线程对应的等待节点（懒创建）
        boolean queued = false; // 当前节点是否已经成功入栈waiters
        for(; ;) {
            int s = state;
            if(s > COMPLETING) {    // 任务已完成，直接返回
                if(q != null)
                    q.thread = null;
                return s;
            }else if(s == COMPLETING) { // 见COMPLETING上面的注释
                Thread.yield(); // 没有选择park，因为太重了，这个状态转换的会非常快，所以选择直接让出CPU。
            }else if(Thread.interrupted()) {    // 被标记了中断
                this.removeWaiter(q);   // 队列中尝试移除自己
                throw new InterruptedException();
            }else if(q == null) {   // 任务正常运行中，需要等
                if(timed && nanos <= 0L)    // 要求延迟但是等待时间传的0
                    return s;
                q = new WaitNode();
            }else if(!queued) { // 经过上面创建WaitNode的流程了，但是还没有入栈
                queued = WAITERS.weakCompareAndSet(this, q.next = waiters, q);  // 把自己压到栈顶
            }else if(timed) {
                final long parkNanos;
                if(startTime == 0L) {   // 首次park
                    startTime = System.nanoTime();
                    if(startTime == 0L) // 万一nanoTime返回0（极少），用1作为“已初始化标志”
                        startTime = 1L;
                    parkNanos = nanos;
                }else { // 已经park中了
                    long elapsed = System.nanoTime() - startTime;
                    if(elapsed >= nanos) {  // 延迟到期了
                        this.removeWaiter(q);
                        return state;
                    }
                    // 还没到期，计算新的park时间
                    parkNanos = nanos - elapsed;
                }
                if(state < COMPLETING)
                    LockSupport.parkNanos(this, parkNanos); // 再次park
            }else {
                LockSupport.park(this);
            }
        }
    }

    /**
     * 尝试（注意是尝试）解除超时或中断的等待节点的关联，以避免垃圾累计。
     * 内部节点直接解除连接而不使用CAS，因为即使被释放器遍历也无妨。
     * 为避免已移除节点解除连接的影响，在存在明显竞争时会重新遍历列表。
     * 节点数量庞大时此操作较慢，但我们预计列表长度不会大到足以抵消高开销方案的优势。
     */
    private void removeWaiter(WaitNode node) {
        if(node != null) {
            node.thread = null;    // 把自己先标记成无效节点，注意并不是摘链，finishCompletion遍历时，thread == null则不会再unpark
            retry:
            for(; ;) {
                /**
                 * pred：上一个有效节点
                 * q：当前节点
                 * s：q.next（先保存，便于跳过）
                 */
                for(WaitNode pred = null, q = waiters, s; q != null ; q = s) {
                    s = q.next;
                    if(q.thread != null)    // 说明q是有效节点，不能删
                        pred = q;
                    else if(pred != null) { // q是无效节点，并且pred != null，要移除q
                        pred.next = s;
                        if(pred.thread == null) // pred被并发修改了，为了安全要重来
                            continue retry;
                    }else if(!WAITERS.compareAndSet(this, q, s))  // 最后一个分支说明q是无效节点，并且q本身就是头部
                        continue retry;
                }
                break;
            }
        }
    }

    private static final VarHandle STATE;
    private static final VarHandle RUNNER;
    private static final VarHandle WAITERS;

    @Override
    public String toString() {
        final String status;
        switch(state) {
            case NORMAL:
                status = "[Completed normally]";
                break;
            case EXCEPTIONAL:
                status = "[Completed exceptionally: " + outcome + "]";
                break;
            case CANCELLED:
            case INTERRUPTING:
            case INTERRUPTED:
                status = "[Cancelled]";
                break;
            default:
                final Callable<?> callable = this.callable;
                status = (callable == null)
                        ? "[Not completed]"
                        : "[Not completed, task = " + callable + "]";
        }
        return super.toString() + status;
    }

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(FutureTask.class, "state", int.class);
            RUNNER = l.findVarHandle(FutureTask.class, "runner", Thread.class);
            WAITERS = l.findVarHandle(FutureTask.class, "waiters", WaitNode.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }

        Class<?> ensureLoaded = LockSupport.class;
    }
}

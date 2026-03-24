package com.github.liyibo1110.jdk.java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * 是一种基于完成传播的ForkJoinTask，任务是否完成，不依靠显式join，而靠pending计数和completer链向上触发。
 * 也就是说CountedCompleter的完成时机，不由compute()返回决定，而由pending归零和completer传播决定。
 * 和RecursiveAction/RecursiveTask不同，特点是：
 * 不依赖join等子任务，而是靠pending计数器归零，触发完成动作，并沿着completer链一路向上传播完成。
 *
 * 普通的RecursiveAction/RecursiveTask合适的模型：
 * 1、父任务fork出子任务。
 * 2、父任务最后join子任务。
 * 3、父任务自己知道什么时候该合并。
 * 有一类问题，上面的模型处理不够好：
 * 1、子任务耗时差异很大：有的任务快，有的很慢，如果父任务必须显式join，很容易会等待。
 * 2、完成后要触发的是回调式完成：即谁最后完成，谁触发父任务来结束，而不是父任务主动等。
 * 3、有时根本不需要显式join，例如：
 * - forEach
 * - 搜索命中后触发root完成
 * - 多个异步任务凑齐后触发下游任务
 * - map-reduce中由最后一个完成的子任务触发聚合
 * 这种模式，用CountedCompleter更自然，因为它的思想不是我等你，而是：你们做完后，自动把完成信号往上推。
 * @author liyibo
 * @date 2026-03-23 13:40
 */
public abstract class CountedCompleter<T> extends ForkJoinTask<T> {
    private static final long serialVersionUID = 5232453752276485070L;

    /**
     * 当前任务完成后，应该把完成这个信息传播给completer，因此这是个父节点。
     */
    final CountedCompleter<?> completer;

    /**
     * 在当前任务允许触发completion之前，还剩多少个待完成的动作。
     * 注意不是说：有多少个子任务对象，而是：有多少完成信号还没有回来。
     */
    volatile int pending;

    protected CountedCompleter(CountedCompleter<?> completer, int initialPendingCount) {
        this.completer = completer;
        this.pending = initialPendingCount;
    }

    protected CountedCompleter(CountedCompleter<?> completer) {
        this.completer = completer;
    }

    protected CountedCompleter() {
        this.completer = null;
    }

    public abstract void compute();

    public void onCompletion(CountedCompleter<?> caller) {}

    public boolean onExceptionalCompletion(Throwable ex, CountedCompleter<?> caller) {
        return true;
    }

    public final CountedCompleter<?> getCompleter() {
        return completer;
    }

    public final int getPendingCount() {
        return pending;
    }

    public final void setPendingCount(int count) {
        pending = count;
    }

    public final void addToPendingCount(int delta) {
        PENDING.getAndAdd(this, delta);
    }

    public final boolean compareAndSetPendingCount(int expected, int count) {
        return PENDING.compareAndSet(this, expected, count);
    }

    final boolean weakCompareAndSetPendingCount(int expected, int count) {
        return PENDING.weakCompareAndSet(this, expected, count);
    }

    public final int decrementPendingCountUnlessZero() {
        int c;
        do {

        }while ((c = pending) != 0 && !weakCompareAndSetPendingCount(c, c - 1));
        return c;
    }

    public final CountedCompleter<?> getRoot() {
        CountedCompleter<?> a = this;
        CountedCompleter<?> p;
        while((p = a.completer) != null)
            a = p;
        return a;
    }

    /**
     * 核心方法：如果当前节点pending不为0则先减1，如果是0就触发当前节点的onCompletion，然后继续尝试完成它的completer。
     * 这是完成信号向上传播的核心
     */
    public final void tryComplete() {
        CountedCompleter<?> a = this;   // 当前正在处理的节点
        CountedCompleter<?> s = a;  // 重要：触发当前完成动作的调用者节点
        for(int c; ;) {
            /**
             * 当前节点a已经没有待完成的动作了，所以它可以触发自己得完成逻辑了（注意是进来发现是0才开始完成传播，而不是先减再判断0）
             */
            if((c = a.pending) == 0) {
                a.onCompletion(s);  // 调用当前节点的completion回调
                /**
                 * 让s记住刚才那个完成的节点，然后把a移动到它的completer上，继续往上处理（注意外面的for循环，会重新循环），
                 * 为null说明已经是null了，调用quietlyComplete把根节点真正标记为ForkJoinTask完成。
                 */
                if((a = (s = a).completer) == null) {
                    s.quietlyComplete();
                    return;
                }
            /**
             * pending不是0，把pending减1就结束了
             */
            }else if (a.weakCompareAndSetPendingCount(c, c - 1))
                return;
        }
    }

    /**
     * 和tryComplete方法流程很像，但是内部没有调用onCompletion这一步，因此：
     * 这个方法只做计数传播，不触发回调。
     * 适用于forEach类任务，没有结果合并逻辑，只要知道了都完成了就行了。
     */
    public final void propagateCompletion() {
        CountedCompleter<?> a = this;
        CountedCompleter<?> s;
        for(int c;;) {
            if((c = a.pending) == 0) {
                if((a = (s = a).completer) == null) {
                    s.quietlyComplete();
                    return;
                }
            }else if (a.weakCompareAndSetPendingCount(c, c - 1))
                return;
        }
    }

    /**
     * 和tryComplete不太一样了，作用是：
     * 不管pending是多少，强制把当前节点当成已完成来处理，然后再触发父节点的tryComplete，相当于是个强制完成的入口。
     *
     * 适用场景：某个子任务找到搜索结果了，不想再等其他pending，直接让根或者某层提早完成，
     * 类似上面这种抢先完成/强制完成的场景。
     */
    public void complete(T rawResult) {
        CountedCompleter<?> p;
        setRawResult(rawResult);    // 直接设置结果
        onCompletion(this); // 调用自己的completion回调，注意caller是this
        quietlyComplete();  // 把当前节点标记为完成
        if((p = completer) != null) // 如果有completer，就让父节点继续tryComplete
            p.tryComplete();
    }

    /**
     * 如果当前节点pending是0了，则返回自己，否则把pending减1，然后返回null。
     * 本质就是确认：当前线程是不是可以开始完成处理的节点。
     */
    public final CountedCompleter<?> firstComplete() {
        for(int c;;) {
            if((c = pending) == 0)
                return this;
            else if(weakCompareAndSetPendingCount(c, c - 1))
                return null;
        }
    }

    /**
     * 如果没有completer了，说明到root了，就调用root的quietlyComplete。
     * 与上面firstComplete配合使用，支持一种自定义completion travelsal：
     * 不通过onCompletion回调，而是由用户自己写一个循环，逐层处理完成节点，这在一些复杂归约场景很有用。
     */
    public final CountedCompleter<?> nextComplete() {
        CountedCompleter<?> p;
        if((p = completer) != null)
            return p.firstComplete();
        else {
            quietlyComplete();
            return null;
        }
    }

    /**
     * 沿着completer一路找到root，然后调用root的quietlyComplete方法
     * 适用场景：
     * 某个子任务已经找到最终答案，整棵树没必要再等所有pending，直接让root变成joinable/invode可返回
     */
    public final void quietlyCompleteRoot() {
        for(CountedCompleter<?> a = this, p;;) {
            if((p = a.completer) == null) {
                a.quietlyComplete();
                return;
            }
            a = p;
        }
    }

    /**
     * 如果当前任务还没完成，当前线程可以帮忙处理一部分和这个CountedCompleter的completion路径相关的任务。
     * CountedCompleter不只是能自己传播完成，也支持worker主动帮completion路径上的任务推进。
     */
    public final void helpComplete(int maxTasks) {
        ForkJoinPool.WorkQueue q;
        Thread t;
        boolean owned;
        if (owned = (t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
            q = ((ForkJoinWorkerThread)t).workQueue;
        else
            q = ForkJoinPool.commonQueue();
        if(q != null && maxTasks > 0)
            q.helpComplete(this, owned, maxTasks);
    }

    // ForkJoinTask overrides

    /**
     * 异常不仅能让当前任务异常完成，还可以沿着completer链继续向上传播。
     * 是否继续传播，由CountedCompleter方法来决定，默认返回值就是true。
     */
    @Override
    final int trySetException(Throwable ex) {
        CountedCompleter<?> a = this;
        CountedCompleter<?> p = a;
        do {

        } while(isExceptionalStatus(a.trySetThrown(ex)) &&
                a.onExceptionalCompletion(ex, p) &&
                (a = (p = a).completer) != null && a.status >= 0);
        return status;
    }

    @Override
    public final boolean exec() {
        /**
         * 对CountedCompleter来说，compute执行完，不代表任务已经完成，所以总是会返回false，
         * exec只负责触发任务，最终完成不是靠exec方法。
         */
        compute();
        return false;
    }

    @Override
    public T getRawResult() {
        return null;
    }

    @Override
    protected void setRawResult(T t) {}

    // VarHandle mechanics
    private static final VarHandle PENDING;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            PENDING = l.findVarHandle(java.util.concurrent.CountedCompleter.class, "pending", int.class);

        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

package com.github.liyibo1110.jdk.java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 官方冗长的注释不翻译了，核心模型就一句话：Futurn完成时触发依赖任务（Future完成 -> 触发依赖任务 -> 生成新的Future，相当于DAG）
 * API可以分成四大类：
 * 1、单输入（Uni）：只有一个依赖（A -> B）
 * 涉及的API：thenApply、thenAccept、thenRun、handle、whenComplete、exceptionally、thenCompose
 * 对应内部类：UniApply、UniAccept、UniRun、UniHandle、UniWhenComplete、UniExceptionally、UniCompose
 *
 * 2、双输入（Bi）：两个Future完成（A and B -> C）
 * 涉及的API：thenCombine、thenAcceptBoth、runAfterBoth
 * 对应内部类：BiApply、BiAccept、BiRun
 *
 * 3、Either（Or）：两个Future任意一个完成（A or B -> C）
 * 涉及的API：applyToEither、acceptEither、runAfterEither
 * 对应内部类：OrApply、OrAccept、OrRun
 *
 * 4、多输入
 * 涉及的API：allOf、anyOf
 * 对应内部类：andTree、AnyOf
 * @author liyibo
 * @date 2026-03-04 22:56
 */
public class CompletableFuture<T> implements Future<T>, CompletionStage<T> {
    /** 执行结果，可能是正常值，也可能是异常或者null，最终存的应该是AltResult */
    volatile Object result;

    /**
     * 重要字段：保存这个Completion依赖的任务（又一个Treiber stack）
     * 注意这个结构是LIFO（相当于栈），如此设计的原因如下：
     * 1、在push的时候直接操作头节点即可，无需遍历到尾节点再插入（但这个不是主要优化点）。
     * 2、在取出Completion时，顺序尽管是反的，因为每个Completion会插件上游src的result是否可用，不可用则返回null，
     * 因为最终顺序依然会是正确的。
     * 3、LIFO主要是为了CPU cache优化，刚创建的Completion仍在CPU cache，而LIFO会优先执行新创建的Completion，所以cache hit更高，
     * 如果是FIFO，则会先执行最老的Completion，在cache中已经被驱逐，Doug Lea在并发库里非常重视cache locality。
     * 4、例如CompletableFuture.supplyAsync(...).thenApply(...).thenApply(...)，
     * 在supplyAsync完成时，当前线程已经在CPU上，LIFO可以让最新的stage立即执行，这样pipeline就会在同一个线程继续执行。
     * 5、LIFO还避免了深递归，如果是FIFO执行可能是f.complete -> Completion1 -> Completion2 -> Completion3，递归深度而可能是pipeline length。
     * 而现在的LIFO以及相关的NESTED的tryFire调用方式，可能把递归变成了迭代，避免方法暴栈。
     * 6、因为默认的executor是ForkJoinPool.commonPool，ForkJoinPool核心策略是work-strealing，而ForkJoinPool的任务队列也是LIFO，
     * 原因同样是：最近创建的任务最可能被当前线程继续执行，所以stack和ForkJoinPool的task queue都是用了LIFO，目的基本是一致的。
     *
     */
    volatile Completion stack;

    final boolean internalComplete(Object r) {
        return RESULT.compareAndSet(this, null, r);
    }

    /**
     * 将指定的Completion成功入栈则返回true。
     */
    final boolean tryPushStack(Completion c) {
        Completion h = stack;
        NEXT.set(c, h); // 给c的next赋值
        return STACK.compareAndSet(this, h, c);
    }

    /**
     * 无条件地将指定Completion入栈，不成功则会重试。
     */
    final void pushStack(Completion c) {
        do {
            // nothing to do
        } while(!tryPushStack(c));
    }

    /* ------------- Encoding and decoding outcomes -------------- */

    static final class AltResult {
        final Throwable ex;

        AltResult(Throwable ex) {
            this.ex = ex;
        }
    }

    static final AltResult NIL = new AltResult(null);

    /**
     * 使用null来表示完成，除非已完成。
     */
    final boolean completeNull() {
        return RESULT.compareAndSet(this, null, NIL);
    }

    /**
     * 返回给定非异常值的结果：如果是null则返回NIL，否则返回原对象。
     */
    final Object encodeValue(T t) {
        return t == null ? NIL : t;
    }

    /**
     * 使用value来表示完成，除非已完成。
     */
    final boolean completeValue(T t) {
        return RESULT.compareAndSet(this, null, t == null ? NIL : t);
    }

    /**
     * 返回给定（非null）异常的AltResult。作为包装的CompletionException返回，除非异常本身就是CompletionException
     */
    static AltResult encodeThrowable(Throwable x) {
        return new AltResult(x instanceof CompletionException ? x : new CompletionException(x));
    }

    /**
     * 使用异常来表示完成，除非已完成。
     */
    final boolean completeThrowable(Throwable x) {
        return RESULT.compareAndSet(this, null, encodeThrowable(x));
    }

    static Object encodeThrowable(Throwable x, Object r) {
        if(!(x instanceof CompletionException)) // 异常的判断优先
            x = new CompletionException(x);
        else if(r instanceof AltResult && x == ((AltResult)r).ex)
            return r;
        return new AltResult(x);
    }

    final boolean completeThrowable(Throwable x, Object r) {
        return RESULT.compareAndSet(this, null, encodeThrowable(x, r));
    }

    Object encodeOutcome(T t, Throwable x) {
        return x == null
                ? t == null ? NIL : t
                : encodeThrowable(x);
    }

    /**
     * 返回outcome的复制结果，如果是异常，尝试重新包装成CompletionException，否则返回自身
     */
    static Object encodeRelay(Object r) {
        Throwable x;
        if(r instanceof AltResult && (x = ((AltResult)r).ex) != null && !(x instanceof CompletionException))
            r = new AltResult(new CompletionException(x));
        return r;
    }

    final boolean completeRelay(Object r) {
        return RESULT.compareAndSet(this, null, encodeRelay(r));
    }

    /**
     * Future的get最终会调用这个获取输出
     */
    private static Object reportGet(Object r) throws InterruptedException, ExecutionException {
        if(r == null)
            throw new InterruptedException();
        if(r instanceof AltResult) {
            Throwable x, cause;
            if((x = ((AltResult)r).ex) == null)
                return null;
            if(x instanceof CancellationException)
                throw (CancellationException)x;
            if(x instanceof CompletionException && (cause = x.getCause()) != null)
                x = cause;
            throw new java.util.concurrent.ExecutionException(x);
        }
        return r;
    }

    /**
     * 解码outcome以返回结果或抛出未检查异常
     */
    private static Object reportJoin(Object r) {
        if(r instanceof AltResult) {
            Throwable x;
            if((x = ((AltResult)r).ex) == null)
                return null;
            if(x instanceof CancellationException)
                throw (CancellationException)x;
            if(x instanceof CompletionException)
                throw (CompletionException)x;
            throw new CompletionException(x);
        }
        return r;
    }

    /* ------------- Async task preliminaries -------------- */

    /**
     * 标记接口，用于标识由异步方法生成的异步任务。
     * 对于监控、调试和追踪异步活动有帮助作用。
     */
    public interface AsynchronousCompletionTask {}

    /** 优先使用ForkJoinPool，而不是ThreadPerTaskExecutor */
    private static final boolean USE_COMMON_POOL = ForkJoinPool.getCommonPoolParallelism() > 1;

    private static final java.util.concurrent.Executor ASYNC_POOL = USE_COMMON_POOL
                                                ? ForkJoinPool.commonPool()
                                                : new ThreadPerTaskExecutor();

    /** ForkJoinPool.commonPool()方法不支持parallelism时的保底方案 */
    static final class ThreadPerTaskExecutor implements java.util.concurrent.Executor {
        @Override
        public void execute(Runnable r) {
            Objects.requireNonNull(r);
            new Thread(r).start();
        }
    }

    static java.util.concurrent.Executor screenExecutor(Executor e) {
        if(!USE_COMMON_POOL && e == ForkJoinPool.commonPool())
            return ASYNC_POOL;
        if(e == null)
            throw new NullPointerException();
        return e;
    }

    // Modes for Completion.tryFire. Signedness matters.
    static final int SYNC = 0;
    static final int ASYNC = 1;
    static final int NESTED = -1;


    /* ------------- Base Completion classes and operations -------------- */

    abstract static class Completion extends ForkJoinTask<Void> implements Runnable, AsynchronousCompletionTask {
        volatile Completion next;

        /**
         * 如果被触发，则执行Completion，并返回可能需要传播的依赖项（如果存在）。
         */
        abstract CompletableFuture<?> tryFire(int mode);

        /**
         * 如果可能仍可触发，则返回true，由cleanStack调用。
         */
        abstract boolean isLive();

        @Override
        public final void run() {
            tryFire(ASYNC);
        }

        @Override
        public final boolean exec() {
            tryFire(ASYNC);
            return false;
        }

        @Override
        public final Void getRawResult() {
            return null;
        }

        @Override
        public final void setRawResult(Void v) {}
    }

    /**
     * 但本实例的result计算出结果并赋值之后，会调用这个方法用来触发stack的下一个Completion执行。
     */
    final void postComplete() {
        CompletableFuture<?> f = this;  // 当前已算出result的CompletableFuture
        Completion h;
        // stack不为空
        while((h = f.stack) != null || (f != this && (h = (f = this).stack) != null)) {
            CompletableFuture<?> d;
            Completion t;   // 就是下游
            if(STACK.compareAndSet(f, h, t = h.next)) {
                if(t != null) {
                    if(f != this) { // 重新入栈，这里对应后面的NEXT.compareAndSet(h, t, null)，目的是防止无限递归（这个优化比较难看懂）
                        pushStack(h);
                        continue;
                    }
                    NEXT.compareAndSet(h, t, null); // 下游的下游先脱钩，防止递归层数太深
                }
                f = (d = h.tryFire(NESTED)) == null ? this : d;
            }
        }
    }

    /**
     * 遍历stack，并解除一个或多个已发现的无效Completion的链接。
     */
    final void cleanStack() {
        // 当前节点
        Completion p = stack;
        for(boolean unlinked = false; ; ) {
            if(p == null)
                return;
            else if(p.isLive()) {
                if(unlinked)
                    return;
                else
                    break;
            }else if(STACK.weakCompareAndSet(this, p, p = p.next)) {
                unlinked = true;
            }else {
                p = stack;
            }
        }

        // 尝试unlink第一个不是live的Completion
        for(Completion q = p.next; q != null; ) {
            Completion s = q.next;
            if(q.isLive()) {    // 都是live则往下继续找
                p = q;
                q = s;
            }else if(NEXT.weakCompareAndSet(p, q, s)) { // 不是live了
                break;
            }else {
                q = p.next;
            }
        }
    }

    /* ------------- One-input Completions -------------- */

    abstract static class UniCompletion<T, V> extends Completion {
        Executor executor;

        /** src执行前要依赖的CompletableFuture */
        CompletableFuture<V> dep;

        /** 自身要执行的CompletableFuture */
        CompletableFuture<T> src;

        UniCompletion(Executor executor, CompletableFuture<V> dep, CompletableFuture<T> src) {
            this.executor = executor;
            this.dep = dep;
            this.src = src;
        }

        /**
         * 如果操作可执行则返回true，仅在确认可触发时调用，使用ForkJoin标记位确保仅单线程获取所有权。
         * 如果为异步操作，则作为任务启动，后续调用tryFire将执行该操作。
         */
        final boolean claim() {
            Executor e = executor;
            if(compareAndSetForkJoinTaskTag((short)0, (short)1)) {
                if(e == null)
                    return true;
                executor = null;    // 关闭
                e.execute(this);
            }
            return false;
        }

        final boolean isLive() {
            return dep != null;
        }
    }

    /**
     * push给定的Completion，除非在尝试过程中已完成。
     * 调用方应该先检查结果是否为空。
     */
    final void unipush(Completion c) {
        if(c != null) {
            while(!tryPushStack(c)) {   // 尝试入栈
                if(result != null) {    // 检查是否已经计算完成了
                    /**
                     * 很重要的地方，Completion的next设为null
                     * 这个属于fast-path，即把c当作一个单独任务跑一下，这里有一个很重要的点，就是当result != null时，
                     * stack肯定已经没有其它Completion了（因为之前必须要走postComplete方法，会把stack剩余的先走完才会result里面有值），
                     * 所以额外单独跑一下c就完事了，不会再调用stack剩余的c了（因为肯定没有了）
                     */
                    NEXT.set(c, null);
                    break;
                }
            }
            if(result != null)  // 如果已经算完了，则立即同步调用tryFire
                c.tryFire(SYNC);
        }
    }

    /**
     * 在UniCompletion成功后，由依赖方执行后处理。
     * 尝试清理a的栈，然后根据mode执行postComplete或将此返回给调用方。
     */
    final CompletableFuture<T> postFire(CompletableFuture<?> a, int mode) {
        if(a != null && a.stack != null) {
            Object r;
            if((r = a.result) == null)
                a.cleanStack();
            if(mode >= 0 && (r != null || a.result != null))
                a.postComplete();
        }
        if(result != null && stack != null) {
            if(mode < 0)
                return this;
            else postComplete();
        }
        return null;
    }

    static final class UniApply<T, V> extends UniCompletion<T, V> {
        Function<? super T,? extends V> fn;

        UniApply(Executor executor, CompletableFuture<V> dep, CompletableFuture<T> src, Function<? super T, ? extends V> fn) {
            super(executor, dep, src);
            this.fn = fn;
        }

        final CompletableFuture<V> tryFire(int mode) {
            CompletableFuture<V> d; // 最终要返回的
            CompletableFuture<T> a; // 来自上游的
            Object r;
            Throwable x;
            Function<? super T, ? extends V> f;
            if((a = src) == null || (r = a.result) == null || (d = dep) == null || (f = fn) == null)
                return null;
            tryComplete: if(d.result == null) {
                if(r instanceof AltResult) {
                    if((x = ((AltResult)r).ex) != null) {   // 上游结果是异常
                        d.completeThrowable(x, r);
                        break tryComplete;
                    }
                    r = null;
                }
                try {
                    if(mode <= 0 && !claim())
                        return null;
                    else {
                        T t = (T)r;
                        d.completeValue(f.apply(t));
                    }
                } catch (Throwable ex) {
                    d.completeThrowable(ex);
                }
            }
            src = null; dep = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    private <V> CompletableFuture<V> uniApplyStage(Executor e, Function<? super T, ? extends V> f) {
        if(f == null)
            throw new NullPointerException();
        Object r;
        if((r = result) != null)
            return uniApplyNow(r, e, f);
        CompletableFuture<V> d = newIncompleteFuture();
        unipush(new UniApply<>(e, d, this, f));
        return d;
    }

    private <V> CompletableFuture<V> uniApplyNow(Object r, Executor e, Function<? super T, ? extends V> f) {
        Throwable x;
        CompletableFuture<V> d = newIncompleteFuture();
        if (r instanceof AltResult) {
            if((x = ((AltResult)r).ex) != null) {
                d.result = encodeThrowable(x, r);
                return d;
            }
            r = null;
        }
        try {
            if(e != null) {
                e.execute(new UniApply<>(null, d, this, f));
            }else {
                T t = (T) r;
                d.result = d.encodeValue(f.apply(t));
            }
        } catch (Throwable ex) {
            d.result = encodeThrowable(ex);
        }
        return d;
    }

    static final class UniAccept<T> extends UniCompletion<T, Void> {
        Consumer<? super T> fn;

        UniAccept(Executor executor, CompletableFuture<Void> dep, CompletableFuture<T> src, Consumer<? super T> fn) {
            super(executor, dep, src);
            this.fn = fn;
        }

        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d; // 最终要返回的
            CompletableFuture<T> a; // 来自上游的
            Object r;
            Throwable x;
            Consumer<? super T> f;
            // result必须要有值才能继续accept
            if((a = src) == null || (r = a.result) == null || (d = dep) == null || (f = fn) == null)
                return null;
            tryComplete: if(d.result == null) {
                if(r instanceof AltResult) {
                    if((x = ((AltResult)r).ex) != null) {   // 上游结果是异常
                        d.completeThrowable(x, r);
                        break tryComplete;
                    }
                    r = null;
                }
                // 触发accept
                try {
                    if(mode <= 0 && !claim())
                        return null;
                    else {
                        T t = (T)r;
                        f.accept(t);
                        d.completeNull();   // 因为是Consumer，结果一定是null
                    }
                } catch (Throwable ex) {
                    d.completeThrowable(ex);
                }
            }
            src = null; dep = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    private CompletableFuture<Void> uniAcceptStage(Executor e, Consumer<? super T> f) {
        if(f == null)
            throw new NullPointerException();
        Object r;
        if((r = result) != null)    // result已经算完了，直接进行accept
            return uniAcceptNow(r, e, f);
        // result还没算完，需要把accept给入栈等待了
        CompletableFuture<Void> d = newIncompleteFuture();
        unipush(new UniAccept<T>(e, d, this, f));
        return d;
    }

    private CompletableFuture<Void> uniAcceptNow(Object r, Executor e, Consumer<? super T> f) {
        Throwable x;
        CompletableFuture<Void> d = newIncompleteFuture();
        if(r instanceof AltResult) {
            if((x = ((AltResult)r).ex) != null) {
                d.result = encodeThrowable(x, r);
                return d;
            }
            r = null;   // 能走到这里说明r的ex == null，不然在里面就return了
        }
        try {
            if(e != null) { // 异步执行accept
                e.execute(new UniAccept<T>(null, d, this, f));
            }else { // 同步执行accept
                T t = (T) r;
                f.accept(t);
                d.result = NIL; // 需要NIL来占位，代表结果是null，因为不能直接存null，null值分不清楚到底是完事还是没完事
            }
        } catch (Throwable ex) {
            d.result = encodeThrowable(ex);
        }
        return d;
    }

    static final class UniRun<T> extends UniCompletion<T, Void> {
        Runnable fn;

        UniRun(Executor executor, CompletableFuture<Void> dep, CompletableFuture<T> src, Runnable fn) {
            super(executor, dep, src);
            this.fn = fn;
        }

        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d;
            CompletableFuture<T> a;
            Object r; Throwable x; Runnable f;
            if((a = src) == null || (r = a.result) == null || (d = dep) == null || (f = fn) == null)
                return null;
            if(d.result == null) {
                if(r instanceof AltResult && (x = ((AltResult)r).ex) != null)
                    d.completeThrowable(x, r);
                else
                    try {
                        if(mode <= 0 && !claim())
                            return null;
                        else{
                            f.run();
                            d.completeNull();
                        }
                    } catch (Throwable ex) {
                        d.completeThrowable(ex);
                    }
            }
            src = null; dep = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    private CompletableFuture<Void> uniRunStage(Executor e, Runnable f) {
        if (f == null)
            throw new NullPointerException();
        Object r;
        if((r = result) != null)
            return uniRunNow(r, e, f);
        CompletableFuture<Void> d = newIncompleteFuture();
        unipush(new UniRun<>(e, d, this, f));
        return d;
    }

    private CompletableFuture<Void> uniRunNow(Object r, Executor e, Runnable f) {
        Throwable x;
        CompletableFuture<Void> d = newIncompleteFuture();
        if(r instanceof AltResult && (x = ((AltResult)r).ex) != null)
            d.result = encodeThrowable(x, r);
        else
            try {
                if(e != null) {
                    e.execute(new UniRun<T>(null, d, this, f));
                }else {
                    f.run();
                    d.result = NIL;
                }
            } catch (Throwable ex) {
                d.result = encodeThrowable(ex);
            }
        return d;
    }

    static final class UniWhenComplete<T> extends UniCompletion<T, T> {
        BiConsumer<? super T, ? super Throwable> fn;

        UniWhenComplete(Executor executor, CompletableFuture<T> dep, CompletableFuture<T> src, BiConsumer<? super T, ? super Throwable> fn) {
            super(executor, dep, src);
            this.fn = fn;
        }
        final CompletableFuture<T> tryFire(int mode) {
            CompletableFuture<T> d;
            CompletableFuture<T> a;
            Object r;
            BiConsumer<? super T, ? super Throwable> f;
            if((a = src) == null || (r = a.result) == null || (d = dep) == null || (f = fn) == null
                    || !d.uniWhenComplete(r, f, mode > 0 ? null : this))
                return null;
            src = null; dep = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    final boolean uniWhenComplete(Object r, BiConsumer<? super T, ? super Throwable> f, CompletableFuture.UniWhenComplete<T> c) {
        T t; Throwable x = null;
        if (result == null) {
            try {
                if(c != null && !c.claim())
                    return false;
                if(r instanceof AltResult) {
                    x = ((AltResult)r).ex;
                    t = null;
                }else {
                    T tr = (T)r;
                    t = tr;
                }
                f.accept(t, x); // 和thenXXX不同的逻辑，无论结果是什么，都要执行accept
                if(x == null) { // 如果上游返回是正常值，就把原来的结果r传给d
                    internalComplete(r);
                    return true;
                }
            } catch (Throwable ex) {
                if(x == null)   // 如果action抛出异常，上游正常，最终结果就要用action的异常，否则x还是原来上游的异常
                    x = ex;
                else if(x != ex)    // 如果action抛出异常，上游也异常，action的异常被挂成suppressed
                    x.addSuppressed(ex);
            }
            completeThrowable(x, r);    // 把最终异常给d
        }
        return true;
    }

    private CompletableFuture<T> uniWhenCompleteStage(Executor e, BiConsumer<? super T, ? super Throwable> f) {
        if(f == null)
            throw new NullPointerException();
        CompletableFuture<T> d = newIncompleteFuture();
        Object r;
        if((r = result) == null)
            unipush(new UniWhenComplete<>(e, d, this, f));
        else if(e == null)
            d.uniWhenComplete(r, f, null);
        else{
            try {
                e.execute(new UniWhenComplete<T>(null, d, this, f));
            } catch (Throwable ex) {
                d.result = encodeThrowable(ex);
            }
        }
        return d;
    }

    static final class UniHandle<T,V> extends UniCompletion<T, V> {
        BiFunction<? super T, Throwable, ? extends V> fn;

        UniHandle(Executor executor, CompletableFuture<V> dep, CompletableFuture<T> src,
                  BiFunction<? super T, Throwable, ? extends V> fn) {
            super(executor, dep, src);
            this.fn = fn;
        }

        final CompletableFuture<V> tryFire(int mode) {
            CompletableFuture<V> d;
            CompletableFuture<T> a;
            Object r; BiFunction<? super T, Throwable, ? extends V> f;
            if ((a = src) == null || (r = a.result) == null || (d = dep) == null || (f = fn) == null
                    || !d.uniHandle(r, f, mode > 0 ? null : this))
                return null;
            src = null; dep = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    final <S> boolean uniHandle(Object r, BiFunction<? super S, Throwable, ? extends T> f,
                                CompletableFuture.UniHandle<S, T> c) {
        S s; Throwable x;
        if(result == null) {
            try {
                if(c != null && !c.claim())
                    return false;
                if(r instanceof CompletableFuture.AltResult) {
                    x = ((AltResult)r).ex;
                    s = null;
                } else {
                    x = null;
                    S ss = (S)r;
                    s = ss;
                }
                completeValue(f.apply(s, x));
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private <V> CompletableFuture<V> uniHandleStage(Executor e, BiFunction<? super T, Throwable, ? extends V> f) {
        if(f == null)
            throw new NullPointerException();
        CompletableFuture<V> d = newIncompleteFuture();
        Object r;
        if((r = result) == null)
            unipush(new UniHandle<>(e, d, this, f));
        else if(e == null)
            d.uniHandle(r, f, null);
        else{
            try {
                e.execute(new UniHandle<>(null, d, this, f));
            } catch (Throwable ex) {
                d.result = encodeThrowable(ex);
            }
        }
        return d;
    }

    static final class UniExceptionally<T> extends UniCompletion<T, T> {
        Function<? super Throwable, ? extends T> fn;

        UniExceptionally(Executor executor, CompletableFuture<T> dep, CompletableFuture<T> src,
                         Function<? super Throwable, ? extends T> fn) {
            super(executor, dep, src);
            this.fn = fn;
        }

        final CompletableFuture<T> tryFire(int mode) {
            CompletableFuture<T> d;
            CompletableFuture<T> a;
            Object r; Function<? super Throwable, ? extends T> f;
            if ((a = src) == null || (r = a.result) == null || (d = dep) == null || (f = fn) == null
                    || !d.uniExceptionally(r, f, mode > 0 ? null : this))
                return null;
            src = null; dep = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    final boolean uniExceptionally(Object r, Function<? super Throwable, ? extends T> f,
                                   CompletableFuture.UniExceptionally<T> c) {
        Throwable x;
        if(result == null) {
            try {
                if(c != null && !c.claim())
                    return false;
                if(r instanceof AltResult && (x = ((AltResult)r).ex) != null)
                    completeValue(f.apply(x));
                else
                    internalComplete(r);
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private CompletableFuture<T> uniExceptionallyStage(Executor e, Function<Throwable, ? extends T> f) {
        if (f == null)
            throw new NullPointerException();
        CompletableFuture<T> d = newIncompleteFuture();
        Object r;
        if((r = result) == null)
            unipush(new UniExceptionally<T>(e, d, this, f));
        else if(e == null)
            d.uniExceptionally(r, f, null);
        else{
            try {
                e.execute(new UniExceptionally<T>(null, d, this, f));
            } catch (Throwable ex) {
                d.result = encodeThrowable(ex);
            }
        }
        return d;
    }

    static final class UniComposeExceptionally<T> extends UniCompletion<T, T> {
        Function<Throwable, ? extends CompletionStage<T>> fn;

        UniComposeExceptionally(Executor executor, CompletableFuture<T> dep,
                                CompletableFuture<T> src,
                                Function<Throwable, ? extends CompletionStage<T>> fn) {
            super(executor, dep, src);
            this.fn = fn;
        }

        final CompletableFuture<T> tryFire(int mode) {
            CompletableFuture<T> d;
            CompletableFuture<T> a;
            Function<Throwable, ? extends CompletionStage<T>> f;
            Object r; Throwable x;
            if((a = src) == null || (r = a.result) == null || (d = dep) == null || (f = fn) == null)
                return null;
            if (d.result == null) {
                if ((r instanceof AltResult) && (x = ((AltResult)r).ex) != null) {
                    try {
                        if(mode <= 0 && !claim())
                            return null;
                        CompletableFuture<T> g = f.apply(x).toCompletableFuture();
                        if((r = g.result) != null)
                            d.completeRelay(r);
                        else{
                            g.unipush(new UniRelay<>(d, g));
                            if(d.result == null)
                                return null;
                        }
                    } catch (Throwable ex) {
                        d.completeThrowable(ex);
                    }
                }
                else
                    d.internalComplete(r);
            }
            src = null; dep = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    private CompletableFuture<T> uniComposeExceptionallyStage(Executor e, Function<Throwable, ? extends CompletionStage<T>> f) {
        if(f == null)
            throw new NullPointerException();
        CompletableFuture<T> d = newIncompleteFuture();
        Object r, s; Throwable x;
        if((r = result) == null)
            unipush(new UniComposeExceptionally<T>(e, d, this, f));
        else if (!(r instanceof AltResult) || (x = ((AltResult)r).ex) == null)
            d.internalComplete(r);
        else
            try {
                if(e != null)
                    e.execute(new UniComposeExceptionally<T>(null, d, this, f));
                else{
                    CompletableFuture<T> g = f.apply(x).toCompletableFuture();
                    if((s = g.result) != null)
                        d.result = encodeRelay(s);
                    else
                        g.unipush(new UniRelay<>(d, g));
                }
            } catch (Throwable ex) {
                d.result = encodeThrowable(ex);
            }
        return d;
    }

    static final class UniCompose<T, V> extends UniCompletion<T, V> {
        Function<? super T, ? extends CompletionStage<V>> fn;

        UniCompose(Executor executor, CompletableFuture<V> dep, CompletableFuture<T> src,
                   Function<? super T, ? extends CompletionStage<V>> fn) {
            super(executor, dep, src);
            this.fn = fn;
        }

        final CompletableFuture<V> tryFire(int mode) {
            CompletableFuture<V> d;
            CompletableFuture<T> a;
            Function<? super T, ? extends CompletionStage<V>> f;
            Object r; Throwable x;
            if ((a = src) == null || (r = a.result) == null || (d = dep) == null || (f = fn) == null)
                return null;
            tryComplete: if (d.result == null) {
                if(r instanceof AltResult) {
                    if((x = ((AltResult)r).ex) != null) {
                        d.completeThrowable(x, r);
                        break tryComplete;
                    }
                    r = null;
                }
                try {
                    if(mode <= 0 && !claim())
                        return null;
                    T t = (T)r;
                    CompletableFuture<V> g = f.apply(t).toCompletableFuture();
                    if ((r = g.result) != null)
                        d.completeRelay(r);
                    else {
                        g.unipush(new UniRelay<>(d, g));
                        if(d.result == null)
                            return null;
                    }
                } catch (Throwable ex) {
                    d.completeThrowable(ex);
                }
            }
            src = null; dep = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    private <V> CompletableFuture<V> uniComposeStage(Executor e, Function<? super T, ? extends CompletionStage<V>> f) {
        if(f == null)
            throw new NullPointerException();
        CompletableFuture<V> d = newIncompleteFuture();
        Object r, s; Throwable x;
        if((r = result) == null)
            unipush(new UniCompose<>(e, d, this, f));
        else {
            if(r instanceof AltResult) {
                if ((x = ((AltResult)r).ex) != null) {
                    d.result = encodeThrowable(x, r);
                    return d;
                }
                r = null;
            }
            try {
                if(e != null)
                    e.execute(new UniCompose<>(null, d, this, f));
                else {
                    T t = (T)r;
                    CompletableFuture<V> g = f.apply(t).toCompletableFuture();
                    if((s = g.result) != null)
                        d.result = encodeRelay(s);
                    else
                        g.unipush(new UniRelay<>(d, g));
                }
            } catch (Throwable ex) {
                d.result = encodeThrowable(ex);
            }
        }
        return d;
    }

    /**
     * 为了实现thenCompose的特殊组件，目的只有一个就是将compose算出来的CompletableFuture，完成后的值挂到d里
     */
    static final class UniRelay<U, T extends U> extends UniCompletion<T, U> {
        UniRelay(CompletableFuture<U> dep, CompletableFuture<T> src) {
            super(null, dep, src);
        }

        final CompletableFuture<U> tryFire(int mode) {
            CompletableFuture<U> d; // 最终要返回的future
            CompletableFuture<T> a; // nextFuture
            Object r;
            if((a = src) == null || (r = a.result) == null || (d = dep) == null)
                return null;
            if(d.result == null)
                d.completeRelay(r);
            src = null; dep = null;
            return d.postFire(a, mode);
        }
    }

    /**
     * 复制给定的CompletableFuture并返回（anyOf方法会使用）
     */
    private static <U, T extends U> CompletableFuture<U> uniCopyStage(CompletableFuture<T> src) {
        Object r;
        CompletableFuture<U> d = src.newIncompleteFuture();
        if((r = src.result) != null)
            d.result = encodeRelay(r);
        else
            src.unipush(new UniRelay<>(d, src));
        return d;
    }

    private MinimalStage<T> uniAsMinimalStage() {
        Object r;
        if((r = result) != null)
            return new MinimalStage<>(encodeRelay(r));
        MinimalStage<T> d = new MinimalStage<>();
        unipush(new UniRelay<>(d, this));
        return d;
    }

    /* ------------- Two-input Completions -------------- */

    abstract static class BiCompletion<T, U, V> extends UniCompletion<T, V> {
        CompletableFuture<U> snd; // 在UniCompletion的基础上，又多了第2个src

        BiCompletion(Executor executor, CompletableFuture<V> dep, CompletableFuture<T> src, CompletableFuture<U> snd) {
            super(executor, dep, src);
            this.snd = snd;
        }
    }

    /**
     * 把snd的触发入口，代理回同一个BiCompletion
     * 如果直接把BiCompletion的实现直接尝试挂到src和snd的stack里面是不行的，因为BiCompletion里面的next只能指向其中一个stack，
     * 所以这个方案是又搞了一个代理类，把代理对象挂到snd的stack，原始的BiCompletion挂到src的stack。
     */
    static final class CoCompletion extends Completion {
        BiCompletion<?, ?, ?> base;

        CoCompletion(BiCompletion<?, ?, ?> base) {
            this.base = base;
        }

        final CompletableFuture<?> tryFire(int mode) {
            BiCompletion<?, ?, ?> c;
            CompletableFuture<?> d;
            if((c = base) == null || (d = c.tryFire(mode)) == null)
                return null;
            base = null; // detach
            return d;
        }

        final boolean isLive() {
            BiCompletion<?, ?, ?> c;
            // 注意base会在上面tryFire成功后置为null
            return (c = base) != null && c.dep != null;
        }
    }

    /**
     * 要通过tryPushStack挂到src的stack里，还要通过b.unipush挂到snd的stack里
     */
    final void bipush(CompletableFuture<?> b, BiCompletion<?, ?, ?> c) {
        if(c != null) {
            while(result == null) {
                if(tryPushStack(c)) {   // 先尝试挂到src的stack
                    if(b.result == null)    // 如果snd的result还没算出来，在把c挂到snd里（以CoCompletion的形式）
                        b.unipush(new CoCompletion(c));
                    else if(result != null) // 注意这里是：src和snd的result都已经算出来了，则直接触发c尝试获取最终计算结果
                        c.tryFire(SYNC);
                    return;
                }
            }
            // 到了这里，说明src的result已经算出来了，根本没进while，直接把c挂到snd里面
            b.unipush(c);
        }
    }

    final CompletableFuture<T> postFire(CompletableFuture<?> a, CompletableFuture<?> b, int mode) {
        if(b != null && b.stack != null) {  // 清理snd
            Object r;
            if((r = b.result) == null)
                b.cleanStack();
            if(mode >= 0 && (r != null | b.result != null))
                b.postComplete();   // 触发b的stack后面的Completion
        }
        return postFire(a, mode);   // 再去处理a
    }

    static final class BiApply<T,U,V> extends BiCompletion<T, U, V> {
        BiFunction<? super T, ? super U, ? extends V> fn;

        BiApply(Executor executor, CompletableFuture<V> dep,
                CompletableFuture<T> src, CompletableFuture<U> snd,
                BiFunction<? super T, ? super U, ? extends V> fn) {
            super(executor, dep, src, snd);
            this.fn = fn;
        }

        final CompletableFuture<V> tryFire(int mode) {
            CompletableFuture<V> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            Object r;
            Object s;
            BiFunction<? super T, ? super U, ? extends V> f;
            /**
             * 这里判断比uni版本复杂多了，总之最重要的就是记住src.result和snd.result都不为null时，才会进行d.biApply方法
             */
            if((a = src) == null || (r = a.result) == null
                    || (b = snd) == null || (s = b.result) == null
                    || (d = dep) == null || (f = fn) == null
                    || !d.biApply(r, s, f, mode > 0 ? null : this)) {
                return null;
            }
            src = null; snd = null; dep = null; fn = null;
            return d.postFire(a, b, mode);
        }
    }

    final <R,S> boolean biApply(Object r, Object s,
                                BiFunction<? super R, ? super S, ? extends T> f,
                                BiApply<R, S, T> c) {
        Throwable x;
        tryComplete: if (result == null) {
            if(r instanceof AltResult) {
                if((x = ((AltResult)r).ex) != null) {
                    completeThrowable(x, r);
                    break tryComplete;
                }
                r = null;
            }
            if(s instanceof AltResult) {
                if((x = ((AltResult)s).ex) != null) {
                    completeThrowable(x, s);
                    break tryComplete;
                }
                s = null;
            }
            try {
                if(c != null && !c.claim())
                    return false;
                R rr = (R)r;
                S ss = (S)s;
                completeValue(f.apply(rr, ss));
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private <U,V> CompletableFuture<V> biApplyStage(Executor e, CompletionStage<U> o,
                                                    BiFunction<? super T,? super U,? extends V> f) {
        CompletableFuture<U> b;
        Object r, s;
        if(f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();
        CompletableFuture<V> d = newIncompleteFuture();
        if((r = result) == null || (s = b.result) == null)
            bipush(b, new BiApply<>(e, d, this, b, f));
        else if(e == null)
            d.biApply(r, s, f, null);
        else
            try {
                e.execute(new BiApply<>(null, d, this, b, f));
            } catch (Throwable ex) {
                d.result = encodeThrowable(ex);
            }
        return d;
    }

    static final class BiAccept<T,U> extends BiCompletion<T, U, Void> {
        BiConsumer<? super T,? super U> fn;

        BiAccept(Executor executor, CompletableFuture<Void> dep,
                 CompletableFuture<T> src, CompletableFuture<U> snd,
                 BiConsumer<? super T,? super U> fn) {
            super(executor, dep, src, snd);
            this.fn = fn;
        }
        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            Object r, s;
            BiConsumer<? super T,? super U> f;
            if ((a = src) == null || (r = a.result) == null
                    || (b = snd) == null || (s = b.result) == null
                    || (d = dep) == null || (f = fn) == null
                    || !d.biAccept(r, s, f, mode > 0 ? null : this))
                return null;
            src = null; snd = null; dep = null; fn = null;
            return d.postFire(a, b, mode);
        }
    }

    final <R,S> boolean biAccept(Object r, Object s, BiConsumer<? super R,? super S> f, BiAccept<R,S> c) {
        Throwable x;
        tryComplete: if (result == null) {
            if(r instanceof AltResult) {
                if((x = ((AltResult)r).ex) != null) {
                    completeThrowable(x, r);
                    break tryComplete;
                }
                r = null;
            }
            if(s instanceof AltResult) {
                if((x = ((AltResult)s).ex) != null) {
                    completeThrowable(x, s);
                    break tryComplete;
                }
                s = null;
            }
            try {
                if(c != null && !c.claim())
                    return false;
                R rr = (R) r;
                S ss = (S) s;
                f.accept(rr, ss);
                completeNull();
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private <U> CompletableFuture<Void> biAcceptStage(Executor e, CompletionStage<U> o, BiConsumer<? super T,? super U> f) {
        CompletableFuture<U> b;
        Object r, s;
        if (f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();
        CompletableFuture<Void> d = newIncompleteFuture();
        if((r = result) == null || (s = b.result) == null)
            bipush(b, new BiAccept<>(e, d, this, b, f));
        else if (e == null)
            d.biAccept(r, s, f, null);
        else
            try {
                e.execute(new BiAccept<>(null, d, this, b, f));
            } catch (Throwable ex) {
                d.result = encodeThrowable(ex);
            }
        return d;
    }

    static final class BiRun<T,U> extends BiCompletion<T, U, Void> {
        Runnable fn;

        BiRun(Executor executor, CompletableFuture<Void> dep,
              CompletableFuture<T> src, CompletableFuture<U> snd,
              Runnable fn) {
            super(executor, dep, src, snd);
            this.fn = fn;
        }

        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            Object r, s; Runnable f;
            if ((a = src) == null || (r = a.result) == null
                    || (b = snd) == null || (s = b.result) == null
                    || (d = dep) == null || (f = fn) == null
                    || !d.biRun(r, s, f, mode > 0 ? null : this))
                return null;
            src = null; snd = null; dep = null; fn = null;
            return d.postFire(a, b, mode);
        }
    }

    final boolean biRun(Object r, Object s, Runnable f, BiRun<?,?> c) {
        Throwable x;
        Object z;
        if(result == null) {
            if((r instanceof AltResult && (x = ((AltResult)(z = r)).ex) != null) ||
                    (s instanceof AltResult && (x = ((AltResult)(z = s)).ex) != null))
                completeThrowable(x, z);
            else
                try {
                    if(c != null && !c.claim())
                        return false;
                    f.run();
                    completeNull();
                } catch (Throwable ex) {
                    completeThrowable(ex);
                }
        }
        return true;
    }

    private CompletableFuture<Void> biRunStage(Executor e, CompletionStage<?> o,
                                               Runnable f) {
        CompletableFuture<?> b; Object r, s;
        if (f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();
        CompletableFuture<Void> d = newIncompleteFuture();
        if((r = result) == null || (s = b.result) == null)
            bipush(b, new BiRun<>(e, d, this, b, f));
        else if(e == null)
            d.biRun(r, s, f, null);
        else
            try {
                e.execute(new BiRun<>(null, d, this, b, f));
            } catch (Throwable ex) {
                d.result = encodeThrowable(ex);
            }
        return d;
    }

    /**
     * 只有andTree会使用的BiCompletion特殊子类，tryFire功能就是等待a和b都计算完成即可，不会保存正常返回值（但是有异常则会记录）
     */
    static final class BiRelay<T, U> extends BiCompletion<T, U, Void> {
        BiRelay(CompletableFuture<Void> dep, CompletableFuture<T> src, CompletableFuture<U> snd) {
            super(null, dep, src, snd);
        }

        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            Object r, s, z; Throwable x;
            // a和b都要计算完成
            if ((a = src) == null || (r = a.result) == null
                    || (b = snd) == null || (s = b.result) == null
                    || (d = dep) == null)
                return null;
            if(d.result == null) {
                if ((r instanceof AltResult
                        && (x = ((AltResult)(z = r)).ex) != null)
                        || (s instanceof AltResult && (x = ((AltResult)(z = s)).ex) != null))
                    d.completeThrowable(x, z);
                else
                    d.completeNull();
            }
            src = null; snd = null; dep = null;
            return d.postFire(a, b, mode);
        }
    }

    /**
     * 构建and树，此方法会被递归调用
     */
    static CompletableFuture<Void> andTree(CompletableFuture<?>[] cfs, int lo, int hi) {
        CompletableFuture<Void> d = new CompletableFuture<>();  // 创建当前and节点
        if(lo > hi) // cfs是空的，直接完成
            d.result = NIL;
        else {
            CompletableFuture<?> a, b;
            Object r, s, z;
            Throwable x;
            int mid = (lo + hi) >>> 1;  // 找到数组的中点，用来分成[lo..mid]和[mid+1..hi]
            /**
             * 构造左子future节点a，以及右future子节点b
             */
            if((a = (lo == mid ? cfs[lo] : andTree(cfs, lo, mid))) == null ||
               (b = (lo == hi ? a : (hi == mid + 1) ? cfs[hi] : andTree(cfs, mid + 1, hi))) == null) {
                throw new NullPointerException();
            }
            if((r = a.result) == null || (s = b.result) == null) {  // a和b的result只要有一个没算完，则入stack
                a.bipush(b, new BiRelay<>(d, a, b));
            }else if ((r instanceof AltResult
                        && (x = ((AltResult)(z = r)).ex) != null)
                        || (s instanceof AltResult && (x = ((AltResult)(z = s)).ex) != null))
                    d.result = encodeThrowable(x, z);
                else
                    d.result = NIL;
        }
        return d;
    }

    /* ------------- Projected (Ored) BiCompletions -------------- */

    final void orpush(CompletableFuture<?> b, BiCompletion<?,?,?> c) {
        if(c != null) {
            while(!tryPushStack(c)) {
                if(result != null) {
                    NEXT.set(c, null);
                    break;
                }
            }
            if(result != null)
                c.tryFire(SYNC);
            else
                b.unipush(new CoCompletion(c));
        }
    }

    static final class OrApply<T, U extends T, V> extends BiCompletion<T, U, V> {
        Function<? super T, ? extends V> fn;
        OrApply(Executor executor, CompletableFuture<V> dep,
                CompletableFuture<T> src, CompletableFuture<U> snd,
                Function<? super T,? extends V> fn) {
            super(executor, dep, src, snd);
            this.fn = fn;
        }

        final CompletableFuture<V> tryFire(int mode) {
            CompletableFuture<V> d;
            CompletableFuture<? extends T> a, b;
            Object r;
            Throwable x;
            Function<? super T, ? extends V> f;
            if ((a = src) == null || (b = snd) == null
                    || ((r = a.result) == null && (r = b.result) == null)   // 注意这里是a.result和b.result有一个不为null就可以了
                    || (d = dep) == null || (f = fn) == null)
                return null;
            tryComplete: if(d.result == null) {
                try {
                    if(mode <= 0 && !claim())
                        return null;
                    if(r instanceof AltResult) {
                        if((x = ((AltResult)r).ex) != null) {
                            d.completeThrowable(x, r);
                            break tryComplete;
                        }
                        r = null;
                    }
                    T t = (T)r;
                    d.completeValue(f.apply(t));
                } catch (Throwable ex) {
                    d.completeThrowable(ex);
                }
            }
            src = null; snd = null; dep = null; fn = null;
            return d.postFire(a, b, mode);
        }
    }

    private <U extends T,V> CompletableFuture<V> orApplyStage(Executor e, CompletionStage<U> o, Function<? super T, ? extends V> f) {
        CompletableFuture<U> b;
        if(f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();
        Object r;
        CompletableFuture<? extends T> z;
        if((r = (z = this).result) != null || (r = (z = b).result) != null)
            return z.uniApplyNow(r, e, f);

        CompletableFuture<V> d = newIncompleteFuture();
        orpush(b, new OrApply<>(e, d, this, b, f));
        return d;
    }

    static final class OrAccept<T,U extends T> extends BiCompletion<T, U, Void> {
        Consumer<? super T> fn;

        OrAccept(Executor executor, CompletableFuture<Void> dep,
                 CompletableFuture<T> src, CompletableFuture<U> snd,
                 Consumer<? super T> fn) {
            super(executor, dep, src, snd);
            this.fn = fn;
        }

        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d;
            CompletableFuture<? extends T> a, b;
            Object r; Throwable x; Consumer<? super T> f;
            if((a = src) == null || (b = snd) == null
                    || ((r = a.result) == null && (r = b.result) == null)
                    || (d = dep) == null || (f = fn) == null)
                return null;
            tryComplete: if(d.result == null) {
                try {
                    if(mode <= 0 && !claim())
                        return null;
                    if(r instanceof AltResult) {
                        if((x = ((AltResult)r).ex) != null) {
                            d.completeThrowable(x, r);
                            break tryComplete;
                        }
                        r = null;
                    }
                    T t = (T)r;
                    f.accept(t);
                    d.completeNull();
                } catch (Throwable ex) {
                    d.completeThrowable(ex);
                }
            }
            src = null; snd = null; dep = null; fn = null;
            return d.postFire(a, b, mode);
        }
    }

    private <U extends T> CompletableFuture<Void> orAcceptStage(Executor e, CompletionStage<U> o, Consumer<? super T> f) {
        CompletableFuture<U> b;
        if(f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();

        Object r;
        CompletableFuture<? extends T> z;
        if((r = (z = this).result) != null || (r = (z = b).result) != null)
            return z.uniAcceptNow(r, e, f);

        CompletableFuture<Void> d = newIncompleteFuture();
        orpush(b, new OrAccept<>(e, d, this, b, f));
        return d;
    }

    static final class OrRun<T,U> extends BiCompletion<T,U,Void> {
        Runnable fn;
        OrRun(Executor executor, CompletableFuture<Void> dep,
              CompletableFuture<T> src, CompletableFuture<U> snd,
              Runnable fn) {
            super(executor, dep, src, snd); this.fn = fn;
        }
        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d;
            CompletableFuture<?> a, b;
            Object r; Throwable x; Runnable f;
            if((a = src) == null || (b = snd) == null
                    || ((r = a.result) == null && (r = b.result) == null)
                    || (d = dep) == null || (f = fn) == null)
                return null;
            if(d.result == null) {
                try {
                    if(mode <= 0 && !claim())
                        return null;
                    else if(r instanceof AltResult && (x = ((AltResult)r).ex) != null)
                        d.completeThrowable(x, r);
                    else {
                        f.run();
                        d.completeNull();
                    }
                } catch (Throwable ex) {
                    d.completeThrowable(ex);
                }
            }
            src = null; snd = null; dep = null; fn = null;
            return d.postFire(a, b, mode);
        }
    }

    private CompletableFuture<Void> orRunStage(Executor e, CompletionStage<?> o, Runnable f) {
        CompletableFuture<?> b;
        if(f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();

        Object r;
        CompletableFuture<?> z;
        if((r = (z = this).result) != null || (r = (z = b).result) != null)
            return z.uniRunNow(r, e, f);

        CompletableFuture<Void> d = newIncompleteFuture();
        orpush(b, new OrRun<>(e, d, this, b, f));
        return d;
    }

    /**
     * anyOf专用的Completion，用来挂到每一个Future的stack上等待最终赋值
     */
    static class AnyOf extends Completion {
        CompletableFuture<Object> dep;
        CompletableFuture<?> src;
        CompletableFuture<?>[] srcs;

        AnyOf(CompletableFuture<Object> dep, CompletableFuture<?> src, CompletableFuture<?>[] srcs) {
            this.dep = dep;
            this.src = src;
            this.srcs = srcs;
        }

        final CompletableFuture<Object> tryFire(int mode) {
            CompletableFuture<Object> d;    // dep
            CompletableFuture<?> a; // src
            CompletableFuture<?>[] as;  // srcs
            Object r;
            if ((a = src) == null || (r = a.result) == null || (d = dep) == null || (as = srcs) == null)
                return null;
            // 到这里就是src已经计算出结果了，要竞争给d赋值result（因为d会关联srcs的每个future的stack）
            src = null; dep = null; srcs = null;
            if(d.completeRelay(r)) {    // CAS赋值
                for(CompletableFuture<?> b : as) {  // 赋值成功了，清理其它future的stack
                    if(b != a)
                        b.cleanStack();
                }
                if(mode < 0)
                    return d;
                else
                    d.postComplete();
            }
            return null;
        }

        final boolean isLive() {
            CompletableFuture<Object> d;
            return (d = dep) != null && d.result == null;
        }
    }

    /* ------------- Zero-input Async forms -------------- */

    static final class AsyncSupply<T> extends ForkJoinTask<Void> implements Runnable, AsynchronousCompletionTask {
        CompletableFuture<T> dep;
        Supplier<? extends T> fn;

        AsyncSupply(CompletableFuture<T> dep, Supplier<? extends T> fn) {
            this.dep = dep;
            this.fn = fn;
        }

        public final Void getRawResult() {
            return null;
        }

        public final void setRawResult(Void v) {}

        public final boolean exec() {
            run();
            return false;
        }

        public void run() {
            CompletableFuture<T> d;
            Supplier<? extends T> f;
            if((d = dep) != null && (f = fn) != null) {
                dep = null;
                fn = null;
                if(d.result == null) {
                    try {
                        d.completeValue(f.get());
                    } catch (Throwable ex) {
                        d.completeThrowable(ex);
                    }
                }
                d.postComplete();
            }
        }
    }

    static <U> CompletableFuture<U> asyncSupplyStage(Executor e, Supplier<U> f) {
        if (f == null)
            throw new NullPointerException();
        CompletableFuture<U> d = new CompletableFuture<>();
        e.execute(new AsyncSupply<U>(d, f));
        return d;
    }

    static final class AsyncRun extends ForkJoinTask<Void> implements Runnable, AsynchronousCompletionTask {
        CompletableFuture<Void> dep;
        Runnable fn;

        AsyncRun(CompletableFuture<Void> dep, Runnable fn) {
            this.dep = dep;
            this.fn = fn;
        }

        public final Void getRawResult() {
            return null;
        }

        public final void setRawResult(Void v) {}

        public final boolean exec() {
            run();
            return false;
        }

        public void run() {
            CompletableFuture<Void> d;
            Runnable f;
            if ((d = dep) != null && (f = fn) != null) {
                dep = null;
                fn = null;
                if(d.result == null) {
                    try {
                        f.run();
                        d.completeNull();
                    } catch (Throwable ex) {
                        d.completeThrowable(ex);
                    }
                }
                d.postComplete();
            }
        }
    }

    static CompletableFuture<Void> asyncRunStage(Executor e, Runnable f) {
        if(f == null)
            throw new NullPointerException();
        CompletableFuture<Void> d = new CompletableFuture<>();
        e.execute(new AsyncRun(d, f));
        return d;
    }

    /* ------------- Signallers -------------- */

    /**
     * 等待线程的Completion实现。
     * Completion本质代表：CompletableFuture完成后要执行的动作。
     * 这个Signaller代表：CompletableFuture完成后要唤醒的线程（等待线程本身会挂到stack里面）。
     */
    static final class Signaller extends Completion implements ForkJoinPool.ManagedBlocker {
        long nanos;
        final long deadline;
        final boolean interruptible;
        boolean interrupted;
        volatile Thread thread;

        Signaller(boolean interruptible, long nanos, long deadline) {
            this.thread = Thread.currentThread();
            this.interruptible = interruptible;
            this.nanos = nanos;
            this.deadline = deadline;
        }

        final CompletableFuture<?> tryFire(int ignore) {
            Thread w;
            if((w = thread) != null) {
                thread = null;
                LockSupport.unpark(w);
            }
            return null;
        }

        public boolean isReleasable() {
            if(Thread.interrupted())
                interrupted = true;
            return ((interrupted && interruptible)
                    || (deadline != 0L && (nanos <= 0L || (nanos = deadline - System.nanoTime()) <= 0L))
                    || thread == null);
        }

        public boolean block() {
            while(!isReleasable()) {
                if(deadline == 0L)  // 不带等待时间的，就是一直阻塞，否则只阻塞给定时间
                    LockSupport.park(this);
                else
                    LockSupport.parkNanos(this, nanos);
            }
            return true;
        }

        final boolean isLive() {
            return thread != null;
        }
    }

    /**
     * 等待后返回原始计算结果，如果interruptible为true则被中断后返回null。
     */
    private Object waitingGet(boolean interruptible) {
        if(interruptible && Thread.interrupted())
            return null;
        Signaller q = null;
        boolean queued = false;
        Object r;
        while((r = result) == null) {
            if(q == null) {
                q = new Signaller(interruptible, 0L, 0L);   // 一直阻塞的版本
                /**
                 * 非常重要的特殊处理，ForkJoinPool的worker线程不能被普通阻塞，因为ForkJoinPool有work-stealing机制，
                 * 如果worker阻塞了，pool可能出现starvation，所以要执行helpAsyncBlocker方法，
                 * 允许worker在等待期间执行其它任务，这就是ForkJoinPoll的managed blocking机制。
                 */
                if(Thread.currentThread() instanceof ForkJoinWorkerThread)
                    ForkJoinPool.helpAsyncBlocker(defaultExecutor(), q);
            }else if(!queued) {
                queued = tryPushStack(q);   // 把Signaller入栈stack
            }else if(interruptible && q.interrupted) {
                q.thread = null;
                cleanStack();
                return null;
            }else {
                try {
                    /**
                     * 真正的阻塞线程，注意Signaller实现了ForkJoinPool.ManagedBlocker接口，
                     * 所以managedBlock(q)里面会调用q.isReleasable()和q.block()
                     */
                    ForkJoinPool.managedBlock(q);
                } catch (InterruptedException ie) { // currently cannot happen
                    q.interrupted = true;
                }
            }
        }
        if(q != null) {
            q.thread = null;
            if(q.interrupted)
                Thread.currentThread().interrupt();
        }
        postComplete();
        return r;
    }

    private Object timedGet(long nanos) throws TimeoutException {
        long d = System.nanoTime() + nanos;
        long deadline = (d == 0L) ? 1L : d; // avoid 0
        boolean interrupted = false;
        boolean queued = false;
        Signaller q = null;
        Object r = null;

        while(true) {
            if(interrupted || (interrupted = Thread.interrupted()))
                break;
            else if((r = result) != null)
                break;
            else if(nanos <= 0L)
                break;
            else if(q == null) {
                q = new Signaller(true, nanos, deadline);
                if(Thread.currentThread() instanceof ForkJoinWorkerThread)
                    ForkJoinPool.helpAsyncBlocker(defaultExecutor(), q);
            }else if(!queued) {
                queued = tryPushStack(q);
            }else {
                try {
                    ForkJoinPool.managedBlock(q);
                    interrupted = q.interrupted;
                    nanos = q.nanos;
                } catch (InterruptedException ie) {
                    interrupted = true;
                }
            }
        }
        if(q != null) {
            q.thread = null;
            if(r == null)
                cleanStack();
        }
        if(r != null) { // 有返回值，则返回正常值
            if(interrupted)
                Thread.currentThread().interrupt();
            postComplete();
            return r;
        }else if(interrupted)   // 被中断
            return null;
        else    // 没有被中断，也没有返回值，则抛出异常
            throw new TimeoutException();
    }

    /* ------------- public methods -------------- */

    public CompletableFuture() {}

    CompletableFuture(Object r) {
        RESULT.setRelease(this, r);
    }

    /**
     * 重要方法：最常规的使用入口
     */
    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier) {
        return asyncSupplyStage(ASYNC_POOL, supplier);
    }

    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier, Executor executor) {
        return asyncSupplyStage(screenExecutor(executor), supplier);
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return asyncRunStage(ASYNC_POOL, runnable);
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
        return asyncRunStage(screenExecutor(executor), runnable);
    }

    /**
     * 返回新的CompletableFuture，值为给定value
     */
    public static <U> CompletableFuture<U> completedFuture(U value) {
        return new CompletableFuture<>(value == null ? NIL : value);
    }

    public boolean isDone() {
        return result != null;
    }

    /**
     * 获取result，如果未完成则会等待
     */
    public T get() throws InterruptedException, ExecutionException {
        Object r;
        if((r = result) == null)
            r = waitingGet(true);   // 传的是true，即如果被中断则返回null
        /**
         * 如果被中断，则会抛出InterruptedException，如果异常，则会抛出ExecutionException
         * get是Future接口定义的方法，所以必须遵守传统约定：
         * 1、可中断
         * 2、抛checked exception
         * 3、用ExecutionException
         * 适合兼容老式Future或传统阻塞调用
         */
        return (T)reportGet(r);
    }

    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        Object r;
        if((r = result) == null)
            r = timedGet(nanos);
        return (T)reportGet(r);
    }

    /**
     * 和get的区别就是waitingGet的interruptible参数传的是false（抛CompletionException而不是Checked Exception）
     */
    public T join() {
        Object r;
        if((r = result) == null)
            /**
             * 传的是false，即等待过程中即使被中断，也不会提前退出（所以不会抛InterruptedException，但会在返回前恢复线程的中断标记）
             */
            r = waitingGet(false);

        /**
         * 与reportGet不同，会直接抛CompletionException而不是ExecutionException，
         * 更适合函数式链式风格，因为：
         * 1、不需要checked exception
         * 2、可以直接在lambda / pipeline里传播
         * 这是CompletableFuture自己提供的便捷方法，目标是：
         * 1、不写checked exception
         * 2、更适合函数式链式调用
         * 3、保留异常语义为CompletionException
         * 适合stream / lambda / async pipeline风格
         */
        return (T) reportJoin(r);
    }

    /**
     * 如果操作已完成，则返回result（可能是异常），否则返回给定的默认值。
     */
    public T getNow(T valueIfAbsent) {
        Object r;
        return ((r = result) == null) ? valueIfAbsent : (T)reportJoin(r);
    }

    /**
     * 如果未完成，则将get()或相关方法返回的值设置为给定值。
     * 最重要的方法：是整个CompletableFuture触发的起点，内部会调用postComplete从而调用stack里面Completion的tryFire方法
     */
    public boolean complete(T value) {
        boolean triggered = completeValue(value);
        postComplete();
        return triggered;
    }

    public boolean completeExceptionally(Throwable ex) {
        if (ex == null)
            throw new NullPointerException();
        boolean triggered = internalComplete(new AltResult(ex));
        postComplete();
        return triggered;
    }

    public <U> CompletableFuture<U> thenApply(Function<? super T,? extends U> fn) {
        return uniApplyStage(null, fn); // 同步调用fn，所以参数1是null
    }

    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T,? extends U> fn, Executor executor) {
        return uniApplyStage(screenExecutor(executor), fn);
    }

    public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        return uniAcceptStage(null, action);
    }

    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
        return uniAcceptStage(defaultExecutor(), action);
    }

    public CompletableFuture<Void> thenRun(Runnable action) {
        return uniRunStage(null, action);
    }

    public CompletableFuture<Void> thenRunAsync(Runnable action) {
        return uniRunStage(defaultExecutor(), action);
    }

    public CompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
        return uniRunStage(screenExecutor(executor), action);
    }

    public <U, V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other,
                                                  BiFunction<? super T,? super U,? extends V> fn) {
        return biApplyStage(null, other, fn);
    }

    public <U, V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
                                                       BiFunction<? super T,? super U,? extends V> fn) {
        return biApplyStage(defaultExecutor(), other, fn);
    }

    public <U, V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
                                                       BiFunction<? super T,? super U,? extends V> fn, Executor executor) {
        return biApplyStage(screenExecutor(executor), other, fn);
    }

    public <U> CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other,
                                                      BiConsumer<? super T, ? super U> action) {
        return biAcceptStage(null, other, action);
    }

    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
                                                           BiConsumer<? super T, ? super U> action) {
        return biAcceptStage(defaultExecutor(), other, action);
    }

    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor) {
        return biAcceptStage(screenExecutor(executor), other, action);
    }

    public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        return biRunStage(null, other, action);
    }

    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
        return biRunStage(defaultExecutor(), other, action);
    }

    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return biRunStage(screenExecutor(executor), other, action);
    }

    public <U> CompletableFuture<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return orApplyStage(null, other, fn);
    }

    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return orApplyStage(defaultExecutor(), other, fn);
    }

    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn,
                                                       Executor executor) {
        return orApplyStage(screenExecutor(executor), other, fn);
    }

    public CompletableFuture<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return orAcceptStage(null, other, action);
    }

    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return orAcceptStage(defaultExecutor(), other, action);
    }

    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action,
                                                     Executor executor) {
        return orAcceptStage(screenExecutor(executor), other, action);
    }

    public CompletableFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        return orRunStage(null, other, action);
    }

    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        return orRunStage(defaultExecutor(), other, action);
    }

    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action,
                                                       Executor executor) {
        return orRunStage(screenExecutor(executor), other, action);
    }

    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        return uniComposeStage(null, fn);
    }

    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
        return uniComposeStage(defaultExecutor(), fn);
    }

    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) {
        return uniComposeStage(screenExecutor(executor), fn);
    }

    /**
     * 与上面3个then的不同之处在于：
     * - thenApply、thenAccept和thenRun只处理上游正常完成的情况，如果上游异常，只把异常透传给下游，不执行用户函数。
     * - 而whenComplete无论上游正常还是异常，都要执行给定的BiConsumer，但执行完之后，下游结果默认要保持和上游一致，
     * 而不是用用户函数的返回值覆盖。
     * - 可以看作一个“旁路观察者”，thenXXX的核心逻辑是在tryFire里，而whenComplete的核心逻辑是在uniWhenComplete方法
     */
    public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return uniWhenCompleteStage(null, action);
    }

    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return uniWhenCompleteStage(defaultExecutor(), action);
    }

    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        return uniWhenCompleteStage(screenExecutor(executor), action);
    }

    public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        return uniHandleStage(null, fn);
    }

    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
        return uniHandleStage(defaultExecutor(), fn);
    }

    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        return uniHandleStage(screenExecutor(executor), fn);
    }

    public CompletableFuture<T> toCompletableFuture() {
        return this;
    }

    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return uniExceptionallyStage(null, fn);
    }

    public CompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn) {
        return uniExceptionallyStage(defaultExecutor(), fn);
    }

    public CompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn, Executor executor) {
        return uniExceptionallyStage(screenExecutor(executor), fn);
    }

    public CompletableFuture<T> exceptionallyCompose(Function<Throwable, ? extends CompletionStage<T>> fn) {
        return uniComposeExceptionallyStage(null, fn);
    }

    public CompletableFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> fn) {
        return uniComposeExceptionallyStage(defaultExecutor(), fn);
    }

    public CompletableFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> fn, Executor executor) {
        return uniComposeExceptionallyStage(screenExecutor(executor), fn);
    }

    /* ------------- Arbitrary-arity constructions -------------- */

    public static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs) {
        return andTree(cfs, 0, cfs.length - 1);
    }

    /**
     * 只要给定的cfs里面有一个Future计算完成，则立即采用计算结果
     * 比allOf要简单很多：
     * 1、创建一个dep future，把一个Completion挂到数组给定的每个future的stack上，
     * 2、哪个future先完成计算，则调用dep.complete(result)即可。
     */
    public static CompletableFuture<Object> anyOf(CompletableFuture<?>... cfs) {
        int n;
        Object r;
        if((n = cfs.length) <= 1) { // 如果数组里面只有0或1个CompletableFuture
            return n == 0
                    ? new CompletableFuture<>()
                    : uniCopyStage(cfs[0]);
        }

        for(CompletableFuture<?> cf : cfs) {    // 如果cfs里面已经有的算出result了，直接使用即可
            if((r = cf.result) != null)
                return new CompletableFuture<>(encodeRelay(r));
        }

        cfs = cfs.clone();
        CompletableFuture<Object> d = new CompletableFuture<>();
        for(CompletableFuture<?> cf : cfs)
            cf.unipush(new AnyOf(d, cf, cfs));
        return d;
    }

    /* ------------- Control and status methods -------------- */

    /**
     * 如果尚未完成，则使用CancellationException完成此CompletableFuture。
     * 尚未完成的依赖CompletableFutures也将异常完成，并抛出由该CancellationException引发的CompletionException。
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        // 调用internalComplete将result设置为CancellationException
        boolean cancelled = (result == null) && internalComplete(new AltResult(new java.util.concurrent.CancellationException()));
        postComplete();
        return cancelled || isCancelled();
    }

    public boolean isCancelled() {
        Object r;
        return ((r = result) instanceof AltResult && (((AltResult)r).ex instanceof CancellationException);
    }

    public boolean isCompletedExceptionally() {
        Object r;
        return ((r = result) instanceof AltResult) && r != NIL;
    }

    /**
     * 强制设置或重置方法get()及相关方法后续返回的值，无论该值是否已完成。
     * 此方法仅适用于错误恢复操作，即便在此类场景下，仍可能导致正在进行的依赖性完成操作使用已建立的结果而非覆盖后的结果。
     */
    public void obtrudeValue(T value) {
        result = (value == null) ? NIL : value;
        postComplete();
    }

    public void obtrudeException(Throwable ex) {
        if(ex == null)
            throw new NullPointerException();
        result = new AltResult(ex);
        postComplete();
    }

    /**
     * 返回估计的CompletableFutures数量，这些CompletableFutures的完成状态正在等待此CompletableFuture的完成。
     * 此方法旨在用于监控系统状态，而非用于同步控制。
     */
    public int getNumberOfDependents() {
        int count = 0;
        for(Completion p = stack; p != null; p = p.next)
            ++count;
        return count;
    }

    public String toString() {
        Object r = result;
        int count = 0;
        for(Completion p = stack; p != null; p = p.next)
            ++count;
        return super.toString() +
                ((r == null)
                        ? ((count == 0)
                        ? "[Not completed]"
                        : "[Not completed, " + count + " dependents]")
                        : (((r instanceof AltResult) && ((AltResult)r).ex != null)
                        ? "[Completed exceptionally: " + ((AltResult)r).ex + "]"
                        : "[Completed normally]"));
    }

    /**
     * 以下是JDK9新增的功能，为了补全原版工程上缺少的能力：
     * 1、超时控制不方便：例如要实现3秒超时失败，5秒超时默认值，需要自己额外搞ScheduledExecutorService
     * 所以新版增加了orTimeout、completeOnTimeout和delayedExecutor
     * 2、只想暴露CompletionStage视图：比如你只想返回给调用方一个只能调用thenApply或thenCompose的对象，不想让他们complete或obtrude
     * 所以新版增加了minimalCompletionStage和MinimalStage
     * 3、想更方便地异步不全：比如你已经有了一个CompletableFuture f，想让它异步地被一个Supplier完成
     * 所以新版增加了completeAsync
     * 4、快速构造已失败的future或stage：原版方法只有completedFuture，没有failedFuture或failedStage。
     */

    /**
     * 返回新的、未完成的CompletableFuture对象。
     * 其类型与CompletionStage方法返回的类型相同，子类通常应重写此方法，以返回与当前CompletableFuture相同类的实例。
     * 默认实现返回CompletableFuture类的实例
     *
     * 目的是为了让子类可以重写，保证链式API返回的还是子类。
     */
    public <U> CompletableFuture<U> newIncompleteFuture() {
        return new CompletableFuture<>();
    }

    /**
     * 子类扩展用
     */
    public Executor defaultExecutor() {
        return ASYNC_POOL;
    }

    /**
     * 返回一个：结果一样，但不能由外部随便complete原对象的副本视图，作用是防御性复制。
     */
    public CompletableFuture<T> copy() {
        return uniCopyStage(this);
    }

    /**
     * 返回一个只暴露CompletionStage能力的视图
     */
    public CompletionStage<T> minimalCompletionStage() {
        return uniAsMinimalStage();
    }

    /**
     * 和原版supplyAsync的区别是：
     * 1、supplyAsync意思是：创建一个新的Future，并异步执行Supplier来完成它。
     * 2、completeAsync意思是：我已经有一个future了，现在只要安排一个异步任务，将来去complete它。
     */
    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
        if(supplier == null || executor == null)
            throw new NullPointerException();
        executor.execute(new AsyncSupply<>(this, supplier));    // 注意这里dep传的是this，而不是一个新的Future
        return this;
    }

    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
        return completeAsync(supplier, defaultExecutor());
    }

    /**
     * 如果Future在指定时间还没有完成result，则result设为TimeoutException
     */
    public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
        if(unit == null)
            throw new NullPointerException();
        if(result == null)
            whenComplete(new Canceller(Delayer.delayer(new Timeout(this), timeout, unit)));
        return this;
    }

    /**
     * 和orTimeout唯一区别就是当超时了，result不是TimeoutException而是默认值
     */
    public CompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
        if(unit == null)
            throw new NullPointerException();
        if(result == null)
            whenComplete(new Canceller(Delayer.delay(new DelayedCompleter<T>(this, value), timeout, unit)));
        return this;
    }

    public static Executor delayedExecutor(long delay, TimeUnit unit, Executor executor) {
        if(unit == null || executor == null)
            throw new NullPointerException();
        return new DelayedExecutor(delay, unit, executor);
    }

    public static Executor delayedExecutor(long delay, TimeUnit unit) {
        if(unit == null)
            throw new NullPointerException();
        return new DelayedExecutor(delay, unit, ASYNC_POOL);
    }

    public static <U> CompletionStage<U> completedStage(U value) {
        return new MinimalStage<U>((value == null) ? NIL : value);
    }

    public static <U> CompletableFuture<U> failedFuture(Throwable ex) {
        if(ex == null)
            throw new NullPointerException();
        return new CompletableFuture<>(new AltResult(ex));
    }

    public static <U> CompletionStage<U> failedStage(Throwable ex) {
        if(ex == null)
            throw new NullPointerException();
        return new MinimalStage<>(new AltResult(ex));
    }

    /**
     * 基于单例的延迟调度器，仅用于启动和取消任务。
     * 即负责过一段时间触发某个Runnable
     */
    static final class Delayer {
        static final ScheduledThreadPoolExecutor delayer;

        static final class DaemonThreadFactory implements ThreadFactory {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("CompletableFutureDelayScheduler");
                return t;
            }
        }

        static {
            (delayer = new ScheduledThreadPoolExecutor(1,
                                                     new DaemonThreadFactory())).setRemoveOnCancelPolicy(true);
        }

        static ScheduledFuture<?> delay(Runnable command, long delay, TimeUnit unit) {
            return delayer.schedule(command, delay, unit);
        }
    }

    /**
     * 小型类化lambda表达式，以更好地支持监控。
     * 内部使用的是上面的Delayer
     */
    static final class DelayedExecutor implements Executor {
        final long delay;
        final TimeUnit unit;
        final Executor executor;

        DelayedExecutor(long delay, TimeUnit unit, Executor executor) {
            this.delay = delay;
            this.unit = unit;
            this.executor = executor;
        }

        public void execute(Runnable r) {
            Delayer.delay(new TaskSubmitter(executor, r), delay, unit);
        }
    }

    /**
     * 提交用户任务的操作封装
     */
    static final class TaskSubmitter implements Runnable {
        final Executor executor;
        final Runnable action;

        TaskSubmitter(Executor executor, Runnable action) {
            this.executor = executor;
            this.action = action;
        }

        public void run() {
            executor.execute(action);
        }
    }

    /**
     * 如果给定的Future未完成，则在result写入TimeoutException的任务
     * 职责是将给定的CompletableFuture的result设置TimeoutException
     */
    static final class Timeout implements Runnable {
        final CompletableFuture<?> f;

        Timeout(CompletableFuture<?> f) {
            this.f = f;
        }

        public void run() {
            if(f != null && !f.isDone())
                f.completeExceptionally(new java.util.concurrent.TimeoutException());
        }
    }

    /**
     * 将给定的Future的result写入特定值，
     */
    static final class DelayedCompleter<U> implements Runnable {
        final CompletableFuture<U> f;
        final U u;

        DelayedCompleter(CompletableFuture<U> f, U u) {
            this.f = f;
            this.u = u;
        }

        public void run() {
            if(f != null)
                f.complete(u);
        }
    }

    /**
     * 用来取消Timeout任务的Future，注意只有上游正常完成才会调用这里面的accept，超时未完成则会触发future里面的Timeout动作。
     */
    static final class Canceller implements BiConsumer<Object, Throwable> {
        final Future<?> f;

        Canceller(Future<?> f) {
            this.f = f;
        }

        public void accept(Object ignore, Throwable ex) {
            /**
             * 重点逻辑：当进入到这个accept时候，说明上游result已经算出来了，不应该再写TimeoutException了
             * 如果Future还没执行（注意这个Future指的是负责给result写TimeoutException的任务），
             * 就要把它给cancel（不然调了也是白调，因为result的值已经定下来了）
             */
            if(ex == null && f != null && !f.isDone())
                f.cancel(false);
        }
    }

    /**
     * 只能调用CompletionStage的方法，CompletableFuture中不应调用的方法全部重写了
     */
    static final class MinimalStage<T> extends CompletableFuture<T> {
        MinimalStage() {}

        MinimalStage(Object r) {
            super(r);
        }

        @Override
        public <U> CompletableFuture<U> newIncompleteFuture() {
            return new MinimalStage<>();
        }
        @Override
        public T get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public T getNow(T valueIfAbsent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public T join() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean complete(T value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void obtrudeValue(T value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void obtrudeException(Throwable ex) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isDone() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isCancelled() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isCompletedExceptionally() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getNumberOfDependents() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletableFuture<T> toCompletableFuture() {
            Object r;
            if((r = result) != null)
                return new CompletableFuture<T>(encodeRelay(r));
            else {
                CompletableFuture<T> d = new CompletableFuture<>();
                unipush(new UniRelay<>(d, this));
                return d;
            }
        }
    }

    // VarHandle mechanics
    private static final VarHandle RESULT;
    private static final VarHandle STACK;
    private static final VarHandle NEXT;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            RESULT = l.findVarHandle(CompletableFuture.class, "result", Object.class);
            STACK = l.findVarHandle(CompletableFuture.class, "stack", Completion.class);
            NEXT = l.findVarHandle(Completion.class, "next", Completion.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }

        // Reduce the risk of rare disastrous classloading in first call to
        // LockSupport.park: https://bugs.openjdk.java.net/browse/JDK-8074773
        Class<?> ensureLoaded = LockSupport.class;
    }
}

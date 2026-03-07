package com.github.liyibo1110.jdk.java.util.concurrent;

import javax.annotation.processing.Completion;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

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

    /** 重要字段：保存这个Completion依赖的任务（又一个Treiber stack） */
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

    private static <U, T extends U> CompletableFuture<U> uniCopyStage(CompletableFuture<T> src) {
        Object r;
        CompletableFuture<U> d = src.newIncompleteFuture();
        if((r = src.result) != null)
            d.result = encodeRelay(r);
        else
            src.unipush(new UniRelay<>(d, src));
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

    /* ------------- Zero-input Async forms -------------- */

    /* ------------- Signallers -------------- */

    /* ------------- public methods -------------- */

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

    // jdk9 additions

    /**
     * 返回新的、未完成的CompletableFuture对象。
     * 其类型与CompletionStage方法返回的类型相同，子类通常应重写此方法，以返回与当前CompletableFuture相同类的实例。
     * 默认实现返回CompletableFuture类的实例
     */
    public <U> CompletableFuture<U> newIncompleteFuture() {
        return new CompletableFuture<>();
    }

    public Executor defaultExecutor() {
        return ASYNC_POOL;
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

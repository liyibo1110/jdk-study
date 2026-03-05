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
         * 如果被触发，则执行完成操作，并返回可能需要传播的依赖项（如果存在）。
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

    final void postComplete() {

    }

    final void cleanStack() {

    }

    /* ------------- One-input Completions -------------- */














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

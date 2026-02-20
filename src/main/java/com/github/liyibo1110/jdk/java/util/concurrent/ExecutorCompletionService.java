package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;

/**
 * 使用指定的executor执行任务的CompletionService实现。
 * 该类确保提交的任务在完成后被放入队列，可通过take方法获取。
 * 该类足够轻量，适用于处理任务组时的临时使用场景。
 * @author liyibo
 * @date 2026-02-20 18:42
 */
public class ExecutorCompletionService<V> implements CompletionService<V> {
    private final Executor executor;
    private final AbstractExecutorService aes;
    private final BlockingQueue<Future<V>> completionQueue;

    public ExecutorCompletionService(Executor executor) {
        if(executor == null)
            throw new NullPointerException();
        this.executor = executor;
        this.aes = executor instanceof AbstractExecutorService
                ? (AbstractExecutorService)executor : null;
        this.completionQueue = new LinkedBlockingQueue<>();
    }

    public ExecutorCompletionService(Executor executor,
                                     BlockingQueue<Future<V>> completionQueue) {
        if(executor == null || completionQueue == null)
            throw new NullPointerException();
        this.executor = executor;
        this.aes = (executor instanceof AbstractExecutorService)
                ? (AbstractExecutorService) executor : null;
        this.completionQueue = completionQueue;
    }

    private RunnableFuture<V> newTaskFor(Callable<V> task) {
        if(aes == null)
            return new FutureTask<>(task);
        else
            return aes.newTaskFor(task);
    }

    private RunnableFuture<V> newTaskFor(Runnable task, V result) {
        if(aes == null)
            return new FutureTask<>(task, result);
        else
            return aes.newTaskFor(task, result);
    }

    @Override
    public Future<V> submit(Callable<V> task) {
        if(task == null)
            throw new NullPointerException();
        RunnableFuture<V> f = this.newTaskFor(task);
        executor.execute(new QueueingFuture<V>(f, completionQueue));
        return f;
    }

    @Override
    public Future<V> submit(Runnable task, V result) {
        if(task == null)
            throw new NullPointerException();
        RunnableFuture<V> f = this.newTaskFor(task, result);
        executor.execute(new QueueingFuture<V>(f, completionQueue));
        return f;
    }

    @Override
    public Future<V> take() throws InterruptedException {
        return completionQueue.take();
    }

    @Override
    public Future<V> poll() {
        return completionQueue.poll();
    }

    @Override
    public Future<V> poll(long timeout, TimeUnit unit) throws InterruptedException {
        return completionQueue.poll(timeout, unit);
    }

    private static class QueueingFuture<V> extends FutureTask<Void> {
        private final Future<V> task;
        private final BlockingQueue<Future<V>> completionQueue;

        QueueingFuture(RunnableFuture<V> task, BlockingQueue<Future<V>> completionQueue) {
            super(task, null);
            this.task = task;
            this.completionQueue = completionQueue;
        }

        /**
         * 重写了hook方法，计算完成后直接加入到完成队列里
         */
        @Override
        protected void done() {
            completionQueue.add(task);
        }
    }
}

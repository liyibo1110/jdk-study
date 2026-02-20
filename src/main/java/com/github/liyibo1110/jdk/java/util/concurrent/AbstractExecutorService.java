package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;

/**
 * 提供ExecutorService执行方法的默认实现。
 * 通过newTaskFor返回的RunnableFuture实现了submit、invokeAny和invokeAll方法，默认使用本包提供的FutureTask类。
 * 例如：submit(runnable)实现会创建关联的RunnableFuture并执行后返回。
 * 子类可以重写newTaskFor方法，以返回FutureTask之外的RunnableFuture实现。
 * @author liyibo
 * @date 2026-02-19 21:34
 */
public abstract class AbstractExecutorService implements ExecutorService {

    public AbstractExecutorService() {}

    /**
     * 返回一个用于给定Runnable对象和默认值的RunnableFuture。
     */
    protected <T> RunnableFuture<T> newTaskFor(Runnable r, T value) {
        return new FutureTask<>(r, value);
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new FutureTask<>(callable);
    }

    @Override
    public Future<?> submit(Runnable task) {
        if(task == null)
            throw new NullPointerException();
        RunnableFuture<Void> ftask = this.newTaskFor(task, null);
        execute(ftask);
        return ftask;
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        if(task == null)
            throw new NullPointerException();
        RunnableFuture<T> ftask = this.newTaskFor(task, result);
        execute(ftask);
        return ftask;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        if(task == null)
            throw new NullPointerException();
        RunnableFuture<T> ftask = newTaskFor(task);
        execute(ftask);
        return ftask;
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        try {
            return this.doInvokeAny(tasks, false, 0);
        } catch (TimeoutException cannotHappen) {
            assert false;
            return null;
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return this.doInvokeAny(tasks, true, unit.toNanos(timeout));
    }

    private <T> T doInvokeAny(Collection<? extends Callable<T>> tasks, boolean timed, long nanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        if(tasks == null)
            throw new NullPointerException();
        int ntasks = tasks.size();
        if (ntasks == 0)
            throw new IllegalArgumentException();
        ArrayList<Future<T>> futures = new ArrayList<>(ntasks);
        ExecutorCompletionService<T> ecs = new ExecutorCompletionService<T>(this);

        try {
            ExecutionException ee = null;
            final long deadline = timed ? System.nanoTime() + nanos : 0L;   // 到期时间戳
            Iterator<? extends Callable<T>> iter = tasks.iterator();

            futures.add(ecs.submit(iter.next()));   // 先放一个开始运行
            --ntasks;
            int active = 1;

            for(; ;) {
                Future<T> f = ecs.poll();   // 尝试取1个
                if(f == null) { // 没有完成
                    if(ntasks > 0) {
                        --ntasks;
                        futures.add(ecs.submit(iter.next()));   // 再放一个
                        ++active;
                    }else if(active == 0) {
                        break;
                    }else if(timed) {
                        f = ecs.poll(nanos, TimeUnit.NANOSECONDS);
                        if(f == null)
                            throw new TimeoutException();
                        nanos = deadline - System.nanoTime();
                    }else { // 只有1个非限时的任务了，直接阻塞等待
                        f = ecs.take();
                    }
                }
                if(f != null) { // 有任务执行完了
                    --active;
                    try {
                        return f.get();
                    } catch (ExecutionException eex) {
                        ee = eex;
                    } catch (RuntimeException rex) {
                        ee = new ExecutionException(rex);
                    }
                }
            }

            if(ee == null)
                ee = new ExecutionException();  // 构造方式是protected的，所以在这个自定义类里面找不到
            throw ee;
        } finally {
            cancelAll(futures);
        }
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        if(tasks == null)
            throw new NullPointerException();
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());
        try {
            // 直接提交所有执行，保留生成的Future
            for(Callable<T> t : tasks) {
                RunnableFuture<T> f = this.newTaskFor(t);
                futures.add(f);
                execute(f);
            }
            for(int i = 0, size = futures.size(); i < size; i++) {
                Future<T> f = futures.get(i);
                if(!f.isDone()) {
                    try {
                        f.get();    // 进入阻塞等待
                    } catch (CancellationException | ExecutionException ignore) {
                        // nothing to do
                    }
                }
            }
            return futures;
        } catch (Throwable t) {
            cancelAll(futures);
            throw t;
        }
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        if(tasks == null)
            throw new NullPointerException();
        final long nanos = unit.toNanos(timeout);
        final long deadline = System.nanoTime() + nanos;
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());
        int j = 0;
        timedOut: try {
            for(Callable<T> t : tasks)
                futures.add(this.newTaskFor(t));
            final int size = futures.size();

            for(int i = 0; i < size; i++) {
                if(((i == 0) ? nanos : deadline - System.nanoTime()) <= 0L) // 提交阶段有可能就已经超时了
                    break timedOut;
                execute((Runnable)futures.get(i));
            }

            for(; j < size; j++) {
                Future<T> f = futures.get(j);
                if(!f.isDone()) {
                    try {
                        f.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    } catch (CancellationException | ExecutionException ignore) {
                        // nothing to do
                    } catch (TimeoutException timedOut) {
                        break timedOut; // 超时了就会跳到方法最后的cancelAll了
                    }
                }
            }
            return futures;
        } catch (Throwable t) {
            cancelAll(futures);
            throw t;
        }
        cancelAll(futures, j);
        return futures;
    }


    private static <T> void cancelAll(ArrayList<Future<T>> futures) {
        cancelAll(futures, 0);
    }

    private static <T> void cancelAll(ArrayList<Future<T>> futures, int j) {
        for(int size = futures.size(); j < size; j++)
            futures.get(j).cancel(true);
    }
}


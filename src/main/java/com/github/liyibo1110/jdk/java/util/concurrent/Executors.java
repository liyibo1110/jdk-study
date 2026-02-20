package com.github.liyibo1110.jdk.java.util.concurrent;

import java.lang.ref.Reference;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本包中定义的Executor、ExecutorService、ScheduledExecutorService、ThreadFactory、Callable类的工厂方法和实用方法。
 * 该类支持以下类型的方法：
 * 1、创建并返回一个已配置常用实用设置的ExecutorService的方法。
 * 2、创建并返回一个预先配置了常用设置的ScheduledExecutorService的方法。
 * 3、创建并返回一个“封装”的ExecutorService的方法，通过使实现特定的方法不可访问来禁用重新配置。
 * 4、创建并返回一个ThreadFactory方法，该工厂将新建线程设置为已知状态。
 * 5、将其他闭包样式形式转换为可调用对方并返回的方法，以便在需要可调用对象的执行方法中使用。
 * @author liyibo
 * @date 2026-02-20 00:47
 */
public class Executors {

    /* 以上即为各种线程池的工厂方法了，下面的都学完了 */

    public static ExecutorService unconfigurableExecutorService(ExecutorService executor) {
        if(executor == null)
            throw new NullPointerException();
        return new DelegatedExecutorService(executor);
    }

    /**
     * 返回一个对象，其将所有定义的ScheduledExecutorService方法委托给执行的executor，但不会委托其他可能通过强制转换访问的方法。
     * 这提供了一种安全“冻结”配置并禁止调整特定具体实现的方式。
     */
    public static ScheduledExecutorService unconfigurableScheduledExecutorService(ScheduledExecutorService executor) {
        if(executor == null)
            throw new NullPointerException();
        return new DelegatedScheduledExecutorService(executor);
    }

    /**
     * 返回一个用于创建新线程的默认线程工厂。
     * 其创建的所有新线程都将由executor在同一个线程组中使用。
     * 若存在安全管理器，则使用System.getSecurityManager的组，否则使用调用此defaultThreadFactory方法的线程所属组。
     * 每个新线程均作为非守护线程创建，其优先级设置为Thread.NORM_PRIORITY与该线程组允许的最高优先级中的较小值。
     * 新线程的名称可通过Thread.getName访问，格式为pool-N-thread-M，其中N表示该factory的序号，M表示该factory创建的线程序号。
     */
    public static ThreadFactory defaultThreadFactory() {
        return new DefaultThreadFactory();
    }

    /**
     * 返回一个Callable对象，调用时会执行给定的Runnable并返回给定的result。
     * 这在将需要Callable的方法，应用于原本无结果的操作时非常有用
     * （注：教科书级别的adapter模式）
     */
    public static <T> java.util.concurrent.Callable<T> callable(Runnable task, T result) {
        if(task == null)
            throw new NullPointerException();
        return new RunnableAdapter<T>(task, result);
    }

    public static java.util.concurrent.Callable<Object> callable(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        return new RunnableAdapter<Object>(task, null);
    }

    public static java.util.concurrent.Callable<Object> callable(final PrivilegedAction<?> action) {
        if(action == null)
            throw new NullPointerException();
        return () -> action.run();
    }

    public static java.util.concurrent.Callable<Object> callable(final PrivilegedExceptionAction<?> action) {
        if(action == null)
            throw new NullPointerException();
        return () -> action.run();
    }

    /**
     * 一个Callable实现，用于执行指定的Runnable并返回指定result
     */
    private static final class RunnableAdapter<T> implements Callable<T> {
        private final Runnable task;
        private final T result;

        RunnableAdapter(Runnable task, T result) {
            this.task = task;
            this.result = result;
        }

        @Override
        public T call() {
            task.run();
            return result;
        }

        public String toString() {
            return super.toString() + "[Wrapped task = " + task + "]";
        }
    }

    /**
     * ThreadFactory的默认实现
     */
    private static class DefaultThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        DefaultThreadFactory() {
            SecurityManager s = System.getSecurityManager();
            group = (s != null)
                    ? s.getThreadGroup()
                    : Thread.currentThread().getThreadGroup();
            namePrefix = "pool-" + poolNumber.getAndIncrement() + "-thread-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
            if(t.isDaemon())
                t.setDaemon(false);
            if(t.getPriority() != Thread.NORM_PRIORITY)
                t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    /**
     * 一个封装类，仅暴露了ExecutorService实现中的ExecutorService方法。
     */
    private static class DelegatedExecutorService implements ExecutorService {
        private final ExecutorService e;

        DelegatedExecutorService(ExecutorService executor) {
            e = executor;
        }

        @Override
        public void execute(Runnable command) {
            try {
                e.execute(command);
            } finally {
                // 保证不会被GC（可能是，没有深入学习）
                Reference.reachabilityFence(this);
            }
        }

        @Override
        public void shutdown() {
            e.shutdown();
        }

        public List<Runnable> shutdownNow() {
            try {
                return e.shutdownNow();
            } finally {
                Reference.reachabilityFence(this);
            }
        }

        public boolean isShutdown() {
            try {
                return e.isShutdown();
            } finally {
                Reference.reachabilityFence(this);
            }
        }

        public boolean isTerminated() {
            try {
                return e.isTerminated();
            } finally {
                Reference.reachabilityFence(this);
            }
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                return e.awaitTermination(timeout, unit);
            } finally {
                Reference.reachabilityFence(this);
            }
        }

        public Future<?> submit(Runnable task) {
            try {
                return e.submit(task);
            } finally {
                Reference.reachabilityFence(this);
            }
        }

        public <T> Future<T> submit(Callable<T> task) {
            try {
                return e.submit(task);
            } finally {
                Reference.reachabilityFence(this);
            }
        }

        public <T> Future<T> submit(Runnable task, T result) {
            try {
                return e.submit(task, result);
            } finally {
                Reference.reachabilityFence(this);
            }
        }

        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            try {
                return e.invokeAll(tasks);
            } finally {
                Reference.reachabilityFence(this);
            }
        }
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException {
            try {
                return e.invokeAll(tasks, timeout, unit);
            } finally {
                Reference.reachabilityFence(this);
            }
        }
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
            try {
                return e.invokeAny(tasks);
            } finally {
                Reference.reachabilityFence(this);
            }
        }
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            try {
                return e.invokeAny(tasks, timeout, unit);
            } finally {
                Reference.reachabilityFence(this);
            }
        }
    }

    private static class FinalizableDelegatedExecutorService extends DelegatedExecutorService {
        FinalizableDelegatedExecutorService(ExecutorService executor) {
            super(executor);
        }

        @SuppressWarnings("deprecation")
        protected void finalize() {
            super.shutdown();
        }
    }

    private static class DelegatedScheduledExecutorService extends DelegatedExecutorService implements ScheduledExecutorService {
        private final ScheduledExecutorService e;

        DelegatedScheduledExecutorService(ScheduledExecutorService executor) {
            super(executor);
            e = executor;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return e.schedule(command, delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(java.util.concurrent.Callable<V> callable, long delay, TimeUnit unit) {
            return e.schedule(callable, delay, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            return e.scheduleAtFixedRate(command, initialDelay, period, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            return e.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        }
    }

    private Executors() {}
}

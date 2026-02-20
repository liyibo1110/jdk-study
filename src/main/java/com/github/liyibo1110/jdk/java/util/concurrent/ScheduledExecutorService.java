package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 可以将command安排在指定延迟后运行，或周期性执行的ExecutorService扩展。
 * 其调度方法可创建具有不同延迟的任务，并返回可用于取消或检查执行的task对象。
 * scheduleAtFixedRate和scheduleWithFixedDelay方法创建并执行周期性运行的任务，直至被取消。
 *
 * 使用Executor.execute(runnable)和ExecutorService.submit方法提交的command将以零延迟进行调度。
 * 在调度方法中，零延迟和负延迟（但不包括周期运行）也是允许的，并将被视为立即执行的请求。
 *
 * 所有调度方法均接受相对延迟和周期作为参数，而非绝对时间或日期，但由于网络时间同步协议、时钟飘移等因素，相对延迟的到期时间未必与任务启动的当前日期完全一致。
 * Executors类为本包提供的ScheduledExecutorService实现类提供了便捷的工厂方法。
 * @author liyibo
 * @date 2026-02-20 01:23
 */
public interface ScheduledExecutorService extends ExecutorService {

    /**
     * 提交一个一次性的Runnable，将在指定延迟后启动
     */
    ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);

    <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit);

    /**
     * 提交一个周期性Runnable，在经过指定初始延迟后首次启用，之后按指定周期重复执行。
     * 任务将无限期持续，直到发生以下异常完成情况之一：
     * 1、通过返回的ScheduledFuture显式cancel任务。
     * 2、Executor终止，同样导致Runnable取消。
     * 3、Runnable执行过程中抛出异常，此时调用ScheduledFuture的get方法将抛出ExecutionException，并将该异常作为cause。
     * 后续执行将被抑制，后续对ScheduledFuture调用isDone方法将返回true。
     * 注意：若该任务的任何执行耗时超过其period，后续执行可能延迟启动，但不会并发执行。
     */
    ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);

    /**
     * 提交一个周期性Runnable，在经过指定初始延迟后首次启动，随后在每次执行结束与下次执行开始之间保持给定delay。
     * 和上面scheduleAtFixedRate方法区别就是，这个delay是在前一次Runnable执行完毕后才计时，而scheduleAtFixedRate时Runnable启动就开始计时
     */
    ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);
}

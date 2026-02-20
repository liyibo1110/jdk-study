package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author liyibo
 * @date 2026-02-19 18:17
 */
public interface ExecutorService extends Executor {

    /**
     * 启动有序关闭功能，在此期间会执行之前已提交的任务，但不会接受新任务。
     * 如果已关闭，则调用不会产生任何额外效果。
     * 此方法不会等待先前提交的任务完成执行，如要等待，请使用awaitTermination
     */
    void shutdown();

    /**
     * 尝试停止所有正在执行的任务，暂停等待任务的处理，并返回一个等待执行的任务列表。
     * 此方法不会等待正在执行的任务终止，如果需要等待，请使用awaitTermination。
     * 除了尽最大努力尝试停止处理正在执行的任务外，并无其他保证。
     * 例如典型的实现方式是通过Thread.interrupt来取消任务，因此任何未能响应中断的任务可能永远无法终止。
     */
    List<Runnable> shutdownNow();

    /**
     * executor是否已被shutdown
     */
    boolean isShutdown();

    /**
     * 如果在shutdown后所有任务都已完成，则返回true。
     * 请注意，除非先调用shutdown或shutdownNow，否则isTerminated永远不会为true。
     */
    boolean isTerminated();

    /**
     * 在收到关闭请求、超时或当前线程被中断前，一直阻塞，直到所有任务都执行完毕
     */
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * 提交一个callable，并返回一个表示该任务待处理结果的Future。
     * 当任务成功完成时，Future的get方法将返回任务结果。
     * 注意：Executors类提供了一组方法，可以将常见的闭包类对象（例如java.security.PrivilegedAction）转换为Callable形式以便提交执行。
     */
    <T> Future<T> submit(Callable<T> task);

    /**
     * 当task完成，get方法将返回指定的result结果
     */
    <T> Future<T> submit(Runnable task, T result);

    /**
     * 提交一个Runnable，当任务完成时get方法将返回null
     */
    Future<?> submit(Runnable task);

    /**
     * 执行指定tasks，并在所有任务完成时返回包含其状态和结果的Future列表。
     * 返回列表中的每个元素的isDone属性均为true。
     * 注意：已完成的任务可能正常终止，也可能因抛出异常而终止，
     * 如果在操作进行期间修改了给定集合，则该方法的结果将无法定义。
     */
    <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException;

    /**
     * 执行指定tasks，并在所有任务完成或超时（以先发生者为准）时返回包含任务状态和结果的Future列表。
     * 返回列表中的每个元素的isDone属性均为true。
     * 返回时，未完成的任务将被取消。
     * 注意：已完成的任务可能通过正常终止或抛出异常终止。
     * 如果在操作进行期间修改了给定集合，则该方法的结果将无法定义。
     */
    <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * 执行指定tasks，若存在成功完成（即未抛出异常）的任务，则返回其结果。
     * 无论正常返回还是异常返回，未完成的任务均被取消。
     */
    <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException;

    <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException;
}

package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 一种将新异步任务的生产，与已完成任务结果的消费解耦的服务。
 * 生产者提交待执行的任务，消费者则按任务完成顺序获取已完成任务并处理其结果。
 * 例如，完成服务可用于管理异步I/O操作：
 * 读取任务在程序或系统某部分提交，待读取完成后在另一部分进行处理，此时任务处理顺序可能与请求顺序不同
 * @author liyibo
 * @date 2026-02-20 18:03
 */
public interface CompletionService<V> {

    /**
     * 提交一个Callable，并返回Future。
     */
    Future<V> submit(Callable<V> task);

    Future<V> submit(Runnable task, V result);

    /**
     * 检索并移除下一个已完成任务的Future对象，若尚无任务则进入阻塞等待状态。
     */
    Future<V> take() throws InterruptedException;

    /**
     * 检索并移除下一个已完成任务的Future对象，若尚无任务则返回null。
     */
    Future<V> poll();

    Future<V> poll(long timeout, TimeUnit unit) throws InterruptedException;
}

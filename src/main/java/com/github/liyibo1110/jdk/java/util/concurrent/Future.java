package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 表示异步结算的结果，提供的方法用于检查计算是否完成、等待计算完成以及获取计算结果。
 * 只有在计算完成后才能通过get获取结果，必要时该方法会阻塞直到计算完成。
 * 取消通过cancel执行，另提供方法判断任务是正常完成还是被取消，计算一旦完成即不可取消。
 * 若要使用Future实现可去小心但不提供可用结果，可以声明Future<?>形式的类型，并将任务结果设为null。
 * @author liyibo
 * @date 2026-02-19 21:24
 */
public interface Future<V> {

    /**
     * 尝试取消此任务执行，若已完成、已被取消、或因其他原因无法取消，此方法无效，否则若调用取消时任务尚未启动，则任务永远不会执行。
     * 若任务已启动，则mayInterruptIfRunning参数决定是否中断执行该任务的线程，以尝试停止任务。
     * 该方法的返回值未必能表明任务是否已被取消，请使用isCancelled方法确认。
     */
    boolean cancel(boolean mayInterruptIfRunning);

    /**
     * 如果任务在正常完成前被cancel，返回true。
     */
    boolean isCancelled();

    /**
     * 如果任务已完成，则返回true。
     * 完成可能由于正常终止、异常或取消，所有上述情况下，都会返回true。
     */
    boolean isDone();

    /**
     * 等待计算完成，然后返回结果。
     */
    V get() throws InterruptedException, ExecutionException;

    /**
     * 最多等待指定的时间以完成计算，然后获取结果。
     */
    V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException;
}

package com.github.liyibo1110.jdk.java.util.concurrent;

/**
 * 一个可运行的ScheduledFuture。
 * 成功执行run方法将导致该Future完成，并允许访问其结果。
 * @author liyibo
 * @date 2026-02-20 12:52
 */
public interface RunnableScheduledFuture<V> extends RunnableFuture<V>, ScheduledFuture<V> {

    /**
     * 如果该Runnable是周期性的，则返回true。
     * 周期性Runnable可能根据某些计划重新运行，而非周期性Runnable只能运行一次。
     */
    boolean isPeriodic();
}

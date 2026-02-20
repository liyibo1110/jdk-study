package com.github.liyibo1110.jdk.java.util.concurrent;

/**
 * 一种延迟生效且可取消的操作。
 * 通常通过ScheduledExecutorService安排task后，其结果即为预定的未来事件。
 * @author liyibo
 * @date 2026-02-20 01:30
 */
public interface ScheduledFuture<V> extends Delayed, Future<V> {

}

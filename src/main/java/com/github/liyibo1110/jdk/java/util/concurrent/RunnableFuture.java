package com.github.liyibo1110.jdk.java.util.concurrent;

/**
 * Runnable对应的Future，成功执行run方法将导致Future完成，并允许访问其结果。
 * @author liyibo
 * @date 2026-02-19 21:39
 */
public interface RunnableFuture<V> extends Runnable, Future<V> {

    void run();
}

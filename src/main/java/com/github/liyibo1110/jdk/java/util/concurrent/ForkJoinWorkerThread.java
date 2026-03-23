package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.concurrent.ForkJoinPool;

/**
 * @author liyibo
 * @date 2026-03-22 22:27
 */
public class ForkJoinWorkerThread extends Thread {
    final ForkJoinPool pool;
    final ForkJoinPool.WorkQueue workQueue;

    public ForkJoinPool getPool() {
        return pool;
    }

    public int getPoolIndex() {
        return workQueue.getPoolIndex();
    }

    protected void onStart() {}

    protected void onTermination(Throwable exception) {}
}

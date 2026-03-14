package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * 一次性门闩，不可复用，如要循环同步应该使用CyclicBarrier。
 * 主线程调用await方法进入阻塞，worker线程完成后调用countDown减少state，降为0主线程的await结束阻塞继续执行。
 *
 * 代码结构很简单，就是基于AQS的shared模式，state表示剩余的任务数。
 * 用shared模式是允许有多个线程同时调用await，当state=0时会全部被唤醒。
 * @author liyibo
 * @date 2026-03-13 15:08
 */
public class CountDownLatch {

    private static final class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 4982264981922014374L;

        Sync(int count) {
            setState(count);
        }

        int getCount() {
            return getState();
        }

        /**
         * 主线程调用await时会调用这个方法，返回true则说明抢到锁，可以继续执行await后面的逻辑了。
         */
        protected int tryAcquireShared(int acquires) {
            return (getState() == 0) ? 1 : -1;
        }

        /**
         * worker线程调用countDown时会调用这个方法，用来降低state值。
         * 返回true说明state已经被减少到0了。
         */
        protected boolean tryReleaseShared(int releases) {
            while(true) {
                int c = getState();
                if(c == 0)
                    return false;
                int nextc = c - 1;
                if(compareAndSetState(c, nextc))
                    return nextc == 0;
            }
        }
    }

    private final Sync sync;

    public CountDownLatch(int count) {
        if(count < 0)
            throw new IllegalArgumentException("count < 0");
        this.sync = new Sync(count);
    }

    public void await() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);   // 会调用tryAcquireShared
    }

    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }

    public void countDown() {
        sync.releaseShared(1);  // 会调用tryReleaseShared
    }

    public long getCount() {
        return sync.getCount();
    }

    public String toString() {
        return super.toString() + "[Count = " + sync.getCount() + "]";
    }
}

package com.github.liyibo1110.jdk.java.util.concurrent;

import java.io.Serializable;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * 用来限制同时访问某个资源的线程数量，属于资源控制器，state（permits）表示最多允许多少个线程访问某个资源
 * @author liyibo
 * @date 2026-03-13 16:12
 */
public class Semaphore implements Serializable {
    private static final long serialVersionUID = -3222578661600680210L;
    private final Sync sync;

    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 1192457210091910933L;

        Sync(int permits) {
            setState(permits);
        }

        final int getPermits() {
            return getState();
        }

        /**
         * 重点在于要了解返回值的含义：
         * 返回值 >= 0代表获取成功
         * 返回值 < 0代表获取失败
         */
        final int nonfairTryAcquireShared(int acquires) {
            while(true) {
                int available = getState();
                int remaining = available - acquires;
                // 注意如果remaining超额了，根本不会执行后面的compareAndSetState，会直接返回负值
                if(remaining < 0 || compareAndSetState(available, remaining))
                    return remaining;
            }
        }

        protected final boolean tryReleaseShared(int releases) {
            // 一直循环直到把releases加回去就完事了
            while(true) {
                int current = getState();
                int next = current + releases;
                if(next < current)  // 溢出了
                    throw new Error("Maximum permit count exceeded");
                if(compareAndSetState(current, next))
                    return true;
            }
        }

        /**
         * 和tryReleaseShared逻辑几乎一样，只是变成了减少state
         */
        final void reducePermits(int reductions) {
            while(true) {
                int current = getState();
                int next = current - reductions;
                if(next > current) // underflow
                    throw new Error("Permit count underflow");
                if(compareAndSetState(current, next))
                    return;
            }
        }

        /**
         * 将state直接改成0，并返回之前的旧state值。
         */
        final int drainPermits() {
            while(true) {
                int current = getState();
                if(current == 0 || compareAndSetState(current, 0))
                    return current;
            }
        }
    }

    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -2694183684443567898L;

        NonfairSync(int permits) {
            super(permits);
        }

        protected int tryAcquireShared(int acquires) {
            return nonfairTryAcquireShared(acquires);
        }
    }

    static final class FairSync extends Sync {
        private static final long serialVersionUID = 2014338818796000944L;

        FairSync(int permits) {
            super(permits);
        }

        protected int tryAcquireShared(int acquires) {
            while(true) {
                if(hasQueuedPredecessors()) // 因为是公平模式，前面有排队的就压根不能抢
                    return -1;
                // 可以抢了
                int available = getState();
                int remaining = available - acquires;
                // 这里和nonfairTryAcquireShared方法没什么区别，返回负数就是没抢到
                if(remaining < 0 || compareAndSetState(available, remaining))
                    return remaining;
            }
        }
    }

    public Semaphore(int permits) {
        sync = new NonfairSync(permits);
    }

    public Semaphore(int permits, boolean fair) {
        sync = fair ? new FairSync(permits) : new NonfairSync(permits);
    }

    public void acquire() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }

    public void acquireUninterruptibly() {
        sync.acquireShared(1);
    }

    public boolean tryAcquire() {
        return sync.nonfairTryAcquireShared(1) >= 0;
    }

    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }

    public void release() {
        sync.releaseShared(1);
    }

    public void acquire(int permits) throws InterruptedException {
        if(permits < 0)
            throw new IllegalArgumentException();
        sync.acquireSharedInterruptibly(permits);
    }

    public void acquireUninterruptibly(int permits) {
        if(permits < 0)
            throw new IllegalArgumentException();
        sync.acquireShared(permits);
    }

    public boolean tryAcquire(int permits) {
        if(permits < 0)
            throw new IllegalArgumentException();
        return sync.nonfairTryAcquireShared(permits) >= 0;
    }

    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        if(permits < 0)
            throw new IllegalArgumentException();
        return sync.tryAcquireSharedNanos(permits, unit.toNanos(timeout));
    }

    public void release(int permits) {
        if(permits < 0)
            throw new IllegalArgumentException();
        sync.releaseShared(permits);
    }

    public int availablePermits() {
        return sync.getPermits();
    }

    public int drainPermits() {
        return sync.drainPermits();
    }

    protected void reducePermits(int reduction) {
        if(reduction < 0)
            throw new IllegalArgumentException();
        sync.reducePermits(reduction);
    }

    public boolean isFair() {
        return sync instanceof FairSync;
    }

    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    public String toString() {
        return super.toString() + "[Permits = " + sync.getPermits() + "]";
    }
}

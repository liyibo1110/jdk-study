package com.github.liyibo1110.jdk.java.util.concurrent.locks;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;

/**
 * @author liyibo
 * @date 2026-03-10 17:22
 */
public class ReentrantLock implements Lock, Serializable {
    private static final long serialVersionUID = 7373984872572414699L;

    private final Sync sync;

    /**
     * 此锁的同步控制基类，子类有公平和非公平的版本，使用AQS的state表示锁的持有数。
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = -5179523762034025860L;

        /**
         * 尝试获取非公平锁。
         * 这个是最简单的，尝试获取锁（state原本是1，当前线程可以改成1）
         * cas成功就是直接获取到了，失败直接返回false，不会在AQS里面排队。
         */
        final boolean tryLock() {
            Thread current = Thread.currentThread();
            int c = getState();
            if(c == 0) {   // 没有其他线程持有锁
                if(compareAndSetState(0, 1)) {  // 尝试抢锁
                    setExclusiveOwnerThread(current);   // 成功了再把自己写入AQS的owner，操作完成
                    return true;
                }
            }else if (getExclusiveOwnerThread() == current) {  // 如果没拿到锁，但是是自己之前拿的
                if (++c < 0) // overflow    // 增加state的值（所以才叫可重入，加就完事了）
                    throw new Error("Maximum lock count exceeded");
                setState(c);
                return true;
            }
            // 没拿到锁，自己也不是owner，直接返回false，不排队了
            return false;
        }

        /**
         * 在公平与非公平规则下，检查重入性并获取锁（若锁立即可用）。锁定方法在转发至对应的AQS获取方法前，会执行initialTryLock检查。
         */
        abstract boolean initialTryLock();

        /**
         * 获取非公平锁。
         * 和tryLock不一样，没抢到就要去排队了。
         */
        final void lock() {
            if(!initialTryLock())   // 先尝试直接获取锁，失败了再用acquire排队获取（fast path + slow path）
                acquire(1);
        }

        final void lockInterruptibly() throws InterruptedException {
            if(Thread.interrupted())
                throw new InterruptedException();
            if(!initialTryLock())
                acquireInterruptibly(1);
        }

        final boolean tryLockNanos(long nanos) throws InterruptedException {
            if(Thread.interrupted())
                throw new InterruptedException();
            return initialTryLock() || tryAcquireNanos(1, nanos);
        }

        protected final boolean tryRelease(int releases) {
            int c = getState() - releases;
            if(getExclusiveOwnerThread() != Thread.currentThread())
                throw new IllegalMonitorStateException();
            boolean free = (c == 0);
            if(free)
                setExclusiveOwnerThread(null);
            setState(c);
            return free;
        }

        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        final Thread getOwner() {
            return getState() == 0 ? null : getExclusiveOwnerThread();
        }

        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }

        final boolean isLocked() {
            return getState() != 0;
        }

        private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
            s.defaultReadObject();
            setState(0); // reset to unlocked state
        }
    }

    /**
     * 非公平锁的最终实现。
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = 7316153563782823691L;

        /**
         * lock方法里面会先调用这个方法，和Sync的tryLock逻辑是基本一致的
         */
        final boolean initialTryLock() {
            Thread current = Thread.currentThread();
            if(compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(current);
                return true;
            }else if(getExclusiveOwnerThread() == current) {
                int c = getState() + 1;
                if(c < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                setState(c);
                return true;
            }else
                return false;
        }

        /**
         * 和Sync的tryLock前半流程是一致的，但是这里没有了处理重入的逻辑
         */
        protected final boolean tryAcquire(int acquires) {
            if(getState() == 0 && compareAndSetState(0, acquires)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }
    }

    /**
     * 公平锁的最终实现。
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -3000897897090466540L;

        final boolean initialTryLock() {
            Thread current = Thread.currentThread();
            int c = getState();
            if(c == 0) {
                if(!hasQueuedThreads() && compareAndSetState(0, 1)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }else if(getExclusiveOwnerThread() == current) {
                if(++c < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                setState(c);
                return true;
            }
            return false;
        }

        protected final boolean tryAcquire(int acquires) {
            if(getState() == 0 && !hasQueuedPredecessors() && compareAndSetState(0, acquires)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }
    }

    /**
     * 默认是非公平锁
     */
    public ReentrantLock() {
        sync = new NonfairSync();
    }

    public ReentrantLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
    }

    public void lock() {
        sync.lock();
    }

    public void lockInterruptibly() throws InterruptedException {
        sync.lockInterruptibly();
    }

    public boolean tryLock() {
        return sync.tryLock();
    }

    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryLockNanos(unit.toNanos(timeout));
    }

    public void unlock() {
        sync.release(1);
    }

    public Condition newCondition() {
        return sync.newCondition();
    }

    public int getHoldCount() {
        return sync.getHoldCount();
    }

    public boolean isHeldByCurrentThread() {
        return sync.isHeldExclusively();
    }

    public boolean isLocked() {
        return sync.isLocked();
    }

    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    protected Thread getOwner() {
        return sync.getOwner();
    }

    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    public boolean hasWaiters(Condition condition) {
        if(condition == null)
            throw new NullPointerException();
        if(!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    public int getWaitQueueLength(Condition condition) {
        if(condition == null)
            throw new NullPointerException();
        if(!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if(condition == null)
            throw new NullPointerException();
        if(!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    public String toString() {
        Thread o = sync.getOwner();
        return super.toString() + ((o == null) ?
                "[Unlocked]" :
                "[Locked by thread " + o.getName() + "]");
    }
}

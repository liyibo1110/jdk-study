package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 让一组线程互相等待，直到所有线程都到达某个同步点，即线程们在某个地方集合，全部到齐再继续执行。
 * 注意这个组件支持循环使用，即Barrier会在每一轮自动重置，即state归0后会再恢复成初始值。
 * @author liyibo
 * @date 2026-03-13 16:11
 */
public class CyclicBarrier {

    /**
     * 代表了当前执行的轮次
     */
    private static class Generation {
        Generation() {}
        boolean broken;
    }

    /** 用来保护状态变量的锁 */
    private final ReentrantLock lock = new ReentrantLock();

    /** 用来阻塞线程 */
    private final Condition trip = lock.newCondition();

    /** 参与的线程数量 */
    private final int parties;

    /** 当所有线程到达barrier时，由最后一个线程执行这个任务，相当于提供了一个生命周期回调 */
    private final Runnable barrierCommand;

    private Generation generation = new Generation();

    /** 还需要等多少个线程运行完，才能通知放行（各个线程调用await会让count减少） */
    private int count;

    public CyclicBarrier(int parties, Runnable barrierAction) {
        if(parties <= 0)
            throw new IllegalArgumentException();
        this.parties = parties;
        this.count = parties;
        this.barrierCommand = barrierAction;
    }

    public CyclicBarrier(int parties) {
        this(parties, null);
    }

    public int getParties() {
        return parties;
    }

    /**
     * 所有线程在本轮已执行完毕，进入下一轮
     */
    private void nextGeneration() {
        trip.signalAll();
        count = parties;    // 恢复count
        generation = new Generation();
    }

    /**
     * 将当前屏障标记为broken，唤醒所有线程，仅在持有锁时会调用。
     */
    private void breakBarrier() {
        generation.broken = true;
        count = parties;
        trip.signalAll();
    }

    /**
     * 线程做完本轮的任务了，会调用此地方进入阻塞，直到所有线程都调用了await再继续下一轮
     */
    private int dowait(boolean timed, long nanos) throws InterruptedException, BrokenBarrierException, TimeoutException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Generation g = generation;
            if(g.broken)
                throw new BrokenBarrierException();
            if(Thread.interrupted()) {
                breakBarrier();
                throw new InterruptedException();
            }

            int index = --count;    // count减1之后的剩余值
            if(index == 0) {    // 剩余0所有这个线程是最后一个完工的线程了，尝试执行barrierCommand
                Runnable command = barrierCommand;
                if(command != null) {
                    try {
                        command.run();
                    } catch (Throwable ex) {
                        breakBarrier();
                        throw ex;
                    }
                }
                // 最后的线程还要负责重置准备进入下一轮
                nextGeneration();
                return 0;
            }

            // 到这里说明还有其他线程没有完工，需要阻塞等待
            while(true) {
                try {
                    if(!timed)
                        trip.await();
                    else if(nanos > 0L)
                        nanos = trip.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    if(g == generation && !g.broken) {
                        breakBarrier();
                        throw ie;
                    }else {
                        Thread.currentThread().interrupt();
                    }
                }

                if(g.broken)
                    throw new BrokenBarrierException();
                if(g != generation)
                    return index;

                if(timed && nanos <= 0L) {
                    breakBarrier();
                    throw new TimeoutException();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return dowait(false, 0L);
        } catch (TimeoutException toe) {
            throw new Error(toe); // cannot happen
        }
    }

    public int await(long timeout, TimeUnit unit) throws InterruptedException, BrokenBarrierException, TimeoutException {
        return dowait(true, unit.toNanos(timeout));
    }

    public boolean isBroken() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return generation.broken;
        } finally {
            lock.unlock();
        }
    }

    public void reset() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            breakBarrier();   // break the current generation
            nextGeneration(); // start a new generation
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回本轮已经干完活的线程
     * @return
     */
    public int getNumberWaiting() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return parties - count;
        } finally {
            lock.unlock();
        }
    }
}

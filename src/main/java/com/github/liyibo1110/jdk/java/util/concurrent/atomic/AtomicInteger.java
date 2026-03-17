package com.github.liyibo1110.jdk.java.util.concurrent.atomic;

import jdk.internal.misc.Unsafe;

import java.io.Serializable;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

/**
 * 本质就是通过CAS操作，保证了线程安全并发读写value，可以看作是：CAS编程模型的最简单教材（代码是在Unsafe里面）。
 * 注意AtomicInteger并不会解决ABA的问题，如要解决，需要使用AtomicInteger或AtomicStampedReference，它们在value以外又增加了version和stamp。
 * @author liyibo
 * @date 2026-03-16 14:04
 */
public class AtomicInteger extends Number implements Serializable {
    private static final long serialVersionUID = 6214790243416807050L;

    private static final Unsafe U = Unsafe.getUnsafe();

    private static final long VALUE = U.objectFieldOffset(AtomicInteger.class, "value");

    private volatile int value;

    public AtomicInteger(int initialValue) {
        value = initialValue;
    }

    public AtomicInteger() {}

    public final int get() {
        return value;
    }

    public final void set(int newValue) {
        value = newValue;
    }

    /**
     * 这个方法语义是：最终一定可见，但可以延迟刷新。
     * 这在某些场景可以减少内存屏障，主要用在生产者-消费者队列，例如LinkedTransferQueue或Disruptor。
     */
    public final void lazySet(int newValue) {
        U.putIntRelease(this, VALUE, newValue);
    }

    public final int getAndSet(int newValue) {
        return U.getAndSetInt(this, VALUE, newValue);
    }

    public final boolean compareAndSet(int expectedValue, int newValue) {
        return U.compareAndSetInt(this, VALUE, expectedValue, newValue);
    }

    @Deprecated(since="9")
    public final boolean weakCompareAndSet(int expectedValue, int newValue) {
        return U.weakCompareAndSetIntPlain(this, VALUE, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetPlain(int expectedValue, int newValue) {
        return U.weakCompareAndSetIntPlain(this, VALUE, expectedValue, newValue);
    }

    public final int getAndIncrement() {
        return U.getAndAddInt(this, VALUE, 1);
    }

    public final int getAndDecrement() {
        return U.getAndAddInt(this, VALUE, -1);
    }

    public final int getAndAdd(int delta) {
        return U.getAndAddInt(this, VALUE, delta);
    }

    public final int incrementAndGet() {
        return U.getAndAddInt(this, VALUE, 1) + 1;
    }

    public final int decrementAndGet() {
        return U.getAndAddInt(this, VALUE, -1) - 1;
    }

    public final int addAndGet(int delta) {
        return U.getAndAddInt(this, VALUE, delta) + delta;
    }

    public final int getAndUpdate(IntUnaryOperator updateFunction) {
        int prev = get(), next = 0;
        for(boolean haveNext = false;;) {
            if(!haveNext)
                next = updateFunction.applyAsInt(prev);
            if(weakCompareAndSetVolatile(prev, next))
                return prev;
            haveNext = (prev == (prev = get()));
        }
    }

    public final int updateAndGet(IntUnaryOperator updateFunction) {
        int prev = get(), next = 0;
        for(boolean haveNext = false;;) {
            if(!haveNext)
                next = updateFunction.applyAsInt(prev);
            if(weakCompareAndSetVolatile(prev, next))
                return next;
            haveNext = (prev == (prev = get()));
        }
    }

    public final int getAndAccumulate(int x, IntBinaryOperator accumulatorFunction) {
        int prev = get(), next = 0;
        for(boolean haveNext = false;;) {
            if(!haveNext)
                next = accumulatorFunction.applyAsInt(prev, x);
            if(weakCompareAndSetVolatile(prev, next))
                return prev;
            haveNext = (prev == (prev = get()));
        }
    }

    public final int accumulateAndGet(int x, IntBinaryOperator accumulatorFunction) {
        int prev = get(), next = 0;
        for(boolean haveNext = false;;) {
            if(!haveNext)
                next = accumulatorFunction.applyAsInt(prev, x);
            if(weakCompareAndSetVolatile(prev, next))
                return next;
            haveNext = (prev == (prev = get()));
        }
    }

    public String toString() {
        return Integer.toString(get());
    }

    public int intValue() {
        return get();
    }

    public long longValue() {
        return get();
    }

    public float floatValue() {
        return (float)get();
    }

    public double doubleValue() {
        return get();
    }

    // jdk9

    /**
     * 返回当前值，其内存语义与读取一个被声明为非易失性的变量时相同。
     */
    public final int getPlain() {
        return U.getInt(this, VALUE);
    }

    public final void setPlain(int newValue) {
        U.putInt(this, VALUE, newValue);
    }

    /**
     * 返回当前值，并遵循VarHandle.getOpaque指定的内存行为。
     */
    public final int getOpaque() {
        return U.getIntOpaque(this, VALUE);
    }

    public final void setOpaque(int newValue) {
        U.putIntOpaque(this, VALUE, newValue);
    }

    public final int getAcquire() {
        return U.getIntAcquire(this, VALUE);
    }

    public final void setRelease(int newValue) {
        U.putIntRelease(this, VALUE, newValue);
    }

    public final int compareAndExchange(int expectedValue, int newValue) {
        return U.compareAndExchangeInt(this, VALUE, expectedValue, newValue);
    }

    public final int compareAndExchangeAcquire(int expectedValue, int newValue) {
        return U.compareAndExchangeIntAcquire(this, VALUE, expectedValue, newValue);
    }

    public final int compareAndExchangeRelease(int expectedValue, int newValue) {
        return U.compareAndExchangeIntRelease(this, VALUE, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetVolatile(int expectedValue, int newValue) {
        return U.weakCompareAndSetInt(this, VALUE, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetAcquire(int expectedValue, int newValue) {
        return U.weakCompareAndSetIntAcquire(this, VALUE, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetRelease(int expectedValue, int newValue) {
        return U.weakCompareAndSetIntRelease(this, VALUE, expectedValue, newValue);
    }
}

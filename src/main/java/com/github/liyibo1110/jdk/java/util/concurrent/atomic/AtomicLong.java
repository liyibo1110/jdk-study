package com.github.liyibo1110.jdk.java.util.concurrent.atomic;

import jdk.internal.misc.Unsafe;

import java.io.Serializable;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

/**
 * 参照AtomicInteger
 * @author liyibo
 * @date 2026-03-16 14:28
 */
public class AtomicLong extends Number implements Serializable {
    private static final long serialVersionUID = 1927816293512124184L;

    static final boolean VM_SUPPORTS_LONG_CAS = VMSupportsCS8();

    private static native boolean VMSupportsCS8();

    private static final Unsafe U = Unsafe.getUnsafe();

    private static final long VALUE = U.objectFieldOffset(AtomicLong.class, "value");

    private volatile long value;

    public AtomicLong(long initialValue) {
        value = initialValue;
    }

    public AtomicLong() {}

    public final long get() {
        return value;
    }

    public final void set(long newValue) {
        // See JDK-8180620: Clarify VarHandle mixed-access subtleties
        U.putLongVolatile(this, VALUE, newValue);
    }

    public final void lazySet(long newValue) {
        U.putLongRelease(this, VALUE, newValue);
    }

    public final long getAndSet(long newValue) {
        return U.getAndSetLong(this, VALUE, newValue);
    }

    public final boolean compareAndSet(long expectedValue, long newValue) {
        return U.compareAndSetLong(this, VALUE, expectedValue, newValue);
    }

    @Deprecated(since="9")
    public final boolean weakCompareAndSet(long expectedValue, long newValue) {
        return U.weakCompareAndSetLongPlain(this, VALUE, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetPlain(long expectedValue, long newValue) {
        return U.weakCompareAndSetLongPlain(this, VALUE, expectedValue, newValue);
    }

    public final long getAndIncrement() {
        return U.getAndAddLong(this, VALUE, 1L);
    }

    public final long getAndDecrement() {
        return U.getAndAddLong(this, VALUE, -1L);
    }

    public final long getAndAdd(long delta) {
        return U.getAndAddLong(this, VALUE, delta);
    }

    public final long incrementAndGet() {
        return U.getAndAddLong(this, VALUE, 1L) + 1L;
    }

    public final long decrementAndGet() {
        return U.getAndAddLong(this, VALUE, -1L) - 1L;
    }

    public final long addAndGet(long delta) {
        return U.getAndAddLong(this, VALUE, delta) + delta;
    }

    public final long getAndUpdate(LongUnaryOperator updateFunction) {
        long prev = get(), next = 0L;
        for(boolean haveNext = false;;) {
            if(!haveNext)
                next = updateFunction.applyAsLong(prev);
            if(weakCompareAndSetVolatile(prev, next))
                return prev;
            haveNext = (prev == (prev = get()));
        }
    }

    public final long updateAndGet(LongUnaryOperator updateFunction) {
        long prev = get(), next = 0L;
        for(boolean haveNext = false;;) {
            if(!haveNext)
                next = updateFunction.applyAsLong(prev);
            if(weakCompareAndSetVolatile(prev, next))
                return next;
            haveNext = (prev == (prev = get()));
        }
    }

    public final long getAndAccumulate(long x, LongBinaryOperator accumulatorFunction) {
        long prev = get(), next = 0L;
        for(boolean haveNext = false;;) {
            if(!haveNext)
                next = accumulatorFunction.applyAsLong(prev, x);
            if(weakCompareAndSetVolatile(prev, next))
                return prev;
            haveNext = (prev == (prev = get()));
        }
    }

    public final long accumulateAndGet(long x, LongBinaryOperator accumulatorFunction) {
        long prev = get(), next = 0L;
        for(boolean haveNext = false;;) {
            if(!haveNext)
                next = accumulatorFunction.applyAsLong(prev, x);
            if(weakCompareAndSetVolatile(prev, next))
                return next;
            haveNext = (prev == (prev = get()));
        }
    }

    public String toString() {
        return Long.toString(get());
    }

    public int intValue() {
        return (int)get();
    }

    public long longValue() {
        return get();
    }

    public float floatValue() {
        return (float)get();
    }

    public double doubleValue() {
        return (double)get();
    }

    // jdk9

    public final long getPlain() {
        return U.getLong(this, VALUE);
    }

    public final void setPlain(long newValue) {
        U.putLong(this, VALUE, newValue);
    }

    public final long getOpaque() {
        return U.getLongOpaque(this, VALUE);
    }

    public final void setOpaque(long newValue) {
        U.putLongOpaque(this, VALUE, newValue);
    }

    public final long getAcquire() {
        return U.getLongAcquire(this, VALUE);
    }

    public final void setRelease(long newValue) {
        U.putLongRelease(this, VALUE, newValue);
    }

    public final long compareAndExchange(long expectedValue, long newValue) {
        return U.compareAndExchangeLong(this, VALUE, expectedValue, newValue);
    }

    public final long compareAndExchangeAcquire(long expectedValue, long newValue) {
        return U.compareAndExchangeLongAcquire(this, VALUE, expectedValue, newValue);
    }

    public final long compareAndExchangeRelease(long expectedValue, long newValue) {
        return U.compareAndExchangeLongRelease(this, VALUE, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetVolatile(long expectedValue, long newValue) {
        return U.weakCompareAndSetLong(this, VALUE, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetAcquire(long expectedValue, long newValue) {
        return U.weakCompareAndSetLongAcquire(this, VALUE, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetRelease(long expectedValue, long newValue) {
        return U.weakCompareAndSetLongRelease(this, VALUE, expectedValue, newValue);
    }
}

package com.github.liyibo1110.jdk.java.util.concurrent.atomic;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

/**
 * @author liyibo
 * @date 2026-03-16 15:06
 */
public class AtomicLongArray implements Serializable {
    private static final long serialVersionUID = -2308431214976778248L;

    private static final VarHandle AA = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] array;

    public AtomicLongArray(int length) {
        array = new long[length];
    }

    public AtomicLongArray(long[] array) {
        // Visibility guaranteed by final field guarantees
        this.array = array.clone();
    }

    public final int length() {
        return array.length;
    }

    public final long get(int i) {
        return (long)AA.getVolatile(array, i);
    }

    public final void set(int i, long newValue) {
        AA.setVolatile(array, i, newValue);
    }

    public final void lazySet(int i, long newValue) {
        AA.setRelease(array, i, newValue);
    }

    public final long getAndSet(int i, long newValue) {
        return (long)AA.getAndSet(array, i, newValue);
    }

    public final boolean compareAndSet(int i, long expectedValue, long newValue) {
        return AA.compareAndSet(array, i, expectedValue, newValue);
    }

    @Deprecated(since="9")
    public final boolean weakCompareAndSet(int i, long expectedValue, long newValue) {
        return AA.weakCompareAndSetPlain(array, i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetPlain(int i, long expectedValue, long newValue) {
        return AA.weakCompareAndSetPlain(array, i, expectedValue, newValue);
    }

    public final long getAndIncrement(int i) {
        return (long)AA.getAndAdd(array, i, 1L);
    }

    public final long getAndDecrement(int i) {
        return (long)AA.getAndAdd(array, i, -1L);
    }

    public final long getAndAdd(int i, long delta) {
        return (long)AA.getAndAdd(array, i, delta);
    }

    public final long incrementAndGet(int i) {
        return (long)AA.getAndAdd(array, i, 1L) + 1L;
    }

    public final long decrementAndGet(int i) {
        return (long)AA.getAndAdd(array, i, -1L) - 1L;
    }

    public long addAndGet(int i, long delta) {
        return (long)AA.getAndAdd(array, i, delta) + delta;
    }

    public final long getAndUpdate(int i, LongUnaryOperator updateFunction) {
        long prev = get(i), next = 0L;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = updateFunction.applyAsLong(prev);
            if (weakCompareAndSetVolatile(i, prev, next))
                return prev;
            haveNext = (prev == (prev = get(i)));
        }
    }

    public final long updateAndGet(int i, LongUnaryOperator updateFunction) {
        long prev = get(i), next = 0L;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = updateFunction.applyAsLong(prev);
            if (weakCompareAndSetVolatile(i, prev, next))
                return next;
            haveNext = (prev == (prev = get(i)));
        }
    }

    public final long getAndAccumulate(int i, long x, LongBinaryOperator accumulatorFunction) {
        long prev = get(i), next = 0L;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = accumulatorFunction.applyAsLong(prev, x);
            if (weakCompareAndSetVolatile(i, prev, next))
                return prev;
            haveNext = (prev == (prev = get(i)));
        }
    }

    public final long accumulateAndGet(int i, long x, LongBinaryOperator accumulatorFunction) {
        long prev = get(i), next = 0L;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = accumulatorFunction.applyAsLong(prev, x);
            if (weakCompareAndSetVolatile(i, prev, next))
                return next;
            haveNext = (prev == (prev = get(i)));
        }
    }

    public String toString() {
        int iMax = array.length - 1;
        if (iMax == -1)
            return "[]";

        StringBuilder b = new StringBuilder();
        b.append('[');
        for (int i = 0; ; i++) {
            b.append(get(i));
            if (i == iMax)
                return b.append(']').toString();
            b.append(',').append(' ');
        }
    }

    // jdk9

    public final long getPlain(int i) {
        return (long)AA.get(array, i);
    }

    public final void setPlain(int i, long newValue) {
        AA.set(array, i, newValue);
    }

    public final long getOpaque(int i) {
        return (long)AA.getOpaque(array, i);
    }

    public final void setOpaque(int i, long newValue) {
        AA.setOpaque(array, i, newValue);
    }

    public final long getAcquire(int i) {
        return (long)AA.getAcquire(array, i);
    }

    public final void setRelease(int i, long newValue) {
        AA.setRelease(array, i, newValue);
    }

    public final long compareAndExchange(int i, long expectedValue, long newValue) {
        return (long)AA.compareAndExchange(array, i, expectedValue, newValue);
    }

    public final long compareAndExchangeAcquire(int i, long expectedValue, long newValue) {
        return (long)AA.compareAndExchangeAcquire(array, i, expectedValue, newValue);
    }

    public final long compareAndExchangeRelease(int i, long expectedValue, long newValue) {
        return (long)AA.compareAndExchangeRelease(array, i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetVolatile(int i, long expectedValue, long newValue) {
        return AA.weakCompareAndSet(array, i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetAcquire(int i, long expectedValue, long newValue) {
        return AA.weakCompareAndSetAcquire(array, i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetRelease(int i, long expectedValue, long newValue) {
        return AA.weakCompareAndSetRelease(array, i, expectedValue, newValue);
    }
}

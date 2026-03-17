package com.github.liyibo1110.jdk.java.util.concurrent.atomic;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

/**
 * @author liyibo
 * @date 2026-03-16 15:02
 */
public class AtomicIntegerArray implements Serializable {
    private static final long serialVersionUID = 2862133569453604235L;
    private static final VarHandle AA = MethodHandles.arrayElementVarHandle(int[].class);

    private final int[] array;

    public AtomicIntegerArray(int length) {
        array = new int[length];
    }

    public AtomicIntegerArray(int[] array) {
        // Visibility guaranteed by final field guarantees
        this.array = array.clone();
    }

    public final int length() {
        return array.length;
    }

    public final int get(int i) {
        return (int)AA.getVolatile(array, i);
    }

    public final void set(int i, int newValue) {
        AA.setVolatile(array, i, newValue);
    }

    public final void lazySet(int i, int newValue) {
        AA.setRelease(array, i, newValue);
    }

    public final int getAndSet(int i, int newValue) {
        return (int)AA.getAndSet(array, i, newValue);
    }

    public final boolean compareAndSet(int i, int expectedValue, int newValue) {
        return AA.compareAndSet(array, i, expectedValue, newValue);
    }

    @Deprecated(since="9")
    public final boolean weakCompareAndSet(int i, int expectedValue, int newValue) {
        return AA.weakCompareAndSetPlain(array, i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetPlain(int i, int expectedValue, int newValue) {
        return AA.weakCompareAndSetPlain(array, i, expectedValue, newValue);
    }

    public final int getAndIncrement(int i) {
        return (int)AA.getAndAdd(array, i, 1);
    }

    public final int getAndDecrement(int i) {
        return (int)AA.getAndAdd(array, i, -1);
    }

    public final int getAndAdd(int i, int delta) {
        return (int)AA.getAndAdd(array, i, delta);
    }

    public final int incrementAndGet(int i) {
        return (int)AA.getAndAdd(array, i, 1) + 1;
    }

    public final int decrementAndGet(int i) {
        return (int)AA.getAndAdd(array, i, -1) - 1;
    }

    public final int addAndGet(int i, int delta) {
        return (int)AA.getAndAdd(array, i, delta) + delta;
    }

    public final int getAndUpdate(int i, IntUnaryOperator updateFunction) {
        int prev = get(i), next = 0;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = updateFunction.applyAsInt(prev);
            if (weakCompareAndSetVolatile(i, prev, next))
                return prev;
            haveNext = (prev == (prev = get(i)));
        }
    }

    public final int updateAndGet(int i, IntUnaryOperator updateFunction) {
        int prev = get(i), next = 0;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = updateFunction.applyAsInt(prev);
            if (weakCompareAndSetVolatile(i, prev, next))
                return next;
            haveNext = (prev == (prev = get(i)));
        }
    }

    public final int getAndAccumulate(int i, int x, IntBinaryOperator accumulatorFunction) {
        int prev = get(i), next = 0;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = accumulatorFunction.applyAsInt(prev, x);
            if (weakCompareAndSetVolatile(i, prev, next))
                return prev;
            haveNext = (prev == (prev = get(i)));
        }
    }

    public final int accumulateAndGet(int i, int x, IntBinaryOperator accumulatorFunction) {
        int prev = get(i), next = 0;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = accumulatorFunction.applyAsInt(prev, x);
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

    public final int getPlain(int i) {
        return (int)AA.get(array, i);
    }

    public final void setPlain(int i, int newValue) {
        AA.set(array, i, newValue);
    }

    public final int getOpaque(int i) {
        return (int)AA.getOpaque(array, i);
    }

    public final void setOpaque(int i, int newValue) {
        AA.setOpaque(array, i, newValue);
    }

    public final int getAcquire(int i) {
        return (int)AA.getAcquire(array, i);
    }

    public final void setRelease(int i, int newValue) {
        AA.setRelease(array, i, newValue);
    }

    public final int compareAndExchange(int i, int expectedValue, int newValue) {
        return (int)AA.compareAndExchange(array, i, expectedValue, newValue);
    }

    public final int compareAndExchangeAcquire(int i, int expectedValue, int newValue) {
        return (int)AA.compareAndExchangeAcquire(array, i, expectedValue, newValue);
    }

    public final int compareAndExchangeRelease(int i, int expectedValue, int newValue) {
        return (int)AA.compareAndExchangeRelease(array, i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetVolatile(int i, int expectedValue, int newValue) {
        return AA.weakCompareAndSet(array, i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetAcquire(int i, int expectedValue, int newValue) {
        return AA.weakCompareAndSetAcquire(array, i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetRelease(int i, int expectedValue, int newValue) {
        return AA.weakCompareAndSetRelease(array, i, expectedValue, newValue);
    }
}

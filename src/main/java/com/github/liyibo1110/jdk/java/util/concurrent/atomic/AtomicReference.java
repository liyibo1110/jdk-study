package com.github.liyibo1110.jdk.java.util.concurrent.atomic;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * @author liyibo
 * @date 2026-03-16 15:10
 */
public class AtomicReference<V> implements Serializable {
    private static final long serialVersionUID = -1848883965231344442L;
    private static final VarHandle VALUE;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            VALUE = l.findVarHandle(AtomicReference.class, "value", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile V value;

    public AtomicReference(V initialValue) {
        value = initialValue;
    }

    public AtomicReference() {}

    public final V get() {
        return value;
    }

    public final void set(V newValue) {
        value = newValue;
    }

    public final void lazySet(V newValue) {
        VALUE.setRelease(this, newValue);
    }

    public final boolean compareAndSet(V expectedValue, V newValue) {
        return VALUE.compareAndSet(this, expectedValue, newValue);
    }

    @Deprecated(since="9")
    public final boolean weakCompareAndSet(V expectedValue, V newValue) {
        return VALUE.weakCompareAndSetPlain(this, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetPlain(V expectedValue, V newValue) {
        return VALUE.weakCompareAndSetPlain(this, expectedValue, newValue);
    }

    public final V getAndSet(V newValue) {
        return (V)VALUE.getAndSet(this, newValue);
    }

    public final V getAndUpdate(UnaryOperator<V> updateFunction) {
        V prev = get(), next = null;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = updateFunction.apply(prev);
            if (weakCompareAndSetVolatile(prev, next))
                return prev;
            haveNext = (prev == (prev = get()));
        }
    }

    public final V updateAndGet(UnaryOperator<V> updateFunction) {
        V prev = get(), next = null;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = updateFunction.apply(prev);
            if (weakCompareAndSetVolatile(prev, next))
                return next;
            haveNext = (prev == (prev = get()));
        }
    }

    public final V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
        V prev = get(), next = null;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = accumulatorFunction.apply(prev, x);
            if (weakCompareAndSetVolatile(prev, next))
                return prev;
            haveNext = (prev == (prev = get()));
        }
    }

    public final V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
        V prev = get(), next = null;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = accumulatorFunction.apply(prev, x);
            if (weakCompareAndSetVolatile(prev, next))
                return next;
            haveNext = (prev == (prev = get()));
        }
    }

    public String toString() {
        return String.valueOf(get());
    }

    // jdk9

    public final V getPlain() {
        return (V)VALUE.get(this);
    }

    public final void setPlain(V newValue) {
        VALUE.set(this, newValue);
    }

    public final V getOpaque() {
        return (V)VALUE.getOpaque(this);
    }

    public final void setOpaque(V newValue) {
        VALUE.setOpaque(this, newValue);
    }

    public final V getAcquire() {
        return (V)VALUE.getAcquire(this);
    }

    public final void setRelease(V newValue) {
        VALUE.setRelease(this, newValue);
    }

    public final V compareAndExchange(V expectedValue, V newValue) {
        return (V)VALUE.compareAndExchange(this, expectedValue, newValue);
    }

    public final V compareAndExchangeAcquire(V expectedValue, V newValue) {
        return (V)VALUE.compareAndExchangeAcquire(this, expectedValue, newValue);
    }

    public final V compareAndExchangeRelease(V expectedValue, V newValue) {
        return (V)VALUE.compareAndExchangeRelease(this, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetVolatile(V expectedValue, V newValue) {
        return VALUE.weakCompareAndSet(this, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetAcquire(V expectedValue, V newValue) {
        return VALUE.weakCompareAndSetAcquire(this, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetRelease(V expectedValue, V newValue) {
        return VALUE.weakCompareAndSetRelease(this, expectedValue, newValue);
    }
}

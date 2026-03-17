package com.github.liyibo1110.jdk.java.util.concurrent.atomic;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * @author liyibo
 * @date 2026-03-16 15:13
 */
public class AtomicReferenceArray<E> implements Serializable {
    private static final long serialVersionUID = -6209656149925076980L;
    private static final VarHandle AA = MethodHandles.arrayElementVarHandle(Object[].class);
    private final Object[] array;

    public AtomicReferenceArray(int length) {
        array = new Object[length];
    }

    public AtomicReferenceArray(E[] array) {
        // Visibility guaranteed by final field guarantees
        this.array = Arrays.copyOf(array, array.length, Object[].class);
    }

    public final int length() {
        return array.length;
    }

    public final E get(int i) {
        return (E)AA.getVolatile(array, i);
    }

    public final void set(int i, E newValue) {
        AA.setVolatile(array, i, newValue);
    }

    public final void lazySet(int i, E newValue) {
        AA.setRelease(array, i, newValue);
    }

    public final E getAndSet(int i, E newValue) {
        return (E)AA.getAndSet(array, i, newValue);
    }

    public final boolean compareAndSet(int i, E expectedValue, E newValue) {
        return AA.compareAndSet(array, i, expectedValue, newValue);
    }

    @Deprecated(since="9")
    public final boolean weakCompareAndSet(int i, E expectedValue, E newValue) {
        return AA.weakCompareAndSetPlain(array, i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetPlain(int i, E expectedValue, E newValue) {
        return AA.weakCompareAndSetPlain(array, i, expectedValue, newValue);
    }

    public final E getAndUpdate(int i, UnaryOperator<E> updateFunction) {
        E prev = get(i), next = null;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = updateFunction.apply(prev);
            if (weakCompareAndSetVolatile(i, prev, next))
                return prev;
            haveNext = (prev == (prev = get(i)));
        }
    }

    public final E updateAndGet(int i, UnaryOperator<E> updateFunction) {
        E prev = get(i), next = null;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = updateFunction.apply(prev);
            if (weakCompareAndSetVolatile(i, prev, next))
                return next;
            haveNext = (prev == (prev = get(i)));
        }
    }

    public final E getAndAccumulate(int i, E x, BinaryOperator<E> accumulatorFunction) {
        E prev = get(i), next = null;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = accumulatorFunction.apply(prev, x);
            if (weakCompareAndSetVolatile(i, prev, next))
                return prev;
            haveNext = (prev == (prev = get(i)));
        }
    }

    public final E accumulateAndGet(int i, E x, BinaryOperator<E> accumulatorFunction) {
        E prev = get(i), next = null;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = accumulatorFunction.apply(prev, x);
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

    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        // Note: This must be changed if any additional fields are defined
        Object a = s.readFields().get("array", null);
        if (a == null || !a.getClass().isArray())
            throw new java.io.InvalidObjectException("Not array type");
        if (a.getClass() != Object[].class)
            a = Arrays.copyOf((Object[])a, Array.getLength(a), Object[].class);
        @SuppressWarnings("removal")
        Field arrayField = java.security.AccessController.doPrivileged(
                (java.security.PrivilegedAction<Field>) () -> {
                    try {
                        Field f = java.util.concurrent.atomic.AtomicReferenceArray.class
                                .getDeclaredField("array");
                        f.setAccessible(true);
                        return f;
                    } catch (ReflectiveOperationException e) {
                        throw new Error(e);
                    }});
        try {
            arrayField.set(this, a);
        } catch (IllegalAccessException e) {
            throw new Error(e);
        }
    }

    // jdk9

    public final E getPlain(int i) {
        return (E)AA.get(array, i);
    }

    public final void setPlain(int i, E newValue) {
        AA.set(array, i, newValue);
    }

    public final E getOpaque(int i) {
        return (E)AA.getOpaque(array, i);
    }

    public final void setOpaque(int i, E newValue) {
        AA.setOpaque(array, i, newValue);
    }

    public final E getAcquire(int i) {
        return (E)AA.getAcquire(array, i);
    }

    public final void setRelease(int i, E newValue) {
        AA.setRelease(array, i, newValue);
    }

    public final E compareAndExchange(int i, E expectedValue, E newValue) {
        return (E)AA.compareAndExchange(array, i, expectedValue, newValue);
    }

    public final E compareAndExchangeAcquire(int i, E expectedValue, E newValue) {
        return (E)AA.compareAndExchangeAcquire(array, i, expectedValue, newValue);
    }

    public final E compareAndExchangeRelease(int i, E expectedValue, E newValue) {
        return (E)AA.compareAndExchangeRelease(array, i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetVolatile(int i, E expectedValue, E newValue) {
        return AA.weakCompareAndSet(array, i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetAcquire(int i, E expectedValue, E newValue) {
        return AA.weakCompareAndSetAcquire(array, i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetRelease(int i, E expectedValue, E newValue) {
        return AA.weakCompareAndSetRelease(array, i, expectedValue, newValue);
    }
}


package com.github.liyibo1110.jdk.java.util.concurrent.atomic;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * 注意和AtomicInteger以及AtomicLong不同，这个版本使用了更新的VarHandle（标准API）而不是Unsafe（内部API）
 * @author liyibo
 * @date 2026-03-16 14:51
 */
public class AtomicBoolean {
    private static final long serialVersionUID = 4654671469794556979L;

    private static final VarHandle VALUE;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            VALUE = l.findVarHandle(java.util.concurrent.atomic.AtomicBoolean.class, "value", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile int value;

    public AtomicBoolean(boolean initialValue) {
        if(initialValue)
            value = 1;
    }

    public AtomicBoolean() {}

    public final boolean get() {
        return value != 0;
    }

    public final boolean compareAndSet(boolean expectedValue, boolean newValue) {
        return VALUE.compareAndSet(this, (expectedValue ? 1 : 0), (newValue ? 1 : 0));
    }

    @Deprecated(since="9")
    public boolean weakCompareAndSet(boolean expectedValue, boolean newValue) {
        return VALUE.weakCompareAndSetPlain(this, (expectedValue ? 1 : 0), (newValue ? 1 : 0));
    }

    public boolean weakCompareAndSetPlain(boolean expectedValue, boolean newValue) {
        return VALUE.weakCompareAndSetPlain(this, (expectedValue ? 1 : 0), (newValue ? 1 : 0));
    }

    public final void set(boolean newValue) {
        value = newValue ? 1 : 0;
    }

    public final void lazySet(boolean newValue) {
        VALUE.setRelease(this, (newValue ? 1 : 0));
    }

    public final boolean getAndSet(boolean newValue) {
        return (int)VALUE.getAndSet(this, (newValue ? 1 : 0)) != 0;
    }

    public String toString() {
        return Boolean.toString(get());
    }

    // jdk9

    public final boolean getPlain() {
        return (int)VALUE.get(this) != 0;
    }

    public final void setPlain(boolean newValue) {
        VALUE.set(this, newValue ? 1 : 0);
    }

    public final boolean getOpaque() {
        return (int)VALUE.getOpaque(this) != 0;
    }

    public final void setOpaque(boolean newValue) {
        VALUE.setOpaque(this, newValue ? 1 : 0);
    }

    public final boolean getAcquire() {
        return (int)VALUE.getAcquire(this) != 0;
    }

    public final void setRelease(boolean newValue) {
        VALUE.setRelease(this, newValue ? 1 : 0);
    }

    public final boolean compareAndExchange(boolean expectedValue, boolean newValue) {
        return (int)VALUE.compareAndExchange(this, (expectedValue ? 1 : 0), (newValue ? 1 : 0)) != 0;
    }

    public final boolean compareAndExchangeAcquire(boolean expectedValue, boolean newValue) {
        return (int)VALUE.compareAndExchangeAcquire(this, (expectedValue ? 1 : 0), (newValue ? 1 : 0)) != 0;
    }

    public final boolean compareAndExchangeRelease(boolean expectedValue, boolean newValue) {
        return (int)VALUE.compareAndExchangeRelease(this, (expectedValue ? 1 : 0), (newValue ? 1 : 0)) != 0;
    }

    public final boolean weakCompareAndSetVolatile(boolean expectedValue, boolean newValue) {
        return VALUE.weakCompareAndSet(this, (expectedValue ? 1 : 0), (newValue ? 1 : 0));
    }

    public final boolean weakCompareAndSetAcquire(boolean expectedValue, boolean newValue) {
        return VALUE.weakCompareAndSetAcquire(this, (expectedValue ? 1 : 0), (newValue ? 1 : 0));
    }

    public final boolean weakCompareAndSetRelease(boolean expectedValue, boolean newValue) {
        return VALUE.weakCompareAndSetRelease(this, (expectedValue ? 1 : 0), (newValue ? 1 : 0));
    }
}

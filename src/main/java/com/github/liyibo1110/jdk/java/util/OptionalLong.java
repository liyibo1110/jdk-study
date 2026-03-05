package com.github.liyibo1110.jdk.java.util;

import java.util.NoSuchElementException;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.LongStream;

/**
 * Optional组件的long专用版本，因为是int所以不会是null，默认值就是0，所以是否存在只能用isPresent标记来判断，而不能用value判断。
 * @author liyibo
 * @date 2026-03-04 14:19
 */
public final class OptionalLong {
    private static final OptionalLong EMPTY = new OptionalLong();

    private final boolean isPresent;
    private final long value;

    private OptionalLong() {
        this.isPresent = false;
        this.value = 0;
    }

    public static OptionalLong empty() {
        return EMPTY;
    }

    private OptionalLong(long value) {
        this.isPresent = true;
        this.value = value;
    }

    public static OptionalLong of(long value) {
        return new OptionalLong(value);
    }

    public long getAsLong() {
        if(!isPresent)
            throw new NoSuchElementException("No value present");
        return value;
    }

    public boolean isPresent() {
        return isPresent;
    }

    public boolean isEmpty() {
        return !isPresent;
    }

    public void ifPresent(LongConsumer action) {
        if(isPresent)
            action.accept(value);
    }

    public void ifPresentOrElse(LongConsumer action, Runnable emptyAction) {
        if(isPresent)
            action.accept(value);
        else
            emptyAction.run();
    }

    public LongStream stream() {
        if(isPresent)
            return LongStream.of(value);
        else
            return LongStream.empty();
    }

    public long orElse(long other) {
        return isPresent ? value : other;
    }

    public long orElseGet(LongSupplier supplier) {
        return isPresent ? value : supplier.getAsLong();
    }

    public long orElseThrow() {
        if(!isPresent)
            throw new NoSuchElementException("No value present");
        return value;
    }

    public<X extends Throwable> long orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        if(isPresent)
            return value;
        else
            throw exceptionSupplier.get();
    }

    @Override
    public boolean equals(Object obj) {
        if(this == obj)
            return true;
        return obj instanceof OptionalLong other
                && (isPresent && other.isPresent ? value == other.value : isPresent == other.isPresent);
    }

    @Override
    public int hashCode() {
        return isPresent ? Long.hashCode(value) : 0;
    }

    @Override
    public String toString() {
        return isPresent ? ("OptionalLong[" + value + "]") : "OptionalLong.empty";
    }
}

package com.github.liyibo1110.jdk.java.util;

import java.util.NoSuchElementException;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Optional组件的int专用版本，因为是int所以不会是null，默认值就是0，所以是否存在只能用isPresent标记来判断，而不能用value判断。
 * @author liyibo
 * @date 2026-03-04 14:15
 */
public final class OptionalInt {
    private static final OptionalInt EMPTY = new OptionalInt();

    private final boolean isPresent;
    private final int value;

    private OptionalInt() {
        this.isPresent = false;
        this.value = 0;
    }

    public static OptionalInt empty() {
        return EMPTY;
    }

    private OptionalInt(int value) {
        this.isPresent = true;
        this.value = value;
    }

    public static OptionalInt of(int value) {
        return new OptionalInt(value);
    }

    public int getAsInt() {
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

    public void ifPresent(IntConsumer action) {
        if(isPresent)
            action.accept(value);
    }

    public void ifPresentOrElse(IntConsumer action, Runnable emptyAction) {
        if(isPresent)
            action.accept(value);
        else
            emptyAction.run();
    }

    public IntStream stream() {
        if(isPresent)
            return IntStream.of(value);
        else
            return IntStream.empty();
    }

    public int orElse(int other) {
        return isPresent ? value : other;
    }

    public int orElseGet(IntSupplier supplier) {
        return isPresent ? value : supplier.getAsInt();
    }

    public int orElseThrow() {
        if(!isPresent)
            throw new NoSuchElementException("No value present");
        return value;
    }

    public<X extends Throwable> int orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        if(isPresent)
            return value;
        else
            throw exceptionSupplier.get();
    }

    @Override
    public boolean equals(Object obj) {
        if(this == obj)
            return true;

        return obj instanceof OptionalInt other
                && (isPresent && other.isPresent ? value == other.value : isPresent == other.isPresent);
    }

    @Override
    public int hashCode() {
        return isPresent ? Integer.hashCode(value) : 0;
    }

    @Override
    public String toString() {
        return isPresent ? ("OptionalInt[" + value + "]") : "OptionalInt.empty";
    }
}

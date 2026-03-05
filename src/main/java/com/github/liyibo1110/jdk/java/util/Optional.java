package com.github.liyibo1110.jdk.java.util;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 一个容器对象，可能包含也可能不包含非空值。
 * 若存在值，isPresent()返回true，若不存在则该对象视为空，isPresent()返回false。
 * 还提供了其他依赖于是否包含值的方法，例如orElse()，如果无值则返回默认值，还有ifPresent()，如果存在值则执行操作。
 *
 * 这是基于值的类，开发人员应将相等的实例视为可互换，且不应该将实例用于同步操作，否则可能引发不可预测的行为，
 * 例如在未来版本中，同步操作可能会失败。
 * @author liyibo
 * @date 2026-03-04 13:55
 */
public final class Optional<T> {

    private static final Optional<?> EMPTY = new Optional<>(null);

    private final T value;

    public static<T> Optional<T> empty() {
        Optional<T> t = (Optional<T>)EMPTY;
        return t;
    }

    private Optional(T value) {
        this.value = value;
    }

    public static <T> Optional<T> of(T value) {
        return new Optional<>(Objects.requireNonNull(value));
    }

    /**
     * 返回一个描述给定值的Optional，如果值不为空则返回该值，否则返回empty的Optional。
     */
    public static <T> Optional<T> ofNullable(T value) {
        return value == null ? (Optional<T>)EMPTY : new Optional<>(value);
    }

    /**
     * 如果存在值则返回，否则抛出异常
     */
    public T get() {
        if(value == null)
            throw new NoSuchElementException("No value present");
        return value;
    }

    public boolean isPresent() {
        return value != null;
    }

    public boolean isEmpty() {
        return value == null;
    }

    /**
     * 如果存在值，则对value执行action，否则啥也不干
     */
    public void ifPresent(Consumer<? super T> action) {
        if(value != null)
            action.accept(value);
    }

    /**
     * 如果存在值，则对value执行action，否则执行Runnable
     */
    public void ifPresentOrElse(Consumer<? super T> action, Runnable emptyAction) {
        if(value != null)
            action.accept(value);
        else
            emptyAction.run();
    }

    /**
     * 如果存在值，并且匹配Predicate，则返回自身Optional，否则返回empty的Optional
     */
    public Optional<T> filter(Predicate<? super T> predicate) {
        Objects.requireNonNull(predicate);
        if(!isPresent())
            return this;
        else
            return predicate.test(value) ? this : empty();
    }

    public <U> Optional<U> map(Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper);
        if(!isPresent())
            return empty();
        else
            return Optional.ofNullable(mapper.apply(value));
    }

    /**
     * 类似于map(Function)版本，但mapper函数本身返回值已经是Optional了，且flatMap调用时不会将其额外包裹在Optional中
     * 意思就是这里的mapper返回值，本身就需要时一个Optional实例，并不是返回计算后的value
     */
    public <U> Optional<U> flatMap(Function<? super T, ? extends Optional<? extends U>> mapper) {
        Objects.requireNonNull(mapper);
        if(!isPresent())
            return empty();
        else {
            Optional<U> r = (Optional<U>)mapper.apply(value);
            return Objects.requireNonNull(r);   // 防止Function返回的本来就是null
        }
    }

    /**
     * 如果存在值，则返回该值的Optional，否则返回Supplier提供的Optional
     */
    public Optional<T> or(Supplier<? extends Optional<? extends T>> supplier) {
        Objects.requireNonNull(supplier);
        if(isPresent())
            return this;
        else {
            Optional<T> r = (Optional<T>)supplier.get();
            return Objects.requireNonNull(r);
        }
    }

    public Stream<T> stream() {
        if(!isPresent())
            return Stream.empty();
        else
            return Stream.of(value);
    }

    public T orElse(T other) {
        return value != null ? value : other;
    }

    public T orElseGet(Supplier<? extends T> supplier) {
        return value != null ? value : supplier.get();
    }

    public T orElseThrow() {
        if(value == null)
            throw new NoSuchElementException("No value present");
        return value;
    }

    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        if(value != null)
            return value;
        else
            throw exceptionSupplier.get();
    }

    @Override
    public boolean equals(Object obj) {
        if(this == obj)
            return true;
        return obj instanceof Optional<?> other && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value != null ? ("Optional[" + value + "]") : "Optional.empty";
    }
}

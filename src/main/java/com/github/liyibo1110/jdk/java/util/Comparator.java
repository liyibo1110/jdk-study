package com.github.liyibo1110.jdk.java.util;

import java.io.Serializable;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Comparator是一种比较函数，它对对象集合施加全序关系，可以传递给排序方法（Collection.sort或Arrays.sort），以实现对排序顺序的精确控制。
 * Comparator还可用于控制特定数据结果（如SortSet或SortMap）的顺序，或为不具备自然排序关系的对象集合提供排序机制。
 *
 * Comparator对元素集合S施加排序关系，当且仅当满足以下条件时称为与equals一致：
 * 对于集合S中的任意e1和e2，c.compare(e1, e2) == 0的布尔值等同于e1.equals(e2)的返回值。
 *
 * 使用可能施加与equals不一致排序关系的Comparator对排序集合（或排序映射）进行排序时需谨慎。
 * 假设使用显式比较器c对集合S中的元素（或键）构建排序集合（或排序映射）。
 * 若c在S上施加的排序与equals不一致，则排序集合（或排序映射）将表现出“异常”行为。尤其会违反基于equals定义的集合（或映射）通用契约。
 *
 * 例如，假设向使用比较器c的空TreeSet中添加两个元素a和b，满足(a.equals(b) && c.compare(a, b) != 0)。
 * 第二次添加操作将返回true（且树集合的大小会增加），因为从树集合的角度看a和b不等价——尽管这违背了Set.add方法的规范。
 *
 * 注：比较器通常应同时实现java.io.Serializable接口，因为它们可能在序列化数据结构（如 TreeSet、TreeMap）中作为排序方法使用。
 * 为使数据结构成功序列化，提供的比较器必须实现Serializable接口。
 * @author liyibo
 * @date 2026-03-04 14:30
 */
@FunctionalInterface
public interface Comparator<T> {

    /**
     * 比较两个参数的大小顺序。当第一个参数小于、等于或大于第二个参数时，分别返回负整数、零或正整数。
     */
    int compare(T o1, T o2);

    boolean equals(Object obj);

    default Comparator<T> reversed() {
        return Collections.reverseOrder(this);
    }

    /**
     * 返回一个基于字典顺序的Comparator，与另一个Comparator协同工作，当Comparator认为两个元素相等，则使用另一个Comparator继续比较。
     */
    default Comparator<T> thenComparing(Comparator<? super T> other) {
        Objects.requireNonNull(other);
        return (Comparator<T> & Serializable) (c1, c2) -> {
            int res = compare(c1, c2);
            return res != 0 ? res : other.compare(c1, c2);
        };
    }

    default <U> Comparator<T> thenComparing(Function<? super T, ? extends U> keyExtractor, Comparator<? super U> keyComparator) {
        return thenComparing(comparing(keyExtractor, keyComparator));
    }

    default <U extends Comparable<? super U>> Comparator<T> thenComparing(Function<? super T, ? extends U> keyExtractor) {
        return thenComparing(comparing(keyExtractor));
    }

    default Comparator<T> thenComparingInt(ToIntFunction<? super T> keyExtractor) {
        return thenComparing(comparingInt(keyExtractor));
    }

    default Comparator<T> thenComparingLong(ToLongFunction<? super T> keyExtractor) {
        return thenComparing(comparingLong(keyExtractor));
    }

    default Comparator<T> thenComparingDouble(ToDoubleFunction<? super T> keyExtractor) {
        return thenComparing(comparingDouble(keyExtractor));
    }

    static <T extends Comparable<? super T>> Comparator<T> reverseOrder() {
        return Collections.reverseOrder();
    }

    static <T extends Comparable<? super T>> Comparator<T> naturalOrder() {
        return (Comparator<T>)Comparators.NaturalOrderComparator.INSTANCE;
    }

    static <T> Comparator<T> nullsFirst(Comparator<? super T> comparator) {
        return new Comparators.NullComparator<>(true, comparator);
    }

    static <T> Comparator<T> nullsLast(Comparator<? super T> comparator) {
        return new Comparators.NullComparator<>(false, comparator);
    }

    /**
     * 接收一个从类型T中提取排序key的Function，并返回一个Comparator。
     * 该Comparator使用指定的Comparator通过该排序key进行比较。
     */
    static <T, U> Comparator<T> comparing(Function<? super T, ? extends U> keyExtractor, Comparator<? super U> keyComparator) {
        Objects.requireNonNull(keyExtractor);
        Objects.requireNonNull(keyComparator);
        return (Comparator<T> & Serializable) (c1, c2) -> keyComparator.compare(keyExtractor.apply(c1), keyExtractor.apply(c2));
    }

    static <T, U extends Comparable<? super U>> Comparator<T> comparing(Function<? super T, ? extends U> keyExtractor) {
        Objects.requireNonNull(keyExtractor);
        return (Comparator<T> & Serializable) (c1, c2) -> keyExtractor.apply(c1).compareTo(keyExtractor.apply(c2));
    }

    static <T> Comparator<T> comparingInt(ToIntFunction<? super T> keyExtractor) {
        Objects.requireNonNull(keyExtractor);
        return (Comparator<T> & Serializable) (c1, c2) -> Integer.compare(keyExtractor.applyAsInt(c1), keyExtractor.applyAsInt(c2));
    }

    static <T> Comparator<T> comparingLong(ToLongFunction<? super T> keyExtractor) {
        Objects.requireNonNull(keyExtractor);
        return (Comparator<T> & Serializable) (c1, c2) -> Long.compare(keyExtractor.applyAsLong(c1), keyExtractor.applyAsLong(c2));
    }

    static<T> Comparator<T> comparingDouble(ToDoubleFunction<? super T> keyExtractor) {
        Objects.requireNonNull(keyExtractor);
        return (Comparator<T> & Serializable) (c1, c2) -> Double.compare(keyExtractor.applyAsDouble(c1), keyExtractor.applyAsDouble(c2));
    }
}

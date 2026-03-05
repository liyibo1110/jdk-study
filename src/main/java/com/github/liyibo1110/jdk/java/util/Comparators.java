package com.github.liyibo1110.jdk.java.util;

import java.io.Serializable;
import java.util.Objects;

/**
 * @author liyibo
 * @date 2026-03-04 14:59
 */
class Comparators {
    private Comparators() {
        throw new AssertionError("no instances");
    }

    /**
     * 基于自然排序（Comparable.compareTo）的Comparator实现
     */
    enum NaturalOrderComparator implements Comparator<Comparable<Object>> {
        INSTANCE;

        @Override
        public int compare(Comparable<Object> c1, Comparable<Object> c2) {
            return c1.compareTo(c2);
        }

        @Override
        public Comparator<Comparable<Object>> reversed() {
            return Comparator.reverseOrder();
        }
    }

    /**
     * 额外处理null值的Comparator实现
     */
    static final class NullComparator<T> implements Comparator<T>, Serializable {
        @java.io.Serial
        private static final long serialVersionUID = -7569533591570686392L;

        private final boolean nullFirst;
        private final Comparator<T> real;

        NullComparator(boolean nullFirst, Comparator<? super T> real) {
            this.nullFirst = nullFirst;
            this.real = (Comparator<T>) real;
        }

        @Override
        public int compare(T a, T b) {
            if(a == null)
                return b == null ? 0 : (nullFirst ? -1 : 1);
            else if(b == null)
                return nullFirst ? 1 : -1;
            else
                return real == null ? 0 : real.compare(a, b);
        }

        @Override
        public Comparator<T> thenComparing(Comparator<? super T> other) {
            Objects.requireNonNull(other);
            return new NullComparator<>(nullFirst, real == null ? other : real.thenComparing(other));
        }

        @Override
        public Comparator<T> reversed() {
            return new NullComparator<>(!nullFirst, real == null ? null : real.reversed());
        }
    }
}

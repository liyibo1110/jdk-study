package com.github.liyibo1110.jdk.java.util;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * 返回Java原生类型专用的Iterator实现
 * <T>  此Iterator返回元素的类型，必须是基本类型的包装器类型
 * <T_CONS>  基本消费者类型，必须是T的Consumer的基本类型特化，例如T是Integer，则T_CONS就是IntConsumer
 * @author liyibo
 * @date 2026-03-03 19:07
 */
public interface PrimitiveIterator<T, T_CONS> extends Iterator<T> {

    void forEachRemaining(T_CONS action);

    interface OfInt extends PrimitiveIterator<Integer, IntConsumer> {
        int nextInt();

        default void forEachRemaining(IntConsumer action) {
            Objects.requireNonNull(action);
            while(hasNext())
                action.accept(nextInt());
        }

        @Override
        default Integer next() {
            return nextInt();
        }

        @Override
        default void forEachRemaining(Consumer<? super Integer> action) {
            if(action instanceof IntConsumer)
                forEachRemaining((IntConsumer)action);
            else {
                Objects.requireNonNull(action);
                forEachRemaining((IntConsumer)action::accept);
            }
        }
    }

    interface OfLong extends PrimitiveIterator<Long, LongConsumer> {
        long nextLong();

        default void forEachRemaining(LongConsumer action) {
            Objects.requireNonNull(action);
            while(hasNext())
                action.accept(nextLong());
        }

        @Override
        default Long next() {
            return nextLong();
        }

        @Override
        default void forEachRemaining(Consumer<? super Long> action) {
            if(action instanceof LongConsumer)
                forEachRemaining((LongConsumer)action);
            else {
                Objects.requireNonNull(action);
                forEachRemaining((LongConsumer)action::accept);
            }
        }
    }

    interface OfDouble extends PrimitiveIterator<Double, DoubleConsumer> {
        double nextDouble();

        default void forEachRemaining(DoubleConsumer action) {
            Objects.requireNonNull(action);
            while(hasNext())
                action.accept(nextDouble());
        }

        @Override
        default Double next() {
            return nextDouble();
        }

        @Override
        default void forEachRemaining(Consumer<? super Double> action) {
            if(action instanceof DoubleConsumer)
                forEachRemaining((DoubleConsumer)action);
            else {
                Objects.requireNonNull(action);
                forEachRemaining((DoubleConsumer)action::accept);
            }
        }
    }
}

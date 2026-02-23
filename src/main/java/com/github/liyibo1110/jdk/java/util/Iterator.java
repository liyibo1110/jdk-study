package com.github.liyibo1110.jdk.java.util;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 用于遍历集合，在Java集合框架中，遍历器取代了Enumeration的作用，它与枚举器存在两点差异：
 * 1、迭代器允许调用方在迭代过程中，移除底层集合中的元素，且具有明确定义的语义。
 * 2、方法名称已得到改进。
 * @author liyibo
 * @date 2026-02-22 20:03
 */
public interface Iterator<E> {

    boolean hasNext();

    E next();

    /**
     * 从底层集合移除本迭代器返回的最后一个元素（可选操作），每次调用next方法时，此方法仅可调用一次。
     * 若在迭代过程中通过除了调用本方法外的任何方式修改了底层集合，迭代器的行为将未定义，除非重写类已指定并发修改的策略。
     * 若在调用forEachRemaining方法后再次调用本方法，迭代器的行为将未定义。
     */
    default void remove() {
        throw new UnsupportedOperationException("remove");
    }

    /**
     * 对每个剩余元素执行给定操作，直到所有元素处理完毕或操作抛出异常。
     * 若指定迭代顺序，则按该顺序执行操作，操作抛出的异常将传递给调用方。
     * 若操作以任何方式修改集合（包括调用remove方法或其他Iterator子类的修改方法），则迭代器的行为未作规定，除非重写类已指定并发修改策略。
     * 若操作抛出异常，迭代器的后续行为未作规定。
     */
    default void forEachRemaining(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        while(hasNext())
            action.accept(this.next());
    }
}

package com.github.liyibo1110.jdk.java.util;

import java.util.Comparator;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * 用于遍历和分割源元素的对象。Spliterator所处理的元素源可以是数组、集合、IO通道或生成器函数等。
 * Spliterator可逐个遍历元素（通过tryAdvance()），也可批量顺序遍历（通过forEachRemaining()）。
 * Spliterator 还可通过trySplit将部分元素拆分出另一个 Spliterator，用于可能的并行操作。
 * 若使用无法拆分或拆分方式极不均衡/低效的Spliterator进行操作，则难以获得并行处理的效益。
 * 遍历与拆分操作会耗尽元素；每个Spliterator仅适用于单次批量计算。
 *
 * Spliterator还会报告其结构、源及元素的一组特征（characteristics()），可选值包括：
 * ORDERED（有序）、
 * DISTINCT（唯一）、
 * SORTED（排序）、
 * SIZED（已计数）、
 * NONNULL（非空）、
 * IMMUTABLE（不可变）、
 * CONCURRENT（并发）
 * SUBSIZED（子集已计数）。
 * 这些特征可供Spliterator客户端用于控制、优化或简化计算。
 * 例如：集合的Spliterator会报告SIZED特性，集合的Spliterator会报告DISTINCT特性，排序集合的Spliterator还会报告SORTED特性。
 * 特性以简单联合位集形式报告。部分特性会额外约束方法行为，例如当报告ORDERED时，遍历方法必须遵循其文档定义的排序规则。
 * 未来可能新增特性，因此实现者不应为未列出的值赋予含义。
 *
 * 未声明IMMUTABLE或CONCURRENT的Spliterator应具备以下文档化策略：
 * 元素源的绑定时机；以及绑定后检测到的元素源结构干扰。
 * 晚绑定Spliterator在首次遍历、首次拆分或首次查询估计大小时绑定元素源，而非在Spliterator创建时绑定。
 * 非延迟绑定的Spliterator在构造时或首次调用任何方法时绑定元素源。绑定前对源的修改将在遍历时生效。
 * 绑定后若检测到结构干扰，Spliterator应尽最大努力抛出ConcurrentModificationException。
 * 具备此行为的Spliterator称为快速失败型。Spliterator 的批量遍历方法（forEachRemaining()）可优化遍历流程，在遍历所有元素后统一检查结构干扰，而非逐元素检查并立即失败。
 *
 * Spliterator 可通过 estimateSize 方法提供剩余元素数量的估计值。
 * 理想情况下（如特征 SIZED 所示），该值应与成功遍历时实际遇到的元素数量完全一致。
 * 然而，即使无法精确知晓，估计值仍可为源数据操作提供价值，例如帮助判断是继续拆分还是顺序遍历剩余元素更优。
 *
 * 尽管在并行算法中具有明显实用性，但拆分器本身并不具备线程安全特性；使用拆分器的并行算法实现应确保拆分器每次仅被单线程使用。
 * 通常通过串行线程限制即可轻松实现此目标，而递归分解的典型并行算法往往自然具备此特性。
 * 调用trySplit()的线程可将返回的Spliterator传递给另一线程，后者进而可遍历或继续分割该Spliterator。
 * 若两个或多个线程并发操作同一Spliterator，其分割与遍历行为将无法定义。
 * 若原始线程将Spliterator传递给其他线程处理，最佳实践是在任何元素被tryAdvance()消耗前完成交接，因为某些保证（如SIZED类型Spliterator的estimateSize()精度）仅在遍历开始前有效。
 *
 * Spliterator为int、long和double类型提供了原始子类型特化实现。
 * 子类的默认实现会将tryAdvance(Consumer)和forEachRemaining(Consumer)中的基本类型值装箱为对应包装类的实例。
 * 这种装箱操作可能抵消使用基本类型特化实现带来的性能优势。为避免装箱，应使用对应的基本类型方法。
 * 例如，应优先使用Spliterator.OfInt.tryAdvance(IntConsumer)和Spliterator.OfInt.forEachRemaining(IntConsumer)，而非Spliterator.tryAdvance(IntConsumer)和Spliterator.forEachRemaining(IntConsumer)。
 * OfInt. forEachRemaining(IntConsumer) 优先于Spliterator.OfInt. tryAdvance(Consumer)和Spliterator.OfInt. forEachRemaining(Consumer)。
 * 使用基于装箱的方法 tryAdvance() 和 forEachRemaining() 遍历基本类型值时，不会改变值被转换为装箱值后的遍历顺序。
 *
 * 通俗地说：Spliterator = Split + Iterator，为并行分治算法设计的数据遍历抽象，
 * Iterator有以下功能缺失：
 * 1、不支持拆分，只能一个一个往前遍历。
 * 2、hasNext和next方法存在竞态窗口。
 * 3、不知道内部一共有多少数据，以及还有多少数据
 * @author liyibo
 * @date 2026-03-03 15:13
 */
public interface Spliterator<T> {

    /**
     * 如果还有元素，就consume一个并返回true，否则返回false
     * 相当于整合了hasNext和next，避免了竞态窗口。
     */
    boolean tryAdvance(Consumer<? super T> action);

    /**
     * 默认实现，子类的实现可能细节不一样
     */
    default void forEachRemaining(Consumer<? super T> action) {
        do {

        } while (tryAdvance(action));
    }

    /**
     * 尝试把当前数据一分为二，返回前一半，自己保留后一半
     * 例如原来区间[0, 100)，split后返回[0, 50)，当前数据变成[50, 100)
     * 最重要的方法，具体实现决定了并行的性能。
     */
    Spliterator<T> trySplit();

    /**
     * 返回剩余元素数据估计值，如果是SIZED，那么必须准确，如果不是则可以估算。
     * 这个值来决定：是否要继续拆分。
     */
    long estimateSize();

    default long getExactSizeIfKnown() {
        return (characteristics() & SIZED) == 0 ? -1L : estimateSize();
    }

    /**
     * 返回一个bitmask，作为元数据，具体特性如下：
     * ORDERED：有顺序
     * DISTINCT：无重复
     * SORTED：已排序
     * SIZED：大小已知
     * SUBSIZED：子区间也精确
     * IMMUTABLE：不可修改
     * CONCURRENT：支持并发修改
     * 实现举例：
     * ArrayList Spliterator：ORDERED | SIZED | SUBSIZED
     * HashSet Spliterator：DISTINCT | SIZED
     * TreeSet Spliterator：SORTED | ORDERED | DISTINCT | SIZED
     * Stream框架会根据这些特性：
     * 1、是否跳过distinct操作。
     * 2、是否可以跳过sorted。
     * 3、是否可以优化limit。
     * 4、是否可以使用更快的collector。
     * 属于一种声明式优化机制。
     */
    int characteristics();

    default boolean hasCharacteristics(int characteristics) {
        return (characteristics() & characteristics) == characteristics;
    }

    default Comparator<? super T> getComparator() {
        throw new IllegalStateException();
    }

    int ORDERED = 0x00000010;
    int DISTINCT = 0x00000001;
    int SORTED = 0x00000004;
    int SIZED = 0x00000040;
    int NONNULL = 0x00000100;
    int IMMUTABLE = 0x00000400;
    int CONCURRENT = 0x00001000;
    int SUBSIZED = 0x00004000;

    /**
     * 返回Java原生类型专用的Spliterator实现
     * @param <T>   此Spliterator返回元素的类型，必须是基本类型的包装器类型
     * @param <T_CONS>  基本消费者类型，必须是T的Consumer的基本类型特化，例如T是Integer，则T_CONS就是IntConsumer
     * @param <T_SPLITR> 基本类型Spliterator的类型，必须是T的Spliterator的基本类型特化，例如T是Integer，T_SPLITR就是Spliterator.OfInt
     */
    interface OfPrimitive<T, T_CONS, T_SPLITR extends Spliterator.OfPrimitive<T, T_CONS, T_SPLITR>> extends Spliterator<T> {
        @Override
        T_SPLITR trySplit();

        boolean tryAdvance(T_CONS action);

        default void forEachRemaining(T_CONS action) {
            do {

            } while (tryAdvance(action));
        }
    }

    interface OfInt extends OfPrimitive<Integer, IntConsumer, OfInt> {
        @Override
        OfInt trySplit();

        @Override
        boolean tryAdvance(IntConsumer action);

        @Override
        default void forEachRemaining(IntConsumer action) {
            do {

            } while (tryAdvance(action));
        }

        @Override
        default boolean tryAdvance(Consumer<? super Integer> action) {
            if(action instanceof IntConsumer)
                return tryAdvance((IntConsumer)action);
            else
                return tryAdvance((IntConsumer)action::accept);
        }

        @Override
        default void forEachRemaining(Consumer<? super Integer> action) {
            if(action instanceof IntConsumer)
                forEachRemaining((IntConsumer)action);
            else
                forEachRemaining((IntConsumer)action::accept);
        }
    }

    interface OfLong extends OfPrimitive<Long, LongConsumer, OfLong> {
        @Override
        OfLong trySplit();

        @Override
        boolean tryAdvance(LongConsumer action);

        @Override
        default void forEachRemaining(LongConsumer action) {
            do {

            } while (tryAdvance(action));
        }

        @Override
        default boolean tryAdvance(Consumer<? super Long> action) {
            if(action instanceof LongConsumer)
                return tryAdvance((LongConsumer)action);
            else
                return tryAdvance((LongConsumer)action::accept);
        }

        @Override
        default void forEachRemaining(Consumer<? super Long> action) {
            if(action instanceof LongConsumer)
                forEachRemaining((LongConsumer)action);
            else
                forEachRemaining((LongConsumer)action::accept);
        }
    }

    interface OfDouble extends OfPrimitive<Double, DoubleConsumer, OfDouble> {
        @Override
        OfDouble trySplit();

        @Override
        boolean tryAdvance(DoubleConsumer action);

        @Override
        default void forEachRemaining(DoubleConsumer action) {
            do {

            } while (tryAdvance(action));
        }

        @Override
        default boolean tryAdvance(Consumer<? super Double> action) {
            if(action instanceof DoubleConsumer)
                return tryAdvance((DoubleConsumer)action);
            else
                return tryAdvance((DoubleConsumer)action::accept);
        }

        @Override
        default void forEachRemaining(Consumer<? super Double> action) {
            if(action instanceof DoubleConsumer)
                forEachRemaining((DoubleConsumer)action);
            else
                forEachRemaining((DoubleConsumer)action::accept);
        }
    }
}

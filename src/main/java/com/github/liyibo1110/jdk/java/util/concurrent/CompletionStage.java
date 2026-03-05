package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 官方冗长的注释不翻译了，通俗地说：是异步计算流水线的一个阶段（Stage），每个Stage：
 * 1、等待前一个Stage完成。
 * 2、用结果执行新的计算。
 * 3、生成新的Stage。
 * CompletionStage只解决三件事：
 * 1、触发（trigger）
 * 2、组合（combine）
 * 3、异常处理（exception）
 * 然后又提供三种执行方式：
 * 1、同步执行。
 * 2、默认异步执行。
 * 3、执行Executor执行。
 * API看起来很多，其实都是排序组合。
 *
 * 记忆法：整个接口的方法可以分成六大类：
 * 1、单阶段转换（map）：thenApply、thenApplyAsync、thenApplyAsync(executor)
 * 2、单阶段消费：thenAccept、thenRun
 * 3、双阶段组合（等待两个Stage完成）：thenCombine、thenAcceptBoth、runAfterBoth
 * 4、竞速（either，即两个stage谁先完成就用谁）：applyToEither、acceptEither、runAfterEither
 * 5、扁平化（flatMap）：thenCompose
 * 6、异常处理：exceptionally、handle、whenComplete
 *
 * CompletionStage不做的事情：
 * 1、创建任务
 * 2、完成任务
 * 3、获取结果
 * 4、等待结果
 * 上述功能会在CompletableFuture实现
 *
 * CompletionStage可以看作是一个异步版本的Stream：
 * 1、map -> thenApply
 * 2、flatMap -> thenCompose
 * 3、forEach -> thenAccept
 * 4、combine -> thenCombine
 * @author liyibo
 * @date 2026-03-04 21:29
 */
public interface CompletionStage<T> {

    <U> CompletionStage<U> thenApply(Function<? super T, ? extends U> fn);
    <U> CompletionStage<U> thenApplyAsync(Function<? super T,? extends U> fn);
    <U> CompletionStage<U> thenApplyAsync(Function<? super T,? extends U> fn, Executor executor);

    CompletionStage<Void> thenAccept(Consumer<? super T> action);
    CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action);
    CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor);

    CompletionStage<Void> thenRun(Runnable action);
    CompletionStage<Void> thenRunAsync(Runnable action);
    CompletionStage<Void> thenRunAsync(Runnable action, Executor executor);

    <U, V> CompletionStage<V> thenCombine(CompletionStage<? extends U> other, BiFunction<? super T,? super U,? extends V> fn);
    <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T,? super U,? extends V> fn);
    <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T,? super U,? extends V> fn, Executor executor);

    <U> CompletionStage<Void> thenAcceptBoth(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action);
    <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action);
    <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor);

    CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action);
    CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action);
    CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor);

    <U> CompletionStage<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn);
    <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn);
    <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn, Executor executor);

    CompletionStage<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action);
    CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action);
    CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor);

    CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action);
    CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action);
    CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor);

    /**
     * 非常重要
     */
    <U> CompletionStage<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn);
    <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn);
    <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn, Executor executor);

    <U> CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> fn);
    <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn);
    <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor);

    CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action);
    CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action);
    CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor);

    CompletionStage<T> exceptionally(Function<Throwable, ? extends T> fn);

    default CompletionStage<T> exceptionallyAsync(Function<Throwable, ? extends T> fn) {
        return handle((r, ex) -> (ex == null)
            ? this
            : this.<T>handleAsync((r1, ex1) -> fn.apply(ex1)))
            .thenCompose(Function.identity());
    }

    default CompletionStage<T> exceptionallyAsync(Function<Throwable, ? extends T> fn, Executor executor) {
        return handle((r, ex) -> (ex == null)
                ? this
                : this.<T>handleAsync((r1, ex1) -> fn.apply(ex1), executor))
                .thenCompose(Function.identity());
    }

    default CompletionStage<T> exceptionallyCompose(Function<Throwable, ? extends CompletionStage<T>> fn) {
        return handle((r, ex) -> (ex == null)
                ? this
                : fn.apply(ex))
                .thenCompose(Function.identity());
    }

    /**
     * 这个相对复杂一点：如果当前stage结果是正常，就原样返回，如果是异常，就用一个函数生成一个新的stage，并把它异步展开（flatten）
     */
    default CompletionStage<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> fn) {
        return handle((r, ex) -> (ex == null)
                ? this  // 注意这里返回类型是：CompletionStage<CompletionStage<T>>，然后直接找最后一个thenCompose解出来
                : this.handleAsync((r1, ex1) -> fn.apply(ex1)).thenCompose(Function.identity()))
                .thenCompose(Function.identity());
    }

    default CompletionStage<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> fn, Executor executor) {
        return handle((r, ex) -> (ex == null)
                ? this
                : this.handleAsync((r1, ex1) -> fn.apply(ex1), executor).thenCompose(Function.identity()))
                .thenCompose(Function.identity());
    }

    /**
     * 返回一个与当前阶段具有相同完成属性的CompletableFuture。
     * 如果当前阶段本身已经是CompletableFuture，则可能直接返回，
     * 否则调用效果可能等同于thenApply(x -> x)，但返回类型为CompletableFuture
     */
    CompletableFuture<T> toCompletableFuture();
}

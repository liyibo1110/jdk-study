package com.github.liyibo1110.jdk.java.lang;

import java.util.Iterator;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 实现此接口可使对象成为foreach语句的目标。
 * 注意目前不包括Stream相关的方法。
 * @author liyibo
 * @date 2026-02-22 20:02
 */
public interface Iterable<T> {
    Iterator<T> iterator();

    default void forEach(Consumer<? super T> action) {
        Objects.requireNonNull(action);
        for(T t : this) {   // 不是java.lang的版本，所以行不通
            action.accept(t);
        }
    }
}

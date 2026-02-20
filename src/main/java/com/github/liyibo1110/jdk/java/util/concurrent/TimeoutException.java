package com.github.liyibo1110.jdk.java.util.concurrent;

/**
 * 当阻塞操作超时时，抛出的异常。
 * 对于指定了超时限制的阻塞操作，需要一种机制来指示超时已经发生。
 * 对于许多此类操作，可以返回一个表示超时的值，当无法或不宜这样做时，应声明并抛出超时异常。
 * @author liyibo
 * @date 2026-02-20 00:40
 */
public class TimeoutException extends Exception {
    private static final long serialVersionUID = 1900926677490660714L;

    public TimeoutException() {}

    public TimeoutException(String message) {
        super(message);
    }
}

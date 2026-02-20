package com.github.liyibo1110.jdk.java.util.concurrent;

/**
 * 在尝试检索因抛出异常而中止的任务结果时，会抛出此异常。
 * 可以用过getCause方法检查该异常原本信息。
 * @author liyibo
 * @date 2026-02-20 00:38
 */
public class ExecutionException extends Exception {

    private static final long serialVersionUID = 7830266012832686185L;

    protected ExecutionException() {}

    protected ExecutionException(String message) {
        super(message);
    }

    public ExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public ExecutionException(Throwable cause) {
        super(cause);
    }
}

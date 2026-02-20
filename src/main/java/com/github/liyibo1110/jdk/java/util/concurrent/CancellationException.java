package com.github.liyibo1110.jdk.java.util.concurrent;

/**
 * 用于表示无法获取值生成任务（如FutureTask）的结果，因为该任务已被取消。
 * @author liyibo
 * @date 2026-02-20 00:37
 */
public class CancellationException extends IllegalStateException {
    private static final long serialVersionUID = -9202173006928992231L;

    public CancellationException() {}

    public CancellationException(String message) {
        super(message);
    }
}

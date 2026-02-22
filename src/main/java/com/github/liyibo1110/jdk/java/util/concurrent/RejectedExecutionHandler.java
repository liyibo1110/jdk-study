package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 用于处理无法由executor执行的任务的处理器
 * @author liyibo
 * @date 2026-02-21 16:06
 */
public interface RejectedExecutionHandler {

    void rejectedExecution(Runnable r, ThreadPoolExecutor executor);
}

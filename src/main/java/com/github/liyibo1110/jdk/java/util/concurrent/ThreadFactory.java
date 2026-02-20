package com.github.liyibo1110.jdk.java.util.concurrent;

/**
 * 按需创建新Thread对象。
 * 使用ThreadFactory可以避免硬编码调用new Thread()，使应用程序能够使用特殊线程子类、优先级等特性。
 * @author liyibo
 * @date 2026-02-19 21:50
 */
public interface ThreadFactory {

    /**
     * 创建新Thread，实现还可能初始化优先级、名称、守护进程状态、线程组等。
     */
    Thread newThread(Runnable r);
}

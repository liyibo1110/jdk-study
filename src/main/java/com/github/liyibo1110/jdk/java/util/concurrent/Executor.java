package com.github.liyibo1110.jdk.java.util.concurrent;

/**
 * @author liyibo
 * @date 2026-02-19 17:54
 */
public interface Executor {

    /**
     * 在未来的某个时间点，执行给定的runnable。
     * 根据Executor实现的不同，该runnable可能会在新线程、池线程或调用线程中执行。
     */
    void execute(Runnable command);
}

package com.github.liyibo1110.jdk.java.util;

/**
 * Timer具体要执行的任务，除了封装任务本身，还附带了定时任务状态的相关信息。
 * @author liyibo
 * @date 2026-04-01 13:35
 */
public abstract class TimerTask implements Runnable {

    /** 用于控制对TimerTask内部机制的访问 */
    final Object lock = new Object();

    /** 当前任务状态 */
    int state = VIRGIN;

    /** 未被调度状态 */
    static final int VIRGIN = 0;

    /** 已参与调度，如果是非重复性任务，则还未运行 */
    static final int SCHEDULED = 1;

    /** 此非重复任务已执行（或正在执行），且尚未被取消 */
    static final int EXECUTED = 2;

    /** 任务已被取消，通过调用cancel方法 */
    static final int CANCELLED = 3;

    /**
     * 该任务的下一次执行时间，格式为System.currentTimeMillis返回的值，前提是该任务已被安排执行。
     * 对于重复任务，该字段会在每次任务执行前更新。
     */
    long nextExecutionTime;

    /**
     * 重复任务的周期（以毫秒为单位）。正值表示固定频率（fixed-rate）执行。负值表示固定延迟（fixed-delay）执行。值为 0 表示非重复任务。
     */
    long period = 0;

    protected TimerTask() {}

    /**
     * 要执行的任务
     */
    public abstract void run();

    /**
     * 取消此定时器任务。如果该任务被安排为一次性执行且尚未运行，或者尚未被安排，则它将永远不会运行。
     * 如果该任务被安排为重复执行，则它将不再运行。（如果调用此方法时任务正在运行，该任务将运行至完成，但此后将不再运行。）
     *
     * 请注意，在重复定时器任务的run方法内部调用此方法，绝对保证该定时器任务不会再次运行。
     * 此方法可以被多次调用；第二次及后续的调用均无效
     */
    public boolean cancel() {
        synchronized (lock) {
            boolean result = (state == SCHEDULED);
            state = CANCELLED;
            return result;  // true代表之前是SCHEDULED状态
        }
    }

    public long scheduledExecutionTime() {
        synchronized(lock) {
            /**
             * period为负数代表fixed-delay模式，即上一次任务执行完毕了，才会按照delay执行下一次的执行时间。
             * period为正数代表fixed-rate模式，即从任务开始时就计算时间，到了固定delay直接尝试执行下一次，如果上一次还没完成则不执行下一次。
             */
            return period < 0
                    ? nextExecutionTime + period
                    : nextExecutionTime - period;
        }
    }
}

package com.github.liyibo1110.jdk.java.lang;

/**
 * 当线程处于等待、睡眠或其他占用状态时，若该线程在活动开始前或进行过程中被中断，则抛出此异常。
 * 某些方法可能需要检测当前线程是否已被中断，若已中断则立即抛出此异常。
 * @author liyibo
 * @date 2026-02-20 00:43
 */
public class InterruptedException extends Exception {
    private static final long serialVersionUID = 6700697376100628473L;

    public InterruptedException() {
        super();
    }

    public InterruptedException(String message) {
        super(message);
    }
}

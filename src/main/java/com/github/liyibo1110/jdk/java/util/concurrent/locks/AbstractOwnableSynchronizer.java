package com.github.liyibo1110.jdk.java.util.concurrent.locks;

import java.io.Serializable;

/**
 * 一种可能被线程独占拥有的同步器。该类为创建可能涉及所有权概念的锁及相关同步器提供了基础。
 * AbstractOwnableSynchronizer类本身并不管理或使用此类信息，但子类和工具可利用经过适当维护的值来协助控制和监控访问行为，并提供诊断支持。
 * @author liyibo
 * @date 2026-03-10 14:56
 */
public abstract class AbstractOwnableSynchronizer implements Serializable {
    private static final long serialVersionUID = 3737899427754241961L;

    protected AbstractOwnableSynchronizer() {}

    /** 当前独占模式同步的所有者 */
    private transient Thread exclusiveOwnerThread;

    /**
     * 设置当前拥有独占访问权限的线程。传入null表示没有线程拥有访问权限。
     * 此方法不会强制执行任何同步操作或易失性字段访问。
     */
    protected final void setExclusiveOwnerThread(Thread thread) {
        exclusiveOwnerThread = thread;
    }

    protected final Thread getExclusiveOwnerThread() {
        return exclusiveOwnerThread;
    }
}

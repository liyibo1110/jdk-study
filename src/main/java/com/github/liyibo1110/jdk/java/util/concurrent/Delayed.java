package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.concurrent.TimeUnit;

/**
 * 一种混合式接口，用于标记应在指定延迟后执行的对象。
 * 该接口实现必须定义compareTo方法，其提供的排序规则，需与其getDelay方法保持一致。
 * @author liyibo
 * @date 2026-02-19 21:22
 */
public interface Delayed {

    /**
     * 返回与该对象关联的剩余延迟时间。
     */
    long getDelay(TimeUnit unit);
}

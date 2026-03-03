package com.github.liyibo1110.jdk.java.nio;

import java.lang.ref.Cleaner;

/**
 * @author liyibo
 * @date 2026-03-02 17:12
 */
public interface DirectBuffer {
    long address();

    Object attachment();

    Cleaner cleaner();  // 用的应该是jdk.internal.ref包的
}

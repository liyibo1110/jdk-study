package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;

/**
 * 调用flush方法可以将缓冲输出写入底层流
 * @author liyibo
 * @date 2026-02-27 16:12
 */
public interface Flushable {

    /**
     * 将缓冲的输出写入底层流，从而刷新该流。
     */
    void flush() throws IOException;
}

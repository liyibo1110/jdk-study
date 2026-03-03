package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 可写入字节的Channel。
 * 在任意给定时刻，可写Channel上只能进行一次写操作。若一个线程在Channel上发起写操作，则任何其他试图发起写操作的线程都将阻塞，直至首次操作完成。
 * 其他类型的I/O操作能否与写操作并发进行，取决于通道的类型。
 * @author liyibo
 * @date 2026-03-02 18:59
 */
public interface WritableByteChannel extends Channel {
    /**
     * 和ReadableByteChannel的read(ByteBuffer)时对应的
     */
    int write(ByteBuffer src) throws IOException;
}

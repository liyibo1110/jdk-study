package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 可寻址字节通道，用于维护当前位置并允许更改该位置。
 * SeekableByteChannel连接到某个实体（通常是文件），该实体包含可读写的可变长度字节序列。
 * 当前位置可被查询和修改。该通道还提供访问其连接实体当前大小的功能：当写入字节超出当前大小时，大小会增加；当被截断时，大小会减少。
 *
 * position和truncate方法（这些方法通常不返回值）被指定为返回调用它们的通道。这使得方法调用能够进行链式调用。实现此接口时应指定返回类型，以便在实现类上进行链式方法调用。
 * @author liyibo
 * @date 2026-03-02 21:55
 */
public interface SeekableByteChannel extends ByteChannel {

    /**
     * 从该通道读取字节序列至指定缓冲区。
     * 字节从通道当前位置开始读取，随后位置会根据实际读取的字节数进行更新。除此之外，该方法的行为完全符合ReadableByteChannel接口的规范。
     */
    @Override
    int read(ByteBuffer dst) throws IOException;

    /**
     * 将给定缓冲区中的字节序列写入此通道。
     * 字节将从该通道的当前位置开始写入，除非通道连接的实体（如文件）是以APPEND选项打开的，此时位置会先跳转至末尾。
     * 若需容纳写入的字节，将扩展通道连接的实体，随后根据实际写入字节数更新位置。其他情况下，本方法完全遵循WritableByteChannel接口的规范。
     */
    @Override
    int write(ByteBuffer src) throws IOException;

    /**
     * 返回channel的position
     */
    long position() throws IOException;

    /**
     * 设定channel的position
     */
    SeekableByteChannel position(long newPosition) throws IOException;

    /**
     * 返回当前实体的大小，该实体与本Channel相连。
     */
    long size() throws IOException;

    /**
     * 截断此通道所连接的实体，使其大小符合指定值。
     * 若指定大小小于当前大小，则截断实体，丢弃新结束点之后的所有字节。若指定大小大于或等于当前大小，则实体保持不变。无论哪种情况，若position大于指定大小，则将其设置为该大小。
     */
    SeekableByteChannel truncate(long size) throws IOException;
}

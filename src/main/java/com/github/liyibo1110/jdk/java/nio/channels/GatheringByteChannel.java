package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 一种能够从一组缓冲区序列中写入字节的通道。
 * 聚合写入操作可在单次调用中，从给定缓冲区序列中的一个或多个缓冲区写入字节序列。
 * 当实现网络协议或文件格式时，聚合写入常被采用——例如将数据分组为由一个或多个固定长度头部及随后的可变长度主体组成的分段。
 * 类似的散射读取操作在ScatteringByteChannel接口中定义。
 * @author liyibo
 * @date 2026-03-02 19:11
 */
public interface GatheringByteChannel extends WritableByteChannel {

    /**
     * 将给定缓冲区子序列中的字节序列写入此通道。
     * 尝试向该通道写入最多r个字节，其中r是给定缓冲区数组中特定子序列中剩余字节的总数，即
     * srcs[offset].remaining() + srcs[offset+1].remaining() + ... + srcs[offset+length-1].remaining()在调用本方法时的值。
     *
     * 假设写入长度为n的字节序列（0 <= n <= r）。
     * 序列前srcs[offset].remaining()字节将从缓冲区srcs[offset]写入，随后srcs[offset+1].remaining()字节从缓冲区srcs[offset+1]写入，依此类推直至完整序列写入完毕。
     * 每个缓冲区将尽可能多地写入字节，因此除最后更新的缓冲区外，每个更新缓冲区的最终位置均保证等于该缓冲区的限制值。
     *
     * 除非另有说明，写入操作仅在写入全部r个请求字节后返回。某些类型的通道可能根据其状态仅写入部分字节，甚至可能完全不写入。
     * 例如，非阻塞模式的套接字通道无法写入超过套接字输出缓冲区可用空间的字节。
     *
     * 本方法可随时调用。但若其他线程已在此通道发起写入操作，则调用本方法将阻塞直至前次操作完成。
     */
    long write(ByteBuffer[] srcs, int offset, int length) throws IOException;

    long write(ByteBuffer[] srcs) throws IOException;
}

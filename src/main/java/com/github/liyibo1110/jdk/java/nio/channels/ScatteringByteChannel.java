package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 一个能够将字节读入序列化缓冲区的Channel
 * 散射读取操作通过单次调用，将字节序列读入给定序列中的一个或多个缓冲区。
 * 当实现网络协议或文件格式时，散射读取常被广泛应用——例如将数据分组为由一个或多个固定长度头部及后续可变长度主体组成的分段结构。
 * 类似的聚合写入操作在GatheringByteChannel接口中有所定义。
 * @author liyibo
 * @date 2026-03-02 19:04
 */
public interface ScatteringByteChannel extends ReadableByteChannel {

    /**
     * 从该通道读取字节序列，将其写入给定缓冲区的子序列中。
     * 调用此方法时，将尝试从该通道读取最多r个字节，其中r是给定缓冲区数组中特定子序列当前剩余的总字节数，即
     * dsts[offset].remaining() + dsts[offset+1].remaining() + ... + dsts[offset+length-1].remaining()在调用此方法的时刻。
     *
     * 假设读取长度为n的字节序列（0 <= n <= r）。
     * 该序列中最多前dsts[offset].remaining()字节将传输至缓冲区dsts[offset]，接下来dsts[offset+1].remaining()字节传输至dsts[offset+1]，依此类推，直至完整序列传输完毕。
     * 每个缓冲区将接收尽可能多的字节，因此除最后更新的缓冲区外，其余更新缓冲区的最终位置均保证等于该缓冲区的容量上限。
     *
     * 本方法可随时调用，但若其他线程已在此通道上启动读取操作，则调用本方法将阻塞直至首次操作完成。
     */
    long read(ByteBuffer[] dsts, int offset, int length) throws IOException;

    /**
     * 从该Channel读取字节序列至指定缓冲区。
     * 调用形式为 c.read(dsts) 的方法，其行为完全等同于调用c.read(dsts, 0, dsts.length)；
     */
    long read(ByteBuffer[] dsts) throws IOException;
}

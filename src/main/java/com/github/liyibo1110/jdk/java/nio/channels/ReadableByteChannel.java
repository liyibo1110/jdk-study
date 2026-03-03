package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 一个可读取字节的Channel。
 * 在任意时刻，可读Channel上只能进行一次读取操作。若一个线程在Channel上发起读取操作，则任何其他试图发起读取操作的线程都将阻塞，直至首次操作完成。
 * 其他类型的I/O操作能否与读取操作并行进行，取决于Channel的类型。
 * @author liyibo
 * @date 2026-03-02 18:43
 */
public interface ReadableByteChannel extends Channel {

    /**
     * 从该Channel读取字节序列至指定缓冲区。
     * 尝试从Channel读取最多r个字节，其中r为调用本方法时ByteBuffer（即目标缓冲区剩余字节数）的剩余字节数。
     * 假设读取了长度为n的字节序列（0 <= n <= r），该序列将被传输至缓冲区，使序列首字节位于索引p处，末字节位于索引p + n - 1处（p为调用本方法时缓冲区当前位置）。
     * 返回时缓冲区位置将变为p + n，其边界保持不变。
     *
     * 读取操作可能无法填满缓冲区，甚至可能完全读取不到字节。具体取决于通道的性质和状态。
     * 例如，非阻塞模式的套接字通道无法读取超过套接字输入缓冲区即时可用字节的数量；同理，文件通道无法读取超过文件剩余字节的数量，
     * 但若通道处于阻塞模式且缓冲区至少剩余一个字节，则本方法必将阻塞直至至少读取一个字节。
     *
     * 该方法可随时调用。但若其他线程已在此通道上发起读取操作，则调用此方法将阻塞直至前次操作完成。
     */
    int read(ByteBuffer dst) throws IOException;
}

package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.Channel;
import java.util.Objects;

/**
 * 一个表示文件区域锁的标记。
 * 每次通过FileChannel类的lock或tryLock方法，或AsynchronousFileChannel类的lock或tryLock方法获取文件锁时，都会创建一个文件锁对象。
 *
 * 文件锁对象初始时有效。其有效性将持续至以下情况之一发生时终止：
 * 调用release方法释放锁、关闭获取锁时使用的通道，或Java虚拟机终止运行。可通过调用isValid方法检测锁的有效性。
 * 文件锁分为独占锁与共享锁。共享锁禁止其他并发程序获取重叠的独占锁，但允许获取重叠的共享锁。
 * 独占锁则禁止其他程序获取任何类型的重叠锁。锁一旦释放，便不再影响其他程序可能获取的锁。
 * 可通过调用isShared方法判断锁的独占或共享属性。部分平台不支持共享锁，此时共享锁请求将自动转换为独占锁请求。
 * 单个Java虚拟机对特定文件持有的锁互不重叠。可使用overlaps方法检测候选锁范围是否与现有锁重叠。
 *
 * 文件锁对象记录以下信息：锁定文件所属的FileChannel、锁类型与有效性、锁定区域的位置及大小。仅锁的有效性可能随时间变化，其余状态均不可变。
 * 文件锁由整个Java虚拟机统一持有，不适用于控制同一虚拟机内多线程对文件的访问。
 * 文件锁对象支持多线程并发安全使用。
 *
 * Platform dependencies
 * 此文件锁定API旨在直接映射底层操作系统的原生锁定机制。
 * 因此，对文件持有的锁定状态应可被所有访问该文件的程序可见，无论这些程序采用何种编程语言编写。
 *
 * 锁定操作是否实际阻止其他程序访问锁定区域的内容取决于系统特性，因此未作明确定义。
 * 某些系统的原生文件锁定机制仅具建议性，这意味着程序必须协同遵守已知的锁定协议才能保证数据完整性。
 * 而在其他系统中，原生文件锁定具有强制性，即当某程序锁定文件区域时，其他程序将被实际阻止以违反锁定规则的方式访问该区域。
 * 还有些系统允许按文件逐个配置原生文件锁的强制性。为确保跨平台行为的一致性与正确性，强烈建议将本API提供的锁视为建议性锁使用。
 *
 * 某些系统中，对文件区域获取强制锁会阻止该区域映射到内存，反之亦然。结合锁定与映射操作的程序应做好组合失败的准备。
 * 在某些系统中，关闭Channel会释放Java虚拟机对底层文件持有的所有锁，无论这些锁是通过该通道还是通过同一文件上打开的其他通道获取的。
 * 强烈建议在程序中使用唯一的Channel来获取任何给定文件上的所有锁。
 * 某些网络文件系统仅允许在锁定区域与页面对齐且为底层硬件页面大小整数倍时，对内存映射文件使用文件锁定。
 * 部分网络文件系统不会对超出特定位置（通常为2³⁰或2³¹）的区域实施文件锁定。总体而言，锁定网络文件系统中的文件时需格外谨慎。
 *
 * @author liyibo
 * @date 2026-03-03 00:33
 */
public abstract class FileLock implements AutoCloseable {
    private final Channel channel;
    private final long position;
    private final long size;
    private final boolean shared;

    protected FileLock(FileChannel channel, long position, long size, boolean shared) {
        Objects.requireNonNull(channel, "Null channel");
        if(position < 0)
            throw new IllegalArgumentException("Negative position");
        if(size < 0)
            throw new IllegalArgumentException("Negative size");
        if(position + size < 0)
            throw new IllegalArgumentException("Negative position + size");
        this.channel = channel;
        this.position = position;
        this.size = size;
        this.shared = shared;
    }

    protected FileLock(AsynchronousFileChannel channel, long position, long size, boolean shared) {
        Objects.requireNonNull(channel, "Null channel");
        if(position < 0)
            throw new IllegalArgumentException("Negative position");
        if(size < 0)
            throw new IllegalArgumentException("Negative size");
        if(position + size < 0)
            throw new IllegalArgumentException("Negative position + size");
        this.channel = channel;
        this.position = position;
        this.size = size;
        this.shared = shared;
    }

    public final FileChannel channel() {
        return channel instanceof FileChannel ? (FileChannel)channel : null;
    }

    public Channel acquiredBy() {
        return channel;
    }

    public final long position() {
        return position;
    }

    public final long size() {
        return size;
    }

    public final boolean isShared() {
        return shared;
    }

    /**
     * 判断此锁是否与给定的锁定范围重叠。
     */
    public final boolean overlaps(long position, long size) {
        if(position + size <= this.position)    // 在锁定范围之前
            return false;
        if(this.position + this.size <= position)   // 在锁定范围之后
            return false;
        return true;
    }

    /**
     * 判断此锁是否有效。
     * 锁对应在被释放或关联的FileChannel关闭之前始终有效，以先发生者为准。
     */
    public abstract boolean isValid();

    public abstract void release() throws IOException;

    @Override
    public void close() throws Exception {
        release();
    }

    public final String toString() {
        return (this.getClass().getName()
                + "[" + position
                + ":" + size
                + " " + (shared ? "shared" : "exclusive")
                + " " + (isValid() ? "valid" : "invalid")
                + "]");
    }
}

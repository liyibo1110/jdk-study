package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.Closeable;
import java.io.IOException;

/**
 * I/O操作的纽带。
 * Channel代表与实体（如硬件设备、文件、网络套接字或程序组件）之间的开放连接，该实体能够执行一项或多项独立的I/O操作，例如读取或写入。
 * Channel处于打开或关闭状态。通道在创建时处于打开状态，一旦关闭则保持关闭状态。
 * Channel关闭后，任何对其调用I/O操作的尝试都会抛出ClosedChannelException异常。可通过调用其isOpen方法检测Channel是否处于打开状态。
 * 通常情况下，Channel在多线程访问时是安全的，这在扩展和实现该接口的类及接口规范中已有说明。
 * @author liyibo
 * @date 2026-03-02 18:17
 */
public interface Channel extends Closeable {
    boolean isOpen();
    void close() throws IOException;
}

package com.github.liyibo1110.jdk.java.io;

import java.io.Flushable;
import java.io.IOException;
import java.util.Objects;

/**
 * 此类是所有表示字节输出流类的基类，输出流接受输出字节并将其发送至某个接收端。
 * 需要定义OutputStream子类的应用程序必须始终至少提供一个写入一个字节输出的方法。
 * @author liyibo
 * @date 2026-02-27 16:12
 */
public abstract class OutputStream implements Closeable, Flushable {
    public OutputStream() {}

    /**
     * 返回一个丢弃所有字节的新输出流，返回的流初始为打开状态，通过close方法关闭流，后续调用close将无效。
     * 当流处于打开状态时，write(int)、write(byte[])和write(byte[], int, int)方法均不执行任何操作。流关闭后，这些方法都会抛出IOException异常。
     * flush()方法不执行任何操作。
     */
    public static OutputStream nullOutputStream() {
        return new OutputStream() {
            private volatile boolean closed;

            private void ensureOpen() throws IOException {
                if(closed)
                    throw new IOException("Stream closed");
            }

            @Override
            public void write(int b) throws IOException {
                ensureOpen();
            }

            @Override
            public void write(byte b[], int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, b.length);
                ensureOpen();
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }

    /**
     * 将指定字节写入此输出流。写入操作的一般契约是向输出流写入一个字节。
     * 要写入的字节是参数b的八个低位。b的24个高位将被忽略。
     * OutputStream的子类必须为此方法提供实现。
     */
    public abstract void write(int b) throws IOException;

    public void write(byte b[]) throws IOException {
        write(b, 0, b.length);
    }

    public void write(byte b[], int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        for(int i = 0; i < len; i++)
            write(b[off + i]);
    }

    /**
     * 刷新此输出流，强制写出所有缓冲的输出字节。
     * flush操作的一般契约是：调用该操作即表明，若输出流实现曾缓冲过先前写入的字节，则应立即将这些字节写入其目标位置。
     * 若该流的目标位置是底层操作系统提供的抽象对象（例如文件），则刷新操作仅保证先前写入流中的字节被传递给操作系统进行写入操作；但不保证这些字节实际写入物理设备（如磁盘驱动器）。
     * OutputStream的flush方法不执行任何操作。
     */
    public void flush() throws IOException {}

    /**
     * 关闭此输出流并释放与该流关联的所有系统资源。close方法的一般契约是关闭输出流。已关闭的流无法执行输出操作，也无法重新打开。
     * OutputStream的close方法不执行任何操作。
     */
    public void close() throws IOException {}
}

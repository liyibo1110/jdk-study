package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.io.OutputStream;

/**
 * PipedOutputStream可连接至PipedInputStream以创建通信管道。
 * PipedOutputStream作为发送端，通常由一个线程向PipedOutputStream写入数据，同时另一个线程从关联的PipedInputStream中读取数据。
 * @author liyibo
 * @date 2026-02-27 00:25
 */
public class PipedOutputStream extends OutputStream {
    private volatile PipedInputStream sink;

    public PipedOutputStream(PipedInputStream snk)  throws IOException {
        connect(snk);
    }

    public PipedOutputStream() {}

    public synchronized void connect(PipedInputStream snk) throws IOException {
        if(snk == null)
            throw new NullPointerException();
        else if(sink != null || snk.connected)
            throw new IOException("Already connected");
        sink = snk;
        snk.in = -1;
        snk.out = 0;
        snk.connected = true;
    }

    /**
     * 将指定的字节写入PipedOutputStream
     */
    public void write(int b)  throws IOException {
        PipedInputStream sink = this.sink;
        if(sink == null)
            throw new IOException("Pipe not connected");
        sink.receive(b);
    }

    /**
     * 从指定byte数组，以偏移量off为七点，向PipedOutputStream写入长度为len的字节
     */
    public void write(byte b[], int off, int len) throws IOException {
        PipedInputStream sink = this.sink;
        if(sink == null)
            throw new IOException("Pipe not connected");
        else if(b == null)
            throw new NullPointerException();
        else if((off < 0) || (off > b.length) || (len < 0) ||
                ((off + len) > b.length) || ((off + len) < 0))
            throw new IndexOutOfBoundsException();
        else if(len == 0)
            return;
        sink.receive(b, off, len);
    }

    /**
     * 刷新此输出流，强制写入所有缓冲的输出字节。
     * 这将通知所有读取器，管道中有字节等待处理。
     */
    public synchronized void flush() throws IOException {
        if(sink != null) {
            synchronized(sink) {
                sink.notifyAll();
            }
        }
    }

    /**
     * 关闭此PipedOutputStream，并释放与该流关联的所有系统资源。
     * 此流将无法再用于写入字节。
     */
    public void close()  throws IOException {
        PipedInputStream sink = this.sink;
        if(sink != null)
            sink.receivedLast();
    }
}

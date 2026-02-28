package com.github.liyibo1110.jdk.java.io;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.CharBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Objects;

/**
 * 用于读取字符流的抽象类，子类必须实现的方法仅有read(char[], int, int)和close()。
 * 然而大多数子类会重写此处定义的部分方法，以提供更高的效率、额外功能或两者兼具。
 * @author liyibo
 * @date 2026-02-27 20:45
 */
public abstract class Reader implements Readable, Closeable {

    private static final int TRANSFER_BUFFER_SIZE = 8192;

    /**
     * 用于同步此流上操作的对象，为提高效率，字符流对象可能使用自身以外的对象来保护关键段。
     * 因此子类应使用此字段中的对象，而非this或同步方法。
     */
    protected Object lock;

    protected Reader() {
        this.lock = this;
    }

    protected Reader(Object lock) {
        if(lock == null)
            throw new NullPointerException();
        this.lock = lock;
    }

    @Override
    public int read(CharBuffer cb) throws IOException {
        if(cb.isReadOnly())
            throw new ReadOnlyBufferException();
        int nread;
        if(cb.hasArray()) {
            char[] cbuf = cb.array();
            int pos = cb.position();
            int rem = Math.max(cb.limit() - pos, 0);
            int off = cb.arrayOffset() + pos;
            nread = this.read(cbuf, off, rem);
        }else {
            int len = cb.remaining();
            char[] cbuf = new char[len];
            nread = read(cbuf, 0, len);
            if(nread > 0)
                cb.put(cbuf, 0, nread);
        }
        return nread;
    }

    /**
     * 读取单个字符，此方法将阻塞直到有字符可用，发生I/O错误或到达流尾。
     * 为了支持高效率的单字符输入的子类应该重写此方法。
     */
    public int read() throws IOException {
        char[] cb = new char[1];
        if(read(cb, 0, 1) == -1)
            return -1;
        else
            return cb[0];
    }

    public int read(char[] cbuf) throws IOException {
        return read(cbuf, 0, cbuf.length);
    }

    /**
     * 将字符读入数组的一部分。该方法将阻塞直至有输入可用、发生I/O错误或到达流尾。
     * 若长度为零，则不读取任何字符并返回0；否则将尝试读取至少一个字符。
     * 若因流已达末尾而无可用字符，则返回-1；否则至少读取一个字符并存储至cbuf中。
     */
    public abstract int read(char[] cbuf, int off, int len) throws IOException;

    private static final int maxSkipBufferSize = 8192;

    private char[] skipBuffer = null;

    public long skip(long n) throws IOException {
        if(n < 0L)
            throw new IllegalArgumentException("skip value is negative");
        int nn = (int)Math.min(n, maxSkipBufferSize);
        synchronized(lock) {
            if(skipBuffer == null || skipBuffer.length < nn)    // 延迟初始化skipBuffer
                skipBuffer = new char[nn];
            long r = n;
            while(r > 0) {  // 循环读取
                int nc = read(skipBuffer, 0, (int)Math.min(r, nn));
                if(nc == -1)
                    break;
                r -= nc;
            }
            return n - r;
        }
    }

    /**
     * 指示该流是否已准备好进行读取。
     */
    public boolean ready() throws IOException {
        return false;
    }

    public boolean markSupported() {
        return false;
    }

    public void mark(int readAheadLimit) throws IOException {
        throw new IOException("mark() not supported");
    }

    public void reset() throws IOException {
        throw new IOException("reset() not supported");
    }

    public abstract void close() throws IOException;

    public long transferTo(Writer out) throws IOException {
        Objects.requireNonNull(out, "out");
        long transferred = 0;
        char[] buffer = new char[TRANSFER_BUFFER_SIZE];
        int nRead;
        while((nRead = read(buffer, 0, TRANSFER_BUFFER_SIZE)) >= 0) {
            out.write(buffer, 0, nRead);
            transferred += nRead;
        }
        return transferred;
    }
}

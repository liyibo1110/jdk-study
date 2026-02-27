package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.io.InputStream;

/**
 * FilterInputStream包含其他输入流，将其作为基础数据源，可能在传输过程中对数据进行转换或提供额外功能。
 * FilterInputStream类本身仅通过覆盖InputStream的所有方法实现，这些方法会将所有请求转发给内部包含的输入流。
 * FilterInputStream的子类可进一步覆盖其中某些方法，并可提供额外的方法和字段。
 *
 * InputStream装饰器的基类
 * @author liyibo
 * @date 2026-02-26 14:29
 */
public class FilterInputStream extends InputStream {

    /**
     * 待过滤的输入流
     */
    protected volatile InputStream in;

    protected FilterInputStream(InputStream in) {
        this.in = in;
    }

    /**
     * 从输入流中读取下一个字节数据，返回值为0到255范围内的整数字节值。
     * 若因到达流尾而无可用字节，则返回-1，本方法将阻塞直至满足以下任一条件：存在输入数据、检测到流尾或抛出异常。
     *
     * 该方法仅执行in.read()操作并返回结果。
     */
    public int read() throws IOException {
        return in.read();
    }

    /**
     * 从该输入流中读取最多b.length字节的数据，并将其存储到字节数组中，该方法会阻塞直至有可用输入。
     * 此方法仅执行read(b, 0, b.length)调用并返回结果，关键在于它不能直接调用read(b)，某些FilterInputStream的子类依赖于实现使用的实现策略。
     */
    public int read(byte b[]) throws IOException {
        return read(b, 0, b.length);
    }

    public int read(byte b[], int off, int len) throws IOException {
        return in.read(b, off, len);
    }

    public long skip(long n) throws IOException {
        return in.skip(n);
    }

    public int available() throws IOException {
        return in.available();
    }

    public void close() throws IOException {
        in.close();
    }

    public synchronized void mark(int readlimit) {
        in.mark(readlimit);
    }

    public synchronized void reset() throws IOException {
        in.reset();
    }

    public boolean markSupported() {
        return in.markSupported();
    }
}

package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * 包含了一个内部缓冲区，其中存储了可从流中读取的字节，内部计数器用于追踪read方法将要提供的下一个字节。
 * 关闭ByteArrayInputStream不会产生任何效果。即使流已被关闭，仍可调用该类中的方法，且不会引发IOException。
 *
 * 属于底层干活流（实际和数据来源打交道），不是装饰器流。
 * @author liyibo
 * @date 2026-02-26 18:50
 */
public class ByteArrayInputStream extends InputStream {
    /** 提供数据的byte数组，仅能从该流读取元素buf[0]至buf[count-1]中的字节，元素buf[pos]是下一个可读取的字节 */
    protected byte buf[];

    /** 从buf中读取下一个字节的索引，该值不能为负数且大于count值，从buf中读取的下一个字节是buf[pos] */
    protected int pos;

    /**
     * 流中当前标记的位置。ByteArrayInputStream对象在构造时默认标记在位置零。
     * 可通过mark()方法将其标记在缓冲区内的其他位置。reset()方法将当前缓冲区位置设置至该点。
     *
     * 若未设置标记，则mark的值为传递给构造函数的偏移量（若未提供偏移量则为 0）。
     */
    protected int mark = 0;

    /**
     * 该索引值比输入流缓冲区中最后一个有效字符的索引值大1。
     * 该值应始终为非负数，且不大于缓冲区长度。它比缓冲区中可从输入流缓冲区读取的最后一个字节的位置大1。
     */
    protected int count;

    public ByteArrayInputStream(byte buf[]) {
        this.buf = buf;
        this.pos = 0;
        this.count = buf.length;
    }

    public ByteArrayInputStream(byte buf[], int offset, int length) {
        this.buf = buf;
        this.pos = offset;
        this.count = Math.min(offset + length, buf.length);
        this.mark = offset;
    }

    public synchronized int read() {
        return (pos < count) ? (buf[pos++] & 0xFF) : -1;
    }

    public synchronized int read(byte b[], int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        if(pos >= count)
            return -1;
        int avail = count - pos;
        if(len > avail)
            len = avail;
        if(len <= 0)
            return 0;
        System.arraycopy(buf, pos, b, off, len);
        pos += len;
        return len;
    }

    public synchronized byte[] readAllBytes() {
        byte[] result = Arrays.copyOfRange(buf, pos, count);
        pos = count;
        return result;
    }

    public int readNBytes(byte[] b, int off, int len) {
        int n = read(b, off, len);
        return n == -1 ? 0 : n;
    }

    public synchronized long transferTo(OutputStream out) throws IOException {
        int len = count - pos;
        out.write(buf, pos, len);
        pos = count;
        return len;
    }

    public synchronized long skip(long n) {
        long k = count - pos;
        if(n < k)
            k = n < 0 ? 0 : n;
        pos += k;
        return k;
    }

    public synchronized int available() {
        return count - pos;
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) {
        mark = pos;
    }

    public synchronized void reset() {
        pos = mark;
    }

    public void close() throws IOException {
        // 不需要close
    }

}

package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * 数据将写入byte数组中，缓冲区会随着数据写入自动扩展，可以通过toByteArray()和toString()方法获取数据。
 * 关闭ByteArrayOutputStream不会有任何效果，流关闭后仍可以调用本类的方法，且不会引发IOException。
 * 属于底层干活流（实际和数据来源打交道），不是装饰器流。
 * @author liyibo
 * @date 2026-02-27 18:06
 */
public class ByteArrayOutputStream extends OutputStream {
    protected byte buf[];
    protected int count;

    public ByteArrayOutputStream() {
        this(32);
    }

    public ByteArrayOutputStream(int size) {
        if(size < 0)
            throw new IllegalArgumentException("Negative initial size: " + size);
        buf = new byte[size];
    }

    /**
     * 在必要时增加容量，以确保其至少能容纳由最小容量参数指定的元素数量。
     */
    private void ensureCapacity(int minCapacity) {
        int oldCapacity = buf.length;
        int minGrowth = minCapacity - oldCapacity;
        if(minGrowth > 0)
            buf = Arrays.copyOf(buf, ArraysSupport.newLength(oldCapacity, minGrowth, oldCapacity));
    }

    public synchronized void write(int b) {
        ensureCapacity(count + 1);
        buf[count] = (byte)b;
        count += 1;
    }

    public synchronized void write(byte b[], int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        ensureCapacity(count + len);
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    public void writeBytes(byte b[]) {
        write(b, 0, b.length);
    }

    public synchronized void writeTo(OutputStream out) throws IOException {
        out.write(buf, 0, count);
    }

    public synchronized void reset() {
        count = 0;
    }

    public synchronized byte[] toByteArray() {
        return Arrays.copyOf(buf, count);
    }

    public synchronized int size() {
        return count;
    }

    public synchronized String toString() {
        return new String(buf, 0, count);
    }

    public synchronized String toString(String charsetName) throws UnsupportedEncodingException {
        return new String(buf, 0, count, charsetName);
    }

    public synchronized String toString(Charset charset) {
        return new String(buf, 0, count, charset);
    }

    @Deprecated
    public synchronized String toString(int hibyte) {
        return new String(buf, hibyte, 0, count);
    }

    public void close() throws IOException {}
}

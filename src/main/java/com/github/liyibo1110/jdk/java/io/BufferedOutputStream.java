package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;

/**
 * 实现了一个缓冲输出流，通过设置此类输出流，应用程序可以将字节写入底层输出流，而无需为每个写入的字节都调用底层系统。
 * 属于装饰器组件。
 * @author liyibo
 * @date 2026-02-27 20:06
 */
public class BufferedOutputStream extends FilterOutputStream {
    protected byte buf[];

    /**
     * buf中有效字节的数量，该值始终在0到buf.length之间，元素buf[0]到buf[count-1]包含有效的字节数据。
     */
    protected int count;

    public BufferedOutputStream(OutputStream out) {
        this(out, 8192);
    }

    public BufferedOutputStream(OutputStream out, int size) {
        super(out);
        if(size <= 0)
            throw new IllegalArgumentException("Buffer size <= 0");
        buf = new byte[size];
    }

    private void flushBuffer() throws IOException {
        if(count > 0) {
            out.write(buf, 0, count);
            count = 0;
        }
    }

    @Override
    public synchronized void write(int b) throws IOException {
        if(count >= buf.length)
            flushBuffer();
        buf[count++] = (byte)b;
    }

    @Override
    public synchronized void write(byte b[], int off, int len) throws IOException {
        if(len >= buf.length) { // 长度比buf还大
            flushBuffer();  // 先flush
            out.write(b, off, len); // 再直接write，不会再进buf
            return;
        }
        if(len > buf.length - count)    // 长度小于buf，但是放不下
            flushBuffer();
        System.arraycopy(b, off, buf, count, len);  // 长度小于buf,并且放得下
        count += len;
    }

    @Override
    public synchronized void flush() throws IOException {
        flushBuffer();
        out.flush();
    }
}

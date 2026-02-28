package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;

/**
 * 该类是所有过滤输出流类的基类。这些流位于已存在的输出流（底层输出流）之上，将其作为基础数据接收端，但在传输过程中可能对数据进行转换或提供额外功能。
 * FilterOutputStream类本身仅通过覆盖OutputStream的所有方法实现，其实现版本会将所有请求转发至底层输出流。
 * FilterOutputStream的子类可进一步覆盖其中部分方法，同时提供额外的方法和字段。
 * @author liyibo
 * @date 2026-02-27 17:30
 */
public class FilterOutputStream extends OutputStream {
    protected OutputStream out;

    /** 流是否已关闭，默认初始化为false。 */
    private volatile boolean closed;

    /** 防止closed实例发生竞争的锁对象 */
    private final Object closeLock = new Object();

    public FilterOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
    }

    @Override
    public void write(byte b[]) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte b[], int off, int len) throws IOException {
        if((off | len | (b.length - (len + off)) | (off + len)) < 0)
            throw new IndexOutOfBoundsException();

        for(int i = 0 ; i < len ; i++) {
            write(b[off + i]);
        }
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    public void close() throws IOException {
        if(closed)
            return;
        synchronized (closeLock) {
            if(closed)
                return;
            closed = true;
        }
        Throwable flushException = null;
        try {
            flush();
        } catch (Throwable e) {
            flushException = e;
            throw e;
        } finally {
            if (flushException == null) {
                out.close();
            }else {
                try {
                    out.close();
                } catch (Throwable closeException) {
                    if((flushException instanceof ThreadDeath) && !(closeException instanceof ThreadDeath)) {
                        flushException.addSuppressed(closeException);
                        throw (ThreadDeath)flushException;
                    }

                    if(flushException != closeException)
                        closeException.addSuppressed(flushException);

                    throw closeException;
                }
            }
        }
    }
}

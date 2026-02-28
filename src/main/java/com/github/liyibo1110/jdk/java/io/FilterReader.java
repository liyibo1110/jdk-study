package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;

/**
 * 用于读取过滤字符流的抽象类，FilterReader抽象类本身提供默认方法，将所有请求转发给包含的流。
 * FilterReader的子类应重写其中某些方法，并可提供额外的方法和字段。
 * @author liyibo
 * @date 2026-02-27 22:32
 */
public abstract class FilterReader extends Reader {
    protected Reader in;

    protected FilterReader(Reader in) {
        super(in);
        this.in = in;
    }

    public int read() throws IOException {
        return in.read();
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        return in.read(cbuf, off, len);
    }

    public long skip(long n) throws IOException {
        return in.skip(n);
    }

    public boolean ready() throws IOException {
        return in.ready();
    }

    public boolean markSupported() {
        return in.markSupported();
    }

    public void mark(int readAheadLimit) throws IOException {
        in.mark(readAheadLimit);
    }

    public void reset() throws IOException {
        in.reset();
    }

    public void close() throws IOException {
        in.close();
    }
}

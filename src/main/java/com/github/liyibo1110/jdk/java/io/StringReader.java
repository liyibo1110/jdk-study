package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.util.Objects;

/**
 * 从String中读取字符的Reader实现，很简单。
 * @author liyibo
 * @date 2026-03-01 17:26
 */
public class StringReader extends Reader {
    private String str;
    private int length;  // str.length
    private int next = 0;   // 下一个要读取的下标
    private int mark = 0;

    public StringReader(String s) {
        this.str = s;
        this.length = s.length();
    }

    private void ensureOpen() throws IOException {
        if(str == null)
            throw new IOException("Stream closed");
    }

    public int read() throws IOException {
        synchronized(lock) {
            ensureOpen();
            if(next >= length)  // 全读完了
                return -1;
            return str.charAt(next++);
        }
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        synchronized(lock) {
            ensureOpen();
            Objects.checkFromIndexSize(off, len, cbuf.length);
            if(len == 0)
                return 0;
            if(next >= length)
                return -1;
            int n = Math.min(length - next, len);
            str.getChars(next, next + n, cbuf, off);
            next += n;
            return n;
        }
    }

    public long skip(long n) throws IOException {
        synchronized (lock) {
            ensureOpen();
            if(next >= length)
                return 0;
            long r = Math.min(length - next, n);
            r = Math.max(-next, r);
            next += r;
            return r;
        }
    }

    public boolean ready() throws IOException {
        synchronized (lock) {
            ensureOpen();
            return true;
        }
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) throws IOException {
        if(readAheadLimit < 0)
            throw new IllegalArgumentException("Read-ahead limit < 0");
        synchronized(lock) {
            ensureOpen();
            mark = next;
        }
    }

    public void reset() throws IOException {
        synchronized(lock) {
            ensureOpen();
            next = mark;
        }
    }

    public void close() {
        synchronized(lock) {
            str = null;
        }
    }
}

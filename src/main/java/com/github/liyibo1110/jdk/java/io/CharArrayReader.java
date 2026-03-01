package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.nio.CharBuffer;
import java.util.Objects;

/**
 * 从char[]中读取字符的Reader实现，比较简单。
 * @author liyibo
 * @date 2026-03-01 17:41
 */
public class CharArrayReader extends Reader {
    protected char[] buf;
    protected int pos;
    protected int markedPos = 0;
    protected int count;

    public CharArrayReader(char[] buf) {
        this.buf = buf;
        this.pos = 0;
        this.count = buf.length;
    }

    public CharArrayReader(char[] buf, int offset, int length) {
        if((offset < 0) || (offset > buf.length) || (length < 0) || ((offset + length) < 0))
            throw new IllegalArgumentException();
        this.buf = buf;
        this.pos = offset;
        this.count = Math.min(offset + length, buf.length);
        this.markedPos = offset;
    }

    private void ensureOpen() throws IOException {
        if(buf == null)
            throw new IOException("Stream closed");
    }

    public int read() throws IOException {
        synchronized (lock) {
            ensureOpen();
            if(pos >= count)
                return -1;
            else
                return buf[pos++];
        }
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        synchronized (lock) {
            ensureOpen();
            Objects.checkFromIndexSize(off, len, cbuf.length);
            if(len == 0)
                return 0;
            if(pos >= count)
                return -1;
            int avail = count - pos;
            if(len > avail)
                len = avail;
            if(len <= 0)
                return 0;
            System.arraycopy(buf, pos, cbuf, off, len);
            pos += len;
            return len;
        }
    }

    @Override
    public int read(CharBuffer cb) throws IOException {
        synchronized (lock) {
            ensureOpen();
            if(pos >= count)
                return -1;
            int avail = count - pos;
            int len = Math.min(avail, cb.remaining());
            cb.put(buf, pos, len);
            pos += len;
            return len;
        }
    }

    public long skip(long n) throws IOException {
        synchronized (lock) {
            ensureOpen();
            long avail = count - pos;
            if(n > avail)
                n = avail;
            if(n < 0)
                return 0;
            pos += n;
            return n;
        }
    }

    public boolean ready() throws IOException {
        synchronized(lock) {
            ensureOpen();
            return (count - pos) > 0;
        }
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) throws IOException {
        synchronized(lock) {
            ensureOpen();
            markedPos = pos;
        }
    }

    public void reset() throws IOException {
        synchronized(lock) {
            ensureOpen();
            pos = markedPos;
        }
    }

    public void close() {
        synchronized(lock) {
            buf = null;
        }
    }
}

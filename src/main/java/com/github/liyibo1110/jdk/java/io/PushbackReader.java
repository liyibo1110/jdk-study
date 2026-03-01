package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.util.Objects;

/**
 * 和PushbackInputStream几乎是一样的
 * @author liyibo
 * @date 2026-03-01 17:17
 */
public class PushbackReader extends FilterReader {
    private char[] buf;
    private int pos;    // 同样是越来越小

    public PushbackReader(Reader in, int size) {
        super(in);
        if(size <= 0) {
            throw new IllegalArgumentException("size <= 0");
        }
        this.buf = new char[size];
        this.pos = size;
    }

    public PushbackReader(Reader in) {
        this(in, 1);
    }

    private void ensureOpen() throws IOException {
        if(buf == null)
            throw new IOException("Stream closed");
    }

    public int read() throws IOException {
        synchronized(lock) {
            ensureOpen();
            if(pos < buf.length)    // 优先从buf读
                return buf[pos++];
            else
                return super.read();
        }
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        synchronized(lock) {
            ensureOpen();
            try {
                Objects.checkFromIndexSize(off, len, cbuf.length);
                if(len == 0)
                    return 0;
                int avail = buf.length - pos;
                if(avail > 0) { // 优先从buf中拿字节
                    if(len < avail)
                        avail = len;
                    System.arraycopy(buf, pos, cbuf, off, avail);
                    pos += avail;
                    off += avail;
                    len -= avail;
                }
                if(len > 0) {   // 剩下不足的继续找底层要
                    len = super.read(cbuf, off, len);
                    if(len == -1)
                        return avail == 0 ? -1 : avail;
                    return avail + len;
                }
                return avail;
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IndexOutOfBoundsException();
            }
        }
    }

    public void unread(int c) throws IOException {
        synchronized(lock) {
            ensureOpen();
            if(pos == 0)
                throw new IOException("Pushback buffer overflow");
            buf[--pos] = (char)c;
        }
    }

    public void unread(char[] cbuf, int off, int len) throws IOException {
        synchronized(lock) {
            ensureOpen();
            if(len > pos)
                throw new IOException("Pushback buffer overflow");
            pos -= len;
            System.arraycopy(cbuf, off, buf, pos, len);
        }
    }

    public void unread(char[] cbuf) throws IOException {
        unread(cbuf, 0, cbuf.length);
    }

    public boolean ready() throws IOException {
        synchronized(lock) {
            ensureOpen();
            return (pos < buf.length) || super.ready();
        }
    }

    public void mark(int readAheadLimit) throws IOException {
        throw new IOException("mark/reset not supported");
    }

    public void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    public boolean markSupported() {
        return false;
    }

    public void close() throws IOException {
        synchronized (lock) {
            super.close();
            buf = null;
        }
    }

    public long skip(long n) throws IOException {
        if(n < 0L)
            throw new IllegalArgumentException("skip value is negative");
        synchronized(lock) {
            ensureOpen();
            int avail = buf.length - pos;
            if(avail > 0) { // buf里有，则先skip
                if(n <= avail) {
                    pos += n;
                    return n;
                }else {
                    pos = buf.length;
                    n -= avail;
                }
            }
            return avail + super.skip(n);
        }
    }
}

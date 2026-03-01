package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.util.Arrays;

/**
 * 对应CharArrayReader，但是buf是无限的，空间不够则会自动扩容。
 * @author liyibo
 * @date 2026-03-01 18:05
 */
public class CharArrayWriter extends Writer {
    protected char buf[];
    protected int count;

    public CharArrayWriter() {
        this(32);
    }

    public CharArrayWriter(int initialSize) {
        if(initialSize < 0)
            throw new IllegalArgumentException("Negative initial size: " + initialSize);
        buf = new char[initialSize];
    }

    public void write(int c) {
        synchronized (lock) {
            int newcount = count + 1;
            if(newcount > buf.length)
                buf = Arrays.copyOf(buf, Math.max(buf.length << 1, newcount));  // 扩容
            buf[count] = (char)c;
            count = newcount;
        }
    }

    public void write(char c[], int off, int len) {
        if((off < 0) || (off > c.length) || (len < 0) || ((off + len) > c.length) || ((off + len) < 0))
            throw new IndexOutOfBoundsException();
        else if (len == 0)
            return;

        synchronized(lock) {
            int newcount = count + len;
            if(newcount > buf.length)
                buf = Arrays.copyOf(buf, Math.max(buf.length << 1, newcount));
            System.arraycopy(c, off, buf, count, len);
            count = newcount;
        }
    }

    public void write(String str, int off, int len) {
        synchronized (lock) {
            int newcount = count + len;
            if(newcount > buf.length)
                buf = Arrays.copyOf(buf, Math.max(buf.length << 1, newcount));
            str.getChars(off, off + len, buf, count);
            count = newcount;
        }
    }

    public void writeTo(Writer out) throws IOException {
        synchronized(lock) {
            out.write(buf, 0, count);
        }
    }

    public CharArrayWriter append(CharSequence csq) {
        String s = String.valueOf(csq);
        write(s, 0, s.length());
        return this;
    }

    public CharArrayWriter append(CharSequence csq, int start, int end) {
        if(csq == null)
            csq = "null";
        return append(csq.subSequence(start, end));
    }

    public CharArrayWriter append(char c) {
        write(c);
        return this;
    }

    public void reset() {
        count = 0;
    }

    public char[] toCharArray() {
        synchronized(lock) {
            return Arrays.copyOf(buf, count);
        }
    }

    public int size() {
        return count;
    }

    public String toString() {
        synchronized (lock) {
            return new String(buf, 0, count);
        }
    }

    public void flush() {
        // nothing to do
    }

    public void close() {
        // nothing do to
    }
}

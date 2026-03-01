package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;

/**
 * 对应StringReader，buf是无限长的
 * @author liyibo
 * @date 2026-03-01 18:11
 */
public class StringWriter extends Writer {
    private StringBuffer buf;

    public StringWriter() {
        buf = new StringBuffer();
        lock = buf;
    }

    public StringWriter(int initialSize) {
        if(initialSize < 0)
            throw new IllegalArgumentException("Negative buffer size");
        buf = new StringBuffer(initialSize);
        lock = buf;
    }

    public void write(int c) {
        buf.append((char) c);
    }

    public void write(char cbuf[], int off, int len) {
        if((off < 0) || (off > cbuf.length) || (len < 0) || ((off + len) > cbuf.length) || ((off + len) < 0))
            throw new IndexOutOfBoundsException();
        else if (len == 0)
            return;
        buf.append(cbuf, off, len);
    }

    public void write(String str) {
        buf.append(str);
    }

    public void write(String str, int off, int len) {
        buf.append(str, off, off + len);
    }

    public StringWriter append(CharSequence csq) {
        write(String.valueOf(csq));
        return this;
    }

    public StringWriter append(CharSequence csq, int start, int end) {
        if(csq == null)
            csq = "null";
        return append(csq.subSequence(start, end));
    }

    public StringWriter append(char c) {
        write(c);
        return this;
    }

    public String toString() {
        return buf.toString();
    }

    public StringBuffer getBuffer() {
        return buf;
    }

    public void flush() {
        // nothing to do
    }

    public void close() throws IOException {
        // nothing to do
    }
}

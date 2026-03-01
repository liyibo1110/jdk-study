package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;

/**
 * 对应InputStreamReader
 * @author liyibo
 * @date 2026-03-01 18:15
 */
public class OutputStreamWriter extends Writer {
    private final StreamEncoder se;

    public OutputStreamWriter(OutputStream out, String charsetName) throws UnsupportedEncodingException {
        super(out);
        if(charsetName == null)
            throw new NullPointerException("charsetName");
        se = StreamEncoder.forOutputStreamWriter(out, this, charsetName);
    }

    public OutputStreamWriter(OutputStream out) {
        super(out);
        se = StreamEncoder.forOutputStreamWriter(out, this, Charset.defaultCharset());
    }

    public OutputStreamWriter(OutputStream out, Charset cs) {
        super(out);
        if(cs == null)
            throw new NullPointerException("charset");
        se = StreamEncoder.forOutputStreamWriter(out, this, cs);
    }

    public OutputStreamWriter(OutputStream out, CharsetEncoder enc) {
        super(out);
        if(enc == null)
            throw new NullPointerException("charset encoder");
        se = StreamEncoder.forOutputStreamWriter(out, this, enc);
    }

    public String getEncoding() {
        return se.getEncoding();
    }

    void flushBuffer() throws IOException {
        se.flushBuffer();
    }

    public void write(int c) throws IOException {
        se.write(c);
    }

    public void write(char cbuf[], int off, int len) throws IOException {
        se.write(cbuf, off, len);
    }

    public void write(String str, int off, int len) throws IOException {
        se.write(str, off, len);
    }

    @Override
    public Writer append(CharSequence csq, int start, int end) throws IOException {
        if(csq == null)
            csq = "null";
        return append(csq.subSequence(start, end));
    }

    @Override
    public Writer append(CharSequence csq) throws IOException {
        if(csq instanceof CharBuffer)
            se.write((CharBuffer) csq);
        else
            se.write(String.valueOf(csq));
        return this;
    }

    public void flush() throws IOException {
        se.flush();
    }

    public void close() throws IOException {
        se.close();
    }
}

package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;

/**
 * @author liyibo
 * @date 2026-03-01 18:16
 */
public class StreamEncoder extends Writer {
    private static final int DEFAULT_BYTE_BUFFER_SIZE = 8192;
    private volatile boolean closed;

    private void ensureOpen() throws IOException {
        if(closed)
            throw new IOException("Stream closed");
    }

    public static StreamEncoder forOutputStreamWriter(OutputStream out, Object lock, String charsetName)
            throws UnsupportedEncodingException {
        String csn = charsetName;
        if(csn == null)
            csn = Charset.defaultCharset().name();
        try {
            return new StreamEncoder(out, lock, Charset.forName(csn));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException x) {
            throw new UnsupportedEncodingException (csn);
        }
    }

    public static StreamEncoder forOutputStreamWriter(OutputStream out, Object lock, Charset cs) {
        return new StreamEncoder(out, lock, cs);
    }

    public static StreamEncoder forOutputStreamWriter(OutputStream out, Object lock, CharsetEncoder enc) {
        return new StreamEncoder(out, lock, enc);
    }

    public static StreamEncoder forEncoder(WritableByteChannel ch, CharsetEncoder enc, int minBufferCap) {
        return new StreamEncoder(ch, enc, minBufferCap);
    }

    public String getEncoding() {
        if(isOpen())
            return encodingName();
        return null;
    }

    public void flushBuffer() throws IOException {
        synchronized (lock) {
            if(isOpen())
                implFlushBuffer();
            else
                throw new IOException("Stream closed");
        }
    }

    public void write(int c) throws IOException {
        char[] cbuf = new char[1];
        cbuf[0] = (char) c;
        write(cbuf, 0, 1);
    }

    public void write(char[] cbuf, int off, int len) throws IOException {
        synchronized (lock) {
            ensureOpen();
            if((off < 0) || (off > cbuf.length) || (len < 0) || ((off + len) > cbuf.length) || ((off + len) < 0))
                throw new IndexOutOfBoundsException();
            else if (len == 0)
                return;
            implWrite(cbuf, off, len);
        }
    }

    public void write(String str, int off, int len) throws IOException {
        /* Check the len before creating a char buffer */
        if(len < 0)
            throw new IndexOutOfBoundsException();
        char[] cbuf = new char[len];
        str.getChars(off, off + len, cbuf, 0);
        write(cbuf, 0, len);
    }

    public void write(CharBuffer cb) throws IOException {
        int position = cb.position();
        try {
            synchronized(lock) {
                ensureOpen();
                implWrite(cb);
            }
        } finally {
            cb.position(position);
        }
    }

    public void flush() throws IOException {
        synchronized (lock) {
            ensureOpen();
            implFlush();
        }
    }

    public void close() throws IOException {
        synchronized (lock) {
            if(closed)
                return;
            try {
                implClose();
            } finally {
                closed = true;
            }
        }
    }

    private boolean isOpen() {
        return !closed;
    }

    // -- Charset-based stream encoder impl --

    private final Charset cs;
    private final CharsetEncoder encoder;
    private final ByteBuffer bb;

    private final OutputStream out;
    private final WritableByteChannel ch;

    private boolean haveLeftoverChar = false;
    private char leftoverChar;
    private CharBuffer lcb = null;

    private StreamEncoder(OutputStream out, Object lock, Charset cs) {
        this(out, lock, cs.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                                       .onUnmappableCharacter(CodingErrorAction.REPLACE));
    }

    private StreamEncoder(OutputStream out, Object lock, CharsetEncoder enc) {
        super(lock);
        this.out = out;
        this.ch = null;
        this.cs = enc.charset();
        this.encoder = enc;
        this.bb = ByteBuffer.allocate(DEFAULT_BYTE_BUFFER_SIZE);
    }

    private StreamEncoder(WritableByteChannel ch, CharsetEncoder enc, int mbc) {
        this.out = null;
        this.ch = ch;
        this.cs = enc.charset();
        this.encoder = enc;
        this.bb = ByteBuffer.allocate(mbc < 0 ? DEFAULT_BYTE_BUFFER_SIZE : mbc);
    }

    private void writeBytes() throws IOException {
        bb.flip();
        int lim = bb.limit();
        int pos = bb.position();
        assert (pos <= lim);
        int rem = (pos <= lim ? lim - pos : 0);

        if(rem > 0) {
            if (ch != null) {
                int wc = ch.write(bb);
                assert wc == rem : rem;
            } else {
                out.write(bb.array(), bb.arrayOffset() + pos, rem);
            }
        }
        bb.clear();
    }

    private void flushLeftoverChar(CharBuffer cb, boolean endOfInput) throws IOException {
        if(!haveLeftoverChar && !endOfInput)
            return;
        if(lcb == null)
            lcb = CharBuffer.allocate(2);
        else
            lcb.clear();
        if(haveLeftoverChar)
            lcb.put(leftoverChar);
        if((cb != null) && cb.hasRemaining())
            lcb.put(cb.get());
        lcb.flip();
        while(lcb.hasRemaining() || endOfInput) {
            CoderResult cr = encoder.encode(lcb, bb, endOfInput);
            if(cr.isUnderflow()) {
                if(lcb.hasRemaining()) {
                    leftoverChar = lcb.get();
                    if(cb != null && cb.hasRemaining()) {
                        lcb.clear();
                        lcb.put(leftoverChar).put(cb.get()).flip();
                        continue;
                    }
                    return;
                }
                break;
            }
            if(cr.isOverflow()) {
                assert bb.position() > 0;
                writeBytes();
                continue;
            }
            cr.throwException();
        }
        haveLeftoverChar = false;
    }

    void implWrite(char[] cbuf, int off, int len) throws IOException {
        CharBuffer cb = CharBuffer.wrap(cbuf, off, len);
        implWrite(cb);
    }

    void implWrite(CharBuffer cb) throws IOException {
        if(haveLeftoverChar)
            flushLeftoverChar(cb, false);

        while(cb.hasRemaining()) {
            CoderResult cr = encoder.encode(cb, bb, false);
            if(cr.isUnderflow()) {
                assert (cb.remaining() <= 1) : cb.remaining();
                if(cb.remaining() == 1) {
                    haveLeftoverChar = true;
                    leftoverChar = cb.get();
                }
                break;
            }
            if(cr.isOverflow()) {
                assert bb.position() > 0;
                writeBytes();
                continue;
            }
            cr.throwException();
        }
    }

    void implFlushBuffer() throws IOException {
        if(bb.position() > 0)
            writeBytes();
    }

    void implFlush() throws IOException {
        implFlushBuffer();
        if(out != null)
            out.flush();
    }

    void implClose() throws IOException {
        flushLeftoverChar(null, true);
        try {
            while(true) {
                CoderResult cr = encoder.flush(bb);
                if(cr.isUnderflow())
                    break;
                if(cr.isOverflow()) {
                    assert bb.position() > 0;
                    writeBytes();
                    continue;
                }
                cr.throwException();
            }

            if(bb.position() > 0)
                writeBytes();
            if(ch != null)
                ch.close();
            else{
                try {
                    out.flush();
                } finally {
                    out.close();
                }
            }
        } catch (IOException x) {
            encoder.reset();
            throw x;
        }
    }

    String encodingName() {
        return (cs instanceof HistoricallyNamedCharset ? ((HistoricallyNamedCharset)cs).historicalName() : cs.name());
    }
}

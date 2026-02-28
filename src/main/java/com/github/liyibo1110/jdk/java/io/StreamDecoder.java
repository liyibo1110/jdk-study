package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;

/**
 * InputStreamReader内部的干活组件，负责将字节转换成字符，数据流为：
 * InputStream/Channel -> ByteBuffer -> CharsetDecoder -> char[]
 * @author liyibo
 * @date 2026-02-28 01:09
 */
public class StreamDecoder extends Reader {
    private static final int MIN_BYTE_BUFFER_SIZE = 32;
    private static final int DEFAULT_BYTE_BUFFER_SIZE = 8192;

    private volatile boolean closed;

    private void ensureOpen() throws IOException {
        if(closed)
            throw new IOException("Stream closed");
    }

    /** 为了正确处理字符，我们必须确保每次生成至少2个字符，若仅需返回1个字符，则另1个字符将被暂存，以便后续返回 */
    private boolean haveLeftoverChar = false;

    /** 被暂存的字符 */
    private char leftoverChar;

    public static StreamDecoder forInputStreamReader(InputStream in, Object lock, String charsetName)
            throws UnsupportedEncodingException {
        String csn = charsetName;
        if(csn == null)
            csn = Charset.defaultCharset().name();
        try {
            return new StreamDecoder(in, lock, Charset.forName(csn));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new UnsupportedEncodingException (csn);
        }
    }

    public static StreamDecoder forInputStreamReader(InputStream in, Object lock, Charset cs) {
        return new StreamDecoder(in, lock, cs);
    }

    public static StreamDecoder forInputStreamReader(InputStream in, Object lock, CharsetDecoder dec) {
        return new StreamDecoder(in, lock, dec);
    }

    public static StreamDecoder forDecoder(ReadableByteChannel ch, CharsetDecoder dec, int minBufferCap) {
        return new StreamDecoder(ch, dec, minBufferCap);
    }

    // -- Public methods corresponding to those in InputStreamReader --

    public String getEncoding() {
        if(isOpen())
            return encodingName();
        return null;
    }

    public int read() throws IOException {
        return read0();
    }

    private int read0() throws IOException {
        synchronized (lock) {
            // 如果有暂存字符，则直接返回暂存字符
            if(haveLeftoverChar) {
                haveLeftoverChar = false;
                return leftoverChar;
            }

            char[] cb = new char[2];
            int n = read(cb, 0, 2);
            switch(n) {
                case -1:
                    return -1;
                case 2:
                    leftoverChar = cb[1];
                    haveLeftoverChar = true;
                case 1:
                    return cb[0];
                default:
                    assert false : n;
                    return -1;
            }
        }
    }

    public int read(char[] cbuf, int offset, int length) throws IOException {
        int off = offset;
        int len = length;
        synchronized(lock) {
            ensureOpen();
            if((off < 0) || (off > cbuf.length) || (len < 0) || ((off + len) > cbuf.length) || ((off + len) < 0))
                throw new IndexOutOfBoundsException();
            if(len == 0)
                return 0;

            int n = 0;  // 实际读了多少个字符

            if(haveLeftoverChar) {  // 如果还有暂存字符，这次也放入cbuf里
                cbuf[off] = leftoverChar;
                off++;
                len--;
                haveLeftoverChar = false;
                n = 1;
                if(len == 0 || !implReady())    // len为0直接就可以返回了
                    return n;
            }

            if(len == 1) {  // 特殊情况，len正好是1，直接走read0
                int c = read0();
                if(c == -1)
                    return n == 0 ? -1 : n;
                cbuf[off] = (char)c;
                return n + 1;
            }

            return n + implRead(cbuf, off, off + len);
        }
    }

    public boolean ready() throws IOException {
        synchronized(lock) {
            ensureOpen();
            return haveLeftoverChar || implReady();
        }
    }

    public void close() throws IOException {
        synchronized(lock) {
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

    // -- Charset-based stream decoder impl --

    private final Charset cs;

    /** 负责从byte到char的解码器（因为转码要涉及到字符集） */
    private final CharsetDecoder decoder;

    /** 存储从InputStream读入的字节 */
    private final ByteBuffer bb;

    private final InputStream in;
    private final ReadableByteChannel ch;

    StreamDecoder(InputStream in, Object lock, Charset cs) {
        this(in, lock,
                cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                               .onUnmappableCharacter(CodingErrorAction.REPLACE));
    }

    StreamDecoder(InputStream in, Object lock, CharsetDecoder dec) {
        super(lock);
        this.cs = dec.charset();
        this.decoder = dec;
        this.in = in;
        this.ch = null;
        this.bb = ByteBuffer.allocate(DEFAULT_BYTE_BUFFER_SIZE);
        bb.flip();  // 初始化为空
    }

    /**
     * 字节数组从Channel中来，而不是从InputStream来。
     */
    StreamDecoder(ReadableByteChannel ch, CharsetDecoder dec, int mbc) {
        this.in = null;
        this.ch = ch;
        this.decoder = dec;
        this.cs = dec.charset();
        this.bb = ByteBuffer.allocate(mbc < 0
                    ? DEFAULT_BYTE_BUFFER_SIZE
                    : (mbc < MIN_BYTE_BUFFER_SIZE ? MIN_BYTE_BUFFER_SIZE : mbc));
        bb.flip();
    }

    private int readBytes() throws IOException {
        bb.compact();
        try {
            if(ch != null) {    // 如果字节流是来自Channel，直接走Channel
                int n = ch.read(bb);
                if(n < 0)
                    return n;
            }else { // 否则就走ByteBuffer
                int lim = bb.limit();
                int pos = bb.position();
                assert (pos <= lim);
                int rem = (pos <= lim ? lim - pos : 0);
                int n = in.read(bb.array(), bb.arrayOffset() + pos, rem);
                if (n < 0)
                    return n;
                if (n == 0)
                    throw new IOException("Underlying input stream returned zero bytes");
                assert (n <= rem) : "n = " + n + ", rem = " + rem;
                bb.position(pos + n);
            }
        } finally {
            bb.flip();
        }

        int rem = bb.remaining();
        assert(rem != 0) : rem;
        return rem;
    }

    int implRead(char[] cbuf, int off, int end) throws IOException {
        assert(end - off > 1);

        CharBuffer cb = CharBuffer.wrap(cbuf, off, end - off);
        if(cb.position() != 0) {
            // Ensure that cb[0] == cbuf[off]
            cb = cb.slice();
        }

        boolean eof = false;
        for(;;) {
            CoderResult cr = decoder.decode(bb, cb, eof);
            if(cr.isUnderflow()) {
                if(eof)
                    break;
                if(!cb.hasRemaining())
                    break;
                if((cb.position() > 0) && !inReady())
                    break;          // Block at most once
                int n = readBytes();
                if(n < 0) {
                    eof = true;
                    if((cb.position() == 0) && (!bb.hasRemaining()))
                        break;
                    decoder.reset();
                }
                continue;
            }
            if(cr.isOverflow()) {
                assert cb.position() > 0;
                break;
            }
            cr.throwException();
        }

        if(eof) {
            // ## Need to flush decoder
            decoder.reset();
        }

        if(cb.position() == 0) {
            if(eof)
                return -1;
            assert false;
        }
        return cb.position();
    }

    String encodingName() {
        return cs instanceof HistoricallyNamedCharset
                ? ((HistoricallyNamedCharset)cs).historicalName()
                : cs.name();
    }

    private boolean inReady() {
        try {
            return (in != null && in.available() > 0)
                    || ch instanceof FileChannel;
        } catch (IOException x) {
            return false;
        }
    }

    boolean implReady() {
        return bb.hasRemaining() || inReady();
    }

    void implClose() throws IOException {
        if(ch != null)
            ch.close();
        else
            in.close();
    }
}

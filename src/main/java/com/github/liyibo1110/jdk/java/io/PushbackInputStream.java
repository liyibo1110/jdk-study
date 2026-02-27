package com.github.liyibo1110.jdk.java.io;

import java.io.FilterInputStream;
import java.io.IOException;

/**
 * 为其他输入流添加了新功能，即能够通过将回推字节存储在内部缓冲区来实现“回推”或“取消读取”字节的能力。
 * 这种机制适用于需要代码片段读取由特定字节值分隔的无限数据字节的情景：读取终止字节后，代码片段可以将其“撤回”，使输入流的下一次读取操作重新读取被推回的字节。
 * 例如，构成标识符的字符字节可能以表示运算符字符的字节终止，专门读取标识符的方法可读取至遇到运算符时，将该运算符回推以便重新读取。
 *
 * 上面官方说的比较抽象难懂，本质就是体现了一种重要的底层设计模式：Lookahead（前瞻读取） + unread（回退）机制，很多解析器（parser）都依赖这种机制。
 * 属于装饰器组件。
 * @author liyibo
 * @date 2026-02-27 01:38
 */
public class PushbackInputStream extends FilterInputStream {
    protected byte[] buf;

    /**
     * 在buf中将读取下一个字节的位置，当buf为空时，pos等于buf.length，当buf已满，pos等于0
     * 注意这个是重点，默认pos等于buf.length，只有调用了unread，才会让pos减少，pos越小，能back的字节就越多，理解这个剩下的就容易了。
     */
    protected int pos;

    private void ensureOpen() throws IOException {
        if(in == null)
            throw new IOException("Stream closed");
    }

    public PushbackInputStream(InputStream in, int size) {
        super(in);
        if(size <= 0)
            throw new IllegalArgumentException("size <= 0");
        this.buf = new byte[size];
        this.pos = size;
    }

    public PushbackInputStream(InputStream in) {
        this(in, 1);
    }

    public int read() throws IOException {
        ensureOpen();
        // 尝试先从buf里面读
        if(pos < buf.length)
            return buf[pos++] & 0xFF;
        return super.read();
    }

    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if(b == null)
            throw new NullPointerException();
        else if (off < 0 || len < 0 || len > b.length - off)
            throw new IndexOutOfBoundsException();
        else if (len == 0)
            return 0;

        int avail = buf.length - pos;
        if(avail > 0) { // 如果buf里有内容，则优先读取
            if(len < avail)
                avail = len;
            System.arraycopy(buf, pos, b, off, avail);
            pos += avail;
            off += avail;
            len -= avail;
        }
        // 读完buf再按正常方式读剩下的
        if(len > 0) {
            len = super.read(b, off, len);
            if(len == -1)
                return avail == 0 ? -1 : avail;
            return avail + len;
        }
        return avail;
    }

    public void unread(int b) throws IOException {
        ensureOpen();
        if(pos == 0)
            throw new IOException("Push back buffer is full");
        buf[--pos] = (byte)b;
    }

    public void unread(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if(len > pos)
            throw new IOException("Push back buffer is full");
        pos -= len;
        System.arraycopy(b, off, buf, pos, len);
    }

    public void unread(byte[] b) throws IOException {
        unread(b, 0, b.length);
    }

    public int available() throws IOException {
        ensureOpen();
        int n = buf.length - pos;   // buf里的字节数
        int avail = super.available();
        return n > (Integer.MAX_VALUE - avail) ? Integer.MAX_VALUE : n + avail;
    }

    public long skip(long n) throws IOException {
        ensureOpen();
        if(n <= 0)
            return 0;

        long pskip = buf.length - pos;  // buf里的字节数
        if(pskip > 0) {
            if(n < pskip)
                pskip = n;
            pos += pskip;
            n -= pskip;
        }
        if(n > 0)
            pskip += super.skip(n);
        return pskip;
    }

    public boolean markSupported() {
        return false;
    }

    public synchronized void mark(int readlimit) {
        // nothing to do
    }

    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    public synchronized void close() throws IOException {
        if(in == null)
            return;
        in.close();
        in = null;
        buf = null;
    }
}

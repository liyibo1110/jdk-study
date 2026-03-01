package com.github.liyibo1110.jdk.java.io;

import java.io.Closeable;
import java.io.DataInput;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * 同时支持对随机访问文件的读写操作，RandomAccessFile的行为类似于存储在文件系统中的大型字节数组，
 * 存在一种称为文件指针的游标机制，它能定位到该隐含数组中的索引位置，输入操作从文件指针起始位置读取字节，并在读取后将文件指针向前移动至已读取字节之后。
 * 若RandomAccessFile以读写模式创建，则输出操作同样可用，输出操作从文件指针起始位置写入字节，并使文件指针至已写入字节之后。
 * 当输出操作写入超出隐含数组当前末尾时，将触发数组扩展，文件指针可通过getFilePointer方法读取，并通过seek方法设置。
 *
 * 本类所有读取操作通常遵循以下规则：若在读取目标字节数前触及文件尾，则抛出EOFException（IOException子类）；
 * 若因文件尾以外原因无法读取字节，则抛出非EOFException类型的IOException。特别地，当流已关闭时可能抛出IOException。
 *
 * 新项目一般就直接使用nio的FileChannel了，不会再用这个了（尽管它内部也有FileChannel的封装字段）。
 * @author liyibo
 * @date 2026-03-01 22:31
 */
public class RandomAccessFile implements DataOutput, DataInput, Closeable {
    private FileDescriptor fd;
    private volatile FileChannel channel;
    private boolean rw;

    private final String path;
    private final Object closeLock = new Object();
    private volatile boolean closed;

    private static final int O_RDONLY = 1;
    private static final int O_RDWR =   2;
    private static final int O_SYNC =   4;
    private static final int O_DSYNC =  8;
    private static final int O_TEMPORARY =  16;

    public RandomAccessFile(String name, String mode) throws FileNotFoundException {
        this(name != null ? new File(name) : null, mode);
    }

    public RandomAccessFile(File file, String mode) throws FileNotFoundException {
        this(file, mode, false);
    }

    private RandomAccessFile(File file, String mode, boolean openAndDelete) throws FileNotFoundException {
        String name = file != null ? file.getPath() : null;
        int imode = -1;
        if(mode.equals("r"))
            imode = O_RDONLY;
        else if(mode.startsWith("rw")) {
            imode = O_RDWR;
            rw = true;
            if(mode.length() > 2) {
                if(mode.equals("rws"))
                    imode |= O_SYNC;
                else if(mode.equals("rwd"))
                    imode |= O_DSYNC;
                else
                    imode = -1;
            }
        }
        if(openAndDelete)
            imode |= O_TEMPORARY;
        if(imode < 0)
            throw new IllegalArgumentException("Illegal mode \"" + mode
                    + "\" must be one of "
                    + "\"r\", \"rw\", \"rws\","
                    + " or \"rwd\"");
        if(name == null)
            throw new NullPointerException();
        if(file.isInvalid())
            throw new FileNotFoundException("Invalid file path");
        fd = new FileDescriptor();
        fd.attach(this);
        path = name;
        open(name, imode);
        FileCleanable.register(fd);
    }

    public final FileDescriptor getFD() throws IOException {
        if(fd != null)
            return fd;
        throw new IOException();
    }

    public final FileChannel getChannel() {
        FileChannel fc = this.channel;
        if(fc == null) {
            synchronized(this) {
                fc = this.channel;
                if(fc == null) {
                    this.channel = fc = FileChannelImpl.open(fd, path, true, rw, false, this);
                    if(closed) {
                        try {
                            fc.close();
                        } catch (IOException ioe) {
                            throw new InternalError(ioe); // should not happen
                        }
                    }
                }
            }
        }
        return fc;
    }

    private native void open0(String name, int mode) throws FileNotFoundException;

    private void open(String name, int mode) throws FileNotFoundException {
        open0(name, mode);
    }

    // 'Read' primitives

    public int read() throws IOException {
        return read0();
    }

    private native int read0() throws IOException;

    private native int readBytes(byte b[], int off, int len) throws IOException;

    public int read(byte b[], int off, int len) throws IOException {
        return readBytes(b, off, len);
    }

    public int read(byte b[]) throws IOException {
        return readBytes(b, 0, b.length);
    }

    public final void readFully(byte b[]) throws IOException {
        readFully(b, 0, b.length);
    }

    public final void readFully(byte b[], int off, int len) throws IOException {
        int n = 0;
        do {
            int count = this.read(b, off + n, len - n);
            if (count < 0)
                throw new EOFException();
            n += count;
        } while (n < len);
    }

    public int skipBytes(int n) throws IOException {
        long pos;
        long len;
        long newpos;

        if(n <= 0)
            return 0;

        pos = getFilePointer();
        len = length();
        newpos = pos + n;
        if(newpos > len)
            newpos = len;

        seek(newpos);
        return (int)(newpos - pos);
    }

    // 'Write' primitives

    public void write(int b) throws IOException {
        write0(b);
    }

    private native void write0(int b) throws IOException;

    private native void writeBytes(byte b[], int off, int len) throws IOException;

    public void write(byte b[]) throws IOException {
        writeBytes(b, 0, b.length);
    }

    public void write(byte b[], int off, int len) throws IOException {
        writeBytes(b, off, len);
    }

    public native long getFilePointer() throws IOException;

    public void seek(long pos) throws IOException {
        if(pos < 0)
            throw new IOException("Negative seek offset");
        else
            seek0(pos);
    }

    private native void seek0(long pos) throws IOException;

    public native long length() throws IOException;

    public native void setLength(long newLength) throws IOException;

    public void close() throws IOException {
        if(closed)
            return;

        synchronized(closeLock) {
            if(closed)
                return;
            closed = true;
        }

        FileChannel fc = channel;
        if(fc != null)
            fc.close();

        fd.closeAll(new Closeable() {
            public void close() throws IOException {
                fd.close();
            }
        });
    }

    //
    //  Some "reading/writing Java data types" methods stolen from
    //  DataInputStream and DataOutputStream.
    //

    public final boolean readBoolean() throws IOException {
        int ch = this.read();
        if(ch < 0)
            throw new EOFException();
        return (ch != 0);
    }

    public final byte readByte() throws IOException {
        int ch = this.read();
        if(ch < 0)
            throw new EOFException();
        return (byte)(ch);
    }

    public final int readUnsignedByte() throws IOException {
        int ch = this.read();
        if(ch < 0)
            throw new EOFException();
        return ch;
    }

    public final short readShort() throws IOException {
        int ch1 = this.read();
        int ch2 = this.read();
        if((ch1 | ch2) < 0)
            throw new EOFException();
        return (short)((ch1 << 8) + (ch2 << 0));
    }

    public final int readUnsignedShort() throws IOException {
        int ch1 = this.read();
        int ch2 = this.read();
        if((ch1 | ch2) < 0)
            throw new EOFException();
        return (ch1 << 8) + (ch2 << 0);
    }

    public final char readChar() throws IOException {
        int ch1 = this.read();
        int ch2 = this.read();
        if((ch1 | ch2) < 0)
            throw new EOFException();
        return (char)((ch1 << 8) + (ch2 << 0));
    }

    public final int readInt() throws IOException {
        int ch1 = this.read();
        int ch2 = this.read();
        int ch3 = this.read();
        int ch4 = this.read();
        if((ch1 | ch2 | ch3 | ch4) < 0)
            throw new EOFException();
        return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4 << 0));
    }

    public final long readLong() throws IOException {
        return ((long)(readInt()) << 32) + (readInt() & 0xFFFFFFFFL);
    }

    public final float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    public final double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    public final String readLine() throws IOException {
        StringBuilder input = new StringBuilder();
        int c = -1;
        boolean eol = false;

        while (!eol) {
            switch (c = read()) {
                case -1:
                case '\n':
                    eol = true;
                    break;
                case '\r':
                    eol = true;
                    long cur = getFilePointer();
                    if((read()) != '\n')
                        seek(cur);

                    break;
                default:
                    input.append((char)c);
                    break;
            }
        }

        if(c == -1 && input.length() == 0)
            return null;

        return input.toString();
    }

    public final String readUTF() throws IOException {
        return DataInputStream.readUTF(this);
    }

    public final void writeBoolean(boolean v) throws IOException {
        write(v ? 1 : 0);
        //written++;
    }

    public final void writeByte(int v) throws IOException {
        write(v);
        //written++;
    }

    public final void writeShort(int v) throws IOException {
        write((v >>> 8) & 0xFF);
        write((v >>> 0) & 0xFF);
        //written += 2;
    }

    public final void writeChar(int v) throws IOException {
        write((v >>> 8) & 0xFF);
        write((v >>> 0) & 0xFF);
        //written += 2;
    }

    public final void writeInt(int v) throws IOException {
        write((v >>> 24) & 0xFF);
        write((v >>> 16) & 0xFF);
        write((v >>>  8) & 0xFF);
        write((v >>>  0) & 0xFF);
        //written += 4;
    }

    public final void writeLong(long v) throws IOException {
        write((int)(v >>> 56) & 0xFF);
        write((int)(v >>> 48) & 0xFF);
        write((int)(v >>> 40) & 0xFF);
        write((int)(v >>> 32) & 0xFF);
        write((int)(v >>> 24) & 0xFF);
        write((int)(v >>> 16) & 0xFF);
        write((int)(v >>>  8) & 0xFF);
        write((int)(v >>>  0) & 0xFF);
        //written += 8;
    }

    public final void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    public final void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    public final void writeBytes(String s) throws IOException {
        int len = s.length();
        byte[] b = new byte[len];
        s.getBytes(0, len, b, 0);
        writeBytes(b, 0, len);
    }

    public final void writeChars(String s) throws IOException {
        int clen = s.length();
        int blen = 2*clen;
        byte[] b = new byte[blen];
        char[] c = new char[clen];
        s.getChars(0, clen, c, 0);
        for(int i = 0, j = 0; i < clen; i++) {
            b[j++] = (byte)(c[i] >>> 8);
            b[j++] = (byte)(c[i] >>> 0);
        }
        writeBytes(b, 0, blen);
    }

    public final void writeUTF(String str) throws IOException {
        DataOutputStream.writeUTF(str, this);
    }

    private static native void initIDs();

    static {
        initIDs();
        SharedSecrets.setJavaIORandomAccessFileAccess(new JavaIORandomAccessFileAccess() {
            // This is for j.u.z.ZipFile.OPEN_DELETE. The O_TEMPORARY flag
            // is only implemented/supported on windows.
            public RandomAccessFile openAndDelete(File file, String mode) throws IOException {
                return new RandomAccessFile(file, mode, true);
            }
        });
    }
}

package com.github.liyibo1110.jdk.java.io;

import com.sun.jdi.InternalException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * 从文件系统中的文件获取输入字节，可用的文件取决于主机环境。
 * 该实现类用于读取原始字节流（如图像数据），如需读取字符流，请考虑使用FileReader（并不推荐，因为使用的是默认的字符集）
 *
 * 属于底层干活流（实际和数据来源打交道），不是装饰器流。
 * @author liyibo
 * @date 2026-02-26 20:21
 */
public class FileInputStream extends InputStream {
    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private final FileDescriptor fd;
    private final String path;
    private volatile FileChannel channel;
    private final Object closeLock = new Object();
    private volatile boolean closed;

    public FileInputStream(File file) throws FileNotFoundException {
        String name = file != null ? file.getPath() : null;
        if(name == null)
            throw new NullPointerException();
        if(file.isInvalid())
            throw new FileNotFoundException("Invalid file path");
        fd = new FileDescriptor();
        fd.attach(this);
        path = name;
        open(name);
        FileCleanable.register(fd);
    }

    public FileInputStream(FileDescriptor fdObj) {
        if(fdObj == null)
            throw new NullPointerException();
        fd = fdObj;
        path = null;
        fd.attach(this);
    }

    /**
     * 打开指定文件进行读取。
     */
    private native void open0(String name) throws FileNotFoundException;

    private void open(String name) throws FileNotFoundException {
        open0(name);
    }

    private native int read0() throws IOException;

    /**
     * 从该输入流读取1个字节数据，如果还未有可用输入，此方法将阻塞。
     */
    public int read() throws IOException {
        return read0();
    }

    private native int readBytes(byte b[], int off, int len) throws IOException;

    /**
     * 从该输入流中读取最多b.length字节的数据，并将其存储到byte数组中，该方法会阻塞，直到有输入可用。
     */
    public int read(byte b[]) throws IOException {
        return readBytes(b, 0, b.length);
    }

    public int read(byte b[], int off, int len) throws IOException {
        return readBytes(b, off, len);
    }

    public byte[] readAllBytes() throws IOException {
        long length = length();
        long position = position();
        long size = length - position;

        if(length <= 0 || size <= 0)
            return super.readAllBytes();

        if(size > (long) Integer.MAX_VALUE) {
            String msg = String.format("Required array size too large for %s: %d = %d - %d", path, size, length, position);
            throw new OutOfMemoryError(msg);
        }

        int capacity = (int)size;
        byte[] buf = new byte[capacity];

        int nread = 0;
        int n;
        while(true) {
            while((n = read(buf, nread, capacity - nread)) > 0)
                nread += n;
            if(n < 0 || (n = read()) < 0)
                break;
            capacity = Math.max(ArraysSupport.newLength(capacity,
                            1,         // min growth
                            capacity), // pref growth
                            DEFAULT_BUFFER_SIZE);
            buf = Arrays.copyOf(buf, capacity);
            buf[nread++] = (byte)n;
        }
        return capacity == nread ? buf : Arrays.copyOf(buf, nread);
    }

    public byte[] readNBytes(int len) throws IOException {
        if(len < 0)
            throw new IllegalArgumentException("len < 0");
        if(len == 0)
            return new byte[0];

        long length = length();
        long position = position();
        long size = length - position;

        if(length <= 0 || size <= 0)
            return super.readNBytes(len);

        int capacity = (int)Math.min(len, size);
        byte[] buf = new byte[capacity];

        int remaining = capacity;
        int nread = 0;
        int n;
        do {
            n = read(buf, nread, remaining);
            if(n > 0 ) {
                nread += n;
                remaining -= n;
            }else if (n == 0) {
                // Block until a byte is read or EOF is detected
                byte b = (byte)read();
                if(b == -1)
                    break;
                buf[nread++] = b;
                remaining--;
            }
        } while (n >= 0 && remaining > 0);
        return capacity == nread ? buf : Arrays.copyOf(buf, nread);
    }

    private long length() throws IOException {
        return length0();
    }

    private native long length0() throws IOException;

    private long position() throws IOException {
        return position0();
    }

    private native long position0() throws IOException;

    public long skip(long n) throws IOException {
        return skip0(n);
    }

    private native long skip0(long n) throws IOException;

    public int available() throws IOException {
        return available0();
    }

    private native int available0() throws IOException;

    public void close() throws IOException {
        if(closed)
            return;
        synchronized(closeLock) {
            if(closed)
                return;
            closed = true;
        }

        FileChannel fc = channel;
        if(fc != null) {
            fc.close();
        }

        fd.closeAll(new Closeable() {
            public void close() throws IOException {
                fd.close();
            }
        });
    }

    public final FileDescriptor getFD() throws IOException {
        if(fd != null)
            return fd;
        throw new IOException();
    }

    public FileChannel getChannel() {
        FileChannel fc = this.channel;
        if(fc == null) {
            synchronized(this) {
                fc = this.channel;
                if(fc == null) {
                    this.channel = fc = FileChannelImpl.open(fd, path, true, false, false, this);
                    if(closed) {
                        try {
                            fc.close();
                        } catch (IOException e) {
                            throw new InternalError(e);
                        }
                    }
                }
            }
        }
        return fc;
    }

    private static native void initIDs();

    static {
        initIDs();
    }
}

package com.github.liyibo1110.jdk.java.io;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * FileOutputStream是一种用于向文件或文件描述符写入数据的输出流。
 * 文件是否可用或能否创建取决于底层平台。某些平台特别规定，同一文件每次只能由一个文件输出流（或其他文件写入对象）打开进行写入。
 * 在这种情况下，若涉及的文件已被打开，该类的构造函数将失败。
 * FileOutputStream 专用于写入原始字节流（如图像数据）。若需写入字符流，请考虑使用 FileWriter。
 * @author liyibo
 * @date 2026-02-27 18:32
 */
public class FileOutputStream extends OutputStream {
    private static final JavaIOFileDescriptorAccess fdAccess = SharedSecrets.getJavaIOFileDescriptorAccess();

    private final FileDescriptor fd;

    private volatile FileChannel channel;

    private final String path;

    private final Object closeLock = new Object();

    private volatile boolean closed;

    public FileOutputStream(String name) throws FileNotFoundException {
        this(name != null ? new File(name) : null, false);
    }

    public FileOutputStream(String name, boolean append) throws FileNotFoundException {
        this(name != null ? new File(name) : null, append);
    }

    public FileOutputStream(File file) throws FileNotFoundException {
        this(file, false);
    }

    public FileOutputStream(File file, boolean append) throws FileNotFoundException {
        String name = (file != null ? file.getPath() : null);
        if(name == null)
            throw new NullPointerException();
        if(file.isInvalid())
            throw new FileNotFoundException("Invalid file path");
        this.fd = new FileDescriptor();
        fd.attach(this);
        this.path = name;

        open(name, append);
        FileCleanable.register(fd);
    }

    public FileOutputStream(FileDescriptor fdObj) {
        if(fdObj == null)
            throw new NullPointerException();
        this.fd = fdObj;
        this.path = null;
        fd.attach(this);
    }

    private native void open0(String name, boolean append) throws FileNotFoundException;

    private void open(String name, boolean append) throws FileNotFoundException {
        open0(name, append);
    }

    private native void write(int b, boolean append) throws IOException;

    public void write(int b) throws IOException {
        write(b, fdAccess.getAppend(fd));
    }

    private native void writeBytes(byte b[], int off, int len, boolean append) throws IOException;

    public void write(byte b[]) throws IOException {
        writeBytes(b, 0, b.length, fdAccess.getAppend(fd));
    }

    public void write(byte b[], int off, int len) throws IOException {
        writeBytes(b, off, len, fdAccess.getAppend(fd));
    }

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
                    this.channel = fc = FileChannelImpl.open(fd, path, false, true, false, this);
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

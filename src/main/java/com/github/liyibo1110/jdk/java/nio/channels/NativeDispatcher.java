package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * 允许不同平台调用不同的原生方法来执行读写操作
 * @author liyibo
 * @date 2026-03-03 01:26
 */
abstract class NativeDispatcher {
    abstract int read(FileDescriptor fd, long address, int len) throws IOException;

    boolean needsPositionLock() {
        return false;
    }

    int pread(FileDescriptor fd, long address, int len, long position) throws IOException {
        throw new IOException("Operation Unsupported");
    }

    abstract long readv(FileDescriptor fd, long address, int len) throws IOException;

    abstract int write(FileDescriptor fd, long address, int len) throws IOException;

    int pwrite(FileDescriptor fd, long address, int len, long position) throws IOException {
        throw new IOException("Operation Unsupported");
    }

    abstract long writev(FileDescriptor fd, long address, int len) throws IOException;

    abstract void close(FileDescriptor fd) throws IOException;

    void preClose(FileDescriptor fd) throws IOException {
        // Unix系统下会需要实现
    }

    void dup(FileDescriptor fd1, FileDescriptor fd2) throws IOException {
        throw new UnsupportedOperationException();
    }
}

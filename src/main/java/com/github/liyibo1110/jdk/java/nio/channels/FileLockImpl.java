package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.ClosedChannelException;

/**
 * FileLock的实现类
 * @author liyibo
 * @date 2026-03-03 11:41
 */
public class FileLockImpl extends FileLock {
    private volatile boolean invalid;

    FileLockImpl(FileChannel channel, long position, long size, boolean shared) {
        super(channel, position, size, shared);
    }

    FileLockImpl(AsynchronousFileChannel channel, long position, long size, boolean shared) {
        super(channel, position, size, shared);
    }

    public boolean isValid() {
        return !invalid;
    }

    void invalidate() {
        assert Thread.holdsLock(this);
        invalid = true;
    }

    public synchronized void release() throws IOException {
        Channel ch = acquiredBy();
        if(!ch.isOpen())
            throw new ClosedChannelException();
        if(isValid()) {
            if(ch instanceof FileChannelImpl)
                ((FileChannelImpl)ch).release(this);    // 调用FileChannelImpl自己的release
            else if (ch instanceof AsynchronousFileChannelImpl)
                ((AsynchronousFileChannelImpl)ch).release(this);
            else
                throw new AssertionError();
            invalidate();
        }
    }
}

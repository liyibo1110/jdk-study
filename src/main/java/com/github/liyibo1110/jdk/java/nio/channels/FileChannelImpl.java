package com.github.liyibo1110.jdk.java.nio.channels;

import com.github.liyibo1110.jdk.java.nio.DirectBuffer;
import jdk.nio.mapmode.ExtendedMapMode;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLockInterruptionException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SelectableChannel;
import java.util.Objects;

/**
 * @author liyibo
 * @date 2026-03-03 00:46
 */
public class FileChannelImpl extends FileChannel {

    /* mmap支持相关字段 */

    /** mmap对齐大小，通常是4096，用于MappedByteBuffer */
    private static final long allocationGranularity;

    /** sendfile最大大小，用于transferTo，避免内核限制 */
    private static final int MAX_DIRECT_TRANSFER_SIZE;

    /** 用于访问FileDescriptor内部字段，属于JDK内部反射访问 */
    private static final JavaIOFileDescriptorAccess fdAccess = SharedSecrets.getJavaIOFileDescriptorAccess();

    /* 文件资源相关字段 */

    /** NativeIO调度器，负责把方法调用转换对应OS的真实系统C函数调用 */
    private final FileDispatcher nd;

    /**
     * fd具体输出哪个底层实例，可以是：
     * 1、FileInputStream
     * 2、FileOutputStream
     * 3、RandomAccessFile
     * 用来关闭fd，因为Channel本身不能关闭fd。
     */
    private final Object parent;

    /** 底层文件路径，用于DirectIO，如果不是则返回null */
    private final String path;

    /* native调用相关字段 */

    /** 文件描述符，最重要的字段，最终只要操作文件都需要传入这个fd */
    private final FileDescriptor fd;

    /* 权限控制相关字段 */

    /** 是否可写 */
    private final boolean writable;

    /** 是否可读 */
    private final boolean readable;

    /* 并发控制相关字段 */

    /** 正在执行IO的线程集合，作用是当有线程调用close方法时，会唤醒所有线程 */
    private final NativeThreadSet threads = new NativeThreadSet(2);

    /** 保护文件的position，因为FileChannel允许并发执行read和write，position要加锁 */
    private final Object positionLock = new Object();

    /** 不允许interrupt中断IO，用来绕过AbstractInterruptibleChannel，一般就是false，极少改成true */
    private volatile boolean uninterruptible;

    /* DirectIO支持相关字段 */

    /**
     * 是否使用directIO，即可以绕过OS page cache
     * 普通IO：JVM -> OS page cache -> disk
     * DirectIO：JVM -> disk
     * 高性能场景用，例如数据库。
     */
    private final boolean direct;

    /** DirectIO要求必须地址对齐，例如4K对齐，否则OS会拒绝IO操作 */
    private final int alignment;

    /* 资源回收相关字段 */

    /** 类似finalize方法，但更加安全，主要作用是在GC时自动关闭fd，避免fd泄露 */
    private final Cleanable closer;

    private static class Closer implements Runnable {
        private final FileDescriptor fd;
        Closer(FileDescriptor fd) {
            this.fd = fd;
        }

        @Override
        public void run() {
            try {
                fdAccess.close(fd);
            } catch (IOException e) {
                throw new UncheckedIOException("close", e);
            }
        }
    }

    private FileChannelImpl(FileDescriptor fd, String path, boolean readable, boolean writable, boolean direct, Object parent) {
        this.fd = fd;
        this.readable = readable;
        this.writable = writable;
        this.parent = parent;
        this.path = path;
        this.direct = direct;
        this.nd = new FileDispatcherImpl();
        if(direct) {
            assert path != null;
            this.alignment = nd.setDirectIO(fd, path);
        }else {
            this.alignment = -1;
        }

        this.closer = parent != null ? null : CleanerFactory.cleaner().register(this, new Closer(fd));
    }

    public static FileChannel open(FileDescriptor fd, String path, boolean readable, boolean writable, boolean direct, Object parent) {
        return new FileChannelImpl(fd, path, readable, writable, direct, parent);
    }

    private void ensureOpen() throws IOException {
        if(!isOpen())
            throw new ClosedChannelException();
    }

    public void setUninterruptible() {
        uninterruptible = true;
    }

    private void beginBlocking() {
        if(!uninterruptible)
            begin();
    }

    private void endBlocking(boolean completed) throws AsynchronousCloseException {
        if(!uninterruptible)
            end(completed);
    }

    // -- Standard channel operations --

    protected void implCloseChannel() throws IOException {
        if(!fd.valid()) // fd已经无效，不用再close了
            return;

        // 释放锁
        if(fileLockTable != null) {
            for (FileLock fl: fileLockTable.removeAll()) {
                synchronized(fl) {
                    if(fl.isValid()) {
                        nd.release(fd, fl.position(), fl.size());
                        ((FileLockImpl)fl).invalidate();
                    }
                }
            }
        }

        // 通知其他在这个channel上阻塞的线程
        threads.signalAndWait();

        if(parent != null) {    // 关闭原始IO组件
            ((java.io.Closeable)parent).close();
        }else if(closer != null) {
            try {
                closer.clean();
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }else {
            fdAccess.close(fd);
        }
    }

    /**
     * 核心常用方法，将数据读到dst里
     */
    public int read(ByteBuffer dst) throws IOException {
        ensureOpen();
        if(!readable)
            throw new NonReadableChannelException();
        synchronized(positionLock) {
            if (direct)
                Util.checkChannelPositionAligned(position(), alignment);    // 检查对齐
            int n = 0;
            int ti = -1;
            try {
                beginBlocking();    // 将该线程加入到interrupt特殊处理，即当中断时能自动close（后面很多方法都是这个模式）
                ti = threads.add();
                if(!isOpen())
                    return 0;
                do {
                    n = IOUtil.read(fd, dst, -1, direct, alignment, nd);
                } while(n == IOStatus.INTERRUPTED && isOpen());
                return IOStatus.normalize(n);
            } finally {
                threads.remove(ti);
                endBlocking(n > 0);
                assert IOStatus.check(n);
            }
        }
    }

    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, dsts.length);
        ensureOpen();
        if(!readable)
            throw new NonReadableChannelException();
        synchronized(positionLock) {
            if(direct)
                Util.checkChannelPositionAligned(position(), alignment);
            long n = 0;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if (!isOpen())
                    return 0;
                do {
                    n = IOUtil.read(fd, dsts, offset, length, direct, alignment, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                threads.remove(ti);
                endBlocking(n > 0);
                assert IOStatus.check(n);
            }
        }
    }

    public int write(ByteBuffer src) throws IOException {
        ensureOpen();
        if(!writable)
            throw new NonWritableChannelException();
        synchronized (positionLock) {
            if(direct)
                Util.checkChannelPositionAligned(position(), alignment);
            int n = 0;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if(!isOpen())
                    return 0;
                do {
                    n = IOUtil.write(fd, src, -1, direct, alignment, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                threads.remove(ti);
                endBlocking(n > 0);
                assert IOStatus.check(n);
            }
        }
    }

    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, srcs.length);
        ensureOpen();
        if(!writable)
            throw new NonWritableChannelException();
        synchronized (positionLock) {
            if(direct)
                Util.checkChannelPositionAligned(position(), alignment);
            long n = 0;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if(!isOpen())
                    return 0;
                do {
                    n = IOUtil.write(fd, srcs, offset, length, direct, alignment, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                threads.remove(ti);
                endBlocking(n > 0);
                assert IOStatus.check(n);
            }
        }
    }

    // -- Other operations --

    public long position() throws IOException {
        ensureOpen();
        synchronized (positionLock) {
            long p = -1;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if(!isOpen())
                    return 0;
                boolean append = fdAccess.getAppend(fd);
                do {
                    p = append ? nd.size(fd) : nd.seek(fd, -1);
                } while(p == IOStatus.INTERRUPTED && isOpen());
                return IOStatus.normalize(p);
            } finally {
                threads.remove(ti);
                endBlocking(p > -1);
                assert IOStatus.check(p);
            }
        }
    }

    public FileChannel position(long newPosition) throws IOException {
        ensureOpen();
        if(newPosition < 0)
            throw new IllegalArgumentException();
        synchronized (positionLock) {
            long p = -1;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if(!isOpen())
                    return null;
                do {
                    p = nd.seek(fd, newPosition);
                } while(p == IOStatus.INTERRUPTED && isOpen());
                return this;
            } finally {
                threads.remove(ti);
                endBlocking(p > -1);
                assert IOStatus.check(p);
            }
        }
    }

    public long size() throws IOException {
        ensureOpen();
        synchronized (positionLock) {
            long s = -1;
            int ti = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if(!isOpen())
                    return -1;
                do {
                    s = nd.size(fd);
                } while (s == IOStatus.INTERRUPTED && isOpen());
                return IOStatus.normalize(s);
            } finally {
                threads.remove(ti);
                endBlocking(s > -1);
                assert IOStatus.check(s);
            }
        }
    }

    public FileChannel truncate(long newSize) throws IOException {
        ensureOpen();
        if(newSize < 0)
            throw new IllegalArgumentException("Negative size");
        if(!writable)
            throw new NonWritableChannelException();
        synchronized (positionLock) {
            int rv = -1;
            long p = -1;
            int ti = -1;
            long rp = -1;
            try {
                beginBlocking();
                ti = threads.add();
                if (!isOpen())
                    return null;

                // 获取当前size
                long size;
                do {
                    size = nd.size(fd);
                } while ((size == IOStatus.INTERRUPTED) && isOpen());
                if (!isOpen())
                    return null;

                // 获取当前position
                do {
                    p = nd.seek(fd, -1);
                } while ((p == IOStatus.INTERRUPTED) && isOpen());
                if (!isOpen())
                    return null;
                assert p >= 0;

                // 如果给定的size小于原来的size，则执行truncate
                if(newSize < size) {
                    do {
                        rv = nd.truncate(fd, newSize);
                    } while(rv == IOStatus.INTERRUPTED && isOpen());
                    if (!isOpen())
                        return null;
                }

                // 如果position被truncate出去了，则要调整
                if(p > newSize)
                    p = newSize;
                do {
                    rp = nd.seek(fd, p);
                } while ((rp == IOStatus.INTERRUPTED) && isOpen());
                return this;
            } finally {
                threads.remove(ti);
                endBlocking(rv > -1);
                assert IOStatus.check(rv);
            }
        }
    }

    public void force(boolean metaData) throws IOException {
        ensureOpen();
        int rv = -1;
        int ti = -1;
        try {
            beginBlocking();
            ti = threads.add();
            if (!isOpen())
                return;
            do {
                rv = nd.force(fd, metaData);
            } while ((rv == IOStatus.INTERRUPTED) && isOpen());
        } finally {
            threads.remove(ti);
            endBlocking(rv > -1);
            assert IOStatus.check(rv);
        }
    }
    /* sendfile相关字段 */

    /** OS是否支持sendfile */
    private static volatile boolean transferSupported = true;

    /** pipe是否支持sendfile */
    private static volatile boolean pipeSupported = true;

    /** file -> file是否支持sendfile */
    private static volatile boolean fileSupported = true;

    private long transferToDirectlyInternal(long position, int icount,
                                            WritableByteChannel target, FileDescriptor targetFD) throws IOException {
        assert !nd.transferToDirectlyNeedsPositionLock() || Thread.holdsLock(positionLock);

        long n = -1;
        int ti = -1;
        try {
            beginBlocking();
            ti = threads.add();
            if (!isOpen())
                return -1;
            do {
                n = transferTo0(fd, position, icount, targetFD);
            } while (n == IOStatus.INTERRUPTED && isOpen());
            if(n == IOStatus.UNSUPPORTED_CASE) {
                if(target instanceof SinkChannelImpl)
                    pipeSupported = false;
                if(target instanceof FileChannelImpl)
                    fileSupported = false;
                return IOStatus.UNSUPPORTED_CASE;
            }
            if(n == IOStatus.UNSUPPORTED) {
                // Don't bother trying again
                transferSupported = false;
                return IOStatus.UNSUPPORTED;
            }
            return IOStatus.normalize(n);
        } finally {
            threads.remove(ti);
            end (n > -1);
        }
    }

    private long transferToDirectly(long position, int icount, WritableByteChannel target) throws IOException {
        if(!transferSupported)
            return IOStatus.UNSUPPORTED;

        FileDescriptor targetFD = null;
        if(target instanceof FileChannelImpl) {
            if(!fileSupported)
                return IOStatus.UNSUPPORTED_CASE;
            targetFD = ((FileChannelImpl)target).fd;
        }else if(target instanceof SelChImpl) {
            // Direct transfer to pipe causes EINVAL on some configurations
            if((target instanceof SinkChannelImpl) && !pipeSupported)
                return IOStatus.UNSUPPORTED_CASE;

            // Platform-specific restrictions. Now there is only one:
            // Direct transfer to non-blocking channel could be forbidden
            SelectableChannel sc = (SelectableChannel)target;
            if(!nd.canTransferToDirectly(sc))
                return IOStatus.UNSUPPORTED_CASE;

            targetFD = ((SelChImpl)target).getFD();
        }

        if(targetFD == null)
            return IOStatus.UNSUPPORTED;
        int thisFDVal = IOUtil.fdVal(fd);
        int targetFDVal = IOUtil.fdVal(targetFD);
        if(thisFDVal == targetFDVal) // Not supported on some configurations
            return IOStatus.UNSUPPORTED;

        if(nd.transferToDirectlyNeedsPositionLock()) {
            synchronized (positionLock) {
                long pos = position();
                try {
                    return transferToDirectlyInternal(position, icount, target, targetFD);
                } finally {
                    position(pos);
                }
            }
        } else {
            return transferToDirectlyInternal(position, icount, target, targetFD);
        }
    }

    private static final long MAPPED_TRANSFER_SIZE = 8L*1024L*1024L;

    private long transferToTrustedChannel(long position, long count, WritableByteChannel target) throws IOException {
        boolean isSelChImpl = (target instanceof SelChImpl);
        if (!((target instanceof FileChannelImpl) || isSelChImpl))
            return IOStatus.UNSUPPORTED;

        if (target == this) {
            long posThis = position();
            if (posThis - count + 1 <= position &&
                    position - count + 1 <= posThis &&
                    !nd.canTransferToFromOverlappedMap()) {
                return IOStatus.UNSUPPORTED_CASE;
            }
        }

        // Trusted target: Use a mapped buffer
        long remaining = count;
        while (remaining > 0L) {
            long size = Math.min(remaining, MAPPED_TRANSFER_SIZE);
            try {
                MappedByteBuffer dbb = map(MapMode.READ_ONLY, position, size);
                try {
                    // ## Bug: Closing this channel will not terminate the write
                    int n = target.write(dbb);
                    assert n >= 0;
                    remaining -= n;
                    if (isSelChImpl) {
                        // one attempt to write to selectable channel
                        break;
                    }
                    assert n > 0;
                    position += n;
                } finally {
                    unmap(dbb);
                }
            } catch (ClosedByInterruptException e) {
                // target closed by interrupt as ClosedByInterruptException needs
                // to be thrown after closing this channel.
                assert !target.isOpen();
                try {
                    close();
                } catch (Throwable suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            } catch (IOException ioe) {
                // Only throw exception if no bytes have been written
                if (remaining == count)
                    throw ioe;
                break;
            }
        }
        return count - remaining;
    }

    private long transferToArbitraryChannel(long position, long count, WritableByteChannel target) throws IOException {
        // Untrusted target: Use a newly-erased buffer
        int c = (int)Math.min(count, TRANSFER_SIZE);
        ByteBuffer bb = ByteBuffer.allocate(c);
        long tw = 0;                    // Total bytes written
        long pos = position;
        try {
            while (tw < count) {
                bb.limit((int)Math.min(count - tw, TRANSFER_SIZE));
                int nr = read(bb, pos);
                if (nr <= 0)
                    break;
                bb.flip();
                // ## Bug: Will block writing target if this channel
                // ##      is asynchronously closed
                int nw = target.write(bb);
                tw += nw;
                if (nw != nr)
                    break;
                pos += nw;
                bb.clear();
            }
            return tw;
        } catch (IOException x) {
            if(tw > 0)
                return tw;
            throw x;
        }
    }

    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        ensureOpen();
        if (!target.isOpen())
            throw new ClosedChannelException();
        if (!readable)
            throw new NonReadableChannelException();
        if (target instanceof FileChannelImpl && !((FileChannelImpl)target).writable)
            throw new NonWritableChannelException();
        if ((position < 0) || (count < 0))
            throw new IllegalArgumentException();
        long sz = size();
        if (position > sz)
            return 0;

        if (sz - position < count)
            count = sz - position;

        // Attempt a direct transfer, if the kernel supports it, limiting
        // the number of bytes according to which platform
        int icount = (int)Math.min(count, MAX_DIRECT_TRANSFER_SIZE);
        long n;
        if ((n = transferToDirectly(position, icount, target)) >= 0)
            return n;

        // Attempt a mapped transfer, but only to trusted channel types
        if ((n = transferToTrustedChannel(position, count, target)) >= 0)
            return n;

        // Slow path for untrusted targets
        return transferToArbitraryChannel(position, count, target);
    }

    private long transferFromFileChannel(FileChannelImpl src, long position, long count) throws IOException {
        if (!src.readable)
            throw new NonReadableChannelException();
        synchronized (src.positionLock) {
            long pos = src.position();
            long max = Math.min(count, src.size() - pos);

            if (src == this) {
                if (position() - max + 1 <= pos &&
                        pos - max + 1 <= position() &&
                        !nd.canTransferToFromOverlappedMap()) {
                    return IOStatus.UNSUPPORTED_CASE;
                }
            }

            long remaining = max;
            long p = pos;
            while (remaining > 0L) {
                long size = Math.min(remaining, MAPPED_TRANSFER_SIZE);
                // ## Bug: Closing this channel will not terminate the write
                MappedByteBuffer bb = src.map(MapMode.READ_ONLY, p, size);
                try {
                    long n = write(bb, position);
                    assert n > 0;
                    p += n;
                    position += n;
                    remaining -= n;
                } catch (IOException ioe) {
                    // Only throw exception if no bytes have been written
                    if (remaining == max)
                        throw ioe;
                    break;
                } finally {
                    unmap(bb);
                }
            }
            long nwritten = max - remaining;
            src.position(pos + nwritten);
            return nwritten;
        }
    }

    private static final int TRANSFER_SIZE = 8192;

    private long transferFromArbitraryChannel(ReadableByteChannel src, long position, long count) throws IOException {
        // Untrusted target: Use a newly-erased buffer
        int c = (int)Math.min(count, TRANSFER_SIZE);
        ByteBuffer bb = ByteBuffer.allocate(c);
        long tw = 0;                    // Total bytes written
        long pos = position;
        try {
            while (tw < count) {
                bb.limit((int)Math.min((count - tw), (long)TRANSFER_SIZE));
                // ## Bug: Will block reading src if this channel
                // ##      is asynchronously closed
                int nr = src.read(bb);
                if (nr <= 0)
                    break;
                bb.flip();
                int nw = write(bb, pos);
                tw += nw;
                if (nw != nr)
                    break;
                pos += nw;
                bb.clear();
            }
            return tw;
        } catch (IOException x) {
            if (tw > 0)
                return tw;
            throw x;
        }
    }

    public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
        ensureOpen();
        if(!src.isOpen())
            throw new ClosedChannelException();
        if(!writable)
            throw new NonWritableChannelException();
        if((position < 0) || (count < 0))
            throw new IllegalArgumentException();
        if(position > size())
            return 0;

        if(src instanceof FileChannelImpl fci) {
            long n = transferFromFileChannel(fci, position, count);
            if(n >= 0)
                return n;
        }
        return transferFromArbitraryChannel(src, position, count);
    }

    public int read(ByteBuffer dst, long position) throws IOException {
        if(dst == null)
            throw new NullPointerException();
        if(position < 0)
            throw new IllegalArgumentException("Negative position");
        ensureOpen();
        if(!readable)
            throw new NonReadableChannelException();
        if(direct)
            Util.checkChannelPositionAligned(position, alignment);
        if(nd.needsPositionLock()) {
            synchronized (positionLock) {
                return readInternal(dst, position);
            }
        }else {
            return readInternal(dst, position);
        }
    }

    private int readInternal(ByteBuffer dst, long position) throws IOException {
        assert !nd.needsPositionLock() || Thread.holdsLock(positionLock);
        int n = 0;
        int ti = -1;

        try {
            beginBlocking();
            ti = threads.add();
            if (!isOpen())
                return -1;
            do {
                n = IOUtil.read(fd, dst, position, direct, alignment, nd);
            } while ((n == IOStatus.INTERRUPTED) && isOpen());
            return IOStatus.normalize(n);
        } finally {
            threads.remove(ti);
            endBlocking(n > 0);
            assert IOStatus.check(n);
        }
    }

    public int write(ByteBuffer src, long position) throws IOException {
        if (src == null)
            throw new NullPointerException();
        if (position < 0)
            throw new IllegalArgumentException("Negative position");
        ensureOpen();
        if (!writable)
            throw new NonWritableChannelException();
        if (direct)
            Util.checkChannelPositionAligned(position, alignment);
        if (nd.needsPositionLock()) {
            synchronized (positionLock) {
                return writeInternal(src, position);
            }
        } else {
            return writeInternal(src, position);
        }
    }

    private int writeInternal(ByteBuffer src, long position) throws IOException {
        assert !nd.needsPositionLock() || Thread.holdsLock(positionLock);
        int n = 0;
        int ti = -1;
        try {
            beginBlocking();
            ti = threads.add();
            if (!isOpen())
                return -1;
            do {
                n = IOUtil.write(fd, src, position, direct, alignment, nd);
            } while ((n == IOStatus.INTERRUPTED) && isOpen());
            return IOStatus.normalize(n);
        } finally {
            threads.remove(ti);
            endBlocking(n > 0);
            assert IOStatus.check(n);
        }
    }

    // -- Memory-mapped buffers --

    private static abstract class Unmapper implements Runnable, UnmapperProxy {
        private static final NativeDispatcher nd = new FileDispatcherImpl();

        private volatile long address;
        protected final long size;
        protected final long cap;
        private final FileDescriptor fd;
        private final int pagePosition;

        private Unmapper(long address, long size, long cap, FileDescriptor fd, int pagePosition) {
            assert (address != 0);
            this.address = address;
            this.size = size;
            this.cap = cap;
            this.fd = fd;
            this.pagePosition = pagePosition;
        }

        @Override
        public long address() {
            return address + pagePosition;
        }

        @Override
        public FileDescriptor fileDescriptor() {
            return fd;
        }

        @Override
        public void run() {
            unmap();
        }

        public void unmap() {
            if (address == 0)
                return;
            unmap0(address, size);
            address = 0;

            // if this mapping has a valid file descriptor then we close it
            if (fd.valid()) {
                try {
                    nd.close(fd);
                } catch (IOException ignore) {
                    // nothing we can do
                }
            }

            decrementStats();
        }

        protected abstract void incrementStats();
        protected abstract void decrementStats();
    }

    private static class DefaultUnmapper extends Unmapper {

        // keep track of non-sync mapped buffer usage
        static volatile int count;
        static volatile long totalSize;
        static volatile long totalCapacity;

        public DefaultUnmapper(long address, long size, long cap,
                               FileDescriptor fd, int pagePosition) {
            super(address, size, cap, fd, pagePosition);
            incrementStats();
        }

        protected void incrementStats() {
            synchronized (DefaultUnmapper.class) {
                count++;
                totalSize += size;
                totalCapacity += cap;
            }
        }
        protected void decrementStats() {
            synchronized (DefaultUnmapper.class) {
                count--;
                totalSize -= size;
                totalCapacity -= cap;
            }
        }

        public boolean isSync() {
            return false;
        }
    }

    private static class SyncUnmapper extends Unmapper {

        // keep track of mapped buffer usage
        static volatile int count;
        static volatile long totalSize;
        static volatile long totalCapacity;

        public SyncUnmapper(long address, long size, long cap,
                            FileDescriptor fd, int pagePosition) {
            super(address, size, cap, fd, pagePosition);
            incrementStats();
        }

        protected void incrementStats() {
            synchronized (SyncUnmapper.class) {
                count++;
                totalSize += size;
                totalCapacity += cap;
            }
        }
        protected void decrementStats() {
            synchronized (SyncUnmapper.class) {
                count--;
                totalSize -= size;
                totalCapacity -= cap;
            }
        }

        public boolean isSync() {
            return true;
        }
    }

    private static void unmap(MappedByteBuffer bb) {
        Cleaner cl = ((DirectBuffer)bb).cleaner();
        if (cl != null)
            cl.clean();
    }

    private static final int MAP_INVALID = -1;
    private static final int MAP_RO = 0;
    private static final int MAP_RW = 1;
    private static final int MAP_PV = 2;

    public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        if (size > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Size exceeds Integer.MAX_VALUE");
        boolean isSync = isSync(Objects.requireNonNull(mode, "Mode is null"));
        int prot = toProt(mode);
        Unmapper unmapper = mapInternal(mode, position, size, prot, isSync);
        if (unmapper == null) {
            // a valid file descriptor is not required
            FileDescriptor dummy = new FileDescriptor();
            if ((!writable) || (prot == MAP_RO))
                return Util.newMappedByteBufferR(0, 0, dummy, null, isSync);
            else
                return Util.newMappedByteBuffer(0, 0, dummy, null, isSync);
        } else if ((!writable) || (prot == MAP_RO)) {
            return Util.newMappedByteBufferR((int)unmapper.cap,
                    unmapper.address + unmapper.pagePosition,
                    unmapper.fd,
                    unmapper, isSync);
        } else {
            return Util.newMappedByteBuffer((int)unmapper.cap,
                    unmapper.address + unmapper.pagePosition,
                    unmapper.fd,
                    unmapper, isSync);
        }
    }

    public Unmapper mapInternal(MapMode mode, long position, long size) throws IOException {
        boolean isSync = isSync(Objects.requireNonNull(mode, "Mode is null"));
        int prot = toProt(mode);
        return mapInternal(mode, position, size, prot, isSync);
    }

    private Unmapper mapInternal(MapMode mode, long position, long size, int prot, boolean isSync) throws IOException {
        ensureOpen();
        if (mode == null)
            throw new NullPointerException("Mode is null");
        if (position < 0L)
            throw new IllegalArgumentException("Negative position");
        if (size < 0L)
            throw new IllegalArgumentException("Negative size");
        if (position + size < 0)
            throw new IllegalArgumentException("Position + size overflow");

        checkMode(mode, prot, isSync);
        long addr = -1;
        int ti = -1;
        try {
            beginBlocking();
            ti = threads.add();
            if (!isOpen())
                return null;

            long mapSize;
            int pagePosition;
            synchronized (positionLock) {
                long filesize;
                do {
                    filesize = nd.size(fd);
                } while ((filesize == IOStatus.INTERRUPTED) && isOpen());
                if (!isOpen())
                    return null;

                if (filesize < position + size) { // Extend file size
                    if (!writable) {
                        throw new IOException("Channel not open for writing " +
                                "- cannot extend file to required size");
                    }
                    int rv;
                    do {
                        rv = nd.truncate(fd, position + size);
                    } while ((rv == IOStatus.INTERRUPTED) && isOpen());
                    if (!isOpen())
                        return null;
                }

                if (size == 0) {
                    return null;
                }

                pagePosition = (int)(position % allocationGranularity);
                long mapPosition = position - pagePosition;
                mapSize = size + pagePosition;
                try {
                    // If map0 did not throw an exception, the address is valid
                    addr = map0(prot, mapPosition, mapSize, isSync);
                } catch (OutOfMemoryError x) {
                    // An OutOfMemoryError may indicate that we've exhausted
                    // memory so force gc and re-attempt map
                    System.gc();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException y) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        addr = map0(prot, mapPosition, mapSize, isSync);
                    } catch (OutOfMemoryError y) {
                        // After a second OOME, fail
                        throw new IOException("Map failed", y);
                    }
                }
            } // synchronized

            // On Windows, and potentially other platforms, we need an open
            // file descriptor for some mapping operations.
            FileDescriptor mfd;
            try {
                mfd = nd.duplicateForMapping(fd);
            } catch (IOException ioe) {
                unmap0(addr, mapSize);
                throw ioe;
            }

            assert (IOStatus.checkAll(addr));
            assert (addr % allocationGranularity == 0);
            Unmapper um = (isSync
                    ? new SyncUnmapper(addr, mapSize, size, mfd, pagePosition)
                    : new DefaultUnmapper(addr, mapSize, size, mfd, pagePosition));
            return um;
        } finally {
            threads.remove(ti);
            endBlocking(IOStatus.checkAll(addr));
        }
    }

    private boolean isSync(MapMode mode) {
        // Do not want to initialize ExtendedMapMode until
        // after the module system has been initialized
        return !VM.isModuleSystemInited() ? false :
                (mode == ExtendedMapMode.READ_ONLY_SYNC ||
                        mode == ExtendedMapMode.READ_WRITE_SYNC);
    }

    private int toProt(MapMode mode) {
        int prot;
        if(mode == MapMode.READ_ONLY)
            prot = MAP_RO;
        else if (mode == MapMode.READ_WRITE)
            prot = MAP_RW;
        else if (mode == MapMode.PRIVATE)
            prot = MAP_PV;
        else if (mode == ExtendedMapMode.READ_ONLY_SYNC)
            prot = MAP_RO;
        else if (mode == ExtendedMapMode.READ_WRITE_SYNC)
            prot = MAP_RW;
        else
            prot = MAP_INVALID;
        return prot;
    }

    private void checkMode(MapMode mode, int prot, boolean isSync) {
        if (prot == MAP_INVALID) {
            throw new UnsupportedOperationException();
        }
        if ((mode != MapMode.READ_ONLY) && mode != ExtendedMapMode.READ_ONLY_SYNC && !writable)
            throw new NonWritableChannelException();
        if (!readable)
            throw new NonReadableChannelException();
        // reject SYNC request if writeback is not enabled for this platform
        if (isSync && !Unsafe.isWritebackEnabled()) {
            throw new UnsupportedOperationException();
        }
    }

    public static BufferPool getMappedBufferPool() {
        return new BufferPool() {
            @Override
            public String getName() {
                return "mapped";
            }
            @Override
            public long getCount() {
                return DefaultUnmapper.count;
            }
            @Override
            public long getTotalCapacity() {
                return DefaultUnmapper.totalCapacity;
            }
            @Override
            public long getMemoryUsed() {
                return DefaultUnmapper.totalSize;
            }
        };
    }

    public static BufferPool getSyncMappedBufferPool() {
        return new BufferPool() {
            @Override
            public String getName() {
                return "mapped - 'non-volatile memory'";
            }
            @Override
            public long getCount() {
                return SyncUnmapper.count;
            }
            @Override
            public long getTotalCapacity() {
                return SyncUnmapper.totalCapacity;
            }
            @Override
            public long getMemoryUsed() {
                return SyncUnmapper.totalSize;
            }
        };
    }

    /* 文件锁支持相关字段 */

    /** 记录了当前文件锁，例如lock和tryLock，在close时会释放锁 */
    private volatile FileLockTable fileLockTable;

    private FileLockTable fileLockTable() throws IOException {
        if (fileLockTable == null) {
            synchronized (this) {
                if (fileLockTable == null) {
                    int ti = threads.add();
                    try {
                        ensureOpen();
                        fileLockTable = new FileLockTable(this, fd);
                    } finally {
                        threads.remove(ti);
                    }
                }
            }
        }
        return fileLockTable;
    }

    public FileLock lock(long position, long size, boolean shared)
            throws IOException
    {
        ensureOpen();
        if (shared && !readable)
            throw new NonReadableChannelException();
        if (!shared && !writable)
            throw new NonWritableChannelException();
        FileLockImpl fli = new FileLockImpl(this, position, size, shared);
        FileLockTable flt = fileLockTable();
        flt.add(fli);
        boolean completed = false;
        int ti = -1;
        try {
            beginBlocking();
            ti = threads.add();
            if (!isOpen())
                return null;
            int n;
            do {
                n = nd.lock(fd, true, position, size, shared);
            } while ((n == FileDispatcher.INTERRUPTED) && isOpen());
            if (isOpen()) {
                if (n == FileDispatcher.RET_EX_LOCK) {
                    assert shared;
                    FileLockImpl fli2 = new FileLockImpl(this, position, size, false);
                    flt.replace(fli, fli2);
                    fli = fli2;
                }
                completed = true;
            }
        } finally {
            if (!completed)
                flt.remove(fli);
            threads.remove(ti);
            try {
                endBlocking(completed);
            } catch (ClosedByInterruptException e) {
                throw new FileLockInterruptionException();
            }
        }
        return fli;
    }

    public FileLock tryLock(long position, long size, boolean shared) throws IOException {
        ensureOpen();
        if (shared && !readable)
            throw new NonReadableChannelException();
        if (!shared && !writable)
            throw new NonWritableChannelException();
        FileLockImpl fli = new FileLockImpl(this, position, size, shared);
        FileLockTable flt = fileLockTable();
        flt.add(fli);
        int result;

        int ti = threads.add();
        try {
            try {
                ensureOpen();
                result = nd.lock(fd, false, position, size, shared);
            } catch (IOException e) {
                flt.remove(fli);
                throw e;
            }
            if (result == FileDispatcher.NO_LOCK) {
                flt.remove(fli);
                return null;
            }
            if (result == FileDispatcher.RET_EX_LOCK) {
                assert shared;
                FileLockImpl fli2 = new FileLockImpl(this, position, size, false);
                flt.replace(fli, fli2);
                return fli2;
            }
            return fli;
        } finally {
            threads.remove(ti);
        }
    }

    void release(FileLockImpl fli) throws IOException {
        int ti = threads.add();
        try {
            ensureOpen();
            nd.release(fd, fli.position(), fli.size());
        } finally {
            threads.remove(ti);
        }
        assert fileLockTable != null;
        fileLockTable.remove(fli);
    }

    // -- Native methods --

    // Creates a new mapping
    private native long map0(int prot, long position, long length, boolean isSync) throws IOException;

    // Removes an existing mapping
    private static native int unmap0(long address, long length);

    // Transfers from src to dst, or returns -2 if kernel can't do that
    private native long transferTo0(FileDescriptor src, long position, long count, FileDescriptor dst);

    // Retrieves the maximum size of a transfer
    private static native int maxDirectTransferSize0();

    // Caches fieldIDs
    private static native long initIDs();

    static {
        IOUtil.load();
        allocationGranularity = initIDs();
        MAX_DIRECT_TRANSFER_SIZE = maxDirectTransferSize0();
    }
}

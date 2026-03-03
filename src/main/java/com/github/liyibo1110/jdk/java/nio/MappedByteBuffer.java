package com.github.liyibo1110.jdk.java.nio;

import sun.misc.Unsafe;

import java.io.FileDescriptor;
import java.lang.ref.Reference;
import java.util.Objects;

/**
 * DirectByteBuffer，其内容是1文件的内存映射区域。
 * MappedByteBuffer通过FileChannel的map方法来创建，该类在ByteBuffer类的基础上扩展了针对内存映射文件区域的特定操作。
 * MappedByteBuffer以及所代表的文件映射在缓冲区本身被GC之前始终有效。
 *
 * MappedByteBuffer的内容可能随时发生变化，例如当本程序或其他程序修改了映射文件对应区域的内容时，此类变更是否发生以及发生时机取决于OS，因此未作具体规定。
 * MappedByteBuffer的全部或部分内容可能随时变得不可访问，例如当映射文件被截断时。
 * 尝试访问MappedByteBuffer中不可访问的区域不会改变缓冲区内容，但会在访问时或稍后引发未指定异常。因此强烈建议采取适当预防措施，避免本程序或并发运行的程序操作映射文件（读写文件内容除外）。
 * MappedByteBuffer的其他行为与普通DirectByteBuffer并无差异。
 * @author liyibo
 * @date 2026-03-02 17:14
 */
public abstract class MappedByteBuffer extends ByteBuffer {
    private final FileDescriptor fd;
    private final boolean isSync;
    static final ScopedMemoryAccess SCOPED_MEMORY_ACCESS = ScopedMemoryAccess.getScopedMemoryAccess();

    MappedByteBuffer(int mark, int pos, int lim, int cap, FileDescriptor fd, boolean isSync, MemorySegmentProxy segment) {
        super(mark, pos, lim, cap, segment);
        this.fd = fd;
        this.isSync = isSync;
    }

    MappedByteBuffer(int mark, int pos, int lim, int cap, boolean isSync, MemorySegmentProxy segment) {
        super(mark, pos, lim, cap, segment);
        this.fd = null;
        this.isSync = isSync;
    }

    MappedByteBuffer(int mark, int pos, int lim, int cap, MemorySegmentProxy segment) {
        super(mark, pos, lim, cap, segment);
        this.fd = null;
        this.isSync = false;
    }

    UnmapperProxy unmapper() {
        return fd != null ?
            new UnmapperProxy() {
                @Override
                public long address() {
                    return address;
                }

                @Override
                public FileDescriptor fileDescriptor() {
                    return fd;
                }

                @Override
                public boolean isSync() {
                    return isSync;
                }

                @Override
                public void unmap() {
                    Unsafe.getUnsafe().invokeCleaner(MappedByteBuffer.this);
                }
            } : null;
    }

    final boolean isSync() { // package-private
        return isSync;
    }

    final FileDescriptor fileDescriptor() { // package-private
        return fd;
    }

    /**
     * 指示该buffer内容是否驻留在物理内存中。
     * 返回值为true表示该Buffer中的所有数据极可能驻留在物理内存中，因此访问时不会引发虚拟内存页面错误或I/O操作。
     * 返回值为false并不必然意味着Buffer内容未驻留在物理内存中。
     * 该返回值仅为提示而非保证，因此底层OS可能在本方法调用返回时已将部分Buffer数据分页至虚拟内存。
     */
    public final boolean isLoaded() {
        if(fd == null)
            return true;
        return SCOPED_MEMORY_ACCESS.isLoaded(scope(), address, isSync, capacity());
    }

    /**
     * 将此Buffer内容加载到物理内存中，
     * 此方法尽最大努力确保在返回时，该Buffer的内容驻留在物理内存中，调用此方法可能会导致发生若干次页面故障和I/O操作。
     */
    public final MappedByteBuffer load() {
        if(fd == null)
            return this;
        try {
            SCOPED_MEMORY_ACCESS.load(scope(), address, isSync, capacity());
        } finally {
            Reference.reachabilityFence(this);
        }
        return this;
    }

    /**
     * 强制将此缓冲区内容的任何更改写入包含映射文件的存储设备。
     * 该区域从缓冲区索引零处开始，容量为capacity()字节。调用此方法的行为与调用force(0,capacity())完全相同。
     * 若映射至该缓冲区的文件位于本地存储设备，则方法返回时可确保自缓冲区创建或上次调用本方法以来所有修改均已写入该设备。
     * 若文件未驻留本地设备，则不作此类保证。
     * 若缓冲区未以读写模式（java.nio.channels.FileChannel.MapMode.READ_WRITE）映射，调用本方法可能无效。
     * 尤其对于只读或私有映射模式的缓冲区，本方法完全无效。针对特定实现的映射模式，本方法可能有效也可能无效。
     */
    public final MappedByteBuffer force() {
        if(fd == null)
            return this;
        int capacity = capacity();
        if(isSync || ((address != 0) && (capacity != 0)))
            return force(0, capacity);
        return this;
    }

    /**
     * 强制将缓冲区内容中某个区域的所有修改写入包含映射文件的存储设备。该区域从缓冲区中给定索引处开始，长度为length字节。
     */
    public final MappedByteBuffer force(int index, int length) {
        if(fd == null)
            return this;
        int capacity = capacity();
        if((address != 0) && (capacity != 0)) {
            // check inputs
            Objects.checkFromIndexSize(index, length, capacity);
            SCOPED_MEMORY_ACCESS.force(scope(), fd, address, isSync, index, length);
        }
        return this;
    }

    @Override
    public final MappedByteBuffer position(int newPosition) {
        super.position(newPosition);
        return this;
    }

    @Override
    public final MappedByteBuffer limit(int newLimit) {
        super.limit(newLimit);
        return this;
    }

    @Override
    public final MappedByteBuffer mark() {
        super.mark();
        return this;
    }

    @Override
    public final MappedByteBuffer reset() {
        super.reset();
        return this;
    }

    @Override
    public final MappedByteBuffer clear() {
        super.clear();
        return this;
    }

    @Override
    public final MappedByteBuffer flip() {
        super.flip();
        return this;
    }

    @Override
    public final MappedByteBuffer rewind() {
        super.rewind();
        return this;
    }

    @Override
    public abstract MappedByteBuffer slice();

    @Override
    public abstract MappedByteBuffer slice(int index, int length);

    @Override
    public abstract MappedByteBuffer duplicate();

    @Override
    public abstract MappedByteBuffer compact();
}

package com.github.liyibo1110.jdk.java.nio;

import sun.misc.Unsafe;

import java.io.FileDescriptor;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.InvalidMarkException;
import java.nio.MappedByteBuffer;
import java.util.Spliterator;

/**
 * 用于存储特定基本类型数据的容器。
 * Buffer是由特定基本类型元素组成的线性有限序列，除内容外，Buffer的核心属性包括capacity、limit和position：
 * 1、capacity指其包含的元素数量，该值永远不会为负，也不会改变。
 * 2、limit指不应读取或写入的第一个元素的索引，该值不会为负，也不会大于capacity。
 * 3、position指即将读取或写入的下一个元素的下标，该值不会为负，也不会大于limit。
 * 对于每个非boolean基本类型，该类都存在相应的子类（例如ByteBuffer）
 *
 * Transferring data
 * 该类的每个子类都定义了两种类型的get和set操作：
 * relative操作从position开始读取或写入一个或多个元素，随后将position递增转移元素的数量。
 * 若请求的转移量超出限制，则相对get操作抛出BufferUnderflowException，相对set操作抛出BufferOverflowException，
 * 无论哪种情况，数据均不会被转移。
 * 数据当然也可以通过相应Channel的I/O操作传输到Buffer或从Buffer传输出去，这些操作始终与position相关。
 *
 * Marking and resetting
 * Buffer的mark是调用reset方法时其位置将被重置到的索引，mark并非总是定义的，但当被定义时，不会为负值，也不会大于position。
 * 如果mark已定义，当position或limit调整为小于mark值时，mark将被丢弃，若mark未定义，调用reset将抛出InvalidMarkException。
 *
 * Invariants
 * 对于mark、position、limit和capacity，以下恒等式成立：
 * 0 <= mark <= position <= limit <= capacity
 * 新创建的Buffer始终具有position为0且mark未定义的初始状态。limit可能也为0，也可能是取决于Buffer类型以及构造方式的其他值。
 * 新分配Buffer的每个元素均初始化为0。
 *
 * Additional operations
 * 除了用于访问position、limit和capacity以及mark和reset方法外，该类还定义了对Buffer的以下操作：
 * 1、clear：使Buffer准备好执行新的Channel读取或相对写入操作序列，它将limit设置为capacity，并将position设置为0。
 * 2、flip：使Buffer准备好执行新的Channel写入或相对读取操作序列，它将limit设置为当前位置，然后将position设置为0。
 * 3、rewind：使Buffer准备好重新读取其已包含的数据，它保持limit不变，并将position设置为0。
 * 4、slice和带索引长度的slice(index, lenth)：创建Buffer的子序列，它们保持limit和position不变。
 * 5、duplicate：创建Buffer的浅copy，保持Limit和position不变。
 *
 * Read-only buffers
 * 每个Buffer都是可读的，但并非都是可写的，每个Buffer类的修改方法均被定义为可选操作，当对只读Buffer调用这些方法时，会抛出ReadOnlyBufferException。
 * 只读Buffer不允许修改其内容，但其mark、position和limit可变，通过调用isReadOnly方法可以判断其是否为只读状态。
 *
 * Thread safety
 * Buffer不适合由多个线程并发安全使用，若需多个线程使用同一个Buffer，应通过适当的同步机制控制对Buffer的访问。
 *
 * Invocation chaining
 * 本类中未另行指定返回值的方法，默认返回其被调用的Buffer，这使得方法调用能够进行链式调用，例如以下语句序列：
 * b.flip(); b.position(23); b.limit(42);
 * 可以被更紧凑的单语句替代：
 * b.flip().position(23).limit(42);
 *
 * @author liyibo
 * @date 2026-03-02 01:21
 */
public abstract class Buffer {
    /**
     * 第一代优化：UNSAFE，本质就是让Java可以直接操作内存地址（通过address + offset）。
     * DirectBuffer其实就是使用的Unsafe，对比可见少了一次复制，是零copy的基础：
     * 1、普通I/O：磁盘 -> 内核buffer -> JVM byte[]
     * 2、DirectBuffer：磁盘 -> JVM直接内存
     */
    static final Unsafe UNSAFE = Unsafe.getUnsafe();    // 应该是jdk.internal.misc包里的

    /**
     * 新一代优化：JDK17新机制，是MemorySegment机制的底层工具类，作用是：控制内存访问生命周期。
     * Unsafe的问题：
     * 1、可以访问非法内存。
     * 2、可以访问释放后的内存。
     * 3、JVM无法管理生命周期。
     *
     * ScopedMemoryAccess解决了什么？
     * 1、内存必须在合法scope内访问。
     * 2、不能访问已经被释放的内存。
     */
    static final ScopedMemoryAccess SCOPED_MEMORY_ACCESS = ScopedMemoryAccess.getScopedMemoryAccess();

    static final int SPLITERATOR_CHARACTERISTICS = Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.ORDERED;

    private int mark = -1;
    private int position = 0;
    private int limit;
    private int capacity;

    /**
     * native pointer，对应老版本的base（代表的是byte[]）
     */
    long address;

    /**
     * 新一代DirectBuffer，具备的功能有：
     * 1、生命周期管理
     * 2、线程访问限制
     * 3、边界检查
     */
    final MemorySegmentProxy segment;

    Buffer(long addr, int cap, MemorySegmentProxy segment) {
        this.address = addr;
        this.capacity = cap;
        this.segment = segment;
    }

    Buffer(int mark, int pos, int lim, int cap, MemorySegmentProxy segment) {
        if (cap < 0)
            throw createCapacityException(cap);
        this.capacity = cap;
        this.segment = segment;
        limit(lim);
        position(pos);
        if (mark >= 0) {
            if (mark > pos)
                throw new IllegalArgumentException("mark > position: (" + mark + " > " + pos + ")");
            this.mark = mark;
        }
    }

    static IllegalArgumentException createSameBufferException() {
        return new IllegalArgumentException("The source buffer is this buffer");
    }

    static IllegalArgumentException createCapacityException(int capacity) {
        assert capacity < 0 : "capacity expected to be negative";
        return new IllegalArgumentException("capacity < 0: (" + capacity + " < 0)");
    }

    public final int capacity() {
        return capacity;
    }

    public final int position() {
        return position;
    }

    /**
     * 设置position
     */
    public Buffer position(int newPosition) {
        if (newPosition > limit | newPosition < 0)
            throw createPositionException(newPosition);
        if (mark > newPosition)  // mark是否要无效
            mark = -1;
        position = newPosition;
        return this;
    }

    private IllegalArgumentException createPositionException(int newPosition) {
        String msg;
        if (newPosition > limit) {
            msg = "newPosition > limit: (" + newPosition + " > " + limit + ")";
        } else {
            assert newPosition < 0 : "newPosition expected to be negative";
            msg = "newPosition < 0: (" + newPosition + " < 0)";
        }
        return new IllegalArgumentException(msg);
    }

    public final int limit() {
        return limit;
    }

    public Buffer limit(int newLimit) {
        if (newLimit > capacity | newLimit < 0)
            throw createLimitException(newLimit);
        limit = newLimit;
        if (position > newLimit)
            position = newLimit;
        if (mark > newLimit)
            mark = -1;
        return this;
    }

    private IllegalArgumentException createLimitException(int newLimit) {
        String msg;
        if (newLimit > capacity) {
            msg = "newLimit > capacity: (" + newLimit + " > " + capacity + ")";
        } else {
            assert newLimit < 0 : "newLimit expected to be negative";
            msg = "newLimit < 0: (" + newLimit + " < 0)";
        }
        return new IllegalArgumentException(msg);
    }

    public Buffer mark() {
        mark = position;
        return this;
    }

    public Buffer reset() {
        int m = mark;
        if (m < 0)
            throw new InvalidMarkException();
        position = m;
        return this;
    }

    /**
     * 重要方法：和flip是相对的操作，将Buffer从写数据模式，切换回读数据模式。
     */
    public Buffer clear() {
        position = 0;
        limit = capacity;
        mark = -1;
        return this;
    }

    /**
     * 翻转Buffer，将limit设置为position，然后将position设置为0，尝试丢弃mark。
     * 在执行一系列Channel读取或写入操作后，调用此方法以准备执行一系列的Channel写入或相对获取操作，例如：
     * buf.put(magic);    // Prepend header
     * in.read(buf);      // Read data into rest of buffer
     * buf.flip();        // Flip buffer
     * out.write(buf);    // Write header + data to channel
     * <p>
     * 重要方法：将Buffer从读数据模式（即只能往Buffer增加数据），切换为写数据模式（Buffer向外部输出其内部的数据）。
     */
    public Buffer flip() {
        limit = position;
        position = 0;
        mark = -1;
        return this;
    }

    /**
     * 重要方法，就是将position和mark重置，使得可以重头再次读写数据
     */
    public Buffer rewind() {
        position = 0;
        mark = -1;
        return this;
    }

    /**
     * 还能读或写（看模式）多少数据
     */
    public final int remaining() {
        int rem = limit - position;
        return rem > 0 ? rem : 0;
    }

    /**
     * 是否还有可供读或写（看模式）的数据
     */
    public final boolean hasRemaining() {
        return position < limit;
    }

    /**
     * 指示此Buffer是否由可访问数组支持。
     * 如果返回true，则可以安全调用array和arrayOffset方法。
     */
    public abstract boolean hasArray();

    /**
     * 返回Buffer关联的数据（可选操作）。
     * 此方法旨在更高效地将数组关联Buffer传递给本机代码，具体子类为此方法提供了更强类型的返回值。
     * 修改此Buffer的内容将导致返回数组的内容被修改，反之亦然。
     * 调用本方法前，要先调用hasArray方法，确保该Buffer拥有可访问的支撑数组。
     */
    public abstract Object array();

    /**
     * 返回Buffer首个元素在Buffer后备数组中的偏移量（可选操作）。
     * 若本Buffer由数组支持，则Buffer位置p对应数组索引p + arrayOffset()。
     * 调用本方法前，要先调用hasArray方法，确保该Buffer拥有可访问的支撑数组。
     */
    public abstract int arrayOffset();

    /**
     * Buffer是否为DirectBuffer
     */
    public abstract boolean isDirect();

    /**
     * 创建一个新Buffer，内容是本Buffer内容的共享子序列。
     * 新Buffer内容将从本Buffer的position开始，对本Buffer内容修改将在新Buffer中可见，反之亦然。
     * 两个Buffer的position、limit和mark则相互独立。
     * 新Buffer的position初始为0，其capacity和limit等于本Buffer剩余元素数量，mark为未定义。
     * 新Buffer仅当本Buffer为DirectBuffer时，才为DirectBuffer，仅当本Buffer为ReadOnlyBuffer时，才为ReadOnlyBuffer。
     */
    public abstract Buffer slice();

    public abstract Buffer slice(int index, int length);

    /**
     * 创建一个共享本Buffer内容的新Buffer。
     * 新Buffer将继承本Buffer的内容。对本Buffer内容的修改将同步反映在新Buffer中，反之亦然；但两者的位置、限制和标记值将保持独立。
     * 新Buffer的capacity、limit、position和mark将与当前Buffer完全一致。新Buffer仅当当前Buffer为DirectBuffer时才为DirectBuffer，
     * 仅当当前Buffer为ReadOnlyBuffer时才为ReadOnlyBuffer。
     */
    public abstract Buffer duplicate();

    abstract Object base();

    /**
     * 检查position是否小于limit，若大于等于limit则抛出BufferUnderflowException，随后递增position。
     */
    final int nextGetIndex() {
        int p = position;
        if (p >= limit)
            throw new BufferUnderflowException();
        position = p + 1;
        return p;
    }

    final int nextGetIndex(int nb) {
        int p = position;
        if (limit - p < nb)
            throw new BufferUnderflowException();
        position = p + nb;
        return p;
    }

    /**
     * 检查position是否超过limit，若其值大于limit则抛出BufferOverflowException，随后递增位置。
     */
    final int nextPutIndex() {
        int p = position;
        if (p >= limit)
            throw new BufferOverflowException();
        position = p + 1;
        return p;
    }

    final int nextPutIndex(int nb) {
        int p = position;
        if (limit - p < nb)
            throw new BufferOverflowException();
        position = p + nb;
        return p;
    }

    /**
     * 检查给定index是否超出限制，若大于limit或小于0，则抛出IndexOutOfBoundsException
     */
    final int checkIndex(int i) {
        if(i < 0 || i >= limit)
            throw new IndexOutOfBoundsException();
        return i;
    }

    final int checkIndex(int i, int nb) {
        if (i < 0 || nb > limit - i)
            throw new IndexOutOfBoundsException();
        return i;
    }

    final int markValue() {
        return mark;
    }

    final void discardMark() {
        mark = -1;
    }

    final ScopedMemoryAccess.Scope scope() {
        if(segment != null)
            return segment.scope();
        else
            return null;
    }

    final void checkScope() {
        ScopedMemoryAccess.Scope scope = scope();
        if(scope != null)
            scope.checkValidState();
    }

    static {
        // setup access to this package in SharedSecrets
        SharedSecrets.setJavaNioAccess(
        new JavaNioAccess() {
            @Override
            public BufferPool getDirectBufferPool() {
                return Bits.BUFFER_POOL;
            }

            @Override
            public ByteBuffer newDirectByteBuffer(long addr, int cap, Object obj, MemorySegmentProxy segment) {
                return new DirectByteBuffer(addr, cap, obj, segment);
            }

            @Override
            public ByteBuffer newMappedByteBuffer(UnmapperProxy unmapperProxy, long address, int cap, Object obj, MemorySegmentProxy segment) {
                return new DirectByteBuffer(address, cap, obj, unmapperProxy.fileDescriptor(), unmapperProxy.isSync(), segment);
            }

            @Override
            public ByteBuffer newHeapByteBuffer(byte[] hb, int offset, int capacity, MemorySegmentProxy segment) {
                return new HeapByteBuffer(hb, -1, 0, capacity, capacity, offset, segment);
            }

            @Override
            public Object getBufferBase(ByteBuffer bb) {
                return bb.base();
            }

            @Override
            public long getBufferAddress(ByteBuffer bb) {
                return bb.address;
            }

            @Override
            public UnmapperProxy unmapper(ByteBuffer bb) {
                if (bb instanceof MappedByteBuffer) {
                    return ((MappedByteBuffer)bb).unmapper();
                } else {
                    return null;
                }
            }

            @Override
            public MemorySegmentProxy bufferSegment(Buffer buffer) {
                return buffer.segment;
            }

            @Override
            public Scope.Handle acquireScope(Buffer buffer, boolean async) {
                var scope = buffer.scope();
                if (scope == null) {
                    return null;
                }
                if (async && scope.ownerThread() != null) {
                    throw new IllegalStateException("Confined scope not supported");
                }
                return scope.acquire();
            }

            @Override
            public void force(FileDescriptor fd, long address, boolean isSync, long offset, long size) {
                MappedMemoryUtils.force(fd, address, isSync, offset, size);
            }

            @Override
            public void load(long address, boolean isSync, long size) {
                MappedMemoryUtils.load(address, isSync, size);
            }

            @Override
            public void unload(long address, boolean isSync, long size) {
                MappedMemoryUtils.unload(address, isSync, size);
            }

            @Override
            public boolean isLoaded(long address, boolean isSync, long size) {
                return MappedMemoryUtils.isLoaded(address, isSync, size);
            }

            @Override
            public void reserveMemory(long size, long cap) {
                Bits.reserveMemory(size, cap);
            }

            @Override
            public void unreserveMemory(long size, long cap) {
                Bits.unreserveMemory(size, cap);
            }

            @Override
            public int pageSize() {
                return Bits.pageSize();
            }
        });
    }
}

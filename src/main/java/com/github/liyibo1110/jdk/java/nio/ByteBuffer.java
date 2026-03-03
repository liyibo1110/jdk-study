package com.github.liyibo1110.jdk.java.nio;

import java.lang.ref.Reference;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.ShortBuffer;
import java.util.Objects;

/**
 * 本类定义了六类针对ByteBuffer的操作：
 * 1、绝对和相对get方法，用于读取单个字节。
 * 2、绝对和相对bulk get方法，将连续字节序列从Buffer传输至数组。
 * 3、绝对和相对bulk put方法，将连续字节序列从字节数组，或其他ByteBuffer写入本Buffer.
 * 4、绝对和相对get及put方法，用于读写其他基本类型的值，并将其转换为特定字节序的字节序列。
 * 5、创建视图Buffer的方法，允许将ByteBuffer视为包含其他基本类型值的Buffer，以及压缩ByteBuffer的方法。
 *
 * ByteBuffer可以通过两种方式创建：
 * 1、通过allocation来存储Buffer内容。
 * 2、将现有字节数组或字符串wrapping为Buffer。
 *
 * Direct vs non-direct buffers
 * ByteBuffer分为Direct和非Direct。对于Direct，Java虚拟机将尽最大努力直接对其执行本机I/O操作。
 * 即在每次调用底层操作系统的本机I/O操作之前（或之后），它将尝试避免将Buffer内容复制到（或从）中间Buffer。
 *
 * 可通过调用本类的allocateDirect工厂方法创建直接ByteBuffer。该方法返回的Buffer通常具有比非Direct略高的分配与释放开销。
 * Direct的内容可能驻留在常规垃圾回收堆之外，因此其对应用程序内存占用的影响可能不明显。
 * 因此建议主要为需执行底层系统原生I/O操作的大型长期缓冲区分配Direct。通常仅当能带来可量化的程序性能提升时，才应分配Direct。
 * 也可通过将文件区域直接映射到内存中创建Direct。Java平台的实现可选择性支持通过JNI从本机代码创建Direct。
 * 若此类Buffer实例指向不可访问的内存区域，则访问该区域不会改变Buffer内容，并可能在访问时或后续某个时间点抛出未指定异常。
 * ByteBuffer的直接性可通过调用其isDirect方法判断。该方法旨在支持在性能关键代码中进行显式缓冲区管理。
 *
 * Access to binary data
 * 该类定义了读取和写入除布尔型以外所有其他基本数据类型值的方法。
 * 基本数据值会根据Buffer的当前字节序转换为（或转换为）字节序列，该字节序可通过顺序方法获取和修改。
 * 特定字节序由ByteOrder类的实例表示。ByteBuffer的初始顺序始终为BIG_ENDIAN。
 * 为访问异构二进制数据（即不同类型的值序列），本类为每种类型定义了一组绝对和相对的获取与存入方法。例如对于32位浮点数，本类定义了：getFloat()内容略
 *
 * 针对字符型、短整型、整型、长整型和双精度型定义了对应的方法。绝对读取和写入方法的索引参数以字节为单位，而非读写数据的类型。
 * 为访问同质二进制数据（即相同类型的连续值序列），本类定义了可创建给定字节缓冲区视图的方法。
 * 视图缓冲区本质上是另一个缓冲区，其内容由ByteBuffer提供支持。ByteBuffer内容的变更将同步反映在视图缓冲区中，反之亦然；但两者的位置、限制和标记值保持独立。例如asFloatBuffer方法会创建FloatBuffer类的实例，该实例由调用该方法的字节缓冲区提供支持。针对char、short、int、long和double类型均定义了相应的视图创建方法。
 * 相较于前述各类型专属的get和put方法家族，视图缓冲区具有三大显著优势：
 * 1、视图缓冲区采用基于值的类型特定大小进行索引，而非基于字节。
 * 2、视图缓冲区提供相对批量获取和设置方法，可在Buffer与数组或同类型其他Buffer之间传输连续数值序列。
 * 3、视图缓冲区可能具有更高的效率，因为其仅在底层ByteBuffer为Direct时才成为Direct。
 * 视图缓冲区的字节序在创建视图时被固定为其字节缓冲区的字节序。
 * @author liyibo
 * @date 2026-03-02 12:21
 */
public abstract class ByteBuffer extends Buffer implements Comparable<ByteBuffer> {
    private static final long ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    final byte[] hb;
    final int offset;
    boolean isReadOnly;

    ByteBuffer(int mark, int pos, int lim, int cap, byte[] hb, int offset, MemorySegmentProxy segment) {
        super(mark, pos, lim, cap, segment);
        this.hb = hb;
        this.offset = offset;
    }

    ByteBuffer(int mark, int pos, int lim, int cap, MemorySegmentProxy segment) {
        this(mark, pos, lim, cap, null, 0, segment);
    }

    ByteBuffer(byte[] hb, long addr, int cap, MemorySegmentProxy segment) {
        super(addr, cap, segment);
        this.hb = hb;
        this.offset = 0;
    }

    @Override
    Object base() {
        return hb;
    }

    public static ByteBuffer allocateDirect(int capacity) {
        return new DirectByteBuffer(capacity);
    }

    public static ByteBuffer allocate(int capacity) {
        if(capacity < 0)
            throw createCapacityException(capacity);
        return new HeapByteBuffer(capacity, capacity, null);
    }

    public static ByteBuffer wrap(byte[] array, int offset, int length) {
        try {
            return new HeapByteBuffer(array, offset, length, null);
        } catch (IllegalArgumentException x) {
            throw new IndexOutOfBoundsException();
        }
    }

    public static ByteBuffer wrap(byte[] array) {
        return wrap(array, 0, array.length);
    }

    @Override
    public abstract ByteBuffer slice();

    @Override
    public abstract ByteBuffer slice(int index, int length);

    @Override
    public abstract ByteBuffer duplicate();

    public abstract ByteBuffer asReadOnlyBuffer();

    // -- Singleton get/put methods --

    public abstract byte get();

    public abstract ByteBuffer put(byte b);

    public abstract byte get(int index);

    public abstract ByteBuffer put(int index, byte b);

    // -- Bulk get operations --

    /**
     * 相对批量获取方法。
     * 该方法将字节从Buffer传输至指定数组，若Buffer剩余字节不足（length > remaining），则不传输任何字节并抛出BufferUnderflowException，
     * 否则将从当前Buffer起始位置，按数组中指定的offset，向数组复制length个字节，随后Buffer的position按length递增。
     * 本方法会预先检查Buffer是否存有足够字节，且潜在效率显著更高。
     */
    public ByteBuffer get(byte[] dst, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, dst.length);
        int pos = position();
        if(length > limit() - pos)  // length过大
            throw new BufferUnderflowException();
        getArray(pos, dst, offset, length); // 复制数据
        position(pos + length); // 修改position
        return this;
    }

    public ByteBuffer get(byte[] dst) {
        return get(dst, 0, dst.length);
    }

    public ByteBuffer get(int index, byte[] dst, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, dst.length);
        getArray(index, dst, offset, length);
        return this;
    }

    public ByteBuffer get(int index, byte[] dst) {
        return get(index, dst, 0, dst.length);
    }

    private ByteBuffer getArray(int index, byte[] dst, int offset, int length) {
        if((long)length << 0 > Bits.JNI_COPY_TO_ARRAY_THRESHOLD) {
            long bufAddr = address + ((long)index << 0);
            long dstOffset = ARRAY_BASE_OFFSET + ((long)offset << 0);
            long len = (long)length << 0;
            try {
                SCOPED_MEMORY_ACCESS.copyMemory(scope(), null, base(), bufAddr, dst, dstOffset, len);
            } finally {
                Reference.reachabilityFence(this);
            }
        }else { // 目前只学习了这个分支，就是循环调用get然后复制
            int end = offset + length;
            for(int i = offset, j = index; i < end; i++, j++)
                dst[i] = get(j);
        }
        return this;
    }

    // -- Bulk put operations --

    /**
     * 将给定的ByteBuffer内容写入当前ByteBuffer
     */
    public ByteBuffer put(ByteBuffer src) {
        if(src == this)
            throw createSameBufferException();
        if(isReadOnly())
            throw new ReadOnlyBufferException();

        int srcPos = src.position();
        int srcLim = src.limit();
        int srcRem = (srcPos <= srcLim ? srcLim - srcPos : 0);
        int pos = position();
        int lim = limit();
        int rem = (pos <= lim ? lim - pos : 0);

        if(srcRem > rem)    // src数据过大
            throw new BufferOverflowException();
        putBuffer(pos, src, srcPos, srcRem);
        position(pos + srcRem);
        src.position(srcPos + srcRem);
        return this;
    }

    public ByteBuffer put(int index, ByteBuffer src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.limit());
        if(isReadOnly())
            throw new ReadOnlyBufferException();
        putBuffer(index, src, offset, length);
        return this;
    }

    void putBuffer(int pos, ByteBuffer src, int srcPos, int n) {
        Object srcBase = src.base();
        assert srcBase != null || src.isDirect();

        Object base = base();
        assert base != null || isDirect();

        long srcAddr = src.address + ((long)srcPos << 0);
        long addr = address + ((long)pos << 0);
        long len = (long)n << 0;

        try {
            SCOPED_MEMORY_ACCESS.copyMemory(src.scope(), scope(), srcBase, srcAddr, base, addr, len);
        } finally {
            Reference.reachabilityFence(src);
            Reference.reachabilityFence(this);
        }
    }

    public ByteBuffer put(byte[] src, int offset, int length) {
        if(isReadOnly())
            throw new ReadOnlyBufferException();
        Objects.checkFromIndexSize(offset, length, src.length);
        int pos = position();
        if (length > limit() - pos) // length过大，buffer装不下
            throw new BufferOverflowException();
        putArray(pos, src, offset, length);
        position(pos + length);
        return this;
    }

    public final ByteBuffer put(byte[] src) {
        return put(src, 0, src.length);
    }

    public ByteBuffer put(int index, byte[] src, int offset, int length) {
        if(isReadOnly())
            throw new ReadOnlyBufferException();
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.length);
        putArray(index, src, offset, length);
        return this;
    }

    public ByteBuffer put(int index, byte[] src) {
        return put(index, src, 0, src.length);
    }

    private ByteBuffer putArray(int index, byte[] src, int offset, int length) {
        if(((long)length << 0) > Bits.JNI_COPY_FROM_ARRAY_THRESHOLD) {
            long bufAddr = address + ((long)index << 0);
            long srcOffset = ARRAY_BASE_OFFSET + ((long)offset << 0);
            long len = (long)length << 0;
            try {
                SCOPED_MEMORY_ACCESS.copyMemory(null, scope(), src, srcOffset, base(), bufAddr, len);
            } finally {
                Reference.reachabilityFence(this);
            }
        }else {
            int end = offset + length;
            for(int i = offset, j = index; i < end; i++, j++)
                put(j, src[i]);
        }
        return this;
    }

    // -- Other stuff --

    /**
     * 指示此Buffer是否由可访问的字节数组支持，若为true，则可以安全地调用array和arrayOffset方法。
     */
    public final boolean hasArray() {
        return (hb != null) && !isReadOnly;
    }

    public final byte[] array() {
        if(hb == null)
            throw new UnsupportedOperationException();
        if(isReadOnly)
            throw new ReadOnlyBufferException();
        return hb;
    }

    public final int arrayOffset() {
        if(hb == null)
            throw new UnsupportedOperationException();
        if(isReadOnly)
            throw new ReadOnlyBufferException();
        return offset;
    }

    // -- Covariant return type overrides

    @Override
    public ByteBuffer position(int newPosition) {
        super.position(newPosition);
        return this;
    }

    @Override
    public ByteBuffer limit(int newLimit) {
        super.limit(newLimit);
        return this;
    }

    @Override
    public ByteBuffer mark() {
        super.mark();
        return this;
    }

    @Override
    public ByteBuffer reset() {
        super.reset();
        return this;
    }

    @Override
    public ByteBuffer clear() {
        super.clear();
        return this;
    }

    @Override
    public ByteBuffer flip() {
        super.flip();
        return this;
    }

    @Override
    public ByteBuffer rewind() {
        super.rewind();
        return this;
    }

    /**
     * 压缩Buffer（可选操作）
     * Buffer的position和limit（若存在）之间的字节将被复制到Buffer开头，若mark已定义，则将其丢弃。
     * Buffer的position被设置为已复制的字节数（并非是0），以便本方法调用后可立即调用其他相对写入方法。
     * 当Buffer写入操作未完成时，请在写入数据后调用此方法，例如以下循环通过Buffer将字节从一个Channel复制到另一个Channel：
     * buf.clear()
     * while(in.read(buf) >= 0 || buf.position != 0) {
     *     buf。flip();
     *     out.write(buf);
     *     buf.compact();
     * }
     */
    public abstract ByteBuffer compact();

    public abstract boolean isDirect();

    public String toString() {
        return getClass().getName()
                + "[pos=" + position()
                + " lim=" + limit()
                + " cap=" + capacity()
                + "]";
    }

    public int hashCode() {
        int h = 1;
        int p = position();
        for(int i = limit() - 1; i >= p; i--)
            h = 31 * h + (int)get(i);
        return h;
    }

    public boolean equals(Object ob) {
        if(this == ob)
            return true;
        if(!(ob instanceof ByteBuffer))
            return false;
        ByteBuffer that = (ByteBuffer)ob;
        int thisPos = this.position();
        int thisRem = this.limit() - thisPos;
        int thatPos = that.position();
        int thatRem = that.limit() - thatPos;
        if(thisRem < 0 || thisRem != thatRem)
            return false;
        return BufferMismatch.mismatch(this, thisPos, that, thatPos, thisRem) < 0;
    }

    public int compareTo(ByteBuffer that) {
        int thisPos = this.position();
        int thisRem = this.limit() - thisPos;
        int thatPos = that.position();
        int thatRem = that.limit() - thatPos;
        int length = Math.min(thisRem, thatRem);
        if(length < 0)
            return -1;
        int i = BufferMismatch.mismatch(this, thisPos, that, thatPos, length);
        if(i >= 0)
            return compare(this.get(thisPos + i), that.get(thatPos + i));
        return thisRem - thatRem;
    }

    private static int compare(byte x, byte y) {
        return Byte.compare(x, y);
    }

    public int mismatch(ByteBuffer that) {
        int thisPos = this.position();
        int thisRem = this.limit() - thisPos;
        int thatPos = that.position();
        int thatRem = that.limit() - thatPos;
        int length = Math.min(thisRem, thatRem);
        if(length < 0)
            return -1;
        int r = BufferMismatch.mismatch(this, thisPos, that, thatPos, length);
        return (r == -1 && thisRem != thatRem) ? length : r;
    }

    // -- Other char stuff --

    // -- Other byte stuff: Access to binary data --

    boolean bigEndian = true;
    boolean nativeByteOrder = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);

    public final ByteOrder order() {
        return bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    public final ByteBuffer order(ByteOrder bo) {
        bigEndian = (bo == ByteOrder.BIG_ENDIAN);
        nativeByteOrder = (bigEndian == (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN));
        return this;
    }

    public final int alignmentOffset(int index, int unitSize) {
        if(index < 0)
            throw new IllegalArgumentException("Index less than zero: " + index);
        if(unitSize < 1 || (unitSize & (unitSize - 1)) != 0)
            throw new IllegalArgumentException("Unit size not a power of two: " + unitSize);
        if(unitSize > 8 && !isDirect())
            throw new UnsupportedOperationException("Unit size unsupported for non-direct buffers: " + unitSize);
        return (int) ((address + index) & (unitSize - 1));
    }

    public final ByteBuffer alignedSlice(int unitSize) {
        int pos = position();
        int lim = limit();

        int pos_mod = alignmentOffset(pos, unitSize);
        int lim_mod = alignmentOffset(lim, unitSize);

        // Round up the position to align with unit size
        int aligned_pos = (pos_mod > 0) ? pos + (unitSize - pos_mod) : pos;

        // Round down the limit to align with unit size
        int aligned_lim = lim - lim_mod;

        if(aligned_pos > lim || aligned_lim < pos)
            aligned_pos = aligned_lim = pos;
        return slice(aligned_pos, aligned_lim - aligned_pos);
    }

    /**
     * 相对get方法，用于读取char。
     * 读取Buffer的position的2个字节，根据当前字节序组合成字符值，然后position递增2个字节。
     */
    public abstract char getChar();

    public abstract ByteBuffer putChar(char value);

    public abstract char getChar(int index);

    public abstract ByteBuffer putChar(int index, char value);

    public abstract CharBuffer asCharBuffer();

    public abstract short getShort();

    public abstract ByteBuffer putShort(short value);

    public abstract short getShort(int index);

    public abstract ByteBuffer putShort(int index, short value);

    public abstract ShortBuffer asShortBuffer();

    public abstract int getInt();

    public abstract ByteBuffer putInt(int value);

    public abstract int getInt(int index);

    public abstract ByteBuffer putInt(int index, int value);

    public abstract IntBuffer asIntBuffer();

    public abstract long getLong();

    public abstract ByteBuffer putLong(long value);

    public abstract long getLong(int index);

    public abstract ByteBuffer putLong(int index, long value);

    public abstract LongBuffer asLongBuffer();

    public abstract float getFloat();

    public abstract ByteBuffer putFloat(float value);

    public abstract float getFloat(int index);

    public abstract ByteBuffer putFloat(int index, float value);

    public abstract FloatBuffer asFloatBuffer();

    public abstract double getDouble();

    public abstract ByteBuffer putDouble(double value);

    public abstract double getDouble(int index);

    public abstract ByteBuffer putDouble(int index, double value);

    public abstract DoubleBuffer asDoubleBuffer();
}

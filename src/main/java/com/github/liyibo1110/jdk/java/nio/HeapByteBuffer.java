package com.github.liyibo1110.jdk.java.nio;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.Objects;

/**
 * 基于heap的ByteBuffer实现，底层存储用的就是普通的byte[]（即hb字段外加offset字段来定位），比较原始
 * @author liyibo
 * @date 2026-03-02 13:53
 */
class HeapByteBuffer extends ByteBuffer {
    private static final long ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    private static final long ARRAY_INDEX_SCALE = UNSAFE.arrayIndexScale(byte[].class);

    HeapByteBuffer(int cap, int lim, MemorySegmentProxy segment) {
        super(-1, 0, lim, cap, new byte[cap], 0, segment);
        this.address = ARRAY_BASE_OFFSET;
    }

    HeapByteBuffer(byte[] buf, int off, int len, MemorySegmentProxy segment) {
        super(-1, off, off + len, buf.length, buf, 0, segment);
        this.address = ARRAY_BASE_OFFSET;
    }

    protected HeapByteBuffer(byte[] buf, int mark, int pos, int lim, int cap, int off, MemorySegmentProxy segment) {
        super(mark, pos, lim, cap, buf, off, segment);
        this.address = ARRAY_BASE_OFFSET + off * ARRAY_INDEX_SCALE;
    }

    public ByteBuffer slice() {
        int pos = this.position();
        int lim = this.limit();
        int rem = pos <= lim ? lim - pos : 0;
        return new HeapByteBuffer(hb, -1, 0, rem, rem, pos + offset, segment);
    }

    @Override
    public ByteBuffer slice(int index, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        return new HeapByteBuffer(hb, -1, 0, length, length, index + offset, segment);
    }

    public ByteBuffer duplicate() {
        return new HeapByteBuffer(hb, this.markValue(), this.position(), this.limit(), this.capacity(), offset, segment);
    }

    public ByteBuffer asReadOnlyBuffer() {
        return new HeapByteBufferR(hb, this.markValue(), this.position(), this.limit(), this.capacity(), offset, segment);
    }

    protected int ix(int i) {
        return i + offset;
    }

    private long byteOffset(long i) {
        return address + i;
    }

    public byte get() {
        return hb[ix(nextGetIndex())];
    }

    public byte get(int i) {
        return hb[ix(checkIndex(i))];
    }

    public ByteBuffer get(byte[] dst, int offset, int length) {
        checkScope();
        Objects.checkFromIndexSize(offset, length, dst.length);
        int pos = position();
        if(length > limit() - pos)
            throw new BufferUnderflowException();
        System.arraycopy(hb, ix(pos), dst, offset, length);
        position(pos + length);
        return this;
    }

    public ByteBuffer get(int index, byte[] dst, int offset, int length) {
        checkScope();
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, dst.length);
        System.arraycopy(hb, ix(index), dst, offset, length);
        return this;
    }

    public boolean isDirect() {
        return false;
    }

    public boolean isReadOnly() {
        return false;
    }

    public ByteBuffer put(byte x) {
        hb[ix(nextPutIndex())] = x;
        return this;
    }

    public ByteBuffer put(int i, byte x) {
        hb[ix(checkIndex(i))] = x;
        return this;
    }

    public ByteBuffer put(byte[] src, int offset, int length) {
        checkScope();
        Objects.checkFromIndexSize(offset, length, src.length);
        int pos = position();
        if(length > limit() - pos)
            throw new BufferOverflowException();
        System.arraycopy(src, offset, hb, ix(pos), length);
        position(pos + length);
        return this;
    }

    public ByteBuffer put(ByteBuffer src) {
        checkScope();
        super.put(src);
        return this;
    }

    public ByteBuffer put(int index, ByteBuffer src, int offset, int length) {
        checkScope();
        super.put(index, src, offset, length);
        return this;
    }

    public ByteBuffer put(int index, byte[] src, int offset, int length) {
        checkScope();
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.length);
        System.arraycopy(src, offset, hb, ix(index), length);
        return this;
    }

    public ByteBuffer compact() {
        int pos = position();
        int lim = limit();
        assert (pos <= lim);
        int rem = pos <= lim ? lim - pos : 0;
        System.arraycopy(hb, ix(pos), hb, ix(0), rem);
        position(rem);
        limit(capacity());
        discardMark();
        return this;
    }

    byte _get(int i) {
        return hb[i];
    }

    void _put(int i, byte b) {
        hb[i] = b;
    }

    // char
    public char getChar() {
        return SCOPED_MEMORY_ACCESS.getCharUnaligned(scope(), hb, byteOffset(nextGetIndex(2)), bigEndian);
    }

    public char getChar(int i) {
        return SCOPED_MEMORY_ACCESS.getCharUnaligned(scope(), hb, byteOffset(checkIndex(i, 2)), bigEndian);
    }

    public ByteBuffer putChar(char x) {
        SCOPED_MEMORY_ACCESS.putCharUnaligned(scope(), hb, byteOffset(nextPutIndex(2)), x, bigEndian);
        return this;
    }

    public ByteBuffer putChar(int i, char x) {
        SCOPED_MEMORY_ACCESS.putCharUnaligned(scope(), hb, byteOffset(checkIndex(i, 2)), x, bigEndian);
        return this;
    }

    public CharBuffer asCharBuffer() {
        int pos = position();
        int size = (limit() - pos) >> 1;
        long addr = address + pos;
        return (bigEndian
                ? (CharBuffer)(new ByteBufferAsCharBufferB(this, -1, 0, size, size, addr, segment))
                : (CharBuffer)(new ByteBufferAsCharBufferL(this, -1, 0, size, size, addr, segment)));
    }

    // short

    public short getShort() {
        return SCOPED_MEMORY_ACCESS.getShortUnaligned(scope(), hb, byteOffset(nextGetIndex(2)), bigEndian);
    }

    public short getShort(int i) {
        return SCOPED_MEMORY_ACCESS.getShortUnaligned(scope(), hb, byteOffset(checkIndex(i, 2)), bigEndian);
    }

    public ByteBuffer putShort(short x) {
        SCOPED_MEMORY_ACCESS.putShortUnaligned(scope(), hb, byteOffset(nextPutIndex(2)), x, bigEndian);
        return this;
    }

    public ByteBuffer putShort(int i, short x) {
        SCOPED_MEMORY_ACCESS.putShortUnaligned(scope(), hb, byteOffset(checkIndex(i, 2)), x, bigEndian);
        return this;
    }

    public ShortBuffer asShortBuffer() {
        int pos = position();
        int size = (limit() - pos) >> 1;
        long addr = address + pos;
        return (bigEndian
                ? (ShortBuffer)(new ByteBufferAsShortBufferB(this, -1, 0, size, size, addr, segment))
                : (ShortBuffer)(new ByteBufferAsShortBufferL(this, -1, 0, size, size, addr, segment)));
    }

    // int

    public int getInt() {
        return SCOPED_MEMORY_ACCESS.getIntUnaligned(scope(), hb, byteOffset(nextGetIndex(4)), bigEndian);
    }

    public int getInt(int i) {
        return SCOPED_MEMORY_ACCESS.getIntUnaligned(scope(), hb, byteOffset(checkIndex(i, 4)), bigEndian);
    }

    public ByteBuffer putInt(int x) {
        SCOPED_MEMORY_ACCESS.putIntUnaligned(scope(), hb, byteOffset(nextPutIndex(4)), x, bigEndian);
        return this;
    }

    public ByteBuffer putInt(int i, int x) {
        SCOPED_MEMORY_ACCESS.putIntUnaligned(scope(), hb, byteOffset(checkIndex(i, 4)), x, bigEndian);
        return this;
    }

    public IntBuffer asIntBuffer() {
        int pos = position();
        int size = (limit() - pos) >> 2;
        long addr = address + pos;
        return (bigEndian
                ? (IntBuffer)(new ByteBufferAsIntBufferB(this, -1, 0, size, size, addr, segment))
                : (IntBuffer)(new ByteBufferAsIntBufferL(this, -1, 0, size, size, addr, segment)));
    }

    // long

    public long getLong() {
        return SCOPED_MEMORY_ACCESS.getLongUnaligned(scope(), hb, byteOffset(nextGetIndex(8)), bigEndian);
    }

    public long getLong(int i) {
        return SCOPED_MEMORY_ACCESS.getLongUnaligned(scope(), hb, byteOffset(checkIndex(i, 8)), bigEndian);
    }

    public ByteBuffer putLong(long x) {
        SCOPED_MEMORY_ACCESS.putLongUnaligned(scope(), hb, byteOffset(nextPutIndex(8)), x, bigEndian);
        return this;
    }

    public ByteBuffer putLong(int i, long x) {
        SCOPED_MEMORY_ACCESS.putLongUnaligned(scope(), hb, byteOffset(checkIndex(i, 8)), x, bigEndian);
        return this;
    }

    public LongBuffer asLongBuffer() {
        int pos = position();
        int size = (limit() - pos) >> 3;
        long addr = address + pos;
        return (bigEndian
                ? (LongBuffer)(new ByteBufferAsLongBufferB(this, -1, 0, size, size, addr, segment))
                : (LongBuffer)(new ByteBufferAsLongBufferL(this, -1, 0, size, size, addr, segment)));
    }

    // float

    public float getFloat() {
        int x = SCOPED_MEMORY_ACCESS.getIntUnaligned(scope(), hb, byteOffset(nextGetIndex(4)), bigEndian);
        return Float.intBitsToFloat(x);
    }

    public float getFloat(int i) {
        int x = SCOPED_MEMORY_ACCESS.getIntUnaligned(scope(), hb, byteOffset(checkIndex(i, 4)), bigEndian);
        return Float.intBitsToFloat(x);
    }

    public ByteBuffer putFloat(float x) {
        int y = Float.floatToRawIntBits(x);
        SCOPED_MEMORY_ACCESS.putIntUnaligned(scope(), hb, byteOffset(nextPutIndex(4)), y, bigEndian);
        return this;
    }

    public ByteBuffer putFloat(int i, float x) {
        int y = Float.floatToRawIntBits(x);
        SCOPED_MEMORY_ACCESS.putIntUnaligned(scope(), hb, byteOffset(checkIndex(i, 4)), y, bigEndian);
        return this;
    }

    public FloatBuffer asFloatBuffer() {
        int pos = position();
        int size = (limit() - pos) >> 2;
        long addr = address + pos;
        return (bigEndian
                ? (FloatBuffer)(new ByteBufferAsFloatBufferB(this, -1, 0, size, size, addr, segment))
                : (FloatBuffer)(new ByteBufferAsFloatBufferL(this, -1, 0, size, size, addr, segment)));
    }

    // double

    public double getDouble() {
        long x = SCOPED_MEMORY_ACCESS.getLongUnaligned(scope(), hb, byteOffset(nextGetIndex(8)), bigEndian);
        return Double.longBitsToDouble(x);
    }

    public double getDouble(int i) {
        long x = SCOPED_MEMORY_ACCESS.getLongUnaligned(scope(), hb, byteOffset(checkIndex(i, 8)), bigEndian);
        return Double.longBitsToDouble(x);
    }

    public ByteBuffer putDouble(double x) {
        long y = Double.doubleToRawLongBits(x);
        SCOPED_MEMORY_ACCESS.putLongUnaligned(scope(), hb, byteOffset(nextPutIndex(8)), y, bigEndian);
        return this;
    }

    public ByteBuffer putDouble(int i, double x) {
        long y = Double.doubleToRawLongBits(x);
        SCOPED_MEMORY_ACCESS.putLongUnaligned(scope(), hb, byteOffset(checkIndex(i, 8)), y, bigEndian);
        return this;
    }

    public DoubleBuffer asDoubleBuffer() {
        int pos = position();
        int size = (limit() - pos) >> 3;
        long addr = address + pos;
        return (bigEndian
                ? (DoubleBuffer)(new ByteBufferAsDoubleBufferB(this, -1, 0, size, size, addr, segment))
                : (DoubleBuffer)(new ByteBufferAsDoubleBufferL(this, -1, 0, size, size, addr, segment)));
    }
}

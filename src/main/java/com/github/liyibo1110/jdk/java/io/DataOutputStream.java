package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.io.UTFDataFormatException;

/**
 * DataOutputStream允许应用程序以可移植的方式将原始Java数据类型写入输出流。随后，应用程序可使用DataInputStream将数据读回。
 * DataOutputStream不适用于多个并发线程的安全使用。若需由多个线程使用DataOutputStream，则应通过适当的同步机制控制对数据输出流的访问。
 * @author liyibo
 * @date 2026-02-27 19:28
 */
public class DataOutputStream extends FilterOutputStream implements DataOutput {

    /** 迄今为止写入DataOutputStream的字节数。若该计数器溢出，则会循环到整型最大值。 */
    protected int written;

    private byte[] bytearr = null;
    private final byte[] writeBuffer = new byte[8];

    public DataOutputStream(OutputStream out) {
        super(out);
    }

    private void incCount(int value) {
        int temp = written + value;
        if(temp < 0)    // 负数说明溢出了
            temp = Integer.MAX_VALUE;
        written = temp;
    }

    public synchronized void write(int b) throws IOException {
        out.write(b);
        incCount(1);
    }

    public synchronized void write(byte b[], int off, int len) throws IOException {
        out.write(b, off, len);
        incCount(len);
    }

    public void flush() throws IOException {
        out.flush();
    }

    public final void writeBoolean(boolean v) throws IOException {
        out.write(v ? 1 : 0);
        incCount(1);
    }

    public final void writeByte(int v) throws IOException {
        out.write(v);
        incCount(1);
    }

    public final void writeShort(int v) throws IOException {
        writeBuffer[0] = (byte)(v >>> 8);
        writeBuffer[1] = (byte)(v >>> 0);
        out.write(writeBuffer, 0, 2);
        incCount(2);
    }

    public final void writeChar(int v) throws IOException {
        writeBuffer[0] = (byte)(v >>> 8);
        writeBuffer[1] = (byte)(v >>> 0);
        out.write(writeBuffer, 0, 2);
        incCount(2);
    }

    public final void writeInt(int v) throws IOException {
        writeBuffer[0] = (byte)(v >>> 24);
        writeBuffer[1] = (byte)(v >>> 16);
        writeBuffer[2] = (byte)(v >>>  8);
        writeBuffer[3] = (byte)(v >>>  0);
        out.write(writeBuffer, 0, 4);
        incCount(4);
    }

    public final void writeLong(long v) throws IOException {
        writeBuffer[0] = (byte)(v >>> 56);
        writeBuffer[1] = (byte)(v >>> 48);
        writeBuffer[2] = (byte)(v >>> 40);
        writeBuffer[3] = (byte)(v >>> 32);
        writeBuffer[4] = (byte)(v >>> 24);
        writeBuffer[5] = (byte)(v >>> 16);
        writeBuffer[6] = (byte)(v >>>  8);
        writeBuffer[7] = (byte)(v >>>  0);
        out.write(writeBuffer, 0, 8);
        incCount(8);
    }

    public final void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    public final void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    public final void writeBytes(String s) throws IOException {
        int len = s.length();
        for(int i = 0 ; i < len ; i++)
            out.write((byte)s.charAt(i));
        incCount(len);
    }

    public final void writeChars(String s) throws IOException {
        int len = s.length();
        for(int i = 0 ; i < len ; i++) {
            int v = s.charAt(i);
            writeBuffer[0] = (byte)(v >>> 8);
            writeBuffer[1] = (byte)(v >>> 0);
            out.write(writeBuffer, 0, 2);
        }
        incCount(len * 2);
    }

    public final void writeUTF(String str) throws IOException {
        writeUTF(str, this);
    }

    static int writeUTF(String str, DataOutput out) throws IOException {
        final int strlen = str.length();
        int utflen = strlen; // optimized for ASCII

        for(int i = 0; i < strlen; i++) {
            int c = str.charAt(i);
            if(c >= 0x80 || c == 0)
                utflen += (c >= 0x800) ? 2 : 1;
        }

        if (utflen > 65535 || /* overflow */ utflen < strlen)
            throw new UTFDataFormatException(tooLongMsg(str, utflen));

        final byte[] bytearr;
        if(out instanceof DataOutputStream dos) {
            if(dos.bytearr == null || (dos.bytearr.length < (utflen + 2)))
                dos.bytearr = new byte[(utflen*2) + 2];
            bytearr = dos.bytearr;
        }else {
            bytearr = new byte[utflen + 2];
        }

        int count = 0;
        bytearr[count++] = (byte) ((utflen >>> 8) & 0xFF);
        bytearr[count++] = (byte) ((utflen >>> 0) & 0xFF);

        int i = 0;
        for(i = 0; i < strlen; i++) { // optimized for initial run of ASCII
            int c = str.charAt(i);
            if (c >= 0x80 || c == 0) break;
            bytearr[count++] = (byte) c;
        }

        for(; i < strlen; i++) {
            int c = str.charAt(i);
            if(c < 0x80 && c != 0) {
                bytearr[count++] = (byte) c;
            } else if (c >= 0x800) {
                bytearr[count++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
                bytearr[count++] = (byte) (0x80 | ((c >>  6) & 0x3F));
                bytearr[count++] = (byte) (0x80 | ((c >>  0) & 0x3F));
            } else {
                bytearr[count++] = (byte) (0xC0 | ((c >>  6) & 0x1F));
                bytearr[count++] = (byte) (0x80 | ((c >>  0) & 0x3F));
            }
        }
        out.write(bytearr, 0, utflen + 2);
        return utflen + 2;
    }

    private static String tooLongMsg(String s, int bits32) {
        int slen = s.length();
        String head = s.substring(0, 8);
        String tail = s.substring(slen - 8, slen);
        // handle int overflow with max 3x expansion
        long actualLength = (long)slen + Integer.toUnsignedLong(bits32 - slen);
        return "encoded string (" + head + "..." + tail + ") too long: " + actualLength + " bytes";
    }

    public final int size() {
        return written;
    }
}

package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;

/**
 * DataOutput接口用于将任何Java基本数据类型转换为字节序列，并将这些字节写入二进制流。
 * 该接口还提供将字符串转换为修改后的UTF-8格式，并写入生成的字节序列的功能。
 *
 * JDK的早期I/O接口
 * @author liyibo
 * @date 2026-02-27 19:11
 */
public interface DataOutput {

    /**
     * 参数b的低8位写入输出流，剩余高24位将被忽略。
     */
    void write(int b) throws IOException;

    /**
     * 将数组b中的所有字节写入输出流，若b为空，则抛出NullPointerException。
     * 若b.length为零，则不写入任何字节，否则先写入字节b[0]，接着写入b[1]，最后写入的字节为b[b.length-1]。
     */
    void write(byte[] b) throws IOException;

    void write(byte b[], int off, int len) throws IOException;

    void writeBoolean(boolean v) throws IOException;

    void writeByte(int v) throws IOException;

    void writeShort(int v) throws IOException;

    void writeChar(int v) throws IOException;

    void writeInt(int v) throws IOException;

    void writeLong(long v) throws IOException;

    void writeFloat(float v) throws IOException;

    void writeDouble(double v) throws IOException;

    void writeBytes(String s) throws IOException;

    void writeChars(String s) throws IOException;

    void writeUTF(String s) throws IOException;
}

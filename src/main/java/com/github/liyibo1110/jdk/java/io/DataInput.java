package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;

/**
 * 用于从二进制流中读取字节，并将其重建为Java基本数据类型的数据，该接口还支持将修改后的UTF-8格式数据重建为字符串。
 * 该接口中所有读取操作均遵循以下原则：
 * 1、若在读取目标字节数之前遇到文件结束，则抛出EOFException。
 * 2、若因文件结束以外的原因无法读取字节，则抛出非EOFException类型的IOException。
 * 特别需要注意的是，当输入流已关闭时也可能抛出IOException异常。
 *
 * Modified UTF-8
 * 内容略
 *
 * JDK的早期I/O接口
 * @author liyibo
 * @date 2026-02-26 21:44
 */
public interface DataInput {

    /**
     * 从输入流中读取若干字节，并将它们存储到缓冲区数组b中，读取的字节数等于b的长度。
     * 该方法将阻塞直至满足以下任一条件：
     * 1、b.length的字节可用，此时执行正常返回。
     * 2、检测到文件结束，此时抛出EOFException。
     * 3、发生I/O错误，此时抛出除EOFException外的IOException。
     */
    void readFully(byte b[]) throws IOException;

    void readFully(byte b[], int off, int len) throws IOException;

    /**
     * 尝试从输入流中跳过n个字节的数据，并丢弃被跳过的字节，但实际跳过的字节数可能少于n，甚至为零。
     */
    int skipBytes(int n) throws IOException;

    /**
     * 读取一个输入字节，若字节非零则返回true，若为零则返回false。
     * 该方法适用于读取接口DataOutput的writeBoolean方法写入的字节。
     */
    boolean readBoolean() throws IOException;

    /**
     * 读取并返回一个输入字节，被视为-128到127范围内的有符号值。
     */
    byte readByte() throws IOException;

    int readUnsignedByte() throws IOException;

    short readShort() throws IOException;

    int readUnsignedShort() throws IOException;

    char readChar() throws IOException;

    int readInt() throws IOException;

    long readLong() throws IOException;

    float readFloat() throws IOException;

    double readDouble() throws IOException;

    String readLine() throws IOException;

    String readUTF() throws IOException;
}

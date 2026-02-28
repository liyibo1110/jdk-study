package com.github.liyibo1110.jdk.java.lang;

import java.io.IOException;
import java.nio.CharBuffer;

/**
 * Readable是字符的来源，通过CharBuffer，Readable中的字符可供调用read方法的调用方使用。
 * @author liyibo
 * @date 2026-02-27 20:41
 */
public interface Readable {

    /**
     * 尝试将字符读入指定的CharBuffer，该缓冲区作为字符存储库使用，仅保留原始数据，
     * 唯一的变化是写入操作的结果，不会对缓冲区执行flipping或者rewinding操作
     */
    int read(CharBuffer cb) throws IOException;
}

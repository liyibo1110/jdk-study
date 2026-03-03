package com.github.liyibo1110.jdk.java.nio.channels;

/**
 * 一个能够读写字节的Channel。该接口仅将ReadableByteChannel和WritableByteChannel统一起来，并未定义任何新操作。
 * @author liyibo
 * @date 2026-03-02 19:01
 */
public interface ByteChannel extends ReadableByteChannel, WritableByteChannel {

}

package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;

/**
 * 可关闭对象，是可关闭的数据源或数据目标。
 * 调用close方法可释放该对象持有的资源（例如打开的文件）
 *
 * 个人注：这个接口原来就是个普通的方法接口，在Java7时扩展了AutoCloseable接口（同时增加了对应的新功能）
 * @author liyibo
 * @date 2026-02-26 00:44
 */
public interface Closeable extends AutoCloseable {

    /**
     * 关闭此流并释放与其关联的所有系统资源，若流已处于关闭状态，调用将不产生任何效果。
     * 如AutoCloseable.close()所述，关闭操作可能失败的情况需要特别注意。
     * 强烈建议在抛出IOException之前，先释放底层资源并在内部将Closeable标记为已关闭。
     */
    void close() throws IOException;
}

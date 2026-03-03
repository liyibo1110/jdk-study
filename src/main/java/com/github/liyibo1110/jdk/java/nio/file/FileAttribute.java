package com.github.liyibo1110.jdk.java.nio.file;

/**
 * 一个封装文件属性值的对象，该属性可以在创建新文件或目录时通过调用createFile或createDirectory方法进行原子设置
 * @author liyibo
 * @date 2026-03-02 22:15
 */
public interface FileAttribute<T> {

    /**
     * 返回attribute name。
     */
    String name();

    /**
     * 返回attribute对应的value。
     */
    T value();
}

package com.github.liyibo1110.jdk.java.io;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * 本质就是InputStreamReader + 默认系统字符集的封装实现。
 * 老版本使用了默认的字符集，所以实际使用会有各种不灵活，所以并不推荐使用这个Reader（在Java11新增了接收charset的新构造方法，算是一种弥补）。
 * @author liyibo
 * @date 2026-03-01 17:06
 */
public class FileReader extends InputStreamReader {

    public FileReader(String fileName) throws FileNotFoundException {
        super(new FileInputStream(fileName));
    }

    public FileReader(File file) throws FileNotFoundException {
        super(new FileInputStream(file));
    }

    public FileReader(FileDescriptor fd) {
        super(new FileInputStream(fd));
    }

    public FileReader(String fileName, Charset charset) throws IOException {
        super(new FileInputStream(fileName), charset);
    }

    public FileReader(File file, Charset charset) throws IOException {
        super(new FileInputStream(file), charset);
    }
}

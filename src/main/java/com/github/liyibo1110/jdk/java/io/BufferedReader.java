package com.github.liyibo1110.jdk.java.io;

/**
 * 从字符输入流中读取文本，通过缓冲字符来高效读取字符、数组和行。
 * 缓冲区大小可以指定，也可以使用默认大小，默认值对大多数用途而言已足够大。
 * 通常对Reader的每次读取请求都会触发底层字符流或字节流的对应读取操作，因此建议将BufferedReader封装在可能存在高开销读取操作的Reader
 * （如FileReader和InputStreamReader）外层，例如：
 * BufferedReader in = new BufferedReader(new FileReader(“foo.in”));
 *
 * 若不启用缓冲，每次调用read()或readLine()都需要从文件读取字节、转换为字符再返回，效率很低。
 * 使用DataInputStream进行文本输入的程序，可通过将每个DataInputStream替换为适配的BufferedReader实现本地化处理。
 * @author liyibo
 * @date 2026-02-28 13:15
 */
public class BufferedReader extends Reader {
}

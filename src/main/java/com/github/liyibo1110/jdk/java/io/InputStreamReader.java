package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

/**
 * 是字节流与字符流之间的桥梁：它读取字节，并使用指定的字符集将其解码为字符。
 * 该字符集可通过名称指定，也可以显式提供，或直接采用平台的默认字符集。
 * 每次调用read方法时，都可能从底层InputStream中读取一个或多个字节，为实现高效的字节转字符转换，系统可能从底层流预读取超过当前读取操作所需的字节量。
 * 为获得最佳效率，建议将InputStreamReader封装在BufferedReader中。例如：
 * BufferedReader in = new BufferedReader(new InputStreamReader(anInputStream));
 *
 * @author liyibo
 * @date 2026-02-27 23:07
 */
public class InputStreamReader extends Reader {

    /**
     * 主要干活的转码组件
     */
    private final StreamDecoder sd;

    public InputStreamReader(InputStream in) {
        super(in);
        sd = StreamDecoder.forInputStreamReader(in, this, Charset.defaultCharset());
    }

    public InputStreamReader(InputStream in, String charsetName) throws UnsupportedEncodingException {
        super(in);
        if(charsetName == null)
            throw new NullPointerException("charsetName");
        sd = StreamDecoder.forInputStreamReader(in, this, charsetName);
    }

    public InputStreamReader(InputStream in, Charset cs) {
        super(in);
        if(cs == null)
            throw new NullPointerException("charset");
        sd = StreamDecoder.forInputStreamReader(in, this, cs);
    }

    public InputStreamReader(InputStream in, CharsetDecoder dec) {
        super(in);
        if(dec == null)
            throw new NullPointerException("charset decoder");
        sd = StreamDecoder.forInputStreamReader(in, this, dec);
    }

    /**
     * 返回此流当前使用的字符编码名称。
     * 若编码具有历史名称，则返回该名称，否则返回编码的规范名称。
     * 若此实例通过InputStreamReader(InputStream, String)构造函数创建，则返回的名称（该名称对编码而言是唯一的）可能与构造函数传入的名称不同。
     * 若流已被关闭，此方法将返回null。
     */
    public String getEncoding() {
        return sd.getEncoding();
    }

    public int read(CharBuffer cb) throws IOException {
        return sd.read(cb);
    }

    /**
     * 读取1个字符，返回的是char，或者-1（表示已到达流尾）
     */
    public int read() throws IOException {
        return sd.read();
    }

    /**
     * 将字符读入数组的一部分。
     * 若长度为零，则不读取任何字符并返回0，否则将尝试读取至少一个字符。
     * 若因流已到达末尾而无可用字符，则返回-1，否则至少读取一个字符并存储至cbuf中。
     */
    public int read(char[] cbuf, int off, int len) throws IOException {
        return sd.read(cbuf, off, len);
    }

    /**
     * 指示该流是否已准备好被读取，当输入缓冲区不为空，或底层字节流中有可读取的字节时，InputStreamReader处于就绪状态。
     */
    public boolean ready() throws IOException {
        return sd.ready();
    }

    public void close() throws IOException {
        sd.close();
    }
}

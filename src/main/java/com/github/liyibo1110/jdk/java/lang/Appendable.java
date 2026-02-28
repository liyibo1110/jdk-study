package com.github.liyibo1110.jdk.java.lang;

import java.io.IOException;

/**
 * 可追加CharSequence和值的对象，任何类若如果其实例需要接受来自java.util.Formatter的格式化输出，都必须实现Appendable接口。
 * 待追加的字符应为《Unicode 字符表示法》中定义的有效Unicode字符。需注意补充字符可能由多个16位字符值组成。
 * Appendable对象未必支持多线程安全访问。线程安全由扩展并实现此接口的类自行负责。
 * 由于现有类可能以不同错误处理方式实现此接口，故无法保证错误会传播至调用方。
 * @author liyibo
 * @date 2026-02-28 13:25
 */
public interface Appendable {

    /**
     * 将指定的CharSequence附加到此Appendable对象。
     * 根据实现字符序列csq的类不同，可能不会将整个序列追加。
     * 例如若csq是java.nio.CharBuffer，则要追加的子序列由缓冲区的位置和限制定义。
     */
    Appendable append(CharSequence csq) throws IOException;

    Appendable append(CharSequence csq, int start, int end) throws IOException;

    Appendable append(char c) throws IOException;
}

package com.github.liyibo1110.jdk.java.time.format;

/**
 * 本地化日期、时间或日期时间格式化器的样式枚举。
 * 从配置中获取日期时间样式时会用到这些样式。用法请参见DateTimeFormatter和DateTimeFormatterBuilder
 *
 * @author liyibo
 * @date 2026-03-30 23:05
 */
public enum FormatStyle {

    /**
     * 完整格式，包含最详细的信息。例如，格式可以是“Tuesday, April 12, 1952 AD”或“Tuesday, April 12, 1952 AD' or '3:30:42pm PST”。
     */
    FULL,

    /**
     * 长文本格式，包含大量细节。例如，格式可能是“January 12, 1952”。
     */
    LONG,

    /**
     * 中等长度的文本样式，包含一些细节。例如，格式可以是“Jan 12, 1952”。
     */
    MEDIUM,

    /**
     * 简短文本格式，通常为数字形式。例如，格式可能是“12.13.52”或“3:30pm”。
     */
    SHORT;
}

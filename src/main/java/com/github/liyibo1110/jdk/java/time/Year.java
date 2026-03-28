package com.github.liyibo1110.jdk.java.time;

import java.io.Serializable;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAdjuster;

/**
 * ISO-8601日历系统中的年份，例如2007。
 * Year是一个表示年份的不可变日期时间对象。可以获取任何可从年份推导出的字段。
 *
 * 请注意，ISO纪年法中的年份仅在现代年份与格里高利-儒略历系统中的年份对齐。
 * 俄罗斯的部分地区直到1920年才改用现代的格里高利/ISO规则。因此，处理历史年份时必须谨慎。
 *
 * 本类不存储或表示月份、日期、时间或时区。例如值“2007”可以存储在Year对象中。
 * 本类表示的年份遵循ISO-8601标准，并采用前推编号系统。公元元年（Year 1）之前是公元零年（Year 0），再之前是公元前一年（Year -1）。
 *
 * ISO-8601日历系统是当今世界大部分地区使用的现代公历系统。它等同于推算格里高利历系统，即对所有历史时期均应用当今的闰年规则。
 * 对于当今编写的大多数应用程序而言，ISO-8601规则完全适用。然而，任何使用历史日期且要求其精确的应用程序，都会发现ISO-8601方法并不适用。
 * 这是一个基于值的类；程序员应将相等的实例视为可互换，且不应将实例用于同步，否则可能会导致不可预测的行为。
 * 例如在未来版本中，同步可能会失败。应使用equals方法进行比较。
 * @author liyibo
 * @date 2026-03-27 22:59
 */
public class Year implements Temporal, TemporalAdjuster, Comparable<Year>, Serializable {

    public static final int MIN_VALUE = -999_999_999;
    public static final int MAX_VALUE = 999_999_999;

    @java.io.Serial
    private static final long serialVersionUID = -23038383694477807L;

    private static final DateTimeFormatter PARSER = new DateTimeFormatterBuilder()
            .parseLenient()
            .appendValue(YEAR, 1, 10, SignStyle.NORMAL)
            .toFormatter();

    private final int year;

    //-----------------------------------------------------------------------
}

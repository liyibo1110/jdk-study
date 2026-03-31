package com.github.liyibo1110.jdk.java.time.format;

import java.util.Calendar;

/**
 * 文本格式化和解析样式的枚举。
 * TextStyle为格式化文本定义了三种尺寸——“完整”、“短”和“窄”。这三种尺寸均提供“标准”和“独立”两种变体。
 *
 * 这三种尺寸的区别在大多数语言中都很明显。例如，在英语中，“全称”月份是“January”，“缩写”月份是“Jan”，而“窄体”月份是“J”。
 * 请注意，窄体尺寸通常并非唯一。例如，“January”、“June”和“July”都使用“J”作为窄体文本。
 *
 * “标准”形式与“独立”形式之间的区别则较难描述，因为在英语中二者并无区别。
 * 但在其他语言中，当文本单独使用时（而非作为完整日期的一部分）所用的词汇会有所不同。
 * 例如在日期选择器中单独使用时所用的月份词汇，与在日期中与日期和年份关联使用时所用的月份词汇是不同的。
 * @author liyibo
 * @date 2026-03-30 22:47
 */
public enum TextStyle {

    /**
     * Full text, typically the full description.
     * For example, day-of-week Monday might output "Monday".
     */
    FULL(Calendar.LONG_FORMAT, 0),

    /**
     * Full text for stand-alone use, typically the full description.
     * For example, day-of-week Monday might output "Monday".
     */
    FULL_STANDALONE(Calendar.LONG_STANDALONE, 0),

    /**
     * Short text, typically an abbreviation.
     * For example, day-of-week Monday might output "Mon".
     */
    SHORT(Calendar.SHORT_FORMAT, 1),

    /**
     * Short text for stand-alone use, typically an abbreviation.
     * For example, day-of-week Monday might output "Mon".
     */
    SHORT_STANDALONE(Calendar.SHORT_STANDALONE, 1),

    /**
     * Narrow text, typically a single letter.
     * For example, day-of-week Monday might output "M".
     */
    NARROW(Calendar.NARROW_FORMAT, 1),

    /**
     * Narrow text for stand-alone use, typically a single letter.
     * For example, day-of-week Monday might output "M".
     */
    NARROW_STANDALONE(Calendar.NARROW_STANDALONE, 1);

    private final int calendarStyle;
    private final int zoneNameStyleIndex;

    public boolean isStandalone() {
        return (ordinal() & 1) == 1;    // 奇数就是独立体
    }

    /**
     * 改成独立体版本
     */
    public TextStyle asStandalone() {
        return TextStyle.values()[ordinal() | 1];
    }

    /**
     * 改成标准体版本
     */
    public TextStyle asNormal() {
        return TextStyle.values()[ordinal() & ~1];
    }

    int toCalendarStyle() {
        return calendarStyle;
    }

    int zoneNameStyleIndex() {
        return zoneNameStyleIndex;
    }
}

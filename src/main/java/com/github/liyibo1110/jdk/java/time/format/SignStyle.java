package com.github.liyibo1110.jdk.java.time.format;

/**
 * 处理正负号的方法枚举。
 * 格式化引擎允许通过此枚举来控制数字的正负号。用法请参见DateTimeFormatterBuilder。
 * @author liyibo
 * @date 2026-03-30 22:57
 */
public enum SignStyle {

    /**
     * 仅当值为负数时才输出符号。
     * 在严格解析中，负号会被接受，而正号会被拒绝。
     * 在宽松解析中，任何符号都会被接受。
     */
    NORMAL,

    /**
     * 该样式会始终输出符号，其中零将输出“+”。
     * 在严格解析中，缺少符号将被拒绝。
     * 在宽松解析中，任何符号都会被接受，而缺少符号的情况将被视为正数。
     */
    ALWAYS,

    /**
     * 设置为从不输出符号，仅输出绝对值。
     * 在严格解析中，任何符号都会被拒绝。
     * 在宽松解析中，除非宽度是固定的，否则任何符号都会被接受
     */
    NEVER,

    /**
     * 采用这种风格来阻止负数，并在打印时抛出异常。
     * 在严格解析中，任何符号都会被拒绝。
     * 在宽松解析中，除非宽度是固定的，否则任何符号都会被接受。
     */
    NOT_NEGATIVE,

    /**
     * 若数值超过填充宽度，则始终输出符号。负值将始终输出“−”符号。
     * 在严格解析中，除非数值超过填充宽度，否则将拒绝该符号。
     * 在宽松解析中，将接受任何符号，若无符号则视为正数。
     */
    EXCEEDS_PAD;

    /**
     * 解析辅助方法
     */
    boolean parse(boolean positive, boolean strict, boolean fixedWidth) {
        switch (ordinal()) {
            case 0: // NORMAL
                // valid if negative or (positive and lenient)
                return !positive || !strict;
            case 1: // ALWAYS
            case 4: // EXCEEDS_PAD
                return true;
            default:
                // valid if lenient and not fixed width
                return !strict && !fixedWidth;
        }
    }
}

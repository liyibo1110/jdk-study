package com.github.liyibo1110.jdk.java.time.temporal;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.DateTimeException;
import java.time.temporal.TemporalField;

/**
 * 日期时间字段的有效值范围。
 * 所有TemporalField实例都有一个有效的值范围。例如ISO月份中的日期范围从1到28到31之间的某个值。该类捕获了该有效范围。
 * 请务必注意该类的限制。这里仅提供了最小值和最大值。在外围范围内可能存在无效值。
 * 例如，某个特殊字段的有效值可能是1、2、4、6、7，因此其范围为“1 - 7”，尽管值3和5实际上是无效的。
 * 该类的实例不与特定字段绑定。
 * @author liyibo
 * @date 2026-03-27 17:18
 */
public class ValueRange implements Serializable {
    @java.io.Serial
    private static final long serialVersionUID = -7317881728594519368L;

    private final long minSmallest;

    private final long minLargest;

    private final long maxSmallest;

    private final long maxLargest;

    public static ValueRange of(long min, long max) {
        if(min > max)
            throw new IllegalArgumentException("Minimum value must be less than maximum value");
        return new ValueRange(min, min, max, max);
    }

    public static ValueRange of(long min, long maxSmallest, long maxLargest) {
        if(min > maxSmallest)
            throw new IllegalArgumentException("Minimum value must be less than smallest maximum value");
        return of(min, min, maxSmallest, maxLargest);
    }

    public static ValueRange of(long minSmallest, long minLargest, long maxSmallest, long maxLargest) {
        if(minSmallest > minLargest)
            throw new IllegalArgumentException("Smallest minimum value must be less than largest minimum value");
        if(maxSmallest > maxLargest)
            throw new IllegalArgumentException("Smallest maximum value must be less than largest maximum value");
        if(minLargest > maxLargest)
            throw new IllegalArgumentException("Largest minimum value must be less than largest maximum value");
        if(minSmallest > maxSmallest)
            throw new IllegalArgumentException("Smallest minimum value must be less than smallest maximum value");
        return new ValueRange(minSmallest, minLargest, maxSmallest, maxLargest);
    }

    private ValueRange(long minSmallest, long minLargest, long maxSmallest, long maxLargest) {
        this.minSmallest = minSmallest;
        this.minLargest = minLargest;
        this.maxSmallest = maxSmallest;
        this.maxLargest = maxLargest;
    }

    /**
     * 该数值范围是否固定且完全已知？
     * 例如ISO的“当月日期”范围从1到28至31之间。由于最大值存在不确定性，因此该范围并非固定。
     * 然而对于1月份，该范围始终为1到31，因此它是固定的。
     */
    public boolean isFixed() {
        return minSmallest == minLargest && maxSmallest == maxLargest;
    }

    public long getMinimum() {
        return minSmallest;
    }

    public long getLargestMinimum() {
        return minLargest;
    }

    public long getSmallestMaximum() {
        return maxSmallest;
    }

    public long getMaximum() {
        return maxLargest;
    }

    public boolean isIntValue() {
        return getMinimum() >= Integer.MIN_VALUE && getMaximum() <= Integer.MAX_VALUE;
    }

    public boolean isValidValue(long value) {
        return (value >= getMinimum() && value <= getMaximum());
    }

    public boolean isValidIntValue(long value) {
        return isIntValue() && isValidValue(value);
    }

    public long checkValidValue(long value, TemporalField field) {
        if(isValidValue(value) == false)
            throw new DateTimeException(genInvalidFieldMessage(field, value));
        return value;
    }

    public int checkValidIntValue(long value, TemporalField field) {
        if(isValidIntValue(value) == false)
            throw new DateTimeException(genInvalidFieldMessage(field, value));
        return (int) value;
    }

    private String genInvalidFieldMessage(TemporalField field, long value) {
        if(field != null)
            return "Invalid value for " + field + " (valid values " + this + "): " + value;
        else
            return "Invalid value (valid values " + this + "): " + value;
    }

    @java.io.Serial
    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException, InvalidObjectException {
        s.defaultReadObject();
        if(minSmallest > minLargest)
            throw new InvalidObjectException("Smallest minimum value must be less than largest minimum value");
        if(maxSmallest > maxLargest)
            throw new InvalidObjectException("Smallest maximum value must be less than largest maximum value");
        if(minLargest > maxLargest)
            throw new InvalidObjectException("Minimum value must be less than maximum value");
    }

    @Override
    public boolean equals(Object obj) {
        if(obj == this)
            return true;
        return (obj instanceof ValueRange other)
                && minSmallest == other.minSmallest
                && minLargest == other.minLargest
                && maxSmallest == other.maxSmallest
                && maxLargest == other.maxLargest;
    }

    @Override
    public int hashCode() {
        long hash = minSmallest + (minLargest << 16) + (minLargest >> 48) +
                (maxSmallest << 32) + (maxSmallest >> 32) + (maxLargest << 48) +
                (maxLargest >> 16);
        return (int) (hash ^ (hash >>> 32));
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(minSmallest);
        if(minSmallest != minLargest)
            buf.append('/').append(minLargest);
        buf.append(" - ").append(maxSmallest);
        if(maxSmallest != maxLargest)
            buf.append('/').append(maxLargest);
        return buf.toString();
    }
}

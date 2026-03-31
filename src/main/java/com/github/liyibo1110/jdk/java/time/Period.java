package com.github.liyibo1110.jdk.java.time;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.DateTimeException;
import java.time.Ser;
import java.time.chrono.ChronoPeriod;
import java.time.chrono.Chronology;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.MONTHS;
import static java.time.temporal.ChronoUnit.YEARS;

/**
 * ISO-8601日历系统中基于日期的时长，例如“2年、3个月和4天”。
 * 该类通过年、月和日来表示时长或时间量。关于与该类相对应的基于时间的等效类，请参见Duration。
 *
 * 当添加到ZonedDateTime时，Duration和Period在处理夏令时方面存在差异。Duration会添加精确的秒数，因此1天的Duration始终精确为24小时。
 * 相比之下，Period会添加一个概念上的“天”，以尽量保持本地时间。
 *
 * 例如考虑在夏令时切换间隙前一天晚上18:00处，分别添加1天的Period和1天的Duration。“周期”将增加一个概念上的“天”，从而生成次日18:00的ZonedDateTime。
 * 相比之下，“时长”将精确增加24小时，从而生成次日19:00的ZonedDateTime（假设夏令时差为1小时）。
 *
 * “周期”支持的单位包括YEARS、MONTHS和DAYS。这三个字段始终存在，但可以设置为零。
 * ISO-8601日历系统是当今世界大部分地区使用的现代公历系统。它等同于推算格里高利历系统，即对所有时间都应用当今的闰年规则。
 *
 * 该时间段被建模为一个有向的时间量，这意味着时间段的各个部分可能为负值。
 * 这是一个基于值的类；程序员应将相等的实例视为可互换，且不应将实例用于同步，否则可能会导致不可预测的行为。例如在未来版本中，同步可能会失败。应使用equals方法进行比较。
 * @author liyibo
 * @date 2026-03-30 13:46
 */
public final class Period implements ChronoPeriod, Serializable {

    public static final Period ZERO = new Period(0, 0, 0);

    @java.io.Serial
    private static final long serialVersionUID = -3587258372562876L;

    private static final Pattern PATTERN =
            Pattern.compile("([-+]?)P(?:([-+]?[0-9]+)Y)?(?:([-+]?[0-9]+)M)?(?:([-+]?[0-9]+)W)?(?:([-+]?[0-9]+)D)?", Pattern.CASE_INSENSITIVE);

    private static final List<TemporalUnit> SUPPORTED_UNITS = List.of(YEARS, MONTHS, DAYS);

    /** 主要字段1：存储了对应的年数部分 */
    private final int years;

    /** 主要字段2：存储了对应的月数部分 */
    private final int months;

    /** 主要字段3：存储了对应的天数部分 */
    private final int days;

    //-----------------------------------------------------------------------

    public static Period ofYears(int years) {
        return create(years, 0, 0);
    }

    public static Period ofMonths(int months) {
        return create(0, months, 0);
    }

    public static Period ofWeeks(int weeks) {
        return create(0, 0, Math.multiplyExact(weeks, 7));
    }

    public static Period ofDays(int days) {
        return create(0, 0, days);
    }

    //-----------------------------------------------------------------------

    public static Period of(int years, int months, int days) {
        return create(years, months, days);
    }

    //-----------------------------------------------------------------------

    public static Period from(TemporalAmount amount) {
        if (amount instanceof Period)
            return (Period) amount;

        if (amount instanceof ChronoPeriod) {
            if (IsoChronology.INSTANCE.equals(((ChronoPeriod) amount).getChronology()) == false)
                throw new DateTimeException("Period requires ISO chronology: " + amount);
        }
        Objects.requireNonNull(amount, "amount");
        int years = 0;
        int months = 0;
        int days = 0;
        for (TemporalUnit unit : amount.getUnits()) {
            long unitAmount = amount.get(unit);
            if (unit == ChronoUnit.YEARS)
                years = Math.toIntExact(unitAmount);
            else if (unit == ChronoUnit.MONTHS)
                months = Math.toIntExact(unitAmount);
            else if (unit == ChronoUnit.DAYS)
                days = Math.toIntExact(unitAmount);
            else
                throw new DateTimeException("Unit must be Years, Months or Days, but was " + unit);
        }
        return create(years, months, days);
    }

    //-----------------------------------------------------------------------

    public static Period parse(CharSequence text) {
        Objects.requireNonNull(text, "text");
        Matcher matcher = PATTERN.matcher(text);
        if (matcher.matches()) {
            int negate = (charMatch(text, matcher.start(1), matcher.end(1), '-') ? -1 : 1);
            int yearStart = matcher.start(2), yearEnd = matcher.end(2);
            int monthStart = matcher.start(3), monthEnd = matcher.end(3);
            int weekStart = matcher.start(4), weekEnd = matcher.end(4);
            int dayStart = matcher.start(5), dayEnd = matcher.end(5);
            if (yearStart >= 0 || monthStart >= 0 || weekStart >= 0 || dayStart >= 0) {
                try {
                    int years = parseNumber(text, yearStart, yearEnd, negate);
                    int months = parseNumber(text, monthStart, monthEnd, negate);
                    int weeks = parseNumber(text, weekStart, weekEnd, negate);
                    int days = parseNumber(text, dayStart, dayEnd, negate);
                    days = Math.addExact(days, Math.multiplyExact(weeks, 7));
                    return create(years, months, days);
                } catch (NumberFormatException ex) {
                    throw new DateTimeParseException("Text cannot be parsed to a Period", text, 0, ex);
                }
            }
        }
        throw new DateTimeParseException("Text cannot be parsed to a Period", text, 0);
    }

    private static boolean charMatch(CharSequence text, int start, int end, char c) {
        return (start >= 0 && end == start + 1 && text.charAt(start) == c);
    }

    private static int parseNumber(CharSequence text, int start, int end, int negate) {
        if (start < 0 || end < 0)
            return 0;

        int val = Integer.parseInt(text, start, end, 10);
        try {
            return Math.multiplyExact(val, negate);
        } catch (ArithmeticException ex) {
            throw new DateTimeParseException("Text cannot be parsed to a Period", text, 0, ex);
        }
    }

    //-----------------------------------------------------------------------

    public static Period between(LocalDate startDateInclusive, LocalDate endDateExclusive) {
        return startDateInclusive.until(endDateExclusive);
    }

    //-----------------------------------------------------------------------

    private static Period create(int years, int months, int days) {
        if ((years | months | days) == 0)
            return ZERO;
        return new Period(years, months, days);
    }

    private Period(int years, int months, int days) {
        this.years = years;
        this.months = months;
        this.days = days;
    }

    //-----------------------------------------------------------------------

    @Override
    public long get(TemporalUnit unit) {
        if (unit == ChronoUnit.YEARS)
            return getYears();
        else if (unit == ChronoUnit.MONTHS)
            return getMonths();
        else if (unit == ChronoUnit.DAYS)
            return getDays();
        else
            throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
    }

    @Override
    public List<TemporalUnit> getUnits() {
        return SUPPORTED_UNITS;
    }

    @Override
    public IsoChronology getChronology() {
        return IsoChronology.INSTANCE;
    }

    //-----------------------------------------------------------------------

    public boolean isZero() {
        return (this == ZERO);
    }

    public boolean isNegative() {
        return years < 0 || months < 0 || days < 0;
    }

    //-----------------------------------------------------------------------

    public int getYears() {
        return years;
    }

    public int getMonths() {
        return months;
    }

    public int getDays() {
        return days;
    }

    //-----------------------------------------------------------------------

    public Period withYears(int years) {
        if (years == this.years)
            return this;
        return create(years, months, days);
    }

    public Period withMonths(int months) {
        if (months == this.months)
            return this;
        return create(years, months, days);
    }

    public Period withDays(int days) {
        if (days == this.days)
            return this;
        return create(years, months, days);
    }

    //-----------------------------------------------------------------------

    public Period plus(TemporalAmount amountToAdd) {
        Period isoAmount = Period.from(amountToAdd);
        return create(
                Math.addExact(years, isoAmount.years),
                Math.addExact(months, isoAmount.months),
                Math.addExact(days, isoAmount.days));
    }

    public Period plusYears(long yearsToAdd) {
        if (yearsToAdd == 0)
            return this;
        return create(Math.toIntExact(Math.addExact(years, yearsToAdd)), months, days);
    }

    public Period plusMonths(long monthsToAdd) {
        if (monthsToAdd == 0)
            return this;
        return create(years, Math.toIntExact(Math.addExact(months, monthsToAdd)), days);
    }

    public Period plusDays(long daysToAdd) {
        if (daysToAdd == 0)
            return this;
        return create(years, months, Math.toIntExact(Math.addExact(days, daysToAdd)));
    }

    //-----------------------------------------------------------------------

    public Period minus(TemporalAmount amountToSubtract) {
        Period isoAmount = Period.from(amountToSubtract);
        return create(
                Math.subtractExact(years, isoAmount.years),
                Math.subtractExact(months, isoAmount.months),
                Math.subtractExact(days, isoAmount.days));
    }

    public Period minusYears(long yearsToSubtract) {
        return (yearsToSubtract == Long.MIN_VALUE ? plusYears(Long.MAX_VALUE).plusYears(1) : plusYears(-yearsToSubtract));
    }

    public Period minusMonths(long monthsToSubtract) {
        return (monthsToSubtract == Long.MIN_VALUE ? plusMonths(Long.MAX_VALUE).plusMonths(1) : plusMonths(-monthsToSubtract));
    }

    public Period minusDays(long daysToSubtract) {
        return (daysToSubtract == Long.MIN_VALUE ? plusDays(Long.MAX_VALUE).plusDays(1) : plusDays(-daysToSubtract));
    }

    //-----------------------------------------------------------------------

    public Period multipliedBy(int scalar) {
        if (this == ZERO || scalar == 1)
            return this;
        return create(
                Math.multiplyExact(years, scalar),
                Math.multiplyExact(months, scalar),
                Math.multiplyExact(days, scalar));
    }

    public Period negated() {
        return multipliedBy(-1);
    }

    //-----------------------------------------------------------------------

    public Period normalized() {
        long totalMonths = toTotalMonths();
        long splitYears = totalMonths / 12;
        int splitMonths = (int) (totalMonths % 12);  // no overflow
        if (splitYears == years && splitMonths == months)
            return this;
        return create(Math.toIntExact(splitYears), splitMonths, days);
    }

    public long toTotalMonths() {
        return years * 12L + months;  // no overflow
    }

    //-------------------------------------------------------------------------

    @Override
    public Temporal addTo(Temporal temporal) {
        validateChrono(temporal);
        if (months == 0) {
            if (years != 0)
                temporal = temporal.plus(years, YEARS);
        } else {
            long totalMonths = toTotalMonths();
            if (totalMonths != 0)
                temporal = temporal.plus(totalMonths, MONTHS);
        }
        if (days != 0)
            temporal = temporal.plus(days, DAYS);
        return temporal;
    }

    @Override
    public Temporal subtractFrom(Temporal temporal) {
        validateChrono(temporal);
        if (months == 0) {
            if (years != 0)
                temporal = temporal.minus(years, YEARS);
        } else {
            long totalMonths = toTotalMonths();
            if (totalMonths != 0)
                temporal = temporal.minus(totalMonths, MONTHS);
        }
        if (days != 0)
            temporal = temporal.minus(days, DAYS);
        return temporal;
    }

    private void validateChrono(TemporalAccessor temporal) {
        Objects.requireNonNull(temporal, "temporal");
        Chronology temporalChrono = temporal.query(TemporalQueries.chronology());
        if (temporalChrono != null && IsoChronology.INSTANCE.equals(temporalChrono) == false)
            throw new DateTimeException("Chronology mismatch, expected: ISO, actual: " + temporalChrono.getId());
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        return (obj instanceof Period other)
                && years == other.years
                && months == other.months
                && days == other.days;
    }

    @Override
    public int hashCode() {
        return years + Integer.rotateLeft(months, 8) + Integer.rotateLeft(days, 16);
    }

    //-----------------------------------------------------------------------

    @Override
    public String toString() {
        if (this == ZERO) {
            return "P0D";
        } else {
            StringBuilder buf = new StringBuilder();
            buf.append('P');
            if (years != 0)
                buf.append(years).append('Y');
            if (months != 0)
                buf.append(months).append('M');
            if (days != 0)
                buf.append(days).append('D');
            return buf.toString();
        }
    }

    //-----------------------------------------------------------------------

    @java.io.Serial
    private Object writeReplace() {
        return new Ser(Ser.PERIOD_TYPE, this);
    }

    @java.io.Serial
    private void readObject(ObjectInputStream s) throws InvalidObjectException {
        throw new InvalidObjectException("Deserialization via serialization delegate");
    }

    void writeExternal(DataOutput out) throws IOException {
        out.writeInt(years);
        out.writeInt(months);
        out.writeInt(days);
    }

    static Period readExternal(DataInput in) throws IOException {
        int years = in.readInt();
        int months = in.readInt();
        int days = in.readInt();
        return Period.of(years, months, days);
    }
}

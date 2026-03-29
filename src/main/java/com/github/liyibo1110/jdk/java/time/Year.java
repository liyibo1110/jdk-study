package com.github.liyibo1110.jdk.java.time;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.chrono.Chronology;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.temporal.ValueRange;
import java.util.Objects;

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
            .appendValue(ChronoField.YEAR, 1, 10, SignStyle.NORMAL)
            .toFormatter();

    private final int year;

    //-----------------------------------------------------------------------

    public static Year now() {
        return now(Clock.systemDefaultZone());
    }

    public static Year now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static Year now(Clock clock) {
        final LocalDate now = LocalDate.now(clock);  // called once
        return Year.of(now.getYear());
    }

    //-----------------------------------------------------------------------

    public static Year of(int isoYear) {
        ChronoField.YEAR.checkValidValue(isoYear);
        return new Year(isoYear);
    }

    //-----------------------------------------------------------------------

    public static Year from(TemporalAccessor temporal) {
        if(temporal instanceof Year)
            return (Year) temporal;
        Objects.requireNonNull(temporal, "temporal");
        try {
            if(IsoChronology.INSTANCE.equals(Chronology.from(temporal)) == false)
                temporal = LocalDate.from(temporal);
            return of(temporal.get(ChronoField.YEAR));
        } catch (DateTimeException ex) {
            throw new DateTimeException("Unable to obtain Year from TemporalAccessor: " + temporal + " of type " + temporal.getClass().getName(), ex);
        }
    }

    //-----------------------------------------------------------------------

    public static Year parse(CharSequence text) {
        return parse(text, PARSER);
    }

    public static Year parse(CharSequence text, DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.parse(text, Year::from);
    }

    //-------------------------------------------------------------------------

    public static boolean isLeap(long year) {
        return ((year & 3) == 0) && ((year % 100) != 0 || (year % 400) == 0);
    }

    //-----------------------------------------------------------------------

    private Year(int year) {
        this.year = year;
    }

    //-----------------------------------------------------------------------

    public int getValue() {
        return year;
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean isSupported(TemporalField field) {
        if(field instanceof ChronoField)
            return field == ChronoField.YEAR || field == ChronoField.YEAR_OF_ERA || field == ChronoField.ERA;
        return field != null && field.isSupportedBy(this);
    }

    @Override
    public boolean isSupported(TemporalUnit unit) {
        if(unit instanceof ChronoUnit)
            return unit == ChronoUnit.YEARS || unit == ChronoUnit.DECADES || unit == ChronoUnit.CENTURIES || unit == ChronoUnit.MILLENNIA || unit == ChronoUnit.ERAS;
        return unit != null && unit.isSupportedBy(this);
    }

    //-----------------------------------------------------------------------

    @Override
    public ValueRange range(TemporalField field) {
        if(field == ChronoField.YEAR_OF_ERA)
            return (year <= 0 ? ValueRange.of(1, MAX_VALUE + 1) : ValueRange.of(1, MAX_VALUE));
        return Temporal.super.range(field);
    }

    @Override
    public int get(TemporalField field) {
        return range(field).checkValidIntValue(getLong(field), field);
    }

    @Override
    public long getLong(TemporalField field) {
        if(field instanceof ChronoField chronoField) {
            switch(chronoField) {
                case YEAR_OF_ERA: return (year < 1 ? 1 - year : year);
                case YEAR: return year;
                case ERA: return (year < 1 ? 0 : 1);
            }
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.getFrom(this);
    }

    //-----------------------------------------------------------------------

    public boolean isLeap() {
        return Year.isLeap(year);
    }

    public boolean isValidMonthDay(MonthDay monthDay) {
        return monthDay != null && monthDay.isValidYear(year);
    }

    public int length() {
        return isLeap() ? 366 : 365;
    }

    //-----------------------------------------------------------------------

    @Override
    public Year with(TemporalAdjuster adjuster) {
        return (Year) adjuster.adjustInto(this);
    }

    @Override
    public Year with(TemporalField field, long newValue) {
        if(field instanceof ChronoField chronoField) {
            chronoField.checkValidValue(newValue);
            switch(chronoField) {
                case YEAR_OF_ERA: return Year.of((int) (year < 1 ? 1 - newValue : newValue));
                case YEAR: return Year.of((int) newValue);
                case ERA: return (getLong(ChronoField.ERA) == newValue ? this : Year.of(1 - year));
            }
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.adjustInto(this, newValue);
    }

    //-----------------------------------------------------------------------

    @Override
    public Year plus(TemporalAmount amountToAdd) {
        return (Year) amountToAdd.addTo(this);
    }

    @Override
    public Year plus(long amountToAdd, TemporalUnit unit) {
        if(unit instanceof ChronoUnit chronoUnit) {
            switch(chronoUnit) {
                case YEARS: return plusYears(amountToAdd);
                case DECADES: return plusYears(Math.multiplyExact(amountToAdd, 10));
                case CENTURIES: return plusYears(Math.multiplyExact(amountToAdd, 100));
                case MILLENNIA: return plusYears(Math.multiplyExact(amountToAdd, 1000));
                case ERAS: return with(ChronoField.ERA, Math.addExact(getLong(ChronoField.ERA), amountToAdd));
            }
            throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
        }
        return unit.addTo(this, amountToAdd);
    }

    public Year plusYears(long yearsToAdd) {
        if(yearsToAdd == 0)
            return this;
        return of(ChronoField.YEAR.checkValidIntValue(year + yearsToAdd));  // overflow safe
    }

    //-----------------------------------------------------------------------

    @Override
    public Year minus(TemporalAmount amountToSubtract) {
        return (Year) amountToSubtract.subtractFrom(this);
    }

    @Override
    public Year minus(long amountToSubtract, TemporalUnit unit) {
        return (amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit) : plus(-amountToSubtract, unit));
    }

    public Year minusYears(long yearsToSubtract) {
        return (yearsToSubtract == Long.MIN_VALUE ? plusYears(Long.MAX_VALUE).plusYears(1) : plusYears(-yearsToSubtract));
    }

    //-----------------------------------------------------------------------

    @Override
    public <R> R query(TemporalQuery<R> query) {
        if(query == TemporalQueries.chronology())
            return (R)IsoChronology.INSTANCE;
        else if (query == TemporalQueries.precision())
            return (R)ChronoUnit.YEARS;
        return Temporal.super.query(query);
    }

    @Override
    public Temporal adjustInto(Temporal temporal) {
        if(Chronology.from(temporal).equals(IsoChronology.INSTANCE) == false)
            throw new DateTimeException("Adjustment only supported on ISO date-time");
        return temporal.with(ChronoField.YEAR, year);
    }

    @Override
    public long until(Temporal endExclusive, TemporalUnit unit) {
        Year end = Year.from(endExclusive);
        if(unit instanceof ChronoUnit chronoUnit) {
            long yearsUntil = ((long) end.year) - year;  // no overflow
            switch(chronoUnit) {
                case YEARS: return yearsUntil;
                case DECADES: return yearsUntil / 10;
                case CENTURIES: return yearsUntil / 100;
                case MILLENNIA: return yearsUntil / 1000;
                case ERAS: return end.getLong(ChronoField.ERA) - getLong(ChronoField.ERA);
            }
            throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
        }
        return unit.between(this, end);
    }

    public String format(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.format(this);
    }

    //-----------------------------------------------------------------------

    public LocalDate atDay(int dayOfYear) {
        return LocalDate.ofYearDay(year, dayOfYear);
    }

    public YearMonth atMonth(Month month) {
        return YearMonth.of(year, month);
    }

    public YearMonth atMonth(int month) {
        return YearMonth.of(year, month);
    }

    public LocalDate atMonthDay(MonthDay monthDay) {
        return monthDay.atYear(year);
    }

    //-----------------------------------------------------------------------

    @Override
    public int compareTo(Year other) {
        return year - other.year;
    }

    public boolean isAfter(Year other) {
        return year > other.year;
    }

    public boolean isBefore(Year other) {
        return year < other.year;
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        if(this == obj)
            return true;
        if(obj instanceof Year)
            return year == ((Year)obj).year;
        return false;
    }

    @Override
    public int hashCode() {
        return year;
    }

    //-----------------------------------------------------------------------

    @Override
    public String toString() {
        return Integer.toString(year);
    }

    //-----------------------------------------------------------------------

    @java.io.Serial
    private Object writeReplace() {
        return new Ser(Ser.YEAR_TYPE, this);
    }

    @java.io.Serial
    private void readObject(ObjectInputStream s) throws InvalidObjectException {
        throw new InvalidObjectException("Deserialization via serialization delegate");
    }

    void writeExternal(DataOutput out) throws IOException {
        out.writeInt(year);
    }

    static Year readExternal(DataInput in) throws IOException {
        return Year.of(in.readInt());
    }
}

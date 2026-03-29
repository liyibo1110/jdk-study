package com.github.liyibo1110.jdk.java.time;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.Clock;
import java.time.DateTimeException;
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
 * 和year类是同一类东西，代码结构基本也是一样的，只是增加了月份字段。
 * @author liyibo
 * @date 2026-03-28 23:55
 */
public class YearMonth implements Temporal, TemporalAdjuster, Comparable<YearMonth>, Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 4183400860270640070L;

    private static final DateTimeFormatter PARSER = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
            .appendLiteral('-')
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .toFormatter();

    private final int year;

    private final int month;

    //-----------------------------------------------------------------------

    public static YearMonth now() {
        return now(Clock.systemDefaultZone());
    }

    public static YearMonth now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static YearMonth now(Clock clock) {
        final LocalDate now = LocalDate.now(clock);  // 其实是先构造了LocalDate，再间接构造了YearMonth
        return YearMonth.of(now.getYear(), now.getMonth());
    }

    //-----------------------------------------------------------------------

    public static YearMonth of(int year, Month month) {
        Objects.requireNonNull(month, "month");
        return of(year, month.getValue());
    }

    public static YearMonth of(int year, int month) {
        ChronoField.YEAR.checkValidValue(year);
        ChronoField.MONTH_OF_YEAR.checkValidValue(month);
        return new YearMonth(year, month);
    }

    //-----------------------------------------------------------------------

    public static YearMonth from(TemporalAccessor temporal) {
        if(temporal instanceof YearMonth)
            return (YearMonth) temporal;
        Objects.requireNonNull(temporal, "temporal");
        try {
            if(IsoChronology.INSTANCE.equals(Chronology.from(temporal)) == false)
                temporal = LocalDate.from(temporal);
            return of(temporal.get(ChronoField.YEAR), temporal.get(ChronoField.MONTH_OF_YEAR));
        } catch (DateTimeException ex) {
            throw new DateTimeException("Unable to obtain YearMonth from TemporalAccessor: " + temporal + " of type " + temporal.getClass().getName(), ex);
        }
    }

    //-----------------------------------------------------------------------

    public static YearMonth parse(CharSequence text) {
        return parse(text, PARSER);
    }

    public static YearMonth parse(CharSequence text, DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.parse(text, YearMonth::from);
    }

    //-----------------------------------------------------------------------

    private YearMonth(int year, int month) {
        this.year = year;
        this.month = month;
    }

    private YearMonth with(int newYear, int newMonth) {
        if(year == newYear && month == newMonth)
            return this;
        return new YearMonth(newYear, newMonth);
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean isSupported(TemporalField field) {
        if(field instanceof ChronoField) {
            return field == ChronoField.YEAR || field == ChronoField.MONTH_OF_YEAR ||
                    field == ChronoField.PROLEPTIC_MONTH || field == ChronoField.YEAR_OF_ERA || field == ChronoField.ERA;
        }
        return field != null && field.isSupportedBy(this);
    }

    @Override
    public boolean isSupported(TemporalUnit unit) {
        if(unit instanceof ChronoUnit)
            return unit == ChronoUnit.MONTHS || unit == ChronoUnit.YEARS || unit == ChronoUnit.DECADES
                    || unit == ChronoUnit.CENTURIES || unit == ChronoUnit.MILLENNIA || unit == ChronoUnit.ERAS;
        return unit != null && unit.isSupportedBy(this);
    }

    //-----------------------------------------------------------------------

    @Override
    public ValueRange range(TemporalField field) {
        if(field == ChronoField.YEAR_OF_ERA)
            return (getYear() <= 0 ? ValueRange.of(1, Year.MAX_VALUE + 1) : ValueRange.of(1, Year.MAX_VALUE));
        return Temporal.super.range(field);
    }

    @Override  // override for Javadoc
    public int get(TemporalField field) {
        return range(field).checkValidIntValue(getLong(field), field);
    }

    @Override
    public long getLong(TemporalField field) {
        if(field instanceof ChronoField chronoField) {
            switch(chronoField) {
                case MONTH_OF_YEAR: return month;
                case PROLEPTIC_MONTH: return getProlepticMonth();
                case YEAR_OF_ERA: return (year < 1 ? 1 - year : year);
                case YEAR: return year;
                case ERA: return (year < 1 ? 0 : 1);
            }
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.getFrom(this);
    }

    private long getProlepticMonth() {
        return (year * 12L + month - 1);
    }

    //-----------------------------------------------------------------------

    public int getYear() {
        return year;
    }

    public int getMonthValue() {
        return month;
    }

    public Month getMonth() {
        return Month.of(month);
    }

    //-----------------------------------------------------------------------

    public boolean isLeapYear() {
        return IsoChronology.INSTANCE.isLeapYear(year);
    }

    public boolean isValidDay(int dayOfMonth) {
        return dayOfMonth >= 1 && dayOfMonth <= lengthOfMonth();
    }

    public int lengthOfMonth() {
        return getMonth().length(isLeapYear());
    }

    public int lengthOfYear() {
        return (isLeapYear() ? 366 : 365);
    }

    //-----------------------------------------------------------------------

    @Override
    public YearMonth with(TemporalAdjuster adjuster) {
        return (YearMonth) adjuster.adjustInto(this);
    }

    @Override
    public YearMonth with(TemporalField field, long newValue) {
        if(field instanceof ChronoField chronoField) {
            chronoField.checkValidValue(newValue);
            switch(chronoField) {
                case MONTH_OF_YEAR: return withMonth((int) newValue);
                case PROLEPTIC_MONTH: return plusMonths(newValue - getProlepticMonth());
                case YEAR_OF_ERA: return withYear((int) (year < 1 ? 1 - newValue : newValue));
                case YEAR: return withYear((int) newValue);
                case ERA: return (getLong(ChronoField.ERA) == newValue ? this : withYear(1 - year));
            }
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.adjustInto(this, newValue);
    }

    //-----------------------------------------------------------------------

    public YearMonth withYear(int year) {
        ChronoField.YEAR.checkValidValue(year);
        return with(year, month);
    }

    public YearMonth withMonth(int month) {
        ChronoField.MONTH_OF_YEAR.checkValidValue(month);
        return with(year, month);
    }

    //-----------------------------------------------------------------------

    @Override
    public YearMonth plus(TemporalAmount amountToAdd) {
        return (YearMonth) amountToAdd.addTo(this);
    }

    @Override
    public YearMonth plus(long amountToAdd, TemporalUnit unit) {
        if(unit instanceof ChronoUnit chronoUnit) {
            switch(chronoUnit) {
                case MONTHS: return plusMonths(amountToAdd);
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

    public YearMonth plusYears(long yearsToAdd) {
        if(yearsToAdd == 0)
            return this;
        int newYear = ChronoField.YEAR.checkValidIntValue(year + yearsToAdd);  // safe overflow
        return with(newYear, month);
    }

    public YearMonth plusMonths(long monthsToAdd) {
        if(monthsToAdd == 0)
            return this;
        long monthCount = year * 12L + (month - 1);
        long calcMonths = monthCount + monthsToAdd;  // safe overflow
        int newYear = ChronoField.YEAR.checkValidIntValue(Math.floorDiv(calcMonths, 12));
        int newMonth = Math.floorMod(calcMonths, 12) + 1;
        return with(newYear, newMonth);
    }

    //-----------------------------------------------------------------------

    @Override
    public YearMonth minus(TemporalAmount amountToSubtract) {
        return (YearMonth)amountToSubtract.subtractFrom(this);
    }

    @Override
    public YearMonth minus(long amountToSubtract, TemporalUnit unit) {
        return (amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit) : plus(-amountToSubtract, unit));
    }

    public YearMonth minusYears(long yearsToSubtract) {
        return (yearsToSubtract == Long.MIN_VALUE ? plusYears(Long.MAX_VALUE).plusYears(1) : plusYears(-yearsToSubtract));
    }

    public YearMonth minusMonths(long monthsToSubtract) {
        return (monthsToSubtract == Long.MIN_VALUE ? plusMonths(Long.MAX_VALUE).plusMonths(1) : plusMonths(-monthsToSubtract));
    }

    //-----------------------------------------------------------------------

    @Override
    public <R> R query(TemporalQuery<R> query) {
        if(query == TemporalQueries.chronology())
            return (R)IsoChronology.INSTANCE;
        else if (query == TemporalQueries.precision())
            return (R)ChronoUnit.MONTHS;
        return Temporal.super.query(query);
    }

    @Override
    public Temporal adjustInto(Temporal temporal) {
        if(Chronology.from(temporal).equals(IsoChronology.INSTANCE) == false)
            throw new DateTimeException("Adjustment only supported on ISO date-time");
        return temporal.with(ChronoField.PROLEPTIC_MONTH, getProlepticMonth());
    }

    @Override
    public long until(Temporal endExclusive, TemporalUnit unit) {
        YearMonth end = YearMonth.from(endExclusive);
        if(unit instanceof ChronoUnit chronoUnit) {
            long monthsUntil = end.getProlepticMonth() - getProlepticMonth();  // no overflow
            switch(chronoUnit) {
                case MONTHS: return monthsUntil;
                case YEARS: return monthsUntil / 12;
                case DECADES: return monthsUntil / 120;
                case CENTURIES: return monthsUntil / 1200;
                case MILLENNIA: return monthsUntil / 12000;
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

    public LocalDate atDay(int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth);
    }

    public LocalDate atEndOfMonth() {
        return LocalDate.of(year, month, lengthOfMonth());
    }

    //-----------------------------------------------------------------------

    @Override
    public int compareTo(YearMonth other) {
        int cmp = year - other.year;
        if(cmp == 0)
            cmp = (month - other.month);
        return cmp;
    }

    public boolean isAfter(YearMonth other) {
        return compareTo(other) > 0;
    }

    public boolean isBefore(YearMonth other) {
        return compareTo(other) < 0;
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        if(this == obj)
            return true;
        return (obj instanceof YearMonth other)
                && year == other.year
                && month == other.month;
    }

    @Override
    public int hashCode() {
        return year ^ (month << 27);
    }

    //-----------------------------------------------------------------------

    @Override
    public String toString() {
        int absYear = Math.abs(year);
        StringBuilder buf = new StringBuilder(9);
        if(absYear < 1000) {
            if(year < 0)
                buf.append(year - 10000).deleteCharAt(1);
            else
                buf.append(year + 10000).deleteCharAt(0);
        }else {
            buf.append(year);
        }
        return buf.append(month < 10 ? "-0" : "-").append(month).toString();
    }

    //-----------------------------------------------------------------------

    @java.io.Serial
    private Object writeReplace() {
        return new Ser(Ser.YEAR_MONTH_TYPE, this);
    }

    @java.io.Serial
    private void readObject(ObjectInputStream s) throws InvalidObjectException {
        throw new InvalidObjectException("Deserialization via serialization delegate");
    }

    void writeExternal(DataOutput out) throws IOException {
        out.writeInt(year);
        out.writeByte(month);
    }

    static YearMonth readExternal(DataInput in) throws IOException {
        int year = in.readInt();
        byte month = in.readByte();
        return YearMonth.of(year, month);
    }
}

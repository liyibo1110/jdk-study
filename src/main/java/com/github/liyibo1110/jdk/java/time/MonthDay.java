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
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.temporal.ValueRange;
import java.util.Objects;

/**
 * 和YearMonth类是同一类东西，减少了一些代码。
 * @author liyibo
 * @date 2026-03-29 17:48
 */
public class MonthDay implements TemporalAccessor, TemporalAdjuster, Comparable<MonthDay>, Serializable {
    @java.io.Serial
    private static final long serialVersionUID = -939150713474957432L;

    private static final DateTimeFormatter PARSER = new DateTimeFormatterBuilder()
            .appendLiteral("--")
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .toFormatter();

    private final int month;

    private final int day;

    //-----------------------------------------------------------------------

    public static MonthDay now() {
        return now(Clock.systemDefaultZone());
    }

    public static MonthDay now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static MonthDay now(Clock clock) {
        final LocalDate now = LocalDate.now(clock);  // called once
        return MonthDay.of(now.getMonth(), now.getDayOfMonth());
    }

    //-----------------------------------------------------------------------

    public static MonthDay of(Month month, int dayOfMonth) {
        Objects.requireNonNull(month, "month");
        ChronoField.DAY_OF_MONTH.checkValidValue(dayOfMonth);
        if(dayOfMonth > month.maxLength())
            throw new DateTimeException("Illegal value for DayOfMonth field, value " + dayOfMonth + " is not valid for month " + month.name());
        return new MonthDay(month.getValue(), dayOfMonth);
    }

    public static MonthDay of(int month, int dayOfMonth) {
        return of(Month.of(month), dayOfMonth);
    }

    //-----------------------------------------------------------------------

    public static MonthDay from(TemporalAccessor temporal) {
        if(temporal instanceof MonthDay)
            return (MonthDay) temporal;
        try {
            if(IsoChronology.INSTANCE.equals(Chronology.from(temporal)) == false)
                temporal = LocalDate.from(temporal);
            return of(temporal.get(ChronoField.MONTH_OF_YEAR), temporal.get(ChronoField.DAY_OF_MONTH));
        } catch (DateTimeException ex) {
            throw new DateTimeException("Unable to obtain MonthDay from TemporalAccessor: " + temporal + " of type " + temporal.getClass().getName(), ex);
        }
    }

    //-----------------------------------------------------------------------

    public static MonthDay parse(CharSequence text) {
        return parse(text, PARSER);
    }

    public static MonthDay parse(CharSequence text, DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.parse(text, MonthDay::from);
    }

    //-----------------------------------------------------------------------

    private MonthDay(int month, int dayOfMonth) {
        this.month = month;
        this.day = dayOfMonth;
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean isSupported(TemporalField field) {
        if(field instanceof ChronoField)
            return field == ChronoField.MONTH_OF_YEAR || field == ChronoField.DAY_OF_MONTH;
        return field != null && field.isSupportedBy(this);
    }

    @Override
    public ValueRange range(TemporalField field) {
        if(field == ChronoField.MONTH_OF_YEAR)
            return field.range();
        else if (field == ChronoField.DAY_OF_MONTH)
            return ValueRange.of(1, getMonth().minLength(), getMonth().maxLength());
        return TemporalAccessor.super.range(field);
    }

    @Override  // override for Javadoc
    public int get(TemporalField field) {
        return range(field).checkValidIntValue(getLong(field), field);
    }

    @Override
    public long getLong(TemporalField field) {
        if(field instanceof ChronoField chronoField) {
            switch(chronoField) {
                // alignedDOW and alignedWOM not supported because they cannot be set in with()
                case DAY_OF_MONTH: return day;
                case MONTH_OF_YEAR: return month;
            }
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.getFrom(this);
    }

    //-----------------------------------------------------------------------

    public int getMonthValue() {
        return month;
    }

    public Month getMonth() {
        return Month.of(month);
    }

    public int getDayOfMonth() {
        return day;
    }

    //-----------------------------------------------------------------------

    public boolean isValidYear(int year) {
        return (day == 29 && month == 2 && Year.isLeap(year) == false) == false;
    }

    public MonthDay withMonth(int month) {
        return with(Month.of(month));
    }

    public MonthDay with(Month month) {
        Objects.requireNonNull(month, "month");
        if(month.getValue() == this.month)
            return this;
        int day = Math.min(this.day, month.maxLength());
        return new MonthDay(month.getValue(), day);
    }

    public MonthDay withDayOfMonth(int dayOfMonth) {
        if(dayOfMonth == this.day)
            return this;
        return of(month, dayOfMonth);
    }

    //-----------------------------------------------------------------------

    @Override
    public <R> R query(TemporalQuery<R> query) {
        if(query == TemporalQueries.chronology())
            return (R) IsoChronology.INSTANCE;
        return TemporalAccessor.super.query(query);
    }

    @Override
    public Temporal adjustInto(Temporal temporal) {
        if(Chronology.from(temporal).equals(IsoChronology.INSTANCE) == false)
            throw new DateTimeException("Adjustment only supported on ISO date-time");
        temporal = temporal.with(ChronoField.MONTH_OF_YEAR, month);
        return temporal.with(ChronoField.DAY_OF_MONTH, Math.min(temporal.range(ChronoField.DAY_OF_MONTH).getMaximum(), day));
    }

    public String format(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.format(this);
    }

    //-----------------------------------------------------------------------

    public LocalDate atYear(int year) {
        return LocalDate.of(year, month, isValidYear(year) ? day : 28);
    }

    //-----------------------------------------------------------------------

    @Override
    public int compareTo(MonthDay other) {
        int cmp = (month - other.month);
        if(cmp == 0)
            cmp = (day - other.day);
        return cmp;
    }

    public boolean isAfter(MonthDay other) {
        return compareTo(other) > 0;
    }

    public boolean isBefore(MonthDay other) {
        return compareTo(other) < 0;
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        if(this == obj)
            return true;
        return (obj instanceof MonthDay other) && month == other.month && day == other.day;
    }

    @Override
    public int hashCode() {
        return (month << 6) + day;
    }

    //-----------------------------------------------------------------------

    @Override
    public String toString() {
        return new StringBuilder(10).append("--")
                .append(month < 10 ? "0" : "").append(month)
                .append(day < 10 ? "-0" : "-").append(day)
                .toString();
    }

    //-----------------------------------------------------------------------

    @java.io.Serial
    private Object writeReplace() {
        return new Ser(Ser.MONTH_DAY_TYPE, this);
    }

    @java.io.Serial
    private void readObject(ObjectInputStream s) throws InvalidObjectException {
        throw new InvalidObjectException("Deserialization via serialization delegate");
    }

    void writeExternal(DataOutput out) throws IOException {
        out.writeByte(month);
        out.writeByte(day);
    }

    static MonthDay readExternal(DataInput in) throws IOException {
        byte month = in.readByte();
        byte day = in.readByte();
        return MonthDay.of(month, day);
    }
}

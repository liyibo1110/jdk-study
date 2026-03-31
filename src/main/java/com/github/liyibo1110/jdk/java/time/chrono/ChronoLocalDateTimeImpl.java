package com.github.liyibo1110.jdk.java.time.chrono;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.ChronoLocalDateImpl;
import java.time.chrono.ChronoLocalDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.time.chrono.ChronoZonedDateTimeImpl;
import java.time.chrono.Chronology;
import java.time.chrono.Ser;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalUnit;
import java.time.temporal.ValueRange;
import java.util.Objects;

import static java.time.temporal.ChronoField.EPOCH_DAY;

/**
 * 适用于日历中立API的无时区日期时间对象。
 * ChronoLocalDateTime是一个不可变的日期时间对象，用于表示日期时间，通常以年-月-日-时-分-秒的形式呈现。
 * 该对象还可以访问其他字段，例如年度内第几天、本周几以及年度内第几周。
 *
 * 该类存储所有日期和时间字段，精度可达纳秒。它不存储也不表示时区。
 * 例如，值“2007年10月2日 13:45.30.123456789”可以存储在ChronoLocalDateTime中。
 * @author liyibo
 * @date 2026-03-30 14:31
 */
final class ChronoLocalDateTimeImpl<D extends ChronoLocalDate>
        implements ChronoLocalDateTime<D>, Temporal, TemporalAdjuster, Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 4556003607393004514L;

    static final int HOURS_PER_DAY = 24;

    static final int MINUTES_PER_HOUR = 60;

    static final int MINUTES_PER_DAY = MINUTES_PER_HOUR * HOURS_PER_DAY;

    static final int SECONDS_PER_MINUTE = 60;

    static final int SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;

    static final int SECONDS_PER_DAY = SECONDS_PER_HOUR * HOURS_PER_DAY;

    static final long MILLIS_PER_DAY = SECONDS_PER_DAY * 1000L;

    static final long MICROS_PER_DAY = SECONDS_PER_DAY * 1000_000L;

    static final long NANOS_PER_SECOND = 1000_000_000L;

    static final long NANOS_PER_MINUTE = NANOS_PER_SECOND * SECONDS_PER_MINUTE;

    static final long NANOS_PER_HOUR = NANOS_PER_MINUTE * MINUTES_PER_HOUR;

    static final long NANOS_PER_DAY = NANOS_PER_HOUR * HOURS_PER_DAY;

    private final transient D date;

    private final transient LocalTime time;

    //-----------------------------------------------------------------------

    static <R extends ChronoLocalDate> ChronoLocalDateTimeImpl<R> of(R date, LocalTime time) {
        return new ChronoLocalDateTimeImpl<>(date, time);
    }

    static <R extends ChronoLocalDate> ChronoLocalDateTimeImpl<R> ensureValid(Chronology chrono, Temporal temporal) {
        @SuppressWarnings("unchecked")
        ChronoLocalDateTimeImpl<R> other = (ChronoLocalDateTimeImpl<R>) temporal;
        if (chrono.equals(other.getChronology()) == false)
            throw new ClassCastException("Chronology mismatch, required: " + chrono.getId() + ", actual: " + other.getChronology().getId());
        return other;
    }

    private ChronoLocalDateTimeImpl(D date, LocalTime time) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(time, "time");
        this.date = date;
        this.time = time;
    }

    private ChronoLocalDateTimeImpl<D> with(Temporal newDate, LocalTime newTime) {
        if (date == newDate && time == newTime)
            return this;
        // Validate that the new Temporal is a ChronoLocalDate (and not something else)
        D cd = ChronoLocalDateImpl.ensureValid(date.getChronology(), newDate);
        return new ChronoLocalDateTimeImpl<>(cd, newTime);
    }

    //-----------------------------------------------------------------------

    @Override
    public D toLocalDate() {
        return date;
    }

    @Override
    public LocalTime toLocalTime() {
        return time;
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean isSupported(TemporalField field) {
        if (field instanceof ChronoField chronoField)
            return chronoField.isDateBased() || chronoField.isTimeBased();
        return field != null && field.isSupportedBy(this);
    }

    @Override
    public ValueRange range(TemporalField field) {
        if (field instanceof ChronoField chronoField)
            return (chronoField.isTimeBased() ? time.range(field) : date.range(field));
        return field.rangeRefinedBy(this);
    }

    @Override
    public int get(TemporalField field) {
        if (field instanceof ChronoField chronoField)
            return (chronoField.isTimeBased() ? time.get(field) : date.get(field));
        return range(field).checkValidIntValue(getLong(field), field);
    }

    @Override
    public long getLong(TemporalField field) {
        if (field instanceof ChronoField chronoField)
            return (chronoField.isTimeBased() ? time.getLong(field) : date.getLong(field));
        return field.getFrom(this);
    }

    //-----------------------------------------------------------------------

    @Override
    public ChronoLocalDateTimeImpl<D> with(TemporalAdjuster adjuster) {
        if (adjuster instanceof ChronoLocalDate)
            // The Chronology is checked in with(date,time)
            return with((ChronoLocalDate) adjuster, time);
        else if (adjuster instanceof LocalTime)
            return with(date, (LocalTime) adjuster);
        else if (adjuster instanceof ChronoLocalDateTimeImpl)
            return ChronoLocalDateTimeImpl.ensureValid(date.getChronology(), (ChronoLocalDateTimeImpl<?>) adjuster);

        return ChronoLocalDateTimeImpl.ensureValid(date.getChronology(), (ChronoLocalDateTimeImpl<?>) adjuster.adjustInto(this));
    }

    @Override
    public ChronoLocalDateTimeImpl<D> with(TemporalField field, long newValue) {
        if (field instanceof ChronoField chronoField) {
            if (chronoField.isTimeBased())
                return with(date, time.with(field, newValue));
            else
                return with(date.with(field, newValue), time);
        }
        return ChronoLocalDateTimeImpl.ensureValid(date.getChronology(), field.adjustInto(this, newValue));
    }

    //-----------------------------------------------------------------------

    @Override
    public ChronoLocalDateTimeImpl<D> plus(long amountToAdd, TemporalUnit unit) {
        if (unit instanceof ChronoUnit chronoUnit) {
            switch (chronoUnit) {
                case NANOS: return plusNanos(amountToAdd);
                case MICROS: return plusDays(amountToAdd / MICROS_PER_DAY).plusNanos((amountToAdd % MICROS_PER_DAY) * 1000);
                case MILLIS: return plusDays(amountToAdd / MILLIS_PER_DAY).plusNanos((amountToAdd % MILLIS_PER_DAY) * 1000000);
                case SECONDS: return plusSeconds(amountToAdd);
                case MINUTES: return plusMinutes(amountToAdd);
                case HOURS: return plusHours(amountToAdd);
                case HALF_DAYS: return plusDays(amountToAdd / 256).plusHours((amountToAdd % 256) * 12);  // no overflow (256 is multiple of 2)
            }
            return with(date.plus(amountToAdd, unit), time);
        }
        return ChronoLocalDateTimeImpl.ensureValid(date.getChronology(), unit.addTo(this, amountToAdd));
    }

    private ChronoLocalDateTimeImpl<D> plusDays(long days) {
        return with(date.plus(days, ChronoUnit.DAYS), time);
    }

    private ChronoLocalDateTimeImpl<D> plusHours(long hours) {
        return plusWithOverflow(date, hours, 0, 0, 0);
    }

    private ChronoLocalDateTimeImpl<D> plusMinutes(long minutes) {
        return plusWithOverflow(date, 0, minutes, 0, 0);
    }

    ChronoLocalDateTimeImpl<D> plusSeconds(long seconds) {
        return plusWithOverflow(date, 0, 0, seconds, 0);
    }

    private ChronoLocalDateTimeImpl<D> plusNanos(long nanos) {
        return plusWithOverflow(date, 0, 0, 0, nanos);
    }

    //-----------------------------------------------------------------------

    private ChronoLocalDateTimeImpl<D> plusWithOverflow(D newDate, long hours, long minutes, long seconds, long nanos) {
        // 9223372036854775808 long, 2147483648 int
        if ((hours | minutes | seconds | nanos) == 0)
            return with(newDate, time);

        long totDays = nanos / NANOS_PER_DAY +             //   max/24*60*60*1B
                seconds / SECONDS_PER_DAY +                //   max/24*60*60
                minutes / MINUTES_PER_DAY +                //   max/24*60
                hours / HOURS_PER_DAY;                     //   max/24
        long totNanos = nanos % NANOS_PER_DAY +                    //   max  86400000000000
                (seconds % SECONDS_PER_DAY) * NANOS_PER_SECOND +   //   max  86400000000000
                (minutes % MINUTES_PER_DAY) * NANOS_PER_MINUTE +   //   max  86400000000000
                (hours % HOURS_PER_DAY) * NANOS_PER_HOUR;          //   max  86400000000000
        long curNoD = time.toNanoOfDay();                          //   max  86400000000000
        totNanos = totNanos + curNoD;                              // total 432000000000000
        totDays += Math.floorDiv(totNanos, NANOS_PER_DAY);
        long newNoD = Math.floorMod(totNanos, NANOS_PER_DAY);
        LocalTime newTime = (newNoD == curNoD ? time : LocalTime.ofNanoOfDay(newNoD));
        return with(newDate.plus(totDays, ChronoUnit.DAYS), newTime);
    }

    //-----------------------------------------------------------------------

    @Override
    public ChronoZonedDateTime<D> atZone(ZoneId zone) {
        return ChronoZonedDateTimeImpl.ofBest(this, zone, null);
    }

    //-----------------------------------------------------------------------

    @Override
    public long until(Temporal endExclusive, TemporalUnit unit) {
        Objects.requireNonNull(endExclusive, "endExclusive");
        @SuppressWarnings("unchecked")
        ChronoLocalDateTime<D> end = (ChronoLocalDateTime<D>) getChronology().localDateTime(endExclusive);
        if (unit instanceof ChronoUnit chronoUnit) {
            if (unit.isTimeBased()) {
                long amount = end.getLong(EPOCH_DAY) - date.getLong(EPOCH_DAY);
                switch (chronoUnit) {
                    case NANOS: amount = Math.multiplyExact(amount, NANOS_PER_DAY); break;
                    case MICROS: amount = Math.multiplyExact(amount, MICROS_PER_DAY); break;
                    case MILLIS: amount = Math.multiplyExact(amount, MILLIS_PER_DAY); break;
                    case SECONDS: amount = Math.multiplyExact(amount, SECONDS_PER_DAY); break;
                    case MINUTES: amount = Math.multiplyExact(amount, MINUTES_PER_DAY); break;
                    case HOURS: amount = Math.multiplyExact(amount, HOURS_PER_DAY); break;
                    case HALF_DAYS: amount = Math.multiplyExact(amount, 2); break;
                }
                return Math.addExact(amount, time.until(end.toLocalTime(), unit));
            }
            ChronoLocalDate endDate = end.toLocalDate();
            if (end.toLocalTime().isBefore(time))
                endDate = endDate.minus(1, ChronoUnit.DAYS);

            return date.until(endDate, unit);
        }
        Objects.requireNonNull(unit, "unit");
        return unit.between(this, end);
    }

    //-----------------------------------------------------------------------

    @java.io.Serial
    private Object writeReplace() {
        return new Ser(Ser.CHRONO_LOCAL_DATE_TIME_TYPE, this);
    }

    @java.io.Serial
    private void readObject(ObjectInputStream s) throws InvalidObjectException {
        throw new InvalidObjectException("Deserialization via serialization delegate");
    }

    void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(date);
        out.writeObject(time);
    }

    static ChronoLocalDateTime<?> readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        ChronoLocalDate date = (ChronoLocalDate) in.readObject();
        LocalTime time = (LocalTime) in.readObject();
        return date.atTime(time);
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj instanceof ChronoLocalDateTime)
            return compareTo((ChronoLocalDateTime<?>) obj) == 0;
        return false;
    }

    @Override
    public int hashCode() {
        return toLocalDate().hashCode() ^ toLocalTime().hashCode();
    }

    @Override
    public String toString() {
        return toLocalDate().toString() + 'T' + toLocalTime().toString();
    }
}

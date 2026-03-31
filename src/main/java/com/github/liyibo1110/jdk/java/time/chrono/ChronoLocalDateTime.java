package com.github.liyibo1110.jdk.java.time.chrono;

import java.io.Serializable;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.ChronoLocalDateTimeImpl;
import java.time.chrono.ChronoZonedDateTime;
import java.time.chrono.Chronology;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.time.temporal.TemporalUnit;
import java.util.Comparator;
import java.util.Objects;

import static java.time.temporal.ChronoField.EPOCH_DAY;
import static java.time.temporal.ChronoField.NANO_OF_DAY;
import static java.time.temporal.ChronoUnit.FOREVER;
import static java.time.temporal.ChronoUnit.NANOS;

/**
 * 一个不带时区的日期时间对象，属于任意时间序列，专为高级全球化用例设计。
 * 大多数应用程序应将方法签名、字段和变量声明为LocalDateTime，而非此接口。
 *
 * ChronoLocalDateTime是本地日期时间的抽象表示，其中Chronology时间序列（或日历系统）是可插拔的。
 * 该日期时间通过TemporalField表示的字段来定义，其中最常见的实现定义在ChronoField中。时间序列定义了日历系统的运行方式以及标准字段的含义。
 *
 * 何时使用此接口
 * API的设计鼓励使用LocalDateTime而非此接口，即使在应用程序需要处理多个日历系统的情况下也是如此。其理由在ChronoLocalDate中进行了详细探讨。
 * 在使用此接口之前，请确保已阅读并理解ChronoLocalDate中的相关讨论
 *
 * @author liyibo
 * @date 2026-03-30 14:17
 */
public interface ChronoLocalDateTime<D extends ChronoLocalDate>
        extends Temporal, TemporalAdjuster, Comparable<ChronoLocalDateTime<?>> {

    static Comparator<ChronoLocalDateTime<?>> timeLineOrder() {
        return (Comparator<ChronoLocalDateTime<? extends ChronoLocalDate>> & Serializable) (dateTime1, dateTime2) -> {
            int cmp = Long.compare(dateTime1.toLocalDate().toEpochDay(), dateTime2.toLocalDate().toEpochDay());
            if (cmp == 0)
                cmp = Long.compare(dateTime1.toLocalTime().toNanoOfDay(), dateTime2.toLocalTime().toNanoOfDay());
            return cmp;
        };
    }

    //-----------------------------------------------------------------------

    static ChronoLocalDateTime<?> from(TemporalAccessor temporal) {
        if (temporal instanceof ChronoLocalDateTime)
            return (ChronoLocalDateTime<?>) temporal;

        Objects.requireNonNull(temporal, "temporal");
        Chronology chrono = temporal.query(TemporalQueries.chronology());
        if (chrono == null)
            throw new DateTimeException("Unable to obtain ChronoLocalDateTime from TemporalAccessor: " + temporal.getClass());
        return chrono.localDateTime(temporal);
    }

    //-----------------------------------------------------------------------

    default Chronology getChronology() {
        return toLocalDate().getChronology();
    }

    D toLocalDate();

    LocalTime toLocalTime();

    @Override
    boolean isSupported(TemporalField field);

    @Override
    default boolean isSupported(TemporalUnit unit) {
        if (unit instanceof ChronoUnit)
            return unit != FOREVER;
        return unit != null && unit.isSupportedBy(this);
    }

    //-----------------------------------------------------------------------

    @Override
    default ChronoLocalDateTime<D> with(TemporalAdjuster adjuster) {
        return ChronoLocalDateTimeImpl.ensureValid(getChronology(), Temporal.super.with(adjuster));
    }

    @Override
    ChronoLocalDateTime<D> with(TemporalField field, long newValue);

    @Override
    default ChronoLocalDateTime<D> plus(TemporalAmount amount) {
        return ChronoLocalDateTimeImpl.ensureValid(getChronology(), Temporal.super.plus(amount));
    }

    @Override
    ChronoLocalDateTime<D> plus(long amountToAdd, TemporalUnit unit);

    @Override
    default ChronoLocalDateTime<D> minus(TemporalAmount amount) {
        return ChronoLocalDateTimeImpl.ensureValid(getChronology(), Temporal.super.minus(amount));
    }

    @Override
    default ChronoLocalDateTime<D> minus(long amountToSubtract, TemporalUnit unit) {
        return ChronoLocalDateTimeImpl.ensureValid(getChronology(), Temporal.super.minus(amountToSubtract, unit));
    }

    //-----------------------------------------------------------------------

    @Override
    default <R> R query(TemporalQuery<R> query) {
        if (query == TemporalQueries.zoneId() || query == TemporalQueries.zone() || query == TemporalQueries.offset())
            return null;
        else if (query == TemporalQueries.localTime())
            return (R) toLocalTime();
        else if (query == TemporalQueries.chronology())
            return (R) getChronology();
        else if (query == TemporalQueries.precision())
            return (R) NANOS;
        // inline TemporalAccessor.super.query(query) as an optimization
        // non-JDK classes are not permitted to make this optimization
        return query.queryFrom(this);
    }

    @Override
    default Temporal adjustInto(Temporal temporal) {
        return temporal
                .with(EPOCH_DAY, toLocalDate().toEpochDay())
                .with(NANO_OF_DAY, toLocalTime().toNanoOfDay());
    }

    default String format(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.format(this);
    }

    //-----------------------------------------------------------------------

    ChronoZonedDateTime<D> atZone(ZoneId zone);

    //-----------------------------------------------------------------------

    default Instant toInstant(ZoneOffset offset) {
        return Instant.ofEpochSecond(toEpochSecond(offset), toLocalTime().getNano());
    }

    default long toEpochSecond(ZoneOffset offset) {
        Objects.requireNonNull(offset, "offset");
        long epochDay = toLocalDate().toEpochDay();
        long secs = epochDay * 86400 + toLocalTime().toSecondOfDay();
        secs -= offset.getTotalSeconds();
        return secs;
    }

    //-----------------------------------------------------------------------

    @Override
    default int compareTo(ChronoLocalDateTime<?> other) {
        int cmp = toLocalDate().compareTo(other.toLocalDate());
        if (cmp == 0) {
            cmp = toLocalTime().compareTo(other.toLocalTime());
            if (cmp == 0)
                cmp = getChronology().compareTo(other.getChronology());
        }
        return cmp;
    }

    default boolean isAfter(ChronoLocalDateTime<?> other) {
        long thisEpDay = this.toLocalDate().toEpochDay();
        long otherEpDay = other.toLocalDate().toEpochDay();
        return thisEpDay > otherEpDay ||
                (thisEpDay == otherEpDay && this.toLocalTime().toNanoOfDay() > other.toLocalTime().toNanoOfDay());
    }

    default boolean isBefore(ChronoLocalDateTime<?> other) {
        long thisEpDay = this.toLocalDate().toEpochDay();
        long otherEpDay = other.toLocalDate().toEpochDay();
        return thisEpDay < otherEpDay ||
                (thisEpDay == otherEpDay && this.toLocalTime().toNanoOfDay() < other.toLocalTime().toNanoOfDay());
    }

    default boolean isEqual(ChronoLocalDateTime<?> other) {
        // Do the time check first, it is cheaper than computing EPOCH day.
        return this.toLocalTime().toNanoOfDay() == other.toLocalTime().toNanoOfDay() &&
                this.toLocalDate().toEpochDay() == other.toLocalDate().toEpochDay();
    }

    @Override
    boolean equals(Object obj);

    @Override
    int hashCode();

    //-----------------------------------------------------------------------

    @Override
    String toString();
}

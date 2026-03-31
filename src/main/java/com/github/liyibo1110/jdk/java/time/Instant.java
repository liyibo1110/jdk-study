package com.github.liyibo1110.jdk.java.time;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import OffsetDateTime;
import java.time.Ser;
import java.time.ZoneId;
import java.time.ZoneOffset;
import ZonedDateTime;
import java.time.format.DateTimeFormatter;
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

import static java.time.LocalTime.NANOS_PER_SECOND;
import static java.time.LocalTime.SECONDS_PER_DAY;
import static java.time.LocalTime.SECONDS_PER_HOUR;
import static java.time.LocalTime.SECONDS_PER_MINUTE;
import static java.time.temporal.ChronoField.INSTANT_SECONDS;
import static java.time.temporal.ChronoField.MICRO_OF_SECOND;
import static java.time.temporal.ChronoField.MILLI_OF_SECOND;
import static java.time.temporal.ChronoField.NANO_OF_SECOND;
import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.NANOS;

/**
 * 表示UTC时间线上某一个精确的瞬间（即时间轴上的一个点），本质表达：从epoch（1970-01-01T00:00:00Z）开始，偏移了多少秒，再加上秒内的多少纳秒。
 *
 * 1、LocalDateTime -> Instant + ZoneId / ZoneOffset。
 * 2、Instant + ZoneId / ZoneOffset -> LocalDateTime
 * @author liyibo
 * @date 2026-03-30 15:03
 */
public final class Instant implements Temporal, TemporalAdjuster, Comparable<Instant>, Serializable {

    public static final Instant EPOCH = new Instant(0, 0);

    private static final long MIN_SECOND = -31557014167219200L;

    private static final long MAX_SECOND = 31556889864403199L;

    public static final Instant MIN = Instant.ofEpochSecond(MIN_SECOND, 0);

    public static final Instant MAX = Instant.ofEpochSecond(MAX_SECOND, 999_999_999);

    @java.io.Serial
    private static final long serialVersionUID = -665713676816604388L;

    /** 主要字段1：存储了对应的秒数部分 */
    private final long seconds;

    /** 主要字段1：存储了对应的纳秒数部分 */
    private final int nanos;

    //-----------------------------------------------------------------------

    public static Instant now() {
        return Clock.currentInstant();
    }

    public static Instant now(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return clock.instant();
    }

    //-----------------------------------------------------------------------

    public static Instant ofEpochSecond(long epochSecond) {
        return create(epochSecond, 0);
    }

    public static Instant ofEpochSecond(long epochSecond, long nanoAdjustment) {
        long secs = Math.addExact(epochSecond, Math.floorDiv(nanoAdjustment, NANOS_PER_SECOND));
        int nos = (int)Math.floorMod(nanoAdjustment, NANOS_PER_SECOND);
        return create(secs, nos);
    }

    public static Instant ofEpochMilli(long epochMilli) {
        long secs = Math.floorDiv(epochMilli, 1000);
        int mos = Math.floorMod(epochMilli, 1000);
        return create(secs, mos * 1000_000);
    }

    //-----------------------------------------------------------------------

    public static Instant from(TemporalAccessor temporal) {
        if (temporal instanceof Instant)
            return (Instant) temporal;

        Objects.requireNonNull(temporal, "temporal");
        try {
            long instantSecs = temporal.getLong(INSTANT_SECONDS);
            int nanoOfSecond = temporal.get(NANO_OF_SECOND);
            return Instant.ofEpochSecond(instantSecs, nanoOfSecond);
        } catch (DateTimeException ex) {
            throw new DateTimeException("Unable to obtain Instant from TemporalAccessor: " + temporal + " of type " + temporal.getClass().getName(), ex);
        }
    }

    //-----------------------------------------------------------------------

    public static Instant parse(final CharSequence text) {
        return DateTimeFormatter.ISO_INSTANT.parse(text, Instant::from);
    }

    //-----------------------------------------------------------------------

    private static Instant create(long seconds, int nanoOfSecond) {
        if ((seconds | nanoOfSecond) == 0)
            return EPOCH;
        if (seconds < MIN_SECOND || seconds > MAX_SECOND)
            throw new DateTimeException("Instant exceeds minimum or maximum instant");
        return new Instant(seconds, nanoOfSecond);
    }

    private Instant(long epochSecond, int nanos) {
        super();
        this.seconds = epochSecond;
        this.nanos = nanos;
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean isSupported(TemporalField field) {
        if (field instanceof ChronoField)
            return field == INSTANT_SECONDS || field == NANO_OF_SECOND || field == MICRO_OF_SECOND || field == MILLI_OF_SECOND;
        return field != null && field.isSupportedBy(this);
    }

    @Override
    public boolean isSupported(TemporalUnit unit) {
        if (unit instanceof ChronoUnit)
            return unit.isTimeBased() || unit == DAYS;
        return unit != null && unit.isSupportedBy(this);
    }

    //-----------------------------------------------------------------------

    @Override
    public ValueRange range(TemporalField field) {
        return Temporal.super.range(field);
    }

    @Override
    public int get(TemporalField field) {
        if (field instanceof ChronoField) {
            switch ((ChronoField) field) {
                case NANO_OF_SECOND: return nanos;
                case MICRO_OF_SECOND: return nanos / 1000;
                case MILLI_OF_SECOND: return nanos / 1000_000;
            }
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return range(field).checkValidIntValue(field.getFrom(this), field);
    }

    @Override
    public long getLong(TemporalField field) {
        if (field instanceof ChronoField) {
            switch ((ChronoField) field) {
                case NANO_OF_SECOND: return nanos;
                case MICRO_OF_SECOND: return nanos / 1000;
                case MILLI_OF_SECOND: return nanos / 1000_000;
                case INSTANT_SECONDS: return seconds;
            }
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.getFrom(this);
    }

    //-----------------------------------------------------------------------

    public long getEpochSecond() {
        return seconds;
    }

    public int getNano() {
        return nanos;
    }

    //-------------------------------------------------------------------------

    @Override
    public Instant with(TemporalAdjuster adjuster) {
        return (Instant) adjuster.adjustInto(this);
    }

    @Override
    public Instant with(TemporalField field, long newValue) {
        if (field instanceof ChronoField chronoField) {
            chronoField.checkValidValue(newValue);
            switch (chronoField) {
                case MILLI_OF_SECOND: {
                    int nval = (int) newValue * 1000_000;
                    return (nval != nanos ? create(seconds, nval) : this);
                }
                case MICRO_OF_SECOND: {
                    int nval = (int) newValue * 1000;
                    return (nval != nanos ? create(seconds, nval) : this);
                }
                case NANO_OF_SECOND: return (newValue != nanos ? create(seconds, (int) newValue) : this);
                case INSTANT_SECONDS: return (newValue != seconds ? create(newValue, nanos) : this);
            }
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.adjustInto(this, newValue);
    }

    //-----------------------------------------------------------------------

    public Instant truncatedTo(TemporalUnit unit) {
        if (unit == ChronoUnit.NANOS)
            return this;

        Duration unitDur = unit.getDuration();
        if (unitDur.getSeconds() > java.time.LocalTime.SECONDS_PER_DAY)
            throw new UnsupportedTemporalTypeException("Unit is too large to be used for truncation");

        long dur = unitDur.toNanos();
        if ((java.time.LocalTime.NANOS_PER_DAY % dur) != 0)
            throw new UnsupportedTemporalTypeException("Unit must divide into a standard day without remainder");

        long nod = (seconds % java.time.LocalTime.SECONDS_PER_DAY) * LocalTime.NANOS_PER_SECOND + nanos;
        long result = Math.floorDiv(nod, dur) * dur;
        return plusNanos(result - nod);
    }

    //-----------------------------------------------------------------------

    @Override
    public Instant plus(TemporalAmount amountToAdd) {
        return (Instant) amountToAdd.addTo(this);
    }

    @Override
    public Instant plus(long amountToAdd, TemporalUnit unit) {
        if (unit instanceof ChronoUnit) {
            switch ((ChronoUnit) unit) {
                case NANOS: return plusNanos(amountToAdd);
                case MICROS: return plus(amountToAdd / 1000_000, (amountToAdd % 1000_000) * 1000);
                case MILLIS: return plusMillis(amountToAdd);
                case SECONDS: return plusSeconds(amountToAdd);
                case MINUTES: return plusSeconds(Math.multiplyExact(amountToAdd, SECONDS_PER_MINUTE));
                case HOURS: return plusSeconds(Math.multiplyExact(amountToAdd, SECONDS_PER_HOUR));
                case HALF_DAYS: return plusSeconds(Math.multiplyExact(amountToAdd, SECONDS_PER_DAY / 2));
                case DAYS: return plusSeconds(Math.multiplyExact(amountToAdd, SECONDS_PER_DAY));
            }
            throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
        }
        return unit.addTo(this, amountToAdd);
    }

    //-----------------------------------------------------------------------

    public Instant plusSeconds(long secondsToAdd) {
        return plus(secondsToAdd, 0);
    }

    public Instant plusMillis(long millisToAdd) {
        return plus(millisToAdd / 1000, (millisToAdd % 1000) * 1000_000);
    }

    public Instant plusNanos(long nanosToAdd) {
        return plus(0, nanosToAdd);
    }

    private Instant plus(long secondsToAdd, long nanosToAdd) {
        if ((secondsToAdd | nanosToAdd) == 0) {
            return this;
        }
        long epochSec = Math.addExact(seconds, secondsToAdd);
        epochSec = Math.addExact(epochSec, nanosToAdd / NANOS_PER_SECOND);
        nanosToAdd = nanosToAdd % NANOS_PER_SECOND;
        long nanoAdjustment = nanos + nanosToAdd;  // safe int+NANOS_PER_SECOND
        return ofEpochSecond(epochSec, nanoAdjustment);
    }

    //-----------------------------------------------------------------------

    @Override
    public Instant minus(TemporalAmount amountToSubtract) {
        return (Instant) amountToSubtract.subtractFrom(this);
    }

    @Override
    public Instant minus(long amountToSubtract, TemporalUnit unit) {
        return (amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit) : plus(-amountToSubtract, unit));
    }

    //-----------------------------------------------------------------------

    public Instant minusSeconds(long secondsToSubtract) {
        if (secondsToSubtract == Long.MIN_VALUE)
            return plusSeconds(Long.MAX_VALUE).plusSeconds(1);
        return plusSeconds(-secondsToSubtract);
    }

    public Instant minusMillis(long millisToSubtract) {
        if (millisToSubtract == Long.MIN_VALUE)
            return plusMillis(Long.MAX_VALUE).plusMillis(1);
        return plusMillis(-millisToSubtract);
    }

    public Instant minusNanos(long nanosToSubtract) {
        if (nanosToSubtract == Long.MIN_VALUE)
            return plusNanos(Long.MAX_VALUE).plusNanos(1);
        return plusNanos(-nanosToSubtract);
    }

    //-------------------------------------------------------------------------

    @Override
    public <R> R query(TemporalQuery<R> query) {
        if (query == TemporalQueries.precision())
            return (R) NANOS;
        // inline TemporalAccessor.super.query(query) as an optimization
        if (query == TemporalQueries.chronology() || query == TemporalQueries.zoneId() ||
                query == TemporalQueries.zone() || query == TemporalQueries.offset() ||
                query == TemporalQueries.localDate() || query == TemporalQueries.localTime()) {
            return null;
        }
        return query.queryFrom(this);
    }

    @Override
    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(INSTANT_SECONDS, seconds).with(NANO_OF_SECOND, nanos);
    }

    @Override
    public long until(Temporal endExclusive, TemporalUnit unit) {
        Instant end = Instant.from(endExclusive);
        if (unit instanceof ChronoUnit chronoUnit) {
            switch (chronoUnit) {
                case NANOS: return nanosUntil(end);
                case MICROS: return nanosUntil(end) / 1000;
                case MILLIS: return Math.subtractExact(end.toEpochMilli(), toEpochMilli());
                case SECONDS: return secondsUntil(end);
                case MINUTES: return secondsUntil(end) / SECONDS_PER_MINUTE;
                case HOURS: return secondsUntil(end) / SECONDS_PER_HOUR;
                case HALF_DAYS: return secondsUntil(end) / (12 * SECONDS_PER_HOUR);
                case DAYS: return secondsUntil(end) / (SECONDS_PER_DAY);
            }
            throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
        }
        return unit.between(this, end);
    }

    private long nanosUntil(Instant end) {
        long secsDiff = Math.subtractExact(end.seconds, seconds);
        long totalNanos = Math.multiplyExact(secsDiff, NANOS_PER_SECOND);
        return Math.addExact(totalNanos, end.nanos - nanos);
    }

    private long secondsUntil(Instant end) {
        long secsDiff = Math.subtractExact(end.seconds, seconds);
        long nanosDiff = end.nanos - nanos;
        if (secsDiff > 0 && nanosDiff < 0)
            secsDiff--;
        else if (secsDiff < 0 && nanosDiff > 0)
            secsDiff++;
        return secsDiff;
    }

    //-----------------------------------------------------------------------

    public OffsetDateTime atOffset(ZoneOffset offset) {
        return OffsetDateTime.ofInstant(this, offset);
    }

    public ZonedDateTime atZone(ZoneId zone) {
        return ZonedDateTime.ofInstant(this, zone);
    }

    //-----------------------------------------------------------------------

    public long toEpochMilli() {
        if (seconds < 0 && nanos > 0) {
            long millis = Math.multiplyExact(seconds+1, 1000);
            long adjustment = nanos / 1000_000 - 1000;
            return Math.addExact(millis, adjustment);
        } else {
            long millis = Math.multiplyExact(seconds, 1000);
            return Math.addExact(millis, nanos / 1000_000);
        }
    }

    //-----------------------------------------------------------------------

    @Override
    public int compareTo(Instant otherInstant) {
        int cmp = Long.compare(seconds, otherInstant.seconds);
        if (cmp != 0)
            return cmp;
        return nanos - otherInstant.nanos;
    }

    public boolean isAfter(Instant otherInstant) {
        return compareTo(otherInstant) > 0;
    }

    public boolean isBefore(Instant otherInstant) {
        return compareTo(otherInstant) < 0;
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        return (other instanceof Instant otherInstant)
                && this.seconds == otherInstant.seconds
                && this.nanos == otherInstant.nanos;
    }

    @Override
    public int hashCode() {
        return ((int) (seconds ^ (seconds >>> 32))) + 51 * nanos;
    }

    //-----------------------------------------------------------------------

    @Override
    public String toString() {
        return DateTimeFormatter.ISO_INSTANT.format(this);
    }

    // -----------------------------------------------------------------------

    @java.io.Serial
    private Object writeReplace() {
        return new Ser(Ser.INSTANT_TYPE, this);
    }

    @java.io.Serial
    private void readObject(ObjectInputStream s) throws InvalidObjectException {
        throw new InvalidObjectException("Deserialization via serialization delegate");
    }

    void writeExternal(DataOutput out) throws IOException {
        out.writeLong(seconds);
        out.writeInt(nanos);
    }

    static Instant readExternal(DataInput in) throws IOException {
        long seconds = in.readLong();
        int nanos = in.readInt();
        return Instant.ofEpochSecond(seconds, nanos);
    }
}

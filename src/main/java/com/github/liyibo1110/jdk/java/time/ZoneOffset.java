package com.github.liyibo1110.jdk.java.time;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.DateTimeException;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.temporal.ValueRange;
import java.time.zone.ZoneRules;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 表示：相对于UTC的固定偏移量，例如：
 * 1、+08:00
 * 2、-05:00
 * 3、Z（相当于+00:00）
 * 用来计算特定offset下的本地时间。
 *
 * 本实现强调固定偏移，还有一种实现ZoneRegion，强调基于地区规则。
 * @author liyibo
 * @date 2026-03-30 15:39
 */
public final class ZoneOffset extends ZoneId
        implements TemporalAccessor, TemporalAdjuster, Comparable<ZoneOffset>, Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 2357656521762053153L;

    /** totalSeconds -> ZoneOffset */
    private static final ConcurrentMap<Integer, ZoneOffset> SECONDS_CACHE = new ConcurrentHashMap<>(16, 0.75f, 4);
    /** ID -> ZoneOffset */
    private static final ConcurrentMap<String, ZoneOffset> ID_CACHE = new ConcurrentHashMap<>(16, 0.75f, 4);

    private static final int MAX_SECONDS = 18 * LocalTime.SECONDS_PER_HOUR;

    public static final ZoneOffset UTC = ZoneOffset.ofTotalSeconds(0);

    public static final ZoneOffset MIN = ZoneOffset.ofTotalSeconds(-MAX_SECONDS);

    public static final ZoneOffset MAX = ZoneOffset.ofTotalSeconds(MAX_SECONDS);

    /** 实际存储的偏移单位是秒 */
    private final int totalSeconds;

    /**
     * Z - for UTC
     * +h
     * +hh
     * +hh:mm
     * -hh:mm
     * +hhmm
     * -hhmm
     * +hh:mm:ss
     * -hh:mm:ss
     * +hhmmss
     * -hhmmss
     */
    private final transient String id;

    //-----------------------------------------------------------------------

    public static ZoneOffset of(String offsetId) {
        Objects.requireNonNull(offsetId, "offsetId");
        // 先从cache找
        ZoneOffset offset = ID_CACHE.get(offsetId);
        if (offset != null)
            return offset;

        // parse - +h, +hh, +hhmm, +hh:mm, +hhmmss, +hh:mm:ss
        final int hours, minutes, seconds;
        switch (offsetId.length()) {
            case 2:
                offsetId = offsetId.charAt(0) + "0" + offsetId.charAt(1);  // fallthru
            case 3:
                hours = parseNumber(offsetId, 1, false);
                minutes = 0;
                seconds = 0;
                break;
            case 5:
                hours = parseNumber(offsetId, 1, false);
                minutes = parseNumber(offsetId, 3, false);
                seconds = 0;
                break;
            case 6:
                hours = parseNumber(offsetId, 1, false);
                minutes = parseNumber(offsetId, 4, true);
                seconds = 0;
                break;
            case 7:
                hours = parseNumber(offsetId, 1, false);
                minutes = parseNumber(offsetId, 3, false);
                seconds = parseNumber(offsetId, 5, false);
                break;
            case 9:
                hours = parseNumber(offsetId, 1, false);
                minutes = parseNumber(offsetId, 4, true);
                seconds = parseNumber(offsetId, 7, true);
                break;
            default:
                throw new DateTimeException("Invalid ID for ZoneOffset, invalid format: " + offsetId);
        }
        char first = offsetId.charAt(0);
        if (first != '+' && first != '-')
            throw new DateTimeException("Invalid ID for ZoneOffset, plus/minus not found when expected: " + offsetId);

        if (first == '-')
            return ofHoursMinutesSeconds(-hours, -minutes, -seconds);
        else
            return ofHoursMinutesSeconds(hours, minutes, seconds);
    }

    private static int parseNumber(CharSequence offsetId, int pos, boolean precededByColon) {
        if (precededByColon && offsetId.charAt(pos - 1) != ':')
            throw new DateTimeException("Invalid ID for ZoneOffset, colon not found when expected: " + offsetId);

        char ch1 = offsetId.charAt(pos);
        char ch2 = offsetId.charAt(pos + 1);
        if (ch1 < '0' || ch1 > '9' || ch2 < '0' || ch2 > '9')
            throw new DateTimeException("Invalid ID for ZoneOffset, non numeric characters found: " + offsetId);

        return (ch1 - 48) * 10 + (ch2 - 48);
    }

    //-----------------------------------------------------------------------

    public static ZoneOffset ofHours(int hours) {
        return ofHoursMinutesSeconds(hours, 0, 0);
    }

    public static ZoneOffset ofHoursMinutes(int hours, int minutes) {
        return ofHoursMinutesSeconds(hours, minutes, 0);
    }

    public static ZoneOffset ofHoursMinutesSeconds(int hours, int minutes, int seconds) {
        validate(hours, minutes, seconds);
        int totalSeconds = totalSeconds(hours, minutes, seconds);
        return ofTotalSeconds(totalSeconds);
    }

    //-----------------------------------------------------------------------

    public static ZoneOffset from(TemporalAccessor temporal) {
        Objects.requireNonNull(temporal, "temporal");
        ZoneOffset offset = temporal.query(TemporalQueries.offset());
        if (offset == null)
            throw new DateTimeException("Unable to obtain ZoneOffset from TemporalAccessor: " + temporal + " of type " + temporal.getClass().getName());
        return offset;
    }

    //-----------------------------------------------------------------------

    private static void validate(int hours, int minutes, int seconds) {
        if (hours < -18 || hours > 18)
            throw new DateTimeException("Zone offset hours not in valid range: value " + hours + " is not in the range -18 to 18");

        if (hours > 0) {
            if (minutes < 0 || seconds < 0)
                throw new DateTimeException("Zone offset minutes and seconds must be positive because hours is positive");
        } else if (hours < 0) {
            if (minutes > 0 || seconds > 0)
                throw new DateTimeException("Zone offset minutes and seconds must be negative because hours is negative");

        } else if ((minutes > 0 && seconds < 0) || (minutes < 0 && seconds > 0)) {
            throw new DateTimeException("Zone offset minutes and seconds must have the same sign");
        }
        if (minutes < -59 || minutes > 59)
            throw new DateTimeException("Zone offset minutes not in valid range: value " + minutes + " is not in the range -59 to 59");

        if (seconds < -59 || seconds > 59)
            throw new DateTimeException("Zone offset seconds not in valid range: value " + seconds + " is not in the range -59 to 59");

        if (Math.abs(hours) == 18 && (minutes | seconds) != 0)
            throw new DateTimeException("Zone offset not in valid range: -18:00 to +18:00");
    }

    private static int totalSeconds(int hours, int minutes, int seconds) {
        return hours * LocalTime.SECONDS_PER_HOUR + minutes * LocalTime.SECONDS_PER_MINUTE + seconds;
    }

    //-----------------------------------------------------------------------

    public static ZoneOffset ofTotalSeconds(int totalSeconds) {
        if (totalSeconds < -MAX_SECONDS || totalSeconds > MAX_SECONDS)
            throw new DateTimeException("Zone offset not in valid range: -18:00 to +18:00");

        if (totalSeconds % (15 * LocalTime.SECONDS_PER_MINUTE) == 0) {
            Integer totalSecs = totalSeconds;
            ZoneOffset result = SECONDS_CACHE.get(totalSecs);
            if (result == null) {   // 处理cache
                result = new ZoneOffset(totalSeconds);
                SECONDS_CACHE.putIfAbsent(totalSecs, result);
                result = SECONDS_CACHE.get(totalSecs);
                ID_CACHE.putIfAbsent(result.getId(), result);
            }
            return result;
        } else {
            return new ZoneOffset(totalSeconds);
        }
    }

    //-----------------------------------------------------------------------

    private ZoneOffset(int totalSeconds) {
        super();
        this.totalSeconds = totalSeconds;
        id = buildId(totalSeconds);
    }

    private static String buildId(int totalSeconds) {
        if (totalSeconds == 0) {
            return "Z";
        } else {
            int absTotalSeconds = Math.abs(totalSeconds);
            StringBuilder buf = new StringBuilder();
            int absHours = absTotalSeconds / LocalTime.SECONDS_PER_HOUR;
            int absMinutes = (absTotalSeconds / LocalTime.SECONDS_PER_MINUTE) % LocalTime.MINUTES_PER_HOUR;
            buf.append(totalSeconds < 0 ? "-" : "+")
                    .append(absHours < 10 ? "0" : "").append(absHours)
                    .append(absMinutes < 10 ? ":0" : ":").append(absMinutes);
            int absSeconds = absTotalSeconds % LocalTime.SECONDS_PER_MINUTE;
            if (absSeconds != 0)
                buf.append(absSeconds < 10 ? ":0" : ":").append(absSeconds);

            return buf.toString();
        }
    }

    //-----------------------------------------------------------------------

    public int getTotalSeconds() {
        return totalSeconds;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public ZoneRules getRules() {
        return ZoneRules.of(this);
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean isSupported(TemporalField field) {
        if (field instanceof ChronoField)
            return field == ChronoField.OFFSET_SECONDS;

        return field != null && field.isSupportedBy(this);
    }

    @Override
    public ValueRange range(TemporalField field) {
        return TemporalAccessor.super.range(field);
    }

    @Override
    public int get(TemporalField field) {
        if (field == ChronoField.OFFSET_SECONDS)
            return totalSeconds;
        else if (field instanceof ChronoField)
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        return range(field).checkValidIntValue(getLong(field), field);
    }

    @Override
    public long getLong(TemporalField field) {
        if (field == ChronoField.OFFSET_SECONDS)
            return totalSeconds;
        else if (field instanceof ChronoField)
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        return field.getFrom(this);
    }

    //-----------------------------------------------------------------------

    @Override
    public <R> R query(TemporalQuery<R> query) {
        if (query == TemporalQueries.offset() || query == TemporalQueries.zone())
            return (R) this;
        return TemporalAccessor.super.query(query);
    }

    @Override
    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.OFFSET_SECONDS, totalSeconds);
    }

    //-----------------------------------------------------------------------

    @Override
    public int compareTo(ZoneOffset other) {
        // abs(totalSeconds) <= MAX_SECONDS, so no overflow can happen here
        return other.totalSeconds - totalSeconds;
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj instanceof ZoneOffset)
            return totalSeconds == ((ZoneOffset) obj).totalSeconds;
        return false;
    }

    @Override
    public int hashCode() {
        return totalSeconds;
    }

    //-----------------------------------------------------------------------

    @Override
    public String toString() {
        return id;
    }

    // -----------------------------------------------------------------------

    @java.io.Serial
    private Object writeReplace() {
        return new Ser(Ser.ZONE_OFFSET_TYPE, this);
    }

    @java.io.Serial
    private void readObject(ObjectInputStream s) throws InvalidObjectException {
        throw new InvalidObjectException("Deserialization via serialization delegate");
    }

    @Override
    void write(DataOutput out) throws IOException {
        out.writeByte(Ser.ZONE_OFFSET_TYPE);
        writeExternal(out);
    }

    void writeExternal(DataOutput out) throws IOException {
        final int offsetSecs = totalSeconds;
        int offsetByte = offsetSecs % 900 == 0 ? offsetSecs / 900 : 127;  // compress to -72 to +72
        out.writeByte(offsetByte);
        if (offsetByte == 127)
            out.writeInt(offsetSecs);
    }

    static ZoneOffset readExternal(DataInput in) throws IOException {
        int offsetByte = in.readByte();
        return (offsetByte == 127 ? ZoneOffset.ofTotalSeconds(in.readInt()) : ZoneOffset.ofTotalSeconds(offsetByte * 900));
    }
}

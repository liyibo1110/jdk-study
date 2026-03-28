package com.github.liyibo1110.jdk.java.time.temporal;

import java.time.Duration;
import java.time.temporal.Temporal;

/**
 * 一组标准的日期时间单位。
 * 这组单位提供了基于单位的访问方式，用于操作日期、时间或日期时间。可以通过实现TemporalUnit来扩展这组标准单位。
 * 这些单位旨在适用于多种日历系统。例如大多数非ISO日历系统都定义了年、月和日的单位，只是规则略有不同。每个单位的文档都解释了其工作原理。
 * @author liyibo
 * @date 2026-03-28 15:38
 */
public enum ChronoUnit implements TemporalUnit {

    NANOS("Nanos", Duration.ofNanos(1)),
    MICROS("Micros", Duration.ofNanos(1000)),
    MILLIS("Millis", Duration.ofNanos(1000_000)),
    SECONDS("Seconds", Duration.ofSeconds(1)),
    MINUTES("Minutes", Duration.ofSeconds(60)),
    HOURS("Hours", Duration.ofSeconds(3600)),
    HALF_DAYS("HalfDays", Duration.ofSeconds(43200)),
    DAYS("Days", Duration.ofSeconds(86400)),
    WEEKS("Weeks", Duration.ofSeconds(7 * 86400L)),
    MONTHS("Months", Duration.ofSeconds(31556952L / 12)),   // 注意是平均每月秒数
    YEARS("Years", Duration.ofSeconds(31556952L)),
    DECADES("Decades", Duration.ofSeconds(31556952L * 10L)),
    CENTURIES("Centuries", Duration.ofSeconds(31556952L * 100L)),
    MILLENNIA("Millennia", Duration.ofSeconds(31556952L * 1000L)),
    ERAS("Eras", Duration.ofSeconds(31556952L * 1000_000_000L)),
    FOREVER("Forever", Duration.ofSeconds(Long.MAX_VALUE, 999_999_999));

    private final String name;
    private final Duration duration;

    private ChronoUnit(String name, Duration estimatedDuration) {
        this.name = name;
        this.duration = estimatedDuration;
    }

    @Override
    public Duration getDuration() {
        return duration;
    }

    /**
     * 检查该单位的持续时间是否为估计值。
     * 该类中的所有时间单位均被视为精确值，而所有日期单位均被视为估计值。
     * 此定义忽略闰秒，但考虑到了因夏令时而导致的“天”的长度变化，以及各月份长度的差异。
     */
    @Override
    public boolean isDurationEstimated() {
        return this.compareTo(DAYS) >= 0;
    }

    @Override
    public boolean isDateBased() {
        return this.compareTo(DAYS) >= 0 && this != FOREVER;
    }

    @Override
    public boolean isTimeBased() {
        return this.compareTo(DAYS) < 0;
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean isSupportedBy(Temporal temporal) {
        return temporal.isSupported(this);
    }

    @Override
    public <R extends Temporal> R addTo(R temporal, long amount) {
        return (R)temporal.plus(amount, this);
    }

    //-----------------------------------------------------------------------

    @Override
    public long between(Temporal temporal1Inclusive, Temporal temporal2Exclusive) {
        return temporal1Inclusive.until(temporal2Exclusive, this);
    }

    //-----------------------------------------------------------------------

    @Override
    public String toString() {
        return name;
    }
}

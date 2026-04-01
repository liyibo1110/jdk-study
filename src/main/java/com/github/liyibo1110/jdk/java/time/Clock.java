package com.github.liyibo1110.jdk.java.time;

import jdk.internal.misc.VM;

import java.io.ObjectStreamException;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

import static java.time.LocalTime.NANOS_PER_MILLI;
import static java.time.LocalTime.NANOS_PER_MINUTE;
import static java.time.LocalTime.NANOS_PER_SECOND;

/**
 * 一个提供当前时刻的时钟抽象，表达：你要从哪里获取“现在”这个时间，相当于是一个时间提供器，主要提供两个信息：
 * 1、当前时刻是什么：就是返回当前的Instant。
 * 2、这个时钟属于哪个时区：就是zone信息。
 * 因此不能将Clock设计成一个Supplier就完事了。
 * @author liyibo
 * @date 2026-03-31 14:22
 */
public abstract class Clock implements InstantSource {

    public static Clock systemUTC() {
        return SystemClock.UTC;
    }

    public static Clock systemDefaultZone() {
        return new SystemClock(ZoneId.systemDefault());
    }

    public static Clock system(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        if (zone == ZoneOffset.UTC)
            return SystemClock.UTC;
        return new SystemClock(zone);
    }

    //-----------------------------------------------------------------------

    public static Clock tickMillis(ZoneId zone) {
        return new Clock.TickClock(system(zone), LocalTime.NANOS_PER_MILLI);
    }

    //-------------------------------------------------------------------------

    public static Clock tickSeconds(ZoneId zone) {
        return new Clock.TickClock(system(zone), LocalTime.NANOS_PER_SECOND);
    }

    public static Clock tickMinutes(ZoneId zone) {
        return new Clock.TickClock(system(zone), LocalTime.NANOS_PER_MINUTE);
    }

    public static Clock tick(Clock baseClock, Duration tickDuration) {
        Objects.requireNonNull(baseClock, "baseClock");
        Objects.requireNonNull(tickDuration, "tickDuration");
        if (tickDuration.isNegative())
            throw new IllegalArgumentException("Tick duration must not be negative");

        long tickNanos = tickDuration.toNanos();
        if (tickNanos % 1000_000 == 0) {
            // ok, no fraction of millisecond
        } else if (1000_000_000 % tickNanos == 0) {
            // ok, divides into one second without remainder
        } else
            throw new IllegalArgumentException("Invalid tick duration");

        if (tickNanos <= 1)
            return baseClock;

        return new TickClock(baseClock, tickNanos);
    }

    //-----------------------------------------------------------------------

    public static Clock fixed(Instant fixedInstant, ZoneId zone) {
        Objects.requireNonNull(fixedInstant, "fixedInstant");
        Objects.requireNonNull(zone, "zone");
        return new Clock.FixedClock(fixedInstant, zone);
    }

    //-------------------------------------------------------------------------

    public static Clock offset(Clock baseClock, Duration offsetDuration) {
        Objects.requireNonNull(baseClock, "baseClock");
        Objects.requireNonNull(offsetDuration, "offsetDuration");
        if (offsetDuration.equals(Duration.ZERO))
            return baseClock;
        return new OffsetClock(baseClock, offsetDuration);
    }

    //-----------------------------------------------------------------------

    protected Clock() {}

    //-----------------------------------------------------------------------

    public abstract ZoneId getZone();

    @Override
    public abstract Clock withZone(ZoneId zone);

    //-------------------------------------------------------------------------

    @Override
    public long millis() {
        return instant().toEpochMilli();
    }

    //-----------------------------------------------------------------------

    @Override
    public abstract Instant instant();

    //-----------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj);
    }

    @Override
    public  int hashCode() {
        return super.hashCode();
    }

    //-----------------------------------------------------------------------

    // initial offset
    private static final long OFFSET_SEED = System.currentTimeMillis() / 1000 - 1024;

    private static long offset = OFFSET_SEED;

    static Instant currentInstant() {
        // Take a local copy of offset. offset can be updated concurrently
        // by other threads (even if we haven't made it volatile) so we will
        // work with a local copy.
        long localOffset = offset;
        long adjustment = VM.getNanoTimeAdjustment(localOffset);

        if (adjustment == -1) {
            // -1 is a sentinel value returned by VM.getNanoTimeAdjustment
            // when the offset it is given is too far off the current UTC
            // time. In principle, this should not happen unless the
            // JVM has run for more than ~136 years (not likely) or
            // someone is fiddling with the system time, or the offset is
            // by chance at 1ns in the future (very unlikely).
            // We can easily recover from all these conditions by bringing
            // back the offset in range and retry.

            // bring back the offset in range. We use -1024 to make
            // it more unlikely to hit the 1ns in the future condition.
            localOffset = System.currentTimeMillis() / 1000 - 1024;

            // retry
            adjustment = VM.getNanoTimeAdjustment(localOffset);

            if (adjustment == -1) {
                // Should not happen: we just recomputed a new offset.
                // It should have fixed the issue.
                throw new InternalError("Offset " + localOffset + " is not in range");
            } else {
                // OK - recovery succeeded. Update the offset for the
                // next call...
                offset = localOffset;
            }
        }
        return Instant.ofEpochSecond(localOffset, adjustment);
    }

    //-----------------------------------------------------------------------

    /**
     * 一个即时源，它始终返回来自System.currentTimeMillis()或等效方法的最新时间。
     */
    static final class SystemInstantSource implements InstantSource, Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 3232399674412L;

        static final SystemInstantSource INSTANCE = new SystemInstantSource();

        SystemInstantSource() {}

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.system(zone);
        }

        @Override
        public long millis() {
            // System.currentTimeMillis() and VM.getNanoTimeAdjustment(offset)
            // use the same time source - System.currentTimeMillis() simply
            // limits the resolution to milliseconds.
            // So we take the faster path and call System.currentTimeMillis()
            // directly - in order to avoid the performance penalty of
            // VM.getNanoTimeAdjustment(offset) which is less efficient.
            return System.currentTimeMillis();
        }

        @Override
        public Instant instant() {
            return currentInstant();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof SystemInstantSource;
        }

        @Override
        public int hashCode() {
            return SystemInstantSource.class.hashCode();
        }

        @Override
        public String toString() {
            return "SystemInstantSource";
        }

        @java.io.Serial
        private Object readResolve() throws ObjectStreamException {
            return SystemInstantSource.INSTANCE;
        }
    }

    //-----------------------------------------------------------------------

    /**
     * 实现一个时钟，该时钟始终返回SystemInstantSource.INSTANCE中的最新时间。
     */
    static final class SystemClock extends Clock implements Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 6740630888130243051L;

        static final SystemClock UTC = new SystemClock(ZoneOffset.UTC);

        private final ZoneId zone;

        SystemClock(ZoneId zone) {
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (zone.equals(this.zone))  // intentional NPE
                return this;
            return new SystemClock(zone);
        }

        @Override
        public long millis() {
            // inline of SystemInstantSource.INSTANCE.millis()
            return System.currentTimeMillis();
        }

        @Override
        public Instant instant() {
            // inline of SystemInstantSource.INSTANCE.instant()
            return currentInstant();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof SystemClock)
                return zone.equals(((SystemClock) obj).zone);
            return false;
        }

        @Override
        public int hashCode() {
            return zone.hashCode() + 1;
        }

        @Override
        public String toString() {
            return "SystemClock[" + zone + "]";
        }
    }

    //-----------------------------------------------------------------------

    /**
     * 实现一个始终返回同一时刻的时钟。这通常用于测试。
     */
    static final class FixedClock extends Clock implements Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 7430389292664866958L;

        private final Instant instant;

        private final ZoneId zone;

        FixedClock(Instant fixedInstant, ZoneId zone) {
            this.instant = fixedInstant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (zone.equals(this.zone))  // intentional NPE
                return this;
            return new FixedClock(instant, zone);
        }

        @Override
        public long millis() {
            return instant.toEpochMilli();
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof FixedClock other
                    && instant.equals(other.instant)
                    && zone.equals(other.zone);
        }

        @Override
        public int hashCode() {
            return instant.hashCode() ^ zone.hashCode();
        }

        @Override
        public String toString() {
            return "FixedClock[" + instant + "," + zone + "]";
        }
    }

    //-----------------------------------------------------------------------

    /**
     * 会在底层时钟上添加偏移量的时钟实现。
     * 类似装饰器模式。
     */
    static final class OffsetClock extends Clock implements Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 2007484719125426256L;

        private final Clock baseClock;

        private final Duration offset;

        OffsetClock(Clock baseClock, Duration offset) {
            this.baseClock = baseClock;
            this.offset = offset;
        }

        @Override
        public ZoneId getZone() {
            return baseClock.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (zone.equals(baseClock.getZone()))  // intentional NPE
                return this;
            return new Clock.OffsetClock(baseClock.withZone(zone), offset);
        }

        @Override
        public long millis() {
            return Math.addExact(baseClock.millis(), offset.toMillis());
        }

        @Override
        public Instant instant() {
            return baseClock.instant().plus(offset);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof OffsetClock other
                    && baseClock.equals(other.baseClock)
                    && offset.equals(other.offset);
        }

        @Override
        public int hashCode() {
            return baseClock.hashCode() ^ offset.hashCode();
        }

        @Override
        public String toString() {
            return "OffsetClock[" + baseClock + "," + offset + "]";
        }
    }

    //-----------------------------------------------------------------------

    /**
     * 一种可降低底层时钟时钟频率的时钟实现。
     */
    static final class TickClock extends Clock implements Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 6504659149906368850L;

        private final Clock baseClock;

        private final long tickNanos;

        TickClock(Clock baseClock, long tickNanos) {
            this.baseClock = baseClock;
            this.tickNanos = tickNanos;
        }

        @Override
        public ZoneId getZone() {
            return baseClock.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (zone.equals(baseClock.getZone()))  // intentional NPE
                return this;
            return new TickClock(baseClock.withZone(zone), tickNanos);
        }

        @Override
        public long millis() {
            long millis = baseClock.millis();
            return millis - Math.floorMod(millis, tickNanos / 1000_000L);
        }

        @Override
        public Instant instant() {
            if ((tickNanos % 1000_000) == 0) {
                long millis = baseClock.millis();
                return Instant.ofEpochMilli(millis - Math.floorMod(millis, tickNanos / 1000_000L));
            }
            Instant instant = baseClock.instant();
            long nanos = instant.getNano();
            long adjust = Math.floorMod(nanos, tickNanos);
            return instant.minusNanos(adjust);
        }

        @Override
        public boolean equals(Object obj) {
            return (obj instanceof TickClock other)
                    && tickNanos == other.tickNanos
                    && baseClock.equals(other.baseClock);
        }

        @Override
        public int hashCode() {
            return baseClock.hashCode() ^ ((int) (tickNanos ^ (tickNanos >>> 32)));
        }

        @Override
        public String toString() {
            return "TickClock[" + baseClock + "," + Duration.ofNanos(tickNanos) + "]";
        }
    }

    //-----------------------------------------------------------------------

    /**
     * 基于InstantSource的时钟实现。
     */
    static final class SourceClock extends Clock implements Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 235386528762398L;

        private final InstantSource baseSource;

        private final ZoneId zone;

        SourceClock(InstantSource baseSource, ZoneId zone) {
            this.baseSource = baseSource;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (zone.equals(this.zone))  // intentional NPE
                return this;
            return new SourceClock(baseSource, zone);
        }

        @Override
        public long millis() {
            return baseSource.millis();
        }

        @Override
        public Instant instant() {
            return baseSource.instant();
        }

        @Override
        public boolean equals(Object obj) {
            return (obj instanceof SourceClock other)
                    && zone.equals(other.zone)
                    && baseSource.equals(other.baseSource);
        }

        @Override
        public int hashCode() {
            return baseSource.hashCode() ^ zone.hashCode();
        }

        @Override
        public String toString() {
            return "SourceClock[" + baseSource + "," + zone + "]";
        }
    }
}

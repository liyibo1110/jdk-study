package com.github.liyibo1110.jdk.java.time.chrono;

import java.time.chrono.ChronoLocalDate;
import java.time.chrono.Chronology;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalUnit;
import java.util.List;
import java.util.Objects;

/**
 * 框架级接口，用于定义时间量，例如“6小时”、“8天”或“2年3个月”。
 *
 * 这是时间量的基础接口类型。时间量与日期或具体时间的不同之处在于，它不与时间轴上的任何特定点相关联。
 * 该时间量可视为一个将TemporalUnit映射到long类型的Map，通过getUnits()和get(TemporalUnit)方法进行访问。简单情况下可能仅包含一个单位-值对，例如“6小时”。更复杂的情况下可能包含多个单位-值对，例如“7年3个月5天”。
 * 有两种常见的实现方式。Period是基于日期的实现，存储年、月和日。Duration是基于时间的实现，存储秒和纳秒，但同时也提供基于其他时间单位（如分钟、小时和固定的24小时制天）的访问方式。
 * 该接口属于框架级接口，不应在应用程序代码中广泛使用。相反，应用程序应创建并传递具体类型的实例，例如Period和Duration。
 * @author liyibo
 * @date 2026-03-30 13:40
 */
public interface ChronoPeriod extends TemporalAmount {

    static ChronoPeriod between(ChronoLocalDate startDateInclusive, ChronoLocalDate endDateExclusive) {
        Objects.requireNonNull(startDateInclusive, "startDateInclusive");
        Objects.requireNonNull(endDateExclusive, "endDateExclusive");
        return startDateInclusive.until(endDateExclusive);
    }

    //-----------------------------------------------------------------------

    @Override
    long get(TemporalUnit unit);

    @Override
    List<TemporalUnit> getUnits();

    Chronology getChronology();

    //-----------------------------------------------------------------------

    default boolean isZero() {
        for (TemporalUnit unit : getUnits()) {
            if (get(unit) != 0)
                return false;
        }
        return true;
    }

    default boolean isNegative() {
        for (TemporalUnit unit : getUnits()) {
            if (get(unit) < 0)
                return true;
        }
        return false;
    }

    //-----------------------------------------------------------------------

    ChronoPeriod plus(TemporalAmount amountToAdd);

    ChronoPeriod minus(TemporalAmount amountToSubtract);

    //-----------------------------------------------------------------------

    ChronoPeriod multipliedBy(int scalar);

    default ChronoPeriod negated() {
        return multipliedBy(-1);
    }

    //-----------------------------------------------------------------------

    ChronoPeriod normalized();

    //-------------------------------------------------------------------------

    @Override
    Temporal addTo(Temporal temporal);

    @Override
    Temporal subtractFrom(Temporal temporal);

    //-----------------------------------------------------------------------

    @Override
    boolean equals(Object obj);

    @Override
    int hashCode();

    //-----------------------------------------------------------------------

    @Override
    String toString();
}

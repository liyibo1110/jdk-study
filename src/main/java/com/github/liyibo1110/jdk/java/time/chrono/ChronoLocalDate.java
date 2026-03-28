package com.github.liyibo1110.jdk.java.time.chrono;

import com.github.liyibo1110.jdk.java.time.temporal.Temporal;
import com.github.liyibo1110.jdk.java.time.temporal.TemporalAdjuster;

import java.io.Serializable;
import java.time.DateTimeException;
import java.time.chrono.Chronology;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.Comparator;
import java.util.Objects;

/**
 * 比Temporal更为具体，专门用来表达：某种历法系统下的日期对象：
 * 1、ISO日期：LocalDate。
 * 2、日本历法日期：JapaneseDate。
 * 3、民国历法日期：类似别的chronology实现
 * 因此ChronoLocalDate属于更高层的日期语义抽象，目的是：让java.time不仅仅能支持ISO公历，还能给别的历法留下统一模型。
 * @author liyibo
 * @date 2026-03-27 17:10
 */
public interface ChronoLocalDate extends Temporal, TemporalAdjuster, Comparable<ChronoLocalDate> {

    /**
     * 以toEpochDay的结果来排序
     */
    static Comparator<ChronoLocalDate> timeLineOrder() {
        return (Comparator<ChronoLocalDate> & Serializable) (date1, date2) -> {
            return Long.compare(date1.toEpochDay(), date2.toEpochDay());
        };
    }

    /**
     * TemporalAccessor -> ChronoLocalDate
     */
    static ChronoLocalDate from(TemporalAccessor temporal) {
        if(temporal instanceof ChronoLocalDate)
            return (ChronoLocalDate) temporal;

        Objects.requireNonNull(temporal, "temporal");
        Chronology chrono = temporal.query(TemporalQueries.chronology());
        if(chrono == null)
            throw new DateTimeException("Unable to obtain ChronoLocalDate from TemporalAccessor: " + temporal.getClass());
        return chrono.date(temporal);
    }
}

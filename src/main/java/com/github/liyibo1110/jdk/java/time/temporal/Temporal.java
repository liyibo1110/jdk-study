package com.github.liyibo1110.jdk.java.time.temporal;

import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalUnit;

/**
 * 在TemporalAccessor接口的读取功能基础上，还能被调整和加减：
 * 1、with(...)：将某个字段修改成指定值。
 * 2、plus(...)：加一段时间。
 * 3、minus(...)：减一段时间。
 * 4、until(...)：计算两个时间对象之间差多少。
 * 注意大多数方法都是返回新的对象，即具有不可变性。
 * @author liyibo
 * @date 2026-03-27 16:00
 */
public interface Temporal extends TemporalAccessor {

    /**
     * 是否支持某个时间单位的计算，例如LocalDate：
     * 1、支持DAYS。
     * 2、支持MONTHS。
     * 3、支持YEARS。
     * 4、不支持HOURS。
     */
    boolean isSupported(TemporalUnit unit);

    /**
     * 用给定的adjuster对象，来调整当前对象，例如：
     * 1、调成月末。
     * 2、调成下个周一。
     * 3、调成某个指定日期。
     */
    default Temporal with(TemporalAdjuster adjuster) {
        return adjuster.adjustInto(this);
    }

    /**
     * 直接把某个字段改成新值，例如：
     * 1、year改成2027。
     * 2、day-of-month改成1。
     */
    Temporal with(TemporalField field, long newValue);

    default Temporal plus(TemporalAmount amount) {
        return amount.addTo(this);
    }
}

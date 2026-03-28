package com.github.liyibo1110.jdk.java.time.temporal;

import java.time.temporal.Temporal;
import java.time.temporal.TemporalUnit;
import java.util.List;

/**
 * 框架级接口，用于定义时间量，例如“6小时”、“8天”或“2年3个月”。
 * 这是时间量的基础接口类型。时间量与日期或具体时间的不同之处在于，它不与时间轴上的任何特定点相关联。
 *
 * 该时间量可视为一个将TemporalUnit映射到long类型的 Map，通过getUnits()和get(TemporalUnit)方法进行访问。
 * 简单情况下可能仅包含一个单位-值对，例如“6小时”。更复杂的情况下可能包含多个单位-值对，例如“7年3个月5天”。
 *
 * 有两种常见的实现方式。
 * Period是基于日期的实现，存储年、月和日。
 * Duration是基于时间的实现，存储秒和纳秒，但同时也提供基于其他时间单位（如分钟、小时和固定的 24 小时制天）的访问方式。
 *
 * 该接口属于框架级接口，不应在应用程序代码中广泛使用。相反，应用程序应创建并传递具体类型的实例，例如Period和Duration。
 * @author liyibo
 * @date 2026-03-27 18:16
 */
public interface TemporalAmount {

    /**
     * 返回所请求单元的值。
     * getUnits()返回的单元唯一地定义了TemporalAmount的值。对于getUnits中列出的每个单元，都必须返回一个值。
     */
    long get(TemporalUnit unit);

    /**
     * 返回唯一定义该TemporalAmount值的单位列表。该TemporalUnits列表由实现类定义。
     * 该列表是调用getUnits时单位状态的快照，且不可变。这些单位按持续时间从长到短排序。
     */
    List<TemporalUnit> getUnits();

    /**
     * 向指定的时间对象添加时间量。
     * 使用实现类中封装的逻辑，将指定时间量添加到指定的时间对象中。
     * 使用此方法有两种等效的方式。第一种是直接调用此方法。第二种是使用Temporal.plus(TemporalAmount)：
     * // 这两行代码效果相同，但建议采用第二种方法
     * dateTime = amount.addTo(dateTime);
     * dateTime = dateTime.plus(adder);
     * 建议使用第二种方法plus(TemporalAmount)，因为它在代码中更易于阅读。
     */
    Temporal addTo(Temporal temporal);

    /**
     * 从指定的时间对象中减去此对象。
     * 使用实现类中封装的逻辑，从指定的时间对象中减去该时间量。
     * 使用此方法有两种等效的方式。第一种是直接调用此方法。第二种是使用Temporal.minus(TemporalAmount)：
     * // 这两行代码效果等同，但建议采用第二种方法
     * dateTime = amount.subtractFrom(dateTime);
     * dateTime = dateTime.minus(amount);
     * 建议使用第二种方法 minus(TemporalAmount)，因为它在代码中更易于阅读。
     */
    Temporal subtractFrom(Temporal temporal);
}

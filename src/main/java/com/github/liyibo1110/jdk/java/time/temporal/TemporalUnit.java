package com.github.liyibo1110.jdk.java.time.temporal;

import java.time.Duration;
import java.time.LocalTime;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.ChronoLocalDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.time.temporal.Temporal;
import java.time.temporal.UnsupportedTemporalTypeException;

/**
 * 日期时间单位，例如“天”或“小时”。
 * 时间计量基于各种单位，例如年、月、日、时、分和秒。该接口的实现类代表这些单位。
 *
 * 该接口的实例代表单位本身，而非该单位的数量。有关以常用单位表示数量的类，请参阅Period。
 * 最常用的单位在ChronoUnit中定义。IsoFields提供了更多单位。应用程序代码也可通过实现此接口来定义单位。
 *
 * 该单位采用双重分派机制工作。客户端代码调用LocalDateTime等日期时间对象的方法时，会检查该单位是否属于ChronoUnit。
 * 如果是，则日期时间对象必须处理该单位；否则，方法调用将重新分派至此接口中对应的方法。
 *
 * @author liyibo
 * @date 2026-03-27 17:53
 */
public interface TemporalUnit {

    /**
     * 返回时间单位对应的Duration对象
     */
    Duration getDuration();

    /**
     * 检查该单位的持续时间是否为估计值。
     * 所有单位都有持续时间，但该持续时间并不总是精确的。例如由于夏令时可能发生变化，天数具有估计值。
     * 如果持续时间为估计值，此方法返回true。
     * 如果为精确值则返回false。
     * 请注意accurate/estimated忽略闰秒。
     */
    boolean isDurationEstimated();

    /**
     * 检查该单位是否表示日期的一部分。
     * 如果一个日期能够用来推导出日期相关的含义，则该日期是基于时间的。其持续时间必须是标准日长度的整数倍。
     * 请注意isDateBased()和isTimeBased()返回false都是有效的，例如当表示36小时这样的单位时。
     */
    boolean isDateBased();

    /**
     * 检查该单位是否代表时间的组成部分。
     * 如果一个单位可以用来从时间中推导出含义，则该单位是基于时间的。它的持续时间必须能被标准日长整除且无余。
     * 请注意isDateBased()和isTimeBased()返回false都是有效的，例如当表示36小时这样的单位时。
     */
    boolean isTimeBased();

    /**
     * 检查指定时间对象是否支持该单位。
     * 此方法用于验证实现该时间对象的日期时间类型能否对该单位进行加减运算。这可用于避免抛出异常。
     * 此默认实现通过Temporal.plus(long, TemporalUnit)方法推导出该值。
     */
    default boolean isSupportedBy(Temporal temporal) {
        if(temporal instanceof LocalTime)
            return isTimeBased();
        if(temporal instanceof ChronoLocalDate)
            return isDateBased();
        if(temporal instanceof ChronoLocalDateTime || temporal instanceof ChronoZonedDateTime)
            return true;

        try {
            temporal.plus(1, this);
            return true;
        } catch (UnsupportedTemporalTypeException ex) {
            return false;
        } catch (RuntimeException ex) {
            try {
                temporal.plus(-1, this);
                return true;
            } catch (RuntimeException ex2) {
                return false;
            }
        }
    }

    /**
     * 返回指定时间对象的副本，并添加指定的时间间隔。
     * 添加的时间间隔是该单位的倍数。例如可以通过在表示“天”的实例上调用此方法，并传入日期和时间间隔“3”，来将“3天”添加到某个日期上。要添加的时间间隔可以是负数，这相当于减法。
     * 使用此方法有两种等效的方式。第一种是直接调用此方法。第二种是使用Temporal.plus(long, TemporalUnit)：
     * // 这两行代码等效，但建议采用第二种方法
     * temporal = thisUnit.addTo(temporal);
     * temporal = temporal.plus(thisUnit);
     *
     * 建议使用第二种方法plus(TemporalUnit)，因为它在代码中更易于阅读。
     * 实现应使用ChronoUnit中提供的单位或ChronoField中提供的字段来执行任何查询或计算。
     * 如果该单位不受支持，则必须抛出UnsupportedTemporalTypeException。
     * 实现不得修改指定的时间对象。相反，必须返回原始对象的调整后的副本。这为不可变和可变实现提供了等效且安全的行为。
     */
    <R extends Temporal> R addTo(R temporal, long amount);

    /**
     * 计算两个时间对象之间的时间间隔。
     * 此函数以该单位为基准计算时间间隔。起始点和终点作为时间对象提供，且必须是兼容的类型。
     * 在计算时间间隔之前，实现会将第二种类型转换为第一种类型的实例。如果终点时间早于起始时间，则结果为负数。
     * 例如可以使用HOURS.between(startTime, endTime)计算两个时间对象之间的时间差（以小时为单位）。
     *
     * 计算结果返回一个整数，表示两个时间对象之间完整的单位数。例如11:30与13:29之间的时间差仅为一小时，因为距离两小时还差一分钟。
     * 使用此方法有两种等效的方式。第一种是直接调用此方法。第二种是使用Temporal.until(Temporal, TemporalUnit)：
     * // 这两行是等效的
     * between = thisUnit.between(start, end);
     * between = start.until(end, thisUnit);
     *
     * 应根据哪种写法能使代码更易读来决定。
     * 例如，此方法可用于计算两个日期之间的天数：
     * long daysBetween = DAYS.between(start, end);
     * // 或者
     * long daysBetween = start.until(end, DAYS);
     *
     * 实现应使用ChronoUnit中提供的单位或ChronoField中提供的字段来执行任何查询或计算。如果该单位不受支持，则必须抛出UnsupportedTemporalTypeException。实现不得更改指定的时序对象。
     */
    long between(Temporal temporal1Inclusive, Temporal temporal2Exclusive);

    @Override
    String toString();
}

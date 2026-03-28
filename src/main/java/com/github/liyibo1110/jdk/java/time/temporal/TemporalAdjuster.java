package com.github.liyibo1110.jdk.java.time.temporal;

/**
 * 和Temporal接口相比较，不同之处在于这个实现类可以拿自己去调整其它时间对象，即自己作为调整规则，来应用到其它Temporal上（adjustInto方法）
 * 这个接口不在TemporalAccessor这组继承结构上，和Temporal等接口有间接关系。
 * @author liyibo
 * @date 2026-03-27 16:01
 */
@FunctionalInterface
public interface TemporalAdjuster {

    /**
     * 调整指定的时间对象。
     * 此方法使用实现类中封装的逻辑来调整指定的时间对象。例如，一个调整器可以设置避开周末的日期，或者将日期设为当月的最后一天。
     *
     * 使用此方法有两种等效的方式。第一种是直接调用此方法。第二种是使用Temporal.with(TemporalAdjuster)：
     * // 这两行代码等效，但建议采用第二种方法
     * temporal = thisAdjuster.adjustInto(temporal);
     * temporal = temporal.with(thisAdjuster);
     *
     * 建议使用第二种方法with(TemporalAdjuster)，因为它在代码中更清晰易读。
     */
    Temporal adjustInto(Temporal temporal);
}

package com.github.liyibo1110.jdk.java.time.temporal;

import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 日期时间字段，例如年中的月份或小时中的分钟。
 * 日期和时间通过将时间线划分为对人类有意义的段落来表示。该接口的实现体代表了这些字段。
 * 最常用的单位在ChronoField中定义。IsoFields、WeekFields和JulianFields中提供了更多字段。应用程序代码也可以通过实现该接口来定义字段。
 *
 * 该字段通过双重分派机制工作。客户端代码调用LocalDateTime等日期时间对象的方法时，会检查该字段是否为ChronoField。
 * 如果是，则日期时间对象必须处理该字段；否则，方法调用将重新分派至该接口中对应的方法。
 *
 * @author liyibo
 * @date 2026-03-27 15:58
 */
public interface TemporalField {

    /**
     * 获取该字段在请求的Locale中的显示名称。
     * 如果该区域设置没有显示名称，则必须返回一个合适的默认值。
     * 默认实现必须检查Locale是否为空，并返回toString()。
     */
    default String getDisplayName(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        return toString();
    }

    /**
     * 获取字段的TemporalUnit。
     * 字段的单位是该字段在指定范围内变化的周期。例如，在“MonthOfYear”字段中，单位是“Months”。另请参阅getRangeUnit()
     */
    TemporalUnit getBaseUnit();

    /**
     * 获取字段的取值范围。
     * 字段的取值范围是指该字段的数值变化区间。例如，在“MonthOfYear”字段中，其取值范围即为“Years”。另请参阅getBaseUnit()。
     * 取值范围绝不会为空。例如“Year”字段是“YearOfForever”的简写形式。因此，它的单位为“Years”，取值范围为“Forever”。
     */
    TemporalUnit getRangeUnit();

    /**
     * 获取该字段的有效值范围。
     * 所有字段均可表示为长整型。此方法返回一个描述该值有效范围的对象。此方法通常仅适用于ISO-8601日历系统。
     * 请注意，结果仅描述了有效的最小值和最大值，切勿过度解读这些值。例如该范围内的某些值可能对该字段而言是无效的。
     */
    ValueRange range();

    //-----------------------------------------------------------------------

    /**
     * 检查该字段是否表示日期的组成部分。
     * 如果一个字段可以从EPOCH_DAY推导出来，则该字段是基于日期的。
     * 请注意isDateBased()和isTimeBased()返回false都是有效的，例如当表示“每周第几分钟”这样的字段时。
     */
    boolean isDateBased();

    /**
     * 检查该字段是否表示时间的一个组成部分。
     * 如果一个字段可以从NANO_OF_DAY推导出来，则该字段是基于时间的。
     * 请注意isDateBased()和isTimeBased()返回false都是有效的，例如当表示“一周中的第几分钟”这样的字段时。
     */
    boolean isTimeBased();

    //-----------------------------------------------------------------------

    /**
     * 检查该字段是否受时间对象支持。
     * 这决定了时间访问器是否支持该字段。如果返回 false，则无法查询该时间对象的该字段。
     * 使用此方法有两种等效的方式。第一种是直接调用此方法。第二种是使用TemporalAccessor.isSupported(TemporalField)：
     *
     * // 这两行代码等效，但建议采用第二种方法
     * temporal = thisField.isSupportedBy(temporal);
     * temporal = temporal.isSupported(thisField);
     *
     * 建议使用第二种方法isSupported(TemporalField)，因为它在代码中更清晰易读。
     * 实现类应通过ChronoField中提供的字段来判断是否支持。
     */
    boolean isSupportedBy(TemporalAccessor temporal);

    /**
     * 使用时间对象来优化结果，获取该字段的有效值范围。
     * 此方法利用时间对象查找该字段的有效值范围。它与range()类似，但会利用时间对象来优化结果。
     * 例如如果字段为 DAY_OF_MONTH，range()方法则不够精确，因为月份长度可能有28、29、30和31天四种情况。
     * 若将此方法与日期结合使用，则可确保范围的准确性，仅返回上述四种选项中的一种。
     *
     * 使用此方法有两种等效的方式。第一种是直接调用此方法。第二种是使用TemporalAccessor.range(TemporalField)：
     * // 这两行代码效果相同，但建议采用第二种方法
     * temporal = thisField.rangeRefinedBy(temporal);
     * temporal = temporal.range(thisField);
     *
     * 建议使用第二种方法 range(TemporalField)，因为它在代码中更易于阅读。
     * 实现应使用ChronoField中可用的字段执行任何查询或计算。如果字段不受支持，则必须抛出UnsupportedTemporalTypeException。
     */
    ValueRange rangeRefinedBy(TemporalAccessor temporal);

    /**
     * 从指定的时序对象中获取该字段的值。
     * 此操作会向时序对象查询该字段的值。
     * 使用此方法有两种等效的方式。第一种是直接调用此方法。
     * 第二种是使用 TemporalAccessor.getLong(TemporalField)（或 TemporalAccessor.get(TemporalField)）：
     * // 这两行代码等效，但建议采用第二种方法
     * temporal = thisField.getFrom(temporal);
     * temporal = temporal.getLong(thisField);
     *
     * 建议使用第二种方法getLong(TemporalField)，因为它在代码中更清晰易读。
     * 实现应使用ChronoField中可用的字段执行任何查询或计算。如果该字段不被支持，则必须抛出UnsupportedTemporalTypeException。
     */
    long getFrom(TemporalAccessor temporal);

    /**
     * 返回指定时间对象的副本，其中该字段的值已被设置。
     * 此方法基于指定的时间对象返回一个新对象，其中该字段的值已被更改。
     * 例如，对于LocalDate对象，可用于设置年、月或日期。返回的对象与指定对象具有相同的可观察类型。
     *
     * 在某些情况下，字段的更改行为并未完全定义。例如，如果目标对象是一个表示1月31日的日期，那么将月份更改为2月将导致结果不明确。
     * 在这种情况下，由实现负责确定结果。通常，它会选择前一个有效的日期，在本例中即 2 月的最后一个有效日期。
     *
     * 使用此方法有两种等效的方式。第一种是直接调用此方法。第二种是使用 Temporal.with(TemporalField, long)：
     * // 这两行代码等效，但建议采用第二种方法
     * temporal = thisField.adjustInto(temporal);
     * temporal = temporal.with(thisField);
     *
     * 建议使用第二种方法with(TemporalField)，因为它在代码中更清晰易读。
     * 实现应使用ChronoField中可用的字段执行任何查询或计算。如果字段不受支持，则必须抛出UnsupportedTemporalTypeException。
     * 实现不得修改指定的时序对象。相反，必须返回原始对象的调整后的副本。这为不可变和可变实现提供了等效且安全的行为。
     */
    <R extends Temporal> R adjustInto(R temporal, long newValue);

    /**
     * 将该字段解析为更简单的替代形式或日期。
     * 此方法在解析的解析阶段被调用。其设计目的是允许将应用程序定义的字段简化为更标准的字段（例如ChronoField上的字段）或日期。
     * 应用程序通常不应直接调用此方法。
     */
    default TemporalAccessor resolve(Map<TemporalField, Long> fieldValues,
                                     TemporalAccessor partialTemporal,
                                     ResolverStyle resolverStyle) {
        return null;
    }

    @Override
    String toString();
}

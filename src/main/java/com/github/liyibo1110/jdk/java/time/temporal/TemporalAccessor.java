package com.github.liyibo1110.jdk.java.time.temporal;

import java.time.DateTimeException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.temporal.ValueRange;
import java.util.Objects;

/**
 * 框架级接口，用于定义对时间对象（如日期、时间、偏移量或这些元素的组合）的只读访问。
 *
 * 这是日期、时间和偏移量对象的基础接口类型。能够通过字段或查询提供信息的类会实现该接口。
 *
 * 大多数日期和时间信息都可以用数字表示。这些信息通过TemporalField进行建模，其中数字采用long类型存储以处理大数值。
 * 年、月和日期是字段的简单示例，但还包括时刻和偏移量。有关标准字段集，请参阅ChronoField。
 *
 * 有两类日期/时间信息无法用数字表示，即时间序列和时区。可通过TemporalQuery上定义的静态方法，利用查询来访问这些信息。
 * 子接口Temporal扩展了此定义，使其还支持对更完整的时序对象进行调整和操作。
 *
 * 该接口属于框架级接口，不应在应用程序代码中广泛使用。相反，应用程序应创建并传递具体类型的实例，例如LocalDate。
 * 这样做的原因有很多，其中之一是该接口的实现可能采用非ISO标准的日历系统。有关这些问题的更详细讨论，请参阅java.time.chrono.ChronoLocalDate。
 *
 * 上面是官方直译，这个接口代表：实现类可以被读取时间字段。
 * 只关心自己身上的时间相关字段，可以提供给外部读取，例如：
 * 1、LocalDate可以读出：YEAR / MONTH_OF_YEAR / DAY_OF_MONTH
 * 2、LocalTime可以读出：HOUR_OF_DAY / MINUTE_OF_HOUR
 * 3、LocalDateTime可以读出上面的1和2。
 *
 * 可以看作一个时间字段查询的视图方法接口，即不同的时间对象，怎么用统一的方式去读取。
 * @author liyibo
 */
public interface TemporalAccessor {

    /**
     * 该实现类是否指定特定的时间字段，例如：
     * 1、LocalDate支持YEAR。
     * 2、LocalDate不支持HOUR_OF_DAY。
     */
    boolean isSupported(TemporalField field);

    /**
     * 返回给定时间字段的4个range值
     */
    default ValueRange range(TemporalField field) {
        if(field instanceof ChronoField) {
            if(isSupported(field))
                return field.range();
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        Objects.requireNonNull(field, "field");
        return field.rangeRefinedBy(this);
    }

    /**
     * 取出该实现类特定的时间字段值，以int形式返回
     */
    default int get(TemporalField field) {
        ValueRange range = range(field);
        if(range.isIntValue() == false)
            throw new UnsupportedTemporalTypeException("Invalid field " + field + " for get() method, use getLong() instead");
        long value = getLong(field);
        if(range.isValidValue(value) == false)
            throw new DateTimeException("Invalid value for " + field + " (valid values " + range + "): " + value);
        return (int)value;
    }

    /**
     * 实现类必须自己实现：如何从Field返回对应的整数值。
     */
    long getLong(TemporalField field);

    /**
     * 允许外部以Query对象的方式，向Temporal提出更高层的问题，例如：
     * 1、chronology是什么
     * 2、precision是什么
     * 3、zone是什么
     * 4、localDate能不能提取出来
     * 相当于给基础字段读取之上，再加一个可扩展的查询机制。
     */
    default <R> R query(TemporalQuery<R> query) {
        if(query == TemporalQueries.zoneId()
                || query == TemporalQueries.chronology()
                || query == TemporalQueries.precision()) {
            return null;
        }
        return query.queryFrom(this);
    }
}

package com.github.liyibo1110.jdk.java.time;

import com.github.liyibo1110.jdk.java.time.temporal.ChronoUnit;

import java.time.DateTimeException;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.temporal.ValueRange;
import java.util.Locale;

/**
 * 星期几，例如“星期二”。
 * DayOfWeek是一个枚举类型，代表一周中的7天——星期一、星期二、星期三、星期四、星期五、星期六和星期日。
 * 除了文本形式的枚举名称外，每个星期几都有一个int值。该 int 值遵循ISO-8601标准，从1（星期一）到7（星期日）。
 * 为确保代码清晰度，建议应用程序使用枚举类型而非整数值。
 *
 * 该枚举提供对星期几本地化文本形式的访问。某些区域设置还会为星期赋予不同的数值（例如将星期日设为 1），但本类不支持此类情况。有关本地化星期编号，请参阅WeekFields。
 * 请勿使用ordinal()获取 DayOfWeek 的数值表示形式。请改用getValue()。
 * 该枚举代表了许多日历系统中常见的概念。因此，任何将星期几概念定义为与ISO日历系统完全等同的日历系统均可使用此枚举。
 * @author liyibo
 * @date 2026-03-28 23:34
 */
public enum DayOfWeek implements TemporalAccessor, TemporalAdjuster {

    MONDAY,
    TUESDAY,
    WEDNESDAY,
    THURSDAY,
    FRIDAY,
    SATURDAY,
    SUNDAY;

    private static final DayOfWeek[] ENUMS = DayOfWeek.values();

    //-----------------------------------------------------------------------

    public static DayOfWeek of(int dayOfWeek) {
        if(dayOfWeek < 1 || dayOfWeek > 7)
            throw new DateTimeException("Invalid value for DayOfWeek: " + dayOfWeek);
        return ENUMS[dayOfWeek - 1];
    }

    //-----------------------------------------------------------------------

    public static DayOfWeek from(TemporalAccessor temporal) {
        if(temporal instanceof DayOfWeek)
            return (DayOfWeek)temporal;
        try {
            return of(temporal.get(ChronoField.DAY_OF_WEEK));
        } catch (DateTimeException ex) {
            throw new DateTimeException("Unable to obtain DayOfWeek from TemporalAccessor: " + temporal + " of type " + temporal.getClass().getName(), ex);
        }
    }

    //-----------------------------------------------------------------------

    public int getValue() {
        return ordinal() + 1;
    }

    //-----------------------------------------------------------------------

    public String getDisplayName(TextStyle style, Locale locale) {
        return new DateTimeFormatterBuilder().appendText(ChronoField.DAY_OF_WEEK, style).toFormatter(locale).format(this);
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean isSupported(TemporalField field) {
        if(field instanceof ChronoField)
            return field == ChronoField.DAY_OF_WEEK;
        return field != null && field.isSupportedBy(this);
    }

    @Override
    public ValueRange range(TemporalField field) {
        if(field == ChronoField.DAY_OF_WEEK)
            return field.range();
        return TemporalAccessor.super.range(field);
    }

    @Override
    public int get(TemporalField field) {
        if(field == ChronoField.DAY_OF_WEEK)
            return getValue();
        return TemporalAccessor.super.get(field);
    }

    @Override
    public long getLong(TemporalField field) {
        if(field == ChronoField.DAY_OF_WEEK)
            return getValue();
        else if (field instanceof ChronoField)
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        return field.getFrom(this);
    }

    //-----------------------------------------------------------------------

    public DayOfWeek plus(long days) {
        int amount = (int) (days % 7);
        return ENUMS[(ordinal() + (amount + 7)) % 7];
    }

    public DayOfWeek minus(long days) {
        return plus(-(days % 7));
    }

    //-----------------------------------------------------------------------

    @Override
    public <R> R query(TemporalQuery<R> query) {
        if(query == TemporalQueries.precision())
            return (R)ChronoUnit.DAYS;
        return TemporalAccessor.super.query(query);
    }

    @Override
    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.DAY_OF_WEEK, getValue());
    }
}

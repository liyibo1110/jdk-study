package com.github.liyibo1110.jdk.java.time;

import java.time.DateTimeException;
import java.time.chrono.Chronology;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
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
 * 一年中的某个月，例如“July”。
 * Month是一个枚举类型，代表一年中的12个月——January、February、March、April、May、June、July、August、September、October、November和December。
 * 除了文本枚举名称外，每个月份还具有一个int值。该int值遵循常规用法和 ISO-8601 标准，从 1（一月）到 12（十二月）。
 * 建议应用程序使用枚举而非int值，以确保代码清晰度。
 *
 * 请勿使用ordinal()获取Month的数值表示形式。请改用 getValue()。
 * 此枚举代表了许多日历系统中常见的概念。因此，任何将“月份”概念定义为与 ISO-8601 日历系统完全等同的日历系统均可使用此枚举。
 * @author liyibo
 * @date 2026-03-28 23:17
 */
public enum Month implements TemporalAccessor, TemporalAdjuster {
    JANUARY,
    FEBRUARY,
    MARCH,
    APRIL,
    MAY,
    JUNE,
    JULY,
    AUGUST,
    SEPTEMBER,
    OCTOBER,
    NOVEMBER,
    DECEMBER;

    /** cache */
    private static final Month[] ENUMS = Month.values();

    //-----------------------------------------------------------------------

    public static Month of(int month) {
        if(month < 1 || month > 12)
            throw new DateTimeException("Invalid value for MonthOfYear: " + month);
        return ENUMS[month - 1];
    }

    //-----------------------------------------------------------------------

    public static Month from(TemporalAccessor temporal) {
        if(temporal instanceof Month)
            return (Month) temporal;

        try {
            if(IsoChronology.INSTANCE.equals(Chronology.from(temporal)) == false)
                temporal = LocalDate.from(temporal);
            return of(temporal.get(ChronoField.MONTH_OF_YEAR));
        } catch (DateTimeException ex) {
            throw new DateTimeException("Unable to obtain Month from TemporalAccessor: " + temporal + " of type " + temporal.getClass().getName(), ex);
        }
    }

    //-----------------------------------------------------------------------

    public int getValue() {
        return ordinal() + 1;
    }

    public String getDisplayName(TextStyle style, Locale locale) {
        return new DateTimeFormatterBuilder().appendText(ChronoField.MONTH_OF_YEAR, style).toFormatter(locale).format(this);
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean isSupported(TemporalField field) {
        if(field instanceof ChronoField)
            return field == ChronoField.MONTH_OF_YEAR;
        return field != null && field.isSupportedBy(this);
    }

    @Override
    public ValueRange range(TemporalField field) {
        if(field == ChronoField.MONTH_OF_YEAR)
            return field.range();
        return TemporalAccessor.super.range(field);
    }

    @Override
    public int get(TemporalField field) {
        if(field == ChronoField.MONTH_OF_YEAR)
            return getValue();
        return TemporalAccessor.super.get(field);
    }

    @Override
    public long getLong(TemporalField field) {
        if(field == ChronoField.MONTH_OF_YEAR)
            return getValue();
        else if (field instanceof ChronoField)
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        return field.getFrom(this);
    }

    //-----------------------------------------------------------------------

    public Month plus(long months) {
        int amount = (int) (months % 12);
        return ENUMS[(ordinal() + (amount + 12)) % 12];
    }

    public Month minus(long months) {
        return plus(-(months % 12));
    }

    //-----------------------------------------------------------------------

    public int length(boolean leapYear) {
        switch (this) {
            case FEBRUARY:
                return (leapYear ? 29 : 28);
            case APRIL:
            case JUNE:
            case SEPTEMBER:
            case NOVEMBER:
                return 30;
            default:
                return 31;
        }
    }

    public int minLength() {
        switch (this) {
            case FEBRUARY:
                return 28;
            case APRIL:
            case JUNE:
            case SEPTEMBER:
            case NOVEMBER:
                return 30;
            default:
                return 31;
        }
    }

    public int maxLength() {
        switch (this) {
            case FEBRUARY:
                return 29;
            case APRIL:
            case JUNE:
            case SEPTEMBER:
            case NOVEMBER:
                return 30;
            default:
                return 31;
        }
    }

    //-----------------------------------------------------------------------

    public int firstDayOfYear(boolean leapYear) {
        int leap = leapYear ? 1 : 0;
        switch (this) {
            case JANUARY:
                return 1;
            case FEBRUARY:
                return 32;
            case MARCH:
                return 60 + leap;
            case APRIL:
                return 91 + leap;
            case MAY:
                return 121 + leap;
            case JUNE:
                return 152 + leap;
            case JULY:
                return 182 + leap;
            case AUGUST:
                return 213 + leap;
            case SEPTEMBER:
                return 244 + leap;
            case OCTOBER:
                return 274 + leap;
            case NOVEMBER:
                return 305 + leap;
            case DECEMBER:
            default:
                return 335 + leap;
        }
    }

    public Month firstMonthOfQuarter() {
        return ENUMS[(ordinal() / 3) * 3];
    }

    //-----------------------------------------------------------------------

    @Override
    public <R> R query(TemporalQuery<R> query) {
        if(query == TemporalQueries.chronology())
            return (R)IsoChronology.INSTANCE;
        else if (query == TemporalQueries.precision())
            return (R)ChronoUnit.MONTHS;
        return TemporalAccessor.super.query(query);
    }

    @Override
    public Temporal adjustInto(Temporal temporal) {
        if(Chronology.from(temporal).equals(IsoChronology.INSTANCE) == false)
            throw new DateTimeException("Adjustment only supported on ISO date-time");
        return temporal.with(ChronoField.MONTH_OF_YEAR, getValue());
    }
}

package com.github.liyibo1110.jdk.java.time;

import java.io.Serializable;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.IsoChronology;
import java.time.chrono.IsoEra;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.temporal.ValueRange;
import java.time.zone.ZoneRules;
import java.util.Objects;

/**
 * 一个本地日期值，只包含年、月、日，可以看作某个日期标签，而不是某一瞬间。
 * LocalDate也不包含时区信息，例如2026-03-27这个日期，在中国时区、美国时区、UTC它们开始和结束的实际瞬间都不一样，因此LocalDate没有能力对应到绝对时间点。
 * @author liyibo
 * @date 2026-03-27 23:05
 */
public final class LocalDate implements Temporal, TemporalAdjuster, ChronoLocalDate, Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 2942565459149668126L;

    /** 400年的天数 */
    private static final int DAYS_PER_CYCLE = 146097;

    /** 从公元元年到1970年的天数，从公元元年到2000年共有五个400年周期，从1970年到2000年共有7个闰年 */
    static final long DAYS_0000_TO_1970 = (DAYS_PER_CYCLE * 5L) - (30L * 365L + 7L);

    private final int year;

    private final short month;

    private final short day;

    //-----------------------------------------------------------------------

    public static LocalDate now() {
        return now(Clock.systemDefaultZone());
    }

    public static LocalDate now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static LocalDate now(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        final Instant now = clock.instant();    // 当前时间戳
        return ofInstant(now, clock.getZone());
    }

    //-----------------------------------------------------------------------

    public static LocalDate of(int year, Month month, int dayOfMonth) {
        YEAR.checkValidValue(year);
        Objects.requireNonNull(month, "month");
        DAY_OF_MONTH.checkValidValue(dayOfMonth);
        return create(year, month.getValue(), dayOfMonth);
    }

    public static LocalDate of(int year, int month, int dayOfMonth) {
        YEAR.checkValidValue(year);
        MONTH_OF_YEAR.checkValidValue(month);
        DAY_OF_MONTH.checkValidValue(dayOfMonth);
        return create(year, month, dayOfMonth);
    }

    //-----------------------------------------------------------------------

    public static LocalDate ofYearDay(int year, int dayOfYear) {
        YEAR.checkValidValue(year);
        DAY_OF_YEAR.checkValidValue(dayOfYear);
        boolean leap = IsoChronology.INSTANCE.isLeapYear(year);
        if (dayOfYear == 366 && leap == false) {
            throw new DateTimeException("Invalid date 'DayOfYear 366' as '" + year + "' is not a leap year");
        }
        Month moy = Month.of((dayOfYear - 1) / 31 + 1);
        int monthEnd = moy.firstDayOfYear(leap) + moy.length(leap) - 1;
        if (dayOfYear > monthEnd) {
            moy = moy.plus(1);
        }
        int dom = dayOfYear - moy.firstDayOfYear(leap) + 1;
        return new LocalDate(year, moy.getValue(), dom);
    }

    //-----------------------------------------------------------------------

    public static LocalDate ofInstant(Instant instant, ZoneId zone) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(zone, "zone");
        ZoneRules rules = zone.getRules();
        ZoneOffset offset = rules.getOffset(instant);
        long localSecond = instant.getEpochSecond() + offset.getTotalSeconds(); // 秒级时间戳
        long localEpochDay = Math.floorDiv(localSecond, SECONDS_PER_DAY);   // 天级时间戳
        return ofEpochDay(localEpochDay);
    }

    //-----------------------------------------------------------------------

    public static LocalDate ofEpochDay(long epochDay) {
        EPOCH_DAY.checkValidValue(epochDay);
        long zeroDay = epochDay + DAYS_0000_TO_1970;
        zeroDay -= 60;  // adjust to 0000-03-01 so leap day is at end of four year cycle
        long adjust = 0;
        if (zeroDay < 0) {
            // adjust negative years to positive for calculation
            long adjustCycles = (zeroDay + 1) / DAYS_PER_CYCLE - 1;
            adjust = adjustCycles * 400;
            zeroDay += -adjustCycles * DAYS_PER_CYCLE;
        }
        long yearEst = (400 * zeroDay + 591) / DAYS_PER_CYCLE;
        long doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400);
        if (doyEst < 0) {
            // fix estimate
            yearEst--;
            doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400);
        }
        yearEst += adjust;  // reset any negative year
        int marchDoy0 = (int) doyEst;

        // convert march-based values back to january-based
        int marchMonth0 = (marchDoy0 * 5 + 2) / 153;
        int month = (marchMonth0 + 2) % 12 + 1;
        int dom = marchDoy0 - (marchMonth0 * 306 + 5) / 10 + 1;
        yearEst += marchMonth0 / 10;

        // check year now we are certain it is correct
        int year = YEAR.checkValidIntValue(yearEst);
        return new LocalDate(year, month, dom);
    }

    //-----------------------------------------------------------------------

    public static LocalDate from(TemporalAccessor temporal) {
        Objects.requireNonNull(temporal, "temporal");
        LocalDate date = temporal.query(TemporalQueries.localDate());
        if(date == null)
            throw new DateTimeException("Unable to obtain LocalDate from TemporalAccessor: " + temporal + " of type " + temporal.getClass().getName());
        return date;
    }

    //-----------------------------------------------------------------------

    public static LocalDate parse(CharSequence text) {
        return parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static LocalDate parse(CharSequence text, DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.parse(text, LocalDate::from);
    }

    //-----------------------------------------------------------------------

    private static LocalDate create(int year, int month, int dayOfMonth) {
        if(dayOfMonth > 28) {
            int dom = 31;   // 经过实际year和month，计算出的最大day值
            switch(month) {
                case 2:
                    dom = (IsoChronology.INSTANCE.isLeapYear(year) ? 29 : 28);
                    break;
                case 4:
                case 6:
                case 9:
                case 11:
                    dom = 30;
                    break;
            }
            if(dayOfMonth > dom) {  // 传入的day值，不得大于计算出的最大day值
                if(dayOfMonth == 29)
                    throw new DateTimeException("Invalid date 'February 29' as '" + year + "' is not a leap year");
                else
                    throw new DateTimeException("Invalid date '" + Month.of(month).name() + " " + dayOfMonth + "'");
            }
        }
        return new LocalDate(year, month, dayOfMonth);
    }

    /**
     * 根据给定的year和month，先修正day值再构造LocalDate对象。
     */
    private static LocalDate resolvePreviousValid(int year, int month, int day) {
        switch (month) {
            case 2:
                day = Math.min(day, IsoChronology.INSTANCE.isLeapYear(year) ? 29 : 28);
                break;
            case 4:
            case 6:
            case 9:
            case 11:
                day = Math.min(day, 30);
                break;
        }
        return new LocalDate(year, month, day);
    }

    private LocalDate(int year, int month, int dayOfMonth) {
        this.year = year;
        this.month = (short) month;
        this.day = (short) dayOfMonth;
    }

    //-----------------------------------------------------------------------

    @Override  // override for Javadoc
    public boolean isSupported(TemporalField field) {
        return ChronoLocalDate.super.isSupported(field);    // 委托给ChronoLocalDate
    }

    @Override  // override for Javadoc
    public boolean isSupported(TemporalUnit unit) {
        return ChronoLocalDate.super.isSupported(unit); // 委托给ChronoLocalDate
    }

    //-----------------------------------------------------------------------

    /**
     * TemporalField -> ValueRange
     */
    @Override
    public ValueRange range(TemporalField field) {
        if(field instanceof ChronoField chronoField) {
            if(chronoField.isDateBased()) {
                switch(chronoField) {
                    case DAY_OF_MONTH: return ValueRange.of(1, lengthOfMonth());
                    case DAY_OF_YEAR: return ValueRange.of(1, lengthOfYear());
                    case ALIGNED_WEEK_OF_MONTH: return ValueRange.of(1, getMonth() == Month.FEBRUARY && isLeapYear() == false ? 4 : 5);
                    case YEAR_OF_ERA:
                        return (getYear() <= 0 ? ValueRange.of(1, Year.MAX_VALUE + 1) : ValueRange.of(1, Year.MAX_VALUE));
                }
                return field.range();
            }
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.rangeRefinedBy(this);
    }

    /**
     * 获取该日期指定字段的值，并以int类型返回。
     *
     * 此方法查询该日期指定字段的值。返回的值始终在该字段的有效值范围内。如果因字段不被支持或其他原因无法返回值，则会抛出异常。
     * 如果该字段是ChronoField，则查询在此处实现。受支持的字段将基于当前日期返回有效值，
     * 但EPOCH_DAY和PROLEPTIC_MONTH除外，因其数值过大无法容纳于int类型中，因此会抛出UnsupportedTemporalTypeException。
     * 所有其他 ChronoField 实例都会抛出 UnsupportedTemporalTypeException。
     *
     * 如果字段不是ChronoField，则通过调用TemporalField.getFrom(TemporalAccessor)并将此方法作为参数传递来获取该方法的结果。
     * 能否获取该值以及该值代表什么，由字段本身决定。
     */
    @Override
    public int get(TemporalField field) {
        if(field instanceof ChronoField)
            return get0(field);
        return ChronoLocalDate.super.get(field);
    }

    @Override
    public long getLong(TemporalField field) {
        if(field instanceof ChronoField) {
            if(field == EPOCH_DAY)
                return toEpochDay();
            if(field == PROLEPTIC_MONTH)
                return getProlepticMonth();
            return get0(field);
        }
        return field.getFrom(this);
    }

    private int get0(TemporalField field) {
        switch((ChronoField) field) {
            case DAY_OF_WEEK: return getDayOfWeek().getValue();
            case ALIGNED_DAY_OF_WEEK_IN_MONTH: return ((day - 1) % 7) + 1;
            case ALIGNED_DAY_OF_WEEK_IN_YEAR: return ((getDayOfYear() - 1) % 7) + 1;
            case DAY_OF_MONTH: return day;
            case DAY_OF_YEAR: return getDayOfYear();
            case EPOCH_DAY: throw new UnsupportedTemporalTypeException("Invalid field 'EpochDay' for get() method, use getLong() instead");
            case ALIGNED_WEEK_OF_MONTH: return ((day - 1) / 7) + 1;
            case ALIGNED_WEEK_OF_YEAR: return ((getDayOfYear() - 1) / 7) + 1;
            case MONTH_OF_YEAR: return month;
            case PROLEPTIC_MONTH: throw new UnsupportedTemporalTypeException("Invalid field 'ProlepticMonth' for get() method, use getLong() instead");
            case YEAR_OF_ERA: return (year >= 1 ? year : 1 - year);
            case YEAR: return year;
            case ERA: return (year >= 1 ? 1 : 0);
        }
        throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
    }

    private long getProlepticMonth() {
        return (year * 12L + month - 1);
    }

    //-----------------------------------------------------------------------

    @Override
    public IsoChronology getChronology() {
        return IsoChronology.INSTANCE;
    }

    @Override // override for Javadoc
    public IsoEra getEra() {
        return (getYear() >= 1 ? IsoEra.CE : IsoEra.BCE);
    }

    public int getYear() {
        return year;
    }

    public int getMonthValue() {
        return month;
    }
}

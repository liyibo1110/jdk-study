package com.github.liyibo1110.jdk.java.time.chrono;

import java.io.Serializable;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.Chronology;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.temporal.ValueRange;
import java.util.Objects;

import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.ERA;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.PROLEPTIC_MONTH;
import static java.time.temporal.ChronoField.YEAR_OF_ERA;

/**
 * 以标准年-月-日日历系统表示的日期。
 * 该类适用于需要处理非ISO日历系统日期的应用程序。例如，日本、民国、泰国佛教日历等。
 *
 * ChronoLocalDate基于年、月、日的通用概念构建。日历系统（由Chronology表示）定义了各字段之间的关系，而该类允许对生成的日期进行操作。
 *
 * 请注意，并非所有日历系统都适合与本类配合使用。例如，玛雅历法采用的系统与年、月、日没有任何关联。
 * API设计建议在绝大多数应用程序中使用LocalDate。这包括从持久化数据存储（如数据库）中读写数据，以及通过网络传输日期和时间的代码。
 * 随后在用户界面层使用ChronoLocalDate实例来处理本地化的输入/输出。
 *
 * 示例：
 * System.out.printf(“Example()%n”);
 * // 枚举可用日历列表，并为每个日历打印今日日期
 * Set<Chronology> chronos = Chronology.getAvailableChronologies();
 *
 * for (Chronology chrono : chronos) {
 *     ChronoLocalDate date = chrono.dateNow();
 *     System.out.printf(“ %20s: %s%n”, chrono.getID(), date.toString());
 * }
 *
 * // 打印伊斯兰历日期和日历
 * ChronoLocalDate date = Chronology.of(“Hijrah”).dateNow();
 * int day = date.get(ChronoField.DAY_OF_MONTH);
 * int dow = date.get(ChronoField.DAY_OF_WEEK);
 *
 * int month = date.get(ChronoField.MONTH_OF_YEAR);
 * int year = date.get(ChronoField.YEAR);
 * System.out.printf(“ 今天是 %s %s %d-%s-%d%n”, date.getChronology().getID(), dow, day, month, year);
 *
 * // 打印今天的日期和本年的最后一天
 * ChronoLocalDate now1 = Chronology.of(“Hijrah”).dateNow();
 * ChronoLocalDate first = now1.with(ChronoField.DAY_OF_MONTH, 1).with(ChronoField.MONTH_OF_YEAR, 1);
 *
 * ChronoLocalDate last = first.plus(1, ChronoUnit.YEARS).minus(1, ChronoUnit.DAYS);
 * System.out.printf(“ 今天是 %s：起始：%s；结束：%s%n”, last.getChronology().getID(), first, last);
 *
 * Adding Calendars
 *
 * 可以通过定义ChronoLocalDate的子类来表示日期实例，并实现Chronology接口作为 ChronoLocalDate子类的工厂，从而扩展日历集。
 * 为了允许发现这些额外的日历类型，必须按照java.util.ServiceLoader规范，在META-INF/Services文件中将Chronology的实现注册为一个实现Chronology接口的服务。
 * 该子类必须按照Chronology类的描述进行工作，并必须提供其时间序列ID和日历类型。
 *
 * @author liyibo
 * @date 2026-03-30 14:24
 */
abstract class ChronoLocalDateImpl<D extends java.time.chrono.ChronoLocalDate>
        implements ChronoLocalDate, Temporal, TemporalAdjuster, Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 6282433883239719096L;

    static <D extends ChronoLocalDate> D ensureValid(Chronology chrono, Temporal temporal) {
        @SuppressWarnings("unchecked")
        D other = (D) temporal;
        if (chrono.equals(other.getChronology()) == false)
            throw new ClassCastException("Chronology mismatch, expected: " + chrono.getId() + ", actual: " + other.getChronology().getId());
        return other;
    }

    //-----------------------------------------------------------------------

    ChronoLocalDateImpl() {}

    @Override
    @SuppressWarnings("unchecked")
    public D with(TemporalAdjuster adjuster) {
        return (D) ChronoLocalDate.super.with(adjuster);
    }

    @Override
    @SuppressWarnings("unchecked")
    public D with(TemporalField field, long value) {
        return (D) ChronoLocalDate.super.with(field, value);
    }

    //-----------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public D plus(TemporalAmount amount) {
        return (D) ChronoLocalDate.super.plus(amount);
    }

    //-----------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public D plus(long amountToAdd, TemporalUnit unit) {
        if (unit instanceof ChronoUnit chronoUnit) {
            switch (chronoUnit) {
                case DAYS: return plusDays(amountToAdd);
                case WEEKS: return plusDays(Math.multiplyExact(amountToAdd, 7));
                case MONTHS: return plusMonths(amountToAdd);
                case YEARS: return plusYears(amountToAdd);
                case DECADES: return plusYears(Math.multiplyExact(amountToAdd, 10));
                case CENTURIES: return plusYears(Math.multiplyExact(amountToAdd, 100));
                case MILLENNIA: return plusYears(Math.multiplyExact(amountToAdd, 1000));
                case ERAS: return with(ERA, Math.addExact(getLong(ERA), amountToAdd));
            }
            throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
        }
        return (D) ChronoLocalDate.super.plus(amountToAdd, unit);
    }

    @Override
    @SuppressWarnings("unchecked")
    public D minus(TemporalAmount amount) {
        return (D) ChronoLocalDate.super.minus(amount);
    }

    @Override
    @SuppressWarnings("unchecked")
    public D minus(long amountToSubtract, TemporalUnit unit) {
        return (D) ChronoLocalDate.super.minus(amountToSubtract, unit);
    }

    //-----------------------------------------------------------------------

    abstract D plusYears(long yearsToAdd);

    abstract D plusMonths(long monthsToAdd);

    D plusWeeks(long weeksToAdd) {
        return plusDays(Math.multiplyExact(weeksToAdd, 7));
    }

    abstract D plusDays(long daysToAdd);

    //-----------------------------------------------------------------------

    D minusYears(long yearsToSubtract) {
        return (yearsToSubtract == Long.MIN_VALUE ? ((ChronoLocalDateImpl<D>)plusYears(Long.MAX_VALUE)).plusYears(1) : plusYears(-yearsToSubtract));
    }

    D minusMonths(long monthsToSubtract) {
        return (monthsToSubtract == Long.MIN_VALUE ? ((ChronoLocalDateImpl<D>)plusMonths(Long.MAX_VALUE)).plusMonths(1) : plusMonths(-monthsToSubtract));
    }

    D minusWeeks(long weeksToSubtract) {
        return (weeksToSubtract == Long.MIN_VALUE ? ((ChronoLocalDateImpl<D>)plusWeeks(Long.MAX_VALUE)).plusWeeks(1) : plusWeeks(-weeksToSubtract));
    }

    D minusDays(long daysToSubtract) {
        return (daysToSubtract == Long.MIN_VALUE ? ((ChronoLocalDateImpl<D>)plusDays(Long.MAX_VALUE)).plusDays(1) : plusDays(-daysToSubtract));
    }

    //-----------------------------------------------------------------------

    @Override
    public long until(Temporal endExclusive, TemporalUnit unit) {
        Objects.requireNonNull(endExclusive, "endExclusive");
        ChronoLocalDate end = getChronology().date(endExclusive);
        if (unit instanceof ChronoUnit chronoUnit) {
            switch (chronoUnit) {
                case DAYS: return daysUntil(end);
                case WEEKS: return daysUntil(end) / 7;
                case MONTHS: return monthsUntil(end);
                case YEARS: return monthsUntil(end) / 12;
                case DECADES: return monthsUntil(end) / 120;
                case CENTURIES: return monthsUntil(end) / 1200;
                case MILLENNIA: return monthsUntil(end) / 12000;
                case ERAS: return end.getLong(ERA) - getLong(ERA);
            }
            throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
        }
        Objects.requireNonNull(unit, "unit");
        return unit.between(this, end);
    }

    private long daysUntil(ChronoLocalDate end) {
        return end.toEpochDay() - toEpochDay();  // no overflow
    }

    private long monthsUntil(ChronoLocalDate end) {
        ValueRange range = getChronology().range(MONTH_OF_YEAR);
        if (range.getMaximum() != 12)
            throw new IllegalStateException("ChronoLocalDateImpl only supports Chronologies with 12 months per year");

        long packed1 = getLong(PROLEPTIC_MONTH) * 32L + get(DAY_OF_MONTH);  // no overflow
        long packed2 = end.getLong(PROLEPTIC_MONTH) * 32L + end.get(DAY_OF_MONTH);  // no overflow
        return (packed2 - packed1) / 32;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj instanceof ChronoLocalDate)
            return compareTo((ChronoLocalDate) obj) == 0;
        return false;
    }

    @Override
    public int hashCode() {
        long epDay = toEpochDay();
        return getChronology().hashCode() ^ ((int) (epDay ^ (epDay >>> 32)));
    }

    @Override
    public String toString() {
        // getLong() reduces chances of exceptions in toString()
        long yoe = getLong(YEAR_OF_ERA);
        long moy = getLong(MONTH_OF_YEAR);
        long dom = getLong(DAY_OF_MONTH);
        StringBuilder buf = new StringBuilder(30);
        buf.append(getChronology().toString())
                .append(" ")
                .append(getEra())
                .append(" ")
                .append(yoe)
                .append(moy < 10 ? "-0" : "-").append(moy)
                .append(dom < 10 ? "-0" : "-").append(dom);
        return buf.toString();
    }
}

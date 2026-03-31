package com.github.liyibo1110.jdk.java.time;

import java.io.DataOutput;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneRegion;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesException;
import java.time.zone.ZoneRulesProvider;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;

import static java.util.Map.entry;

/**
 * 表示：一个地区性的时区ID，用来找到该地区对应的时区规则，例如：
 * 1、Asia/Shanghai
 * 2、America/Los_Angeles
 * 3、Europe/London
 * 和ZoneOffset不同，ZoneId只表示属于哪个地区时区体系，这个地区背后会有一套rules，决定：
 * 1、某个日期时offset是多少。
 * 2、是否有夏令时（即同一个地区的时区有可能根据季节变化）。
 * 3、历史上是否改过时区政策。
 * 4、某个本地时间是否存在歧义或不存在。
 * 因此ZoneId可以看作是时区规则的入口，即在这个时刻，应该用什么偏移。
 * @author liyibo
 * @date 2026-03-30 15:38
 */
public abstract class ZoneId implements Serializable {

    public static final Map<String, String> SHORT_IDS = Map.ofEntries(
        entry("ACT", "Australia/Darwin"),
        entry("AET", "Australia/Sydney"),
        entry("AGT", "America/Argentina/Buenos_Aires"),
        entry("ART", "Africa/Cairo"),
        entry("AST", "America/Anchorage"),
        entry("BET", "America/Sao_Paulo"),
        entry("BST", "Asia/Dhaka"),
        entry("CAT", "Africa/Harare"),
        entry("CNT", "America/St_Johns"),
        entry("CST", "America/Chicago"),
        entry("CTT", "Asia/Shanghai"),
        entry("EAT", "Africa/Addis_Ababa"),
        entry("ECT", "Europe/Paris"),
        entry("IET", "America/Indiana/Indianapolis"),
        entry("IST", "Asia/Kolkata"),
        entry("JST", "Asia/Tokyo"),
        entry("MIT", "Pacific/Apia"),
        entry("NET", "Asia/Yerevan"),
        entry("NST", "Pacific/Auckland"),
        entry("PLT", "Asia/Karachi"),
        entry("PNT", "America/Phoenix"),
        entry("PRT", "America/Puerto_Rico"),
        entry("PST", "America/Los_Angeles"),
        entry("SST", "Pacific/Guadalcanal"),
        entry("VST", "Asia/Ho_Chi_Minh"),
        entry("EST", "-05:00"),
        entry("MST", "-07:00"),
        entry("HST", "-10:00")
    );

    @java.io.Serial
    private static final long serialVersionUID = 8352817235686L;

    //-----------------------------------------------------------------------

    public static ZoneId systemDefault() {
        return TimeZone.getDefault().toZoneId();
    }

    public static Set<String> getAvailableZoneIds() {
        return new HashSet<>(ZoneRulesProvider.getAvailableZoneIds());
    }

    //-----------------------------------------------------------------------

    public static ZoneId of(String zoneId, Map<String, String> aliasMap) {
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(aliasMap, "aliasMap");
        String id = Objects.requireNonNullElse(aliasMap.get(zoneId), zoneId);
        return of(id);
    }

    public static ZoneId of(String zoneId) {
        return of(zoneId, true);
    }

    public static ZoneId ofOffset(String prefix, ZoneOffset offset) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(offset, "offset");
        if (prefix.isEmpty())
            return offset;

        if (!prefix.equals("GMT") && !prefix.equals("UTC") && !prefix.equals("UT"))
            throw new IllegalArgumentException("prefix should be GMT, UTC or UT, is: " + prefix);

        if (offset.getTotalSeconds() != 0)
            prefix = prefix.concat(offset.getId());

        return new ZoneRegion(prefix, offset.getRules());
    }

    static ZoneId of(String zoneId, boolean checkAvailable) {
        Objects.requireNonNull(zoneId, "zoneId");
        if (zoneId.length() <= 1 || zoneId.startsWith("+") || zoneId.startsWith("-"))
            return ZoneOffset.of(zoneId);
        else if (zoneId.startsWith("UTC") || zoneId.startsWith("GMT"))
            return ofWithPrefix(zoneId, 3, checkAvailable);
        else if (zoneId.startsWith("UT"))
            return ofWithPrefix(zoneId, 2, checkAvailable);
        return ZoneRegion.ofId(zoneId, checkAvailable);
    }

    private static ZoneId ofWithPrefix(String zoneId, int prefixLength, boolean checkAvailable) {
        String prefix = zoneId.substring(0, prefixLength);
        if (zoneId.length() == prefixLength)
            return ofOffset(prefix, ZoneOffset.UTC);

        if (zoneId.charAt(prefixLength) != '+' && zoneId.charAt(prefixLength) != '-') {
            return ZoneRegion.ofId(zoneId, checkAvailable);  // drop through to ZoneRulesProvider
        }
        try {
            ZoneOffset offset = ZoneOffset.of(zoneId.substring(prefixLength));
            if (offset == ZoneOffset.UTC)
                return ofOffset(prefix, offset);

            return ofOffset(prefix, offset);
        } catch (DateTimeException ex) {
            throw new DateTimeException("Invalid ID for offset-based ZoneId: " + zoneId, ex);
        }
    }

    //-----------------------------------------------------------------------

    public static ZoneId from(TemporalAccessor temporal) {
        ZoneId obj = temporal.query(TemporalQueries.zone());
        if (obj == null)
            throw new DateTimeException("Unable to obtain ZoneId from TemporalAccessor: " + temporal + " of type " + temporal.getClass().getName());
        return obj;
    }

    //-----------------------------------------------------------------------

    ZoneId() {
        if (getClass() != ZoneOffset.class && getClass() != ZoneRegion.class)
            throw new AssertionError("Invalid subclass");
    }

    //-----------------------------------------------------------------------

    /**
     * 获取时区ID
     */
    public abstract String getId();

    //-----------------------------------------------------------------------

    /**
     * 获取时区的文本表示形式，例如“英国时间”或“+02:00”。
     * 此方法返回用于标识时区ID的文本名称，适合向用户展示。参数用于控制返回文本的样式和区域设置。
     * 如果未找到对应的文本映射，则返回完整的ID。
     */
    public String getDisplayName(TextStyle style, Locale locale) {
        return new DateTimeFormatterBuilder().appendZoneText(style).toFormatter(locale).format(toTemporal());
    }

    /**
     * 将该Zone转换为TemporalAccessor。
     * ZoneId可以完全表示为TemporalAccessor。但是，该类并未实现该接口，因为接口上的大多数方法对ZoneId而言没有意义。
     * 返回的Temporal对象不包含任何受支持的字段，其query方法支持通过TemporalQueries.zoneId()返回该区域。
     */
    private TemporalAccessor toTemporal() {
        return new TemporalAccessor() {
            @Override
            public boolean isSupported(TemporalField field) {
                return false;
            }
            @Override
            public long getLong(TemporalField field) {
                throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
            }
            @SuppressWarnings("unchecked")
            @Override
            public <R> R query(TemporalQuery<R> query) {
                if (query == TemporalQueries.zoneId())
                    return (R) ZoneId.this;
                return TemporalAccessor.super.query(query);
            }
        };
    }

    //-----------------------------------------------------------------------

    /**
     * 获取此ID对应的时区规则，以便进行计算。
     *
     * 这些规则提供了与时区相关的功能，例如根据给定时刻或本地日期时间查找时差。
     * 如果时区是在一个Java运行时环境中反序列化的，而该环境加载的规则与存储该时区的Java运行时环境不一致，则该时区可能无效。
     * 在这种情况下，调用此方法将抛出 ZoneRulesException。
     *
     * 这些规则由ZoneRulesProvider提供。高级提供程序可能支持在不重启Java运行时的情况下动态更新规则。
     * 如果是这样，则此方法的结果可能会随时间变化。但每次单独调用仍保持线程安全。
     * ZoneOffset始终返回一组规则，其中偏移量永远不会改变。
     */
    public abstract ZoneRules getRules();

    /**
     * 对时区ID进行标准化处理，并在可能的情况下返回ZoneOffset。
     * 该方法返回一个经过标准化的ZoneId，可替代此ID使用。结果将具有与该对象返回的ZoneRules相当的规则，但getId()返回的ID可能不同。
     * 标准化过程会检查此ZoneId的规则是否具有固定的偏移量。如果有，则返回与该偏移量相等的ZoneOffset。否则返回此值。
     */
    public ZoneId normalized() {
        try {
            ZoneRules rules = getRules();
            if (rules.isFixedOffset())
                return rules.getOffset(Instant.EPOCH);
        } catch (ZoneRulesException ex) {
            // invalid ZoneRegion is not important to this method
        }
        return this;
    }

    //-----------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        return (obj instanceof ZoneId other) && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

    //-----------------------------------------------------------------------

    @java.io.Serial
    private void readObject(ObjectInputStream s) throws InvalidObjectException {
        throw new InvalidObjectException("Deserialization via serialization delegate");
    }

    @Override
    public String toString() {
        return getId();
    }

    //-----------------------------------------------------------------------

    @java.io.Serial
    private Object writeReplace() {
        return new Ser(Ser.ZONE_REGION_TYPE, this);
    }

    abstract void write(DataOutput out) throws IOException;
}

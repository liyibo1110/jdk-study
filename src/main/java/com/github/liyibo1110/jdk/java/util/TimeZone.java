package com.github.liyibo1110.jdk.java.util;

import jdk.internal.util.StaticProperty;
import sun.security.action.GetPropertyAction;
import sun.util.calendar.ZoneInfo;
import sun.util.calendar.ZoneInfoFile;
import sun.util.locale.provider.TimeZoneNameUtility;

import java.io.Serializable;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.PropertyPermission;

/**
 * TimeZone表示时区偏移量，并能自动处理夏令时。
 * 通常您可以通过getDefault获取TimeZone，该方法会根据程序运行所在的时区创建一个TimeZone对象。
 * 例如对于在日本运行的程序，getDefault会基于日本标准时间创建一个TimeZone对象。
 *
 * 您也可以通过getTimeZone方法配合时区ID来获取TimeZone。例如美国太平洋时区的时区ID是 “America/Los_Angeles”。
 * 因此，您可以通过以下方式获取美国太平洋时区的TimeZone对象：
 * TimeZone tz = TimeZone.getTimeZone(“America/Los_Angeles”);
 *
 * 您可以使用getAvailableIDs方法遍历所有受支持的时区ID。随后您可以选择一个受支持的ID来获取TimeZone。
 * 如果所需的时区不在受支持的ID列表中，则可以指定自定义时区ID来生成TimeZone。自定义时区ID的语法如下：
 * CustomID:
 *      GMT 符号 小时 : 分钟
 *      GMT 符号 小时 : 分钟
 *      GMT 符号 小时
 *
 * 符号：以下之一
 *      + -
 * 小时：
 *      数字
 *      数字 数字
 *
 * 分钟：
 *      数字 数字
 *
 * 数字：以下之一
 *      0 1 2 3 4 5 6 7 8 9
 *
 * 小时必须在 0 到 23 之间，分钟必须在 00 到 59 之间。例如，“GMT+10”和“GMT+0010”分别表示比格林尼治标准时间（GMT）快 10 小时和 10 分钟。
 * 该格式与区域设置无关，且数字必须取自 Unicode 标准中的基本拉丁字符集。使用自定义时区 ID 时，无法指定夏令时转换时间表。如果指定的字符串不符合语法，则使用“GMT”。
 * 创建 TimeZone 时，指定的自定义时区 ID 将按以下语法进行规范化：
 *   规范化自定义 ID：
 *           GMT 符号 两位数小时 : 分钟
 *
 * 符号：
 *           + -
 *   双位数小时：
 *           数字 数字
 *   分钟：
 *           数字 数字
 *   数字：取值于
 *           0 1 2 3 4 5 6 7 8 9
 *
 * 例如，TimeZone.getTimeZone(“GMT-8”).getID() 返回 “GMT-08:00”。
 * 三字母时区标识符
 * 为了与JDK1.1.x兼容，还支持其他一些三字母时区标识符（例如 “PST”、‘CTT’、“AST”）。
 * 但是，由于同一个缩写通常用于多个时区（例如，“CST”可以是美国的“中部标准时间”和“中国标准时间”），而Java平台只能识别其中之一，因此不建议使用这些缩写。
 *
 * 这个组件是JDK旧时间体系的时区组件，java.time对其进行了兼容和桥接处理，该组件主要服务于Date、Calendar、GregorianCalendar和SimpleDateFormat。
 * 相当于ZoneId的上一代版本。
 *
 * @author liyibo
 * @date 2026-03-30 17:25
 */
public abstract class TimeZone implements Serializable, Cloneable {

    public TimeZone() {}

    public static final int SHORT = 0;

    public static final int LONG = 1;

    private static final int ONE_MINUTE = 60 * 1000;
    private static final int ONE_HOUR = 60 * ONE_MINUTE;
    private static final int ONE_DAY = 24 * ONE_HOUR;

    @java.io.Serial
    static final long serialVersionUID = 3581463369166924961L;

    public abstract int getOffset(int era, int year, int month, int day,
                                  int dayOfWeek, int milliseconds);

    public int getOffset(long date) {
        if(inDaylightTime(new Date(date)))
            return getRawOffset() + getDSTSavings();
        return getRawOffset();
    }

    int getOffsets(long date, int[] offsets) {
        int rawoffset = getRawOffset();
        int dstoffset = 0;
        if (inDaylightTime(new Date(date)))
            dstoffset = getDSTSavings();
        if (offsets != null) {
            offsets[0] = rawoffset;
            offsets[1] = dstoffset;
        }
        return rawoffset + dstoffset;
    }

    public abstract void setRawOffset(int offsetMillis);

    public abstract int getRawOffset();

    public String getID() {
        return ID;
    }

    public void setID(String ID) {
        if (ID == null)
            throw new NullPointerException();
        this.ID = ID;
        this.zoneId = null;   // invalidate cache
    }

    public final String getDisplayName() {
        return getDisplayName(false, LONG, Locale.getDefault(Locale.Category.DISPLAY));
    }

    public final String getDisplayName(Locale locale) {
        return getDisplayName(false, LONG, locale);
    }

    public final String getDisplayName(boolean daylight, int style) {
        return getDisplayName(daylight, style, Locale.getDefault(Locale.Category.DISPLAY));
    }

    public String getDisplayName(boolean daylight, int style, Locale locale) {
        if (style != SHORT && style != LONG)
            throw new IllegalArgumentException("Illegal style: " + style);

        String id = getID();
        String name = TimeZoneNameUtility.retrieveDisplayName(id, daylight, style, locale);
        if (name != null)
            return name;

        if (id.startsWith("GMT") && id.length() > 3) {
            char sign = id.charAt(3);
            if (sign == '+' || sign == '-')
                return id;
        }
        int offset = getRawOffset();
        if (daylight)
            offset += getDSTSavings();

        return ZoneInfoFile.toCustomID(offset);
    }

    private static String[] getDisplayNames(String id, Locale locale) {
        return TimeZoneNameUtility.retrieveDisplayNames(id, locale);
    }

    public int getDSTSavings() {
        if (useDaylightTime())
            return 3600000;
        return 0;
    }

    public abstract boolean useDaylightTime();

    public boolean observesDaylightTime() {
        return useDaylightTime() || inDaylightTime(new Date());
    }

    public abstract boolean inDaylightTime(Date date);

    public static synchronized java.util.TimeZone getTimeZone(String ID) {
        return getTimeZone(ID, true);
    }

    public static TimeZone getTimeZone(ZoneId zoneId) {
        String tzid = zoneId.getId(); // throws an NPE if null
        char c = tzid.charAt(0);
        if (c == '+' || c == '-')
            tzid = "GMT" + tzid;
        else if (c == 'Z' && tzid.length() == 1)
            tzid = "UTC";

        return getTimeZone(tzid, true);
    }

    public ZoneId toZoneId() {
        ZoneId zId = zoneId;
        if (zId == null)
            zoneId = zId = toZoneId0();
        return zId;
    }

    private ZoneId toZoneId0() {
        String id = getID();
        TimeZone defaultZone = defaultTimeZone;
        // are we not defaultTimeZone but our id is equal to default's?
        if (defaultZone != this && defaultZone != null && id.equals(defaultZone.getID()))
            // delegate to default TZ which is effectively immutable
            return defaultZone.toZoneId();

        // derive it ourselves
        if (ZoneInfoFile.useOldMapping() && id.length() == 3) {
            if ("EST".equals(id))
                return ZoneId.of("America/New_York");
            if ("MST".equals(id))
                return ZoneId.of("America/Denver");
            if ("HST".equals(id))
                return ZoneId.of("America/Honolulu");
        }
        return ZoneId.of(id, ZoneId.SHORT_IDS);
    }

    private static TimeZone getTimeZone(String ID, boolean fallback) {
        TimeZone tz = ZoneInfo.getTimeZone(ID);
        if (tz == null) {
            tz = parseCustomTimeZone(ID);
            if (tz == null && fallback)
                tz = new ZoneInfo(GMT_ID, 0);
        }
        return tz;
    }

    public static synchronized String[] getAvailableIDs(int rawOffset) {
        return ZoneInfo.getAvailableIDs(rawOffset);
    }

    public static synchronized String[] getAvailableIDs() {
        return ZoneInfo.getAvailableIDs();
    }

    private static native String getSystemTimeZoneID(String javaHome);

    private static native String getSystemGMTOffsetID();

    public static TimeZone getDefault() {
        return (TimeZone) getDefaultRef().clone();
    }

    static TimeZone getDefaultRef() {
        TimeZone defaultZone = defaultTimeZone;
        if (defaultZone == null) {
            // Need to initialize the default time zone.
            defaultZone = setDefaultZone();
            assert defaultZone != null;
        }
        // Don't clone here.
        return defaultZone;
    }

    private static synchronized TimeZone setDefaultZone() {
        TimeZone tz;
        // get the time zone ID from the system properties
        Properties props = GetPropertyAction.privilegedGetProperties();
        String zoneID = props.getProperty("user.timezone");

        // if the time zone ID is not set (yet), perform the
        // platform to Java time zone ID mapping.
        if (zoneID == null || zoneID.isEmpty()) {
            String javaHome = StaticProperty.javaHome();
            try {
                zoneID = getSystemTimeZoneID(javaHome);
                if (zoneID == null)
                    zoneID = GMT_ID;
            } catch (NullPointerException e) {
                zoneID = GMT_ID;
            }
        }

        // Get the time zone for zoneID. But not fall back to
        // "GMT" here.
        tz = getTimeZone(zoneID, false);

        if (tz == null) {
            // If the given zone ID is unknown in Java, try to
            // get the GMT-offset-based time zone ID,
            // a.k.a. custom time zone ID (e.g., "GMT-08:00").
            String gmtOffsetID = getSystemGMTOffsetID();
            if (gmtOffsetID != null)
                zoneID = gmtOffsetID;

            tz = getTimeZone(zoneID, true);
        }
        assert tz != null;

        final String id = zoneID;
        props.setProperty("user.timezone", id);

        defaultTimeZone = tz;
        return tz;
    }

    public static void setDefault(java.util.TimeZone zone) {
        @SuppressWarnings("removal")
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(new PropertyPermission("user.timezone", "write"));

        // by saving a defensive clone and returning a clone in getDefault() too,
        // the defaultTimeZone instance is isolated from user code which makes it
        // effectively immutable. This is important to avoid races when the
        // following is evaluated in ZoneId.systemDefault():
        // TimeZone.getDefault().toZoneId().
        defaultTimeZone = (zone == null) ? null : (java.util.TimeZone) zone.clone();
    }

    public boolean hasSameRules(java.util.TimeZone other) {
        return other != null && getRawOffset() == other.getRawOffset() && useDaylightTime() == other.useDaylightTime();
    }

    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    static final TimeZone NO_TIMEZONE = null;

    // =======================privates===============================

    private String ID;

    private transient ZoneId zoneId;

    private static volatile TimeZone defaultTimeZone;

    static final String GMT_ID = "GMT";
    private static final int GMT_ID_LENGTH = 3;

    private static final TimeZone parseCustomTimeZone(String id) {
        int length;

        // Error if the length of id isn't long enough or id doesn't
        // start with "GMT".
        if ((length = id.length()) < (GMT_ID_LENGTH + 2) || id.indexOf(GMT_ID) != 0)
            return null;

        ZoneInfo zi;

        // First, we try to find it in the cache with the given
        // id. Even the id is not normalized, the returned ZoneInfo
        // should have its normalized id.
        zi = ZoneInfoFile.getZoneInfo(id);
        if (zi != null)
            return zi;


        int index = GMT_ID_LENGTH;
        boolean negative = false;
        char c = id.charAt(index++);
        if (c == '-')
            negative = true;
        else if (c != '+')
            return null;


        int hours = 0;
        int num = 0;
        int countDelim = 0;
        int len = 0;
        while (index < length) {
            c = id.charAt(index++);
            if (c == ':') {
                if (countDelim > 0)
                    return null;

                if (len > 2)
                    return null;

                hours = num;
                countDelim++;
                num = 0;
                len = 0;
                continue;
            }
            if (c < '0' || c > '9')
                return null;

            num = num * 10 + (c - '0');
            len++;
        }
        if (index != length)
            return null;

        if (countDelim == 0) {
            if (len <= 2) {
                hours = num;
                num = 0;
            } else {
                hours = num / 100;
                num %= 100;
            }
        } else {
            if (len != 2)
                return null;
        }
        if (hours > 23 || num > 59)
            return null;

        int gmtOffset =  (hours * 60 + num) * 60 * 1000;

        if (gmtOffset == 0) {
            zi = ZoneInfoFile.getZoneInfo(GMT_ID);
            if (negative) {
                zi.setID("GMT-00:00");
            } else {
                zi.setID("GMT+00:00");
            }
        } else {
            zi = ZoneInfoFile.getCustomTimeZone(id, negative ? -gmtOffset : gmtOffset);
        }
        return zi;
    }
}

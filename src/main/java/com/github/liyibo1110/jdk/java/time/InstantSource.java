package com.github.liyibo1110.jdk.java.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 提供对当前时刻的访问。
 * 该接口的实例用于访问当前时刻的可插拔表示形式。例如可以使用InstantSource代替System.currentTimeMillis()。
 * 此抽象的主要目的是允许根据需要插入替代的时刻源。应用程序使用对象来获取当前时间，而不是使用静态方法。这可以简化测试。
 *
 * 因此该接口并不保证结果确实代表时间轴上的当前时刻。相反，它允许应用程序提供一个受控的视图，以展示当前时刻是什么。
 * 应用程序的最佳实践是将InstantSource传递给任何需要当前时刻的方法。依赖注入框架是实现此目标的一种方式：
 * public class MyBean {
 *     private InstantSource source;  // 依赖注入
 *     ...
 *     public void process(Instant endInstant) {
 *        if (source.instant().isAfter(endInstant)) {
 *         ...
 *
 *        }
 *     }
 * }
 *
 * 这种方法允许在测试期间使用替代来源，例如固定时间或偏移时间。
 * 系统工厂方法会基于可用的最佳系统时钟提供一个来源。这可能会使用System.currentTimeMillis()，或者如果有更高分辨率的时钟可用，则使用该时钟。
 * @author liyibo
 * @date 2026-03-31 14:19
 */
public interface InstantSource {

    static InstantSource system() {
        return SystemInstantSource.INSTANCE;
    }

    //-------------------------------------------------------------------------

    static InstantSource tick(InstantSource baseSource, Duration tickDuration) {
        Objects.requireNonNull(baseSource, "baseSource");
        return Clock.tick(baseSource.withZone(ZoneOffset.UTC), tickDuration);
    }

    //-----------------------------------------------------------------------

    static InstantSource fixed(Instant fixedInstant) {
        return Clock.fixed(fixedInstant, ZoneOffset.UTC);
    }

    //-----------------------------------------------------------------------

    static InstantSource offset(InstantSource baseSource, Duration offsetDuration) {
        Objects.requireNonNull(baseSource, "baseSource");
        return Clock.offset(baseSource.withZone(ZoneOffset.UTC), offsetDuration);
    }

    //-----------------------------------------------------------------------

    /**
     * 返回一个时间点，该时间点代表源所定义的当前时间点。
     */
    Instant instant();

    //-------------------------------------------------------------------------

    default long millis() {
        return instant().toEpochMilli();
    }

    //-----------------------------------------------------------------------

    default Clock withZone(ZoneId zone) {
        return new SourceClock(this, zone);
    }
}

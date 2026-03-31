package com.github.liyibo1110.jdk.java.time.zone;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.zone.TzdbZoneRulesProvider;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 向系统提供时区规则的提供者。
 * 该类负责管理时区规则的配置。静态方法提供了用于管理提供者的公共API。抽象方法提供了允许提供规则的SPI。
 *
 * ZoneRulesProvider可作为扩展类安装在Java平台的实例中，即把jar文件放置在任何常规的扩展目录中。
 * 已安装的提供者将通过ServiceLoader类定义的服务提供者加载机制进行加载。
 * ZoneRulesProvider通过位于资源目录META-INF/services中的名为java.time.zone.ZoneRulesProvider的提供程序配置文件进行自我标识。
 * 该文件应包含一行，指定具体的zonerules-provider类的全限定名称。
 * 提供程序还可以通过将其添加到类路径中，或通过registerProvider 法进行自我注册来提供。
 *
 * Java虚拟机有一个默认提供程序，用于为IANA时区数据库(TZDB)定义的时区提供时区规则。
 * 如果定义了系统属性java.time.zone.DefaultZoneRulesProvider，则将其视为一个具体ZoneRulesProvider类的完全限定名称，该类将通过系统类加载器加载为默认提供程序。
 * 如果未定义此系统属性，则会加载系统默认提供程序作为默认提供程序。
 *
 * 规则主要通过时区ID进行查找，该ID由ZoneId使用。此处仅允许使用时区区域ID，不使用时区偏移ID。
 * 时区规则具有政治性，因此数据可能随时变更。每个提供程序都会为每个时区ID提供最新规则，但也可能提供规则变更的历史记录。
 * @author liyibo
 * @date 2026-03-31 10:50
 */
public abstract class ZoneRulesProvider {

    private static final CopyOnWriteArrayList<ZoneRulesProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private static final ConcurrentMap<String, ZoneRulesProvider> ZONES = new ConcurrentHashMap<>(512, 0.75f, 2);

    private static volatile Set<String> ZONE_IDS;

    static {
        // if the property java.time.zone.DefaultZoneRulesProvider is
        // set then its value is the class name of the default provider
        final List<ZoneRulesProvider> loaded = new ArrayList<>();
        AccessController.doPrivileged(new PrivilegedAction<>() {
            public Object run() {
                String prop = System.getProperty("java.time.zone.DefaultZoneRulesProvider");
                if (prop != null) {
                    try {
                        Class<?> c = Class.forName(prop, true, ClassLoader.getSystemClassLoader());
                        @SuppressWarnings("deprecation")
                        ZoneRulesProvider provider = ZoneRulesProvider.class.cast(c.newInstance());
                        registerProvider(provider);
                        loaded.add(provider);
                    } catch (Exception x) {
                        throw new Error(x);
                    }
                } else {
                    registerProvider(new TzdbZoneRulesProvider());
                }
                return null;
            }
        });

        ServiceLoader<ZoneRulesProvider> sl = ServiceLoader.load(ZoneRulesProvider.class, ClassLoader.getSystemClassLoader());
        Iterator<ZoneRulesProvider> it = sl.iterator();
        while (it.hasNext()) {
            ZoneRulesProvider provider;
            try {
                provider = it.next();
            } catch (ServiceConfigurationError ex) {
                if (ex.getCause() instanceof SecurityException)
                    continue;  // ignore the security exception, try the next provider
                throw ex;
            }
            boolean found = false;
            for (ZoneRulesProvider p : loaded) {
                if (p.getClass() == provider.getClass())
                    found = true;
            }
            if (!found) {
                registerProvider0(provider);
                loaded.add(provider);
            }
        }
        // CopyOnWriteList could be slow if lots of providers and each added individually
        PROVIDERS.addAll(loaded);
    }

    //-------------------------------------------------------------------------

    public static Set<String> getAvailableZoneIds() {
        return ZONE_IDS;
    }

    public static ZoneRules getRules(String zoneId, boolean forCaching) {
        Objects.requireNonNull(zoneId, "zoneId");
        return getProvider(zoneId).provideRules(zoneId, forCaching);
    }

    public static NavigableMap<String, ZoneRules> getVersions(String zoneId) {
        Objects.requireNonNull(zoneId, "zoneId");
        return getProvider(zoneId).provideVersions(zoneId);
    }

    private static ZoneRulesProvider getProvider(String zoneId) {
        ZoneRulesProvider provider = ZONES.get(zoneId);
        if (provider == null) {
            if (ZONES.isEmpty())
                throw new ZoneRulesException("No time-zone data files registered");
            throw new ZoneRulesException("Unknown time-zone ID: " + zoneId);
        }
        return provider;
    }

    //-------------------------------------------------------------------------

    public static void registerProvider(ZoneRulesProvider provider) {
        Objects.requireNonNull(provider, "provider");
        registerProvider0(provider);
        PROVIDERS.add(provider);
    }

    private static synchronized void registerProvider0(ZoneRulesProvider provider) {
        for (String zoneId : provider.provideZoneIds()) {
            Objects.requireNonNull(zoneId, "zoneId");
            ZoneRulesProvider old = ZONES.putIfAbsent(zoneId, provider);
            if (old != null) {
                throw new ZoneRulesException(
                        "Unable to register zone as one already registered with that ID: " + zoneId + ", currently loading from provider: " + provider);
            }
        }
        Set<String> combinedSet = new HashSet<>(ZONES.keySet());
        ZONE_IDS = Collections.unmodifiableSet(combinedSet);
    }

    public static boolean refresh() {
        boolean changed = false;
        for (ZoneRulesProvider provider : PROVIDERS) {
            changed |= provider.provideRefresh();
        }
        return changed;
    }

    protected ZoneRulesProvider() {}

    //-----------------------------------------------------------------------

    protected abstract Set<String> provideZoneIds();

    protected abstract ZoneRules provideRules(String zoneId, boolean forCaching);

    protected abstract NavigableMap<String, ZoneRules> provideVersions(String zoneId);

    protected boolean provideRefresh() {
        return false;
    }
}

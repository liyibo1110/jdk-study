package com.github.liyibo1110.jdk.java.lang;

import jdk.internal.access.SharedSecrets;
import jdk.internal.reflect.CallerSensitive;
import jdk.internal.reflect.Reflection;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JVM运行时的状态，对Java层暴露的单例门面对象，可以理解成一个运行时的控制台，主要能力有：
 * 1、退出JVM：exit()
 * 2、注册shutdown hook：addShutdownHook
 * 3、调用外部进程：exec()，新代码已不推荐，推荐直接用ProcessBuilder
 * 4、查看内存：freeMemory() / totalMemory() / maxMemory()
 * 5、获取CPU数量：availableProcessors()
 * 6、GC相关入口：gc()
 * 用于：
 * 1、打印启动日志
 * 2、诊断环境
 * 3、做简单的资源感知配置
 * 4、观测JVM堆大小
 *
 * java.lang.System里面的一些方法实现，就是会直接调用Runtime的方法。
 * @author liyibo
 * @date 2026-03-26 14:01
 */
public class Runtime {

    private static final Runtime currentRuntime = new Runtime();

    private static Version version;

    /**
     * 统一的使用入口
     */
    public static Runtime getRuntime() {
        return currentRuntime;
    }

    private Runtime() {}

    public void exit(int status) {
        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if(security != null)
            security.checkExit(status);
        /**
         * 主要依靠这个类，内部会在关闭前调用注册的shutdown hook方法
         */
        Shutdown.exit(status);
    }

    /**
     * 注册shutdown hook，在exit方法内部会尝试调用特定的线程
     */
    public void addShutdownHook(Thread hook) {
        SecurityManager sm = System.getSecurityManager();
        if(sm != null)
            sm.checkPermission(new RuntimePermission("shutdownHooks"));
        ApplicationShutdownHooks.add(hook);
    }

    /**
     * 移除注册的shutdown hook
     */
    public boolean removeShutdownHook(Thread hook) {
        SecurityManager sm = System.getSecurityManager();
        if(sm != null)
            sm.checkPermission(new RuntimePermission("shutdownHooks"));
        return ApplicationShutdownHooks.remove(hook);
    }

    /**
     * 强制关闭JVM，不会调用shutdown hook
     * @param status
     */
    public void halt(int status) {
        @SuppressWarnings("removal")
        SecurityManager sm = System.getSecurityManager();
        if(sm != null)
            sm.checkExit(status);
        Shutdown.beforeHalt();
        Shutdown.halt(status);
    }

    public static java.lang.Runtime.Version version() {
        var v = version;
        if (v == null) {
            v = new Version(VersionProps.versionNumbers(),
                    VersionProps.pre(), VersionProps.build(),
                    VersionProps.optional());
            version = v;
        }
        return v;
    }

    public Process exec(String command) throws IOException {
        return exec(command, null, null);
    }

    public Process exec(String command, String[] envp) throws IOException {
        return exec(command, envp, null);
    }

    public Process exec(String command, String[] envp, File dir) throws IOException {
        if(command.isEmpty())
            throw new IllegalArgumentException("Empty command");
        StringTokenizer st = new StringTokenizer(command);
        String[] cmdarray = new String[st.countTokens()];
        for (int i = 0; st.hasMoreTokens(); i++)
            cmdarray[i] = st.nextToken();
        return exec(cmdarray, envp, dir);
    }

    public Process exec(String cmdarray[]) throws IOException {
        return exec(cmdarray, null, null);
    }

    public Process exec(String[] cmdarray, String[] envp) throws IOException {
        return exec(cmdarray, envp, null);
    }

    /**
     * 最终还是委托给ProcessBuilder
     */
    public Process exec(String[] cmdarray, String[] envp, File dir) throws IOException {
        return new ProcessBuilder(cmdarray)
                .environment(envp)
                .directory(dir)
                .start();
    }

    /**
     * JVM可用的处理器数量。
     * 常被线程池、并行框架用来估算默认的并行度，不同平台的底层实现代码区别可能比较大。
     */
    public native int availableProcessors();

    /**
     * JVM可尝试使用的最大堆内存上限。
     */
    public native long freeMemory();

    /**
     * 当前已经向OS申请到、归JVM管理的堆总量。
     */
    public native long totalMemory();

    /**
     * JVM可尝试使用的最大堆内存上限。
     */
    public native long maxMemory();

    /**
     * 建议运行GC，不是强制执行GC。
     */
    public native void gc();

    /**
     * finalize这块基本已经被淘汰了
     */
    public void runFinalization() {
        SharedSecrets.getJavaLangRefAccess().runFinalization();
    }

    /**
     * 加载native动态库，允许运行时调用本地代码。
     */
    @CallerSensitive
    public void load(String filename) {
        load0(Reflection.getCallerClass(), filename);
    }

    void load0(Class<?> fromClass, String filename) {
        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if(security != null)
            security.checkLink(filename);

        File file = new File(filename);
        if(!file.isAbsolute())
            throw new UnsatisfiedLinkError("Expecting an absolute path of the library: " + filename);

        ClassLoader.loadLibrary(fromClass, file);
    }

    @CallerSensitive
    public void loadLibrary(String libname) {
        loadLibrary0(Reflection.getCallerClass(), libname);
    }

    void loadLibrary0(Class<?> fromClass, String libname) {
        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if(security != null)
            security.checkLink(libname);

        if(libname.indexOf((int)File.separatorChar) != -1)
            throw new UnsatisfiedLinkError("Directory separator should not appear in library name: " + libname);

        ClassLoader.loadLibrary(fromClass, libname);
    }

    public static final class Version implements Comparable<Version> {
        private final List<Integer> version;
        private final Optional<String> pre;
        private final Optional<Integer> build;
        private final Optional<String>  optional;

        private Version(List<Integer> unmodifiableListOfVersions,
                        Optional<String> pre,
                        Optional<Integer> build,
                        Optional<String> optional) {
            this.version = unmodifiableListOfVersions;
            this.pre = pre;
            this.build = build;
            this.optional = optional;
        }

        public static Version parse(String s) {
            if(s == null)
                throw new NullPointerException();

            // Shortcut to avoid initializing VersionPattern when creating
            // feature-version constants during startup
            if (isSimpleNumber(s)) {
                return new Version(List.of(Integer.parseInt(s)), Optional.empty(), Optional.empty(), Optional.empty());
            }
            Matcher m = VersionPattern.VSTR_PATTERN.matcher(s);
            if(!m.matches())
                throw new IllegalArgumentException("Invalid version string: '" + s + "'");

            // $VNUM is a dot-separated list of integers of arbitrary length
            String[] split = m.group(VersionPattern.VNUM_GROUP).split("\\.");
            Integer[] version = new Integer[split.length];
            for(int i = 0; i < split.length; i++)
                version[i] = Integer.parseInt(split[i]);

            Optional<String> pre = Optional.ofNullable(m.group(VersionPattern.PRE_GROUP));

            String b = m.group(VersionPattern.BUILD_GROUP);
            // $BUILD is an integer
            Optional<Integer> build = (b == null) ? Optional.empty() : Optional.of(Integer.parseInt(b));

            Optional<String> optional = Optional.ofNullable(m.group(VersionPattern.OPT_GROUP));

            // empty '+'
            if(!build.isPresent()) {
                if(m.group(VersionPattern.PLUS_GROUP) != null) {
                    if(optional.isPresent()) {
                        if(pre.isPresent())
                            throw new IllegalArgumentException("'+' found with" + " pre-release and optional components:'" + s + "'");
                    }else {
                        throw new IllegalArgumentException("'+' found with neither" + " build or optional components: '" + s + "'");
                    }
                }else {
                    if(optional.isPresent() && !pre.isPresent())
                        throw new IllegalArgumentException("optional component" + " must be preceded by a pre-release component" + " or '+': '" + s + "'");
                }
            }
            return new Version(List.of(version), pre, build, optional);
        }

        private static boolean isSimpleNumber(String s) {
            for(int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                char lowerBound = (i > 0) ? '0' : '1';
                if(c < lowerBound || c > '9')
                    return false;
            }
            return true;
        }

        public int feature() {
            return version.get(0);
        }

        public int interim() {
            return (version.size() > 1 ? version.get(1) : 0);
        }

        public int update() {
            return (version.size() > 2 ? version.get(2) : 0);
        }

        public int patch() {
            return (version.size() > 3 ? version.get(3) : 0);
        }

        @Deprecated(since = "10")
        public int major() {
            return feature();
        }

        @Deprecated(since = "10")
        public int minor() {
            return interim();
        }

        @Deprecated(since = "10")
        public int security() {
            return update();
        }

        public List<Integer> version() {
            return version;
        }

        public Optional<String> pre() {
            return pre;
        }

        public Optional<Integer> build() {
            return build;
        }

        public Optional<String> optional() {
            return optional;
        }

        @Override
        public int compareTo(Version obj) {
            return compare(obj, false);
        }

        public int compareToIgnoreOptional(Version obj) {
            return compare(obj, true);
        }

        private int compare(Version obj, boolean ignoreOpt) {
            if(obj == null)
                throw new NullPointerException();

            int ret = compareVersion(obj);
            if (ret != 0)
                return ret;

            ret = comparePre(obj);
            if (ret != 0)
                return ret;

            ret = compareBuild(obj);
            if (ret != 0)
                return ret;

            if (!ignoreOpt)
                return compareOptional(obj);

            return 0;
        }

        private int compareVersion(Version obj) {
            int size = version.size();
            int oSize = obj.version().size();
            int min = Math.min(size, oSize);
            for(int i = 0; i < min; i++) {
                int val = version.get(i);
                int oVal = obj.version().get(i);
                if(val != oVal)
                    return val - oVal;
            }
            return size - oSize;
        }

        private int comparePre(Version obj) {
            Optional<String> oPre = obj.pre();
            if(!pre.isPresent()) {
                if(oPre.isPresent())
                    return 1;
            }else {
                if(!oPre.isPresent())
                    return -1;
                String val = pre.get();
                String oVal = oPre.get();
                if(val.matches("\\d+")) {
                    return (oVal.matches("\\d+")
                            ? (new BigInteger(val)).compareTo(new BigInteger(oVal))
                            : -1);
                }else {
                    return (oVal.matches("\\d+") ? 1 : val.compareTo(oVal));
                }
            }
            return 0;
        }

        private int compareBuild(Version obj) {
            Optional<Integer> oBuild = obj.build();
            if(oBuild.isPresent()) {
                return (build.isPresent() ? build.get().compareTo(oBuild.get()) : -1);
            }else if (build.isPresent()) {
                return 1;
            }
            return 0;
        }

        private int compareOptional(Version obj) {
            Optional<String> oOpt = obj.optional();
            if(!optional.isPresent()) {
                if(oOpt.isPresent())
                    return -1;
            }else {
                if(!oOpt.isPresent())
                    return 1;
                return optional.get().compareTo(oOpt.get());
            }
            return 0;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(version.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(".")));

            pre.ifPresent(v -> sb.append("-").append(v));

            if(build.isPresent()) {
                sb.append("+").append(build.get());
                if(optional.isPresent())
                    sb.append("-").append(optional.get());
            }else {
                if(optional.isPresent()) {
                    sb.append(pre.isPresent() ? "-" : "+-");
                    sb.append(optional.get());
                }
            }

            return sb.toString();
        }

        @Override
        public boolean equals(Object obj) {
            boolean ret = equalsIgnoreOptional(obj);
            if(!ret)
                return false;
            Version that = (Version)obj;
            return (this.optional().equals(that.optional()));
        }

        public boolean equalsIgnoreOptional(Object obj) {
            if(this == obj)
                return true;
            return (obj instanceof Version that)
                    && (this.version().equals(that.version())
                    && this.pre().equals(that.pre())
                    && this.build().equals(that.build()));
        }

        @Override
        public int hashCode() {
            int h = 1;
            int p = 17;

            h = p * h + version.hashCode();
            h = p * h + pre.hashCode();
            h = p * h + build.hashCode();
            h = p * h + optional.hashCode();

            return h;
        }
    }

    private static class VersionPattern {
        // $VNUM(-$PRE)?(\+($BUILD)?(\-$OPT)?)?
        // RE limits the format of version strings
        // ([1-9][0-9]*(?:(?:\.0)*\.[1-9][0-9]*)*)(?:-([a-zA-Z0-9]+))?(?:(\+)(0|[1-9][0-9]*)?)?(?:-([-a-zA-Z0-9.]+))?

        private static final String VNUM = "(?<VNUM>[1-9][0-9]*(?:(?:\\.0)*\\.[1-9][0-9]*)*)";
        private static final String PRE = "(?:-(?<PRE>[a-zA-Z0-9]+))?";
        private static final String BUILD = "(?:(?<PLUS>\\+)(?<BUILD>0|[1-9][0-9]*)?)?";
        private static final String OPT = "(?:-(?<OPT>[-a-zA-Z0-9.]+))?";
        private static final String VSTR_FORMAT = VNUM + PRE + BUILD + OPT;

        static final Pattern VSTR_PATTERN = Pattern.compile(VSTR_FORMAT);

        static final String VNUM_GROUP = "VNUM";
        static final String PRE_GROUP = "PRE";
        static final String PLUS_GROUP = "PLUS";
        static final String BUILD_GROUP = "BUILD";
        static final String OPT_GROUP = "OPT";
    }
}

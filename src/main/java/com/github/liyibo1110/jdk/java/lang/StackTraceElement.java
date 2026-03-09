package com.github.liyibo1110.jdk.java.lang;

import java.io.Serializable;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.util.Objects;
import java.util.Optional;

/**
 * StackTrace中的一个元素，由Throwable.getStackTrace()方法返回。
 * 每个元素代表一个单独的栈帧。除栈顶元素外，所有栈帧均表示方法调用。栈顶帧代表生成StackTrace时的执行点，通常即为创建该StackTrace对应异常的时刻。
 * @author liyibo
 * @date 2026-03-08 21:45
 */
public class StackTraceElement implements Serializable {

    private transient Class<?> declaringClassObject;

    private String classLoaderName;
    private String moduleName;
    private String moduleVersion;
    private String declaringClass;
    private String methodName;
    private String fileName;
    private int    lineNumber;
    private byte   format = 0; // Default to show all

    public StackTraceElement(String declaringClass, String methodName, String fileName, int lineNumber) {
        this(null, null, null, declaringClass, methodName, fileName, lineNumber);
    }

    public StackTraceElement(String classLoaderName,
                             String moduleName, String moduleVersion,
                             String declaringClass, String methodName,
                             String fileName, int lineNumber) {
        this.classLoaderName = classLoaderName;
        this.moduleName = moduleName;
        this.moduleVersion = moduleVersion;
        this.declaringClass = Objects.requireNonNull(declaringClass, "Declaring class is null");
        this.methodName = Objects.requireNonNull(methodName, "Method name is null");
        this.fileName = fileName;
        this.lineNumber = lineNumber;
    }

    private StackTraceElement() {}

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getModuleName() {
        return moduleName;
    }

    public String getModuleVersion() {
        return moduleVersion;
    }

    public String getClassLoaderName() {
        return classLoaderName;
    }

    public String getClassName() {
        return declaringClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public boolean isNativeMethod() {
        return lineNumber == -2;
    }

    public String toString() {
        String s = "";
        if(!dropClassLoaderName() && classLoaderName != null && !classLoaderName.isEmpty()) {
            s += classLoaderName + "/";
        }
        if(moduleName != null && !moduleName.isEmpty()) {
            s += moduleName;

            if(!dropModuleVersion() && moduleVersion != null && !moduleVersion.isEmpty()) {
                s += "@" + moduleVersion;
            }
        }
        s = s.isEmpty() ? declaringClass : s + "/" + declaringClass;

        return s + "." + methodName + "(" +
                (isNativeMethod() ? "Native Method)" :
                        (fileName != null && lineNumber >= 0 ?
                                fileName + ":" + lineNumber + ")" :
                                (fileName != null ?  ""+fileName+")" : "Unknown Source)")));
    }

    public boolean equals(Object obj) {
        if(obj==this)
            return true;
        return (obj instanceof StackTraceElement e)
                && e.lineNumber == lineNumber
                && e.declaringClass.equals(declaringClass)
                && Objects.equals(classLoaderName, e.classLoaderName)
                && Objects.equals(moduleName, e.moduleName)
                && Objects.equals(moduleVersion, e.moduleVersion)
                && Objects.equals(methodName, e.methodName)
                && Objects.equals(fileName, e.fileName);
    }

    public int hashCode() {
        int result = 31*declaringClass.hashCode() + methodName.hashCode();
        result = 31*result + Objects.hashCode(classLoaderName);
        result = 31*result + Objects.hashCode(moduleName);
        result = 31*result + Objects.hashCode(moduleVersion);
        result = 31*result + Objects.hashCode(fileName);
        result = 31*result + lineNumber;
        return result;
    }

    private synchronized void computeFormat() {
        try {
            Class<?> cls = (Class<?>) declaringClassObject;
            ClassLoader loader = cls.getClassLoader0();
            Module m = cls.getModule();
            byte bits = 0;

            // First element - class loader name
            // Call package-private ClassLoader::name method

            if (loader instanceof BuiltinClassLoader) {
                bits |= BUILTIN_CLASS_LOADER;
            }

            // Second element - module name and version

            // Omit if is a JDK non-upgradeable module (recorded in the hashes
            // in java.base)
            if (isHashedInJavaBase(m)) {
                bits |= JDK_NON_UPGRADEABLE_MODULE;
            }
            format = bits;
        } finally {
            // Class reference no longer needed, clear it
            declaringClassObject = null;
        }
    }

    private static final byte BUILTIN_CLASS_LOADER       = 0x1;
    private static final byte JDK_NON_UPGRADEABLE_MODULE = 0x2;

    private boolean dropClassLoaderName() {
        return (format & BUILTIN_CLASS_LOADER) == BUILTIN_CLASS_LOADER;
    }

    private boolean dropModuleVersion() {
        return (format & JDK_NON_UPGRADEABLE_MODULE) == JDK_NON_UPGRADEABLE_MODULE;
    }

    private static boolean isHashedInJavaBase(Module m) {
        // return true if module system is not initialized as the code
        // must be in java.base
        if (!VM.isModuleSystemInited())
            return true;

        return ModuleLayer.boot() == m.getLayer() && HashedModules.contains(m);
    }

    private static class HashedModules {
        static Set<String> HASHED_MODULES = hashedModules();

        static Set<String> hashedModules() {

            Optional<ResolvedModule> resolvedModule = ModuleLayer.boot()
                    .configuration()
                    .findModule("java.base");
            assert resolvedModule.isPresent();
            ModuleReference mref = resolvedModule.get().reference();
            assert mref instanceof ModuleReferenceImpl;
            ModuleHashes hashes = ((ModuleReferenceImpl)mref).recordedHashes();
            if (hashes != null) {
                Set<String> names = new HashSet<>(hashes.names());
                names.add("java.base");
                return names;
            }

            return Set.of();
        }

        static boolean contains(Module m) {
            return HASHED_MODULES.contains(m.getName());
        }
    }

    static StackTraceElement[] of(Throwable x, int depth) {
        StackTraceElement[] stackTrace = new StackTraceElement[depth];
        for (int i = 0; i < depth; i++) {
            stackTrace[i] = new StackTraceElement();
        }

        // VM to fill in StackTraceElement
        initStackTraceElements(stackTrace, x);

        // ensure the proper StackTraceElement initialization
        for (StackTraceElement ste : stackTrace) {
            ste.computeFormat();
        }
        return stackTrace;
    }

    static StackTraceElement of(StackFrameInfo sfi) {
        StackTraceElement ste = new StackTraceElement();
        initStackTraceElement(ste, sfi);

        ste.computeFormat();
        return ste;
    }

    private static native void initStackTraceElements(StackTraceElement[] elements, Throwable x);

    private static native void initStackTraceElement(StackTraceElement element, StackFrameInfo sfi);

    @java.io.Serial
    private static final long serialVersionUID = 6992337162326171013L;
}

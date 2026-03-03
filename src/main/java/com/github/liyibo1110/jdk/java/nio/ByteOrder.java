package com.github.liyibo1110.jdk.java.nio;

import sun.misc.Unsafe;

/**
 * 针对字节序的类型安全枚举
 * @author liyibo
 * @date 2026-03-02 13:36
 */
public final class ByteOrder {
    private String name;

    private ByteOrder(String name) {
        this.name = name;
    }

    public static final ByteOrder BIG_ENDIAN = new ByteOrder("BIG_ENDIAN");
    public static final ByteOrder LITTLE_ENDIAN = new ByteOrder("LITTLE_ENDIAN");

    private static final ByteOrder NATIVE_ORDER = Unsafe.getUnsafe().isBigEndian()
            ? ByteOrder.BIG_ENDIAN
            : ByteOrder.LITTLE_ENDIAN;

    public static ByteOrder nativeOrder() {
        return NATIVE_ORDER;
    }

    public String toString() {
        return name;
    }
}

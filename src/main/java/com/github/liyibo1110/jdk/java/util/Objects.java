package com.github.liyibo1110.jdk.java.util;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * 本类包含用于操作对象或在操作前检查特定条件的静态实用方法，包括：
 * 1、计算对象hash的空安全或空容忍方法。
 * 2、返回对象字符串的方法。
 * 3、比较两个对象的方法。
 * 4、以及检查索引或子区间值是否越界的方法。
 * @author liyibo
 * @date 2026-03-04 15:04
 */
public final class Objects {
    private Objects() {
        throw new AssertionError("No java.util.Objects instances for you!");
    }

    public static boolean equals(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    public static boolean deepEquals(Object a, Object b) {
        if(a == b)
            return true;
        else if(a == null || b == null)
            return false;
        else
            return Arrays.deepEquals0(a, b);
    }

    public static int hashCode(Object o) {
        return o != null ? o.hashCode() : 0;
    }

    public static int hash(Object... values) {
        return Arrays.hashCode(values);
    }

    public static String toString(Object o) {
        return String.valueOf(o);
    }

    public static String toString(Object o, String nullDefault) {
        return (o != null) ? o.toString() : nullDefault;
    }

    public static <T> int compare(T a, T b, Comparator<? super T> c) {
        return (a == b) ? 0 : c.compare(a, b);
    }

    public static <T> T requireNonNull(T obj) {
        if(obj == null)
            throw new NullPointerException();
        return obj;
    }

    public static <T> T requireNonNull(T obj, String message) {
        if(obj == null)
            throw new NullPointerException(message);
        return obj;
    }

    public static boolean isNull(Object obj) {
        return obj == null;
    }

    public static boolean nonNull(Object obj) {
        return obj != null;
    }

    public static <T> T requireNonNullElse(T obj, T defaultObj) {
        return (obj != null) ? obj : requireNonNull(defaultObj, "defaultObj");
    }

    public static <T> T requireNonNullElseGet(T obj, Supplier<? extends T> supplier) {
        return (obj != null) ? obj : requireNonNull(requireNonNull(supplier, "supplier").get(), "supplier.get()");
    }

    public static <T> T requireNonNull(T obj, Supplier<String> messageSupplier) {
        if (obj == null)
            throw new NullPointerException(messageSupplier == null ? null : messageSupplier.get());
        return obj;
    }

    /**
     * 检查index是否在[0, length)范围内。
     */
    public static int checkIndex(int index, int length) {
        return Preconditions.checkIndex(index, length, null);
    }

    /**
     * 检查[fromIndex, toIndex)是否在范围[0, length)的边界内。
     */
    public static int checkFromToIndex(int fromIndex, int toIndex, int length) {
        return Preconditions.checkFromToIndex(fromIndex, toIndex, length, null);
    }

    /**
     * 检查[fromIndex, fromIndex + size)是否在范围[0, length)的边界内。
     */
    public static int checkFromIndexSize(int fromIndex, int size, int length) {
        return Preconditions.checkFromIndexSize(fromIndex, size, length, null);
    }

    public static long checkIndex(long index, long length) {
        return Preconditions.checkIndex(index, length, null);
    }

    public static long checkFromToIndex(long fromIndex, long toIndex, long length) {
        return Preconditions.checkFromToIndex(fromIndex, toIndex, length, null);
    }

    public static long checkFromIndexSize(long fromIndex, long size, long length) {
        return Preconditions.checkFromIndexSize(fromIndex, size, length, null);
    }
}

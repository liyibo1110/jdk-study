package com.github.liyibo1110.jdk.java.util;

import jdk.internal.access.SharedSecrets;

import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.JumboEnumSet;
import java.util.RegularEnumSet;

/**
 * 只能存放同一种枚举类型常量的Set，利用枚举的固定性，专门实现的高效集合，特点是：
 * 1、专门给枚举设计的。
 * 2、内部结构不是hash表，通常是位图或位向量。
 * 3、性能和空间效率都很好。
 * 本质上是：把枚举常量映射成bit位的专用集合，在业务代码里适合表达：一组离散开关 / 状态许可 / 能力集合，例如：
 * 1、权限集合：
 * enum Permission {
 *     READ, WRITE, DELETE, EXPORT
 * }
 * EnumSet<Permission> permissions = EnumSet.of(Permission.READ, Permission.WRITE);
 * 表现力优于一堆boolean字段或普通的HashSet<Permission>
 *
 * 2、状态黑白名单：
 * enum OrderStatus {
 *     CREATED, PAID, SHIPPED, DONE, CANCELED
 * }
 * EnumSet<OrderStatus> cancellable = EnumSet.of(OrderStatus.CREATED, OrderStatus.PAID);
 * if (cancellable.contains(status)) { ... }
 *
 * 3、配置型标志位集合：
 * enum Option {
 *     VERBOSE, DRY_RUN, FORCE, RECURSIVE
 * }
 * 命令参数启用了哪些选项，用EnumSet也很合适。
 *
 * 元素是枚举带来的特点：
 * 1、数量在编译器固定：因为枚举常量在编译后就基本定了，因此EnumSet的元素最大值也是编译器固定的。
 * 2、每个枚举元素有稳定的ordinal字段。
 * 3、每个枚举元素身份是唯一的，本来就是单例，直接用==来比较即可。
 * 这意味着JDK可以直接用位图来存储：
 * - 第0个枚举常量 -> 第0bit
 * - 第1个枚举常量 -> 第1bit
 * - 第2个枚举常量 -> 第2bit
 * 一个集合可以用long或long[]直接存储。
 * 1、contains实际是按位判断。
 * 2、add实际是置位操作。
 * 3、remove实际是清空bit位。
 * 4、并集/交集/补集都是位运算。
 *
 * 最后注意这个类是个抽象类，实际干活的类是：
 * 1、RegularEnumSet：枚举常量不超过64个，内部直接用一个long来存储所有。
 * 2、JumboEnumSet：枚举常量超过了64个，内部用long[]来存储所有，其实不太常用。
 * @author liyibo
 * @date 2026-03-26 10:48
 */
public abstract class EnumSet<E extends Enum<E>> extends AbstractSet<E> implements Cloneable, Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1009687484059888093L;

    /** 集合存的元素，是哪个枚举类 */
    final transient Class<E> elementType;

    /**
     * 这个枚举类的所有常量实例，用途是：
     * 1、迭代时要把bit反解成枚举常量。
     * 2、范围操作时要按ordinal找对应元素。
     * 3、补集操作时要知道全集范围。
     **/
    final transient Enum<?>[] universe;

    EnumSet(Class<E> elementType, Enum<?>[] universe) {
        this.elementType = elementType;
        this.universe    = universe;
    }

    /**
     * 创建空集合
     */
    public static <E extends Enum<E>> EnumSet<E> noneOf(Class<E> elementType) {
        Enum<?>[] universe = getUniverse(elementType);
        if(universe == null)
            throw new ClassCastException(elementType + " not an enum");
        /** 根据元素数量选择合适的实现子类 */
        if(universe.length <= 64)
            return new RegularEnumSet<>(elementType, universe);
        else
            return new JumboEnumSet<>(elementType, universe);
    }

    /**
     * 创建带有所有枚举常量的集合
     */
    public static <E extends Enum<E>> EnumSet<E> allOf(Class<E> elementType) {
        EnumSet<E> result = noneOf(elementType);
        result.addAll();
        return result;
    }

    /**
     * 子类实现，将universe里面的枚举，映射到自己的底层数据结构里（long或long[]）
     */
    abstract void addAll();

    public static <E extends Enum<E>> EnumSet<E> copyOf(EnumSet<E> s) {
        return s.clone();
    }

    public EnumSet<E> clone() {
        try {
            return (EnumSet<E>) super.clone();
        } catch(CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    public static <E extends Enum<E>> EnumSet<E> copyOf(Collection<E> c) {
        if(c instanceof EnumSet) {
            return ((EnumSet<E>)c).clone();
        }else {
            if(c.isEmpty())
                throw new IllegalArgumentException("Collection is empty");
            Iterator<E> i = c.iterator();
            E first = i.next();
            EnumSet<E> result = EnumSet.of(first);
            while (i.hasNext())
                result.add(i.next());
            return result;
        }
    }

    /**
     * 求补集，底层基于位图会让这个操作实现比较简单（位翻转 + 清理无效位）。
     */
    public static <E extends Enum<E>> EnumSet<E> complementOf(EnumSet<E> s) {
        EnumSet<E> result = copyOf(s);
        result.complement();
        return result;
    }

    /**
     * 创建单实例集合
     */
    public static <E extends Enum<E>> EnumSet<E> of(E e) {
        EnumSet<E> result = noneOf(e.getDeclaringClass());
        result.add(e);
        return result;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e1, E e2) {
        EnumSet<E> result = noneOf(e1.getDeclaringClass());
        result.add(e1);
        result.add(e2);
        return result;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e1, E e2, E e3) {
        EnumSet<E> result = noneOf(e1.getDeclaringClass());
        result.add(e1);
        result.add(e2);
        result.add(e3);
        return result;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e1, E e2, E e3, E e4) {
        EnumSet<E> result = noneOf(e1.getDeclaringClass());
        result.add(e1);
        result.add(e2);
        result.add(e3);
        result.add(e4);
        return result;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e1, E e2, E e3, E e4, E e5) {
        EnumSet<E> result = noneOf(e1.getDeclaringClass());
        result.add(e1);
        result.add(e2);
        result.add(e3);
        result.add(e4);
        result.add(e5);
        return result;
    }

    @SafeVarargs
    public static <E extends Enum<E>> EnumSet<E> of(E first, E... rest) {
        EnumSet<E> result = noneOf(first.getDeclaringClass());
        result.add(first);
        for(E e : rest)
            result.add(e);
        return result;
    }

    public static <E extends Enum<E>> EnumSet<E> range(E from, E to) {
        if(from.compareTo(to) > 0)
            throw new IllegalArgumentException(from + " > " + to);
        EnumSet<E> result = noneOf(from.getDeclaringClass());
        result.addRange(from, to);
        return result;
    }

    /**
     * 将特定范围的枚举常量，写到底层数据存储，注意这基于了ordinal天然有序的特性才能实现。
     */
    abstract void addRange(E from, E to);

    /**
     *
     */
    abstract void complement();

    final void typeCheck(E e) {
        Class<?> eClass = e.getClass();
        if(eClass != elementType && eClass.getSuperclass() != elementType)
            throw new ClassCastException(eClass + " != " + elementType);
    }

    private static <E extends Enum<E>> E[] getUniverse(Class<E> elementType) {
        return SharedSecrets.getJavaLangAccess().getEnumConstantsShared(elementType);
    }

    private static class SerializationProxy<E extends Enum<E>> implements java.io.Serializable {
        private static final Enum<?>[] ZERO_LENGTH_ENUM_ARRAY = new Enum<?>[0];

        private final Class<E> elementType;

        private final Enum<?>[] elements;

        SerializationProxy(EnumSet<E> set) {
            elementType = set.elementType;
            elements = set.toArray(ZERO_LENGTH_ENUM_ARRAY);
        }

        @java.io.Serial
        private Object readResolve() {
            // instead of cast to E, we should perhaps use elementType.cast()
            // to avoid injection of forged stream, but it will slow the
            // implementation
            EnumSet<E> result = EnumSet.noneOf(elementType);
            for(Enum<?> e : elements)
                result.add((E)e);
            return result;
        }

        @java.io.Serial
        private static final long serialVersionUID = 362491234563181265L;
    }

    @java.io.Serial
    Object writeReplace() {
        return new SerializationProxy<>(this);
    }

    @java.io.Serial
    private void readObject(java.io.ObjectInputStream s) throws java.io.InvalidObjectException {
        throw new java.io.InvalidObjectException("Proxy required");
    }

    @java.io.Serial
    private void readObjectNoData() throws java.io.InvalidObjectException {
        throw new java.io.InvalidObjectException("Proxy required");
    }
}

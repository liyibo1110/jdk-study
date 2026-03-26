package com.github.liyibo1110.jdk.java.lang;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.constant.ClassDesc;
import java.lang.constant.Constable;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicConstantDesc;
import java.lang.invoke.MethodHandles;

import static java.util.Objects.requireNonNull;

/**
 * 这个Enum只是枚举体系的公共父类，不是整个枚举机制的全部，枚举相关的代码分散在：
 * 1、编译器会给enum关键字标记的类，生成values()、valueOf(String)相关动态代码。
 * 2、反射相关包里。
 * 3、EnumSet / EnumMap类里。
 *
 * 要建立的认知：每个枚举实例天生就带着两份由编译器传来的元数据
 * 1、名字：源代码里写的常量名，比如RED。
 * 2、序号：声明的顺序，从0开始计数。
 * 需要理解：枚举常量不是普通对象随便new出来的，是由编译器按固定规则构造、并带有语言语义的对象。
 * @author liyibo
 * @date 2026-03-25 18:25
 */
public abstract class Enum<E extends Enum> implements Constable, Comparable<E>, Serializable {
    private final String name;

    public final String name() {
        return name;
    }

    private final int ordinal;

    public final int ordinal() {
        return ordinal;
    }

    protected Enum(String name, int ordinal) {
        this.name = name;
        this.ordinal = ordinal;
    }

    public String toString() {
        return name;
    }

    public final boolean equals(java.lang.Object other) {
        return this==other;
    }

    public final int hashCode() {
        return super.hashCode();
    }

    protected final java.lang.Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public final int compareTo(E o) {
        Enum<?> other = (Enum<?>)o;
        Enum<E> self = this;
        if(self.getClass() != other.getClass() && self.getDeclaringClass() != other.getDeclaringClass())
            throw new ClassCastException();
        return self.ordinal - other.ordinal;
    }

    public final Class<E> getDeclaringClass() {
        Class<?> clazz = getClass();
        Class<?> zuper = clazz.getSuperclass();
        return (zuper == Enum.class) ? (Class<E>)clazz : (Class<E>)zuper;
    }

    @Override
    public final Optional<EnumDesc<E>> describeConstable() {
        return getDeclaringClass()
                .describeConstable()
                .map(c -> EnumDesc.of(c, name));
    }

    public static <T extends Enum<T>> T valueOf(Class<T> enumClass, String name) {
        T result = enumClass.enumConstantDirectory().get(name);
        if(result != null)
            return result;
        if(name == null)
            throw new NullPointerException("Name is null");
        throw new IllegalArgumentException("No enum constant " + enumClass.getCanonicalName() + "." + name);
    }

    protected final void finalize() {}

    @java.io.Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        throw new InvalidObjectException("can't deserialize enum");
    }

    @java.io.Serial
    private void readObjectNoData() throws ObjectStreamException {
        throw new InvalidObjectException("can't deserialize enum");
    }

    public static final class EnumDesc<E extends Enum<E>> extends DynamicConstantDesc<E> {

        /**
         * Constructs a nominal descriptor for the specified {@code enum} class and name.
         *
         * @param constantClass a {@link ClassDesc} describing the {@code enum} class
         * @param constantName the unqualified name of the enum constant
         * @throws NullPointerException if any argument is null
         * @jvms 4.2.2 Unqualified Names
         */
        private EnumDesc(ClassDesc constantClass, String constantName) {
            super(ConstantDescs.BSM_ENUM_CONSTANT, requireNonNull(constantName), requireNonNull(constantClass));
        }

        /**
         * Returns a nominal descriptor for the specified {@code enum} class and name
         *
         * @param <E> the type of the enum constant
         * @param enumClass a {@link ClassDesc} describing the {@code enum} class
         * @param constantName the unqualified name of the enum constant
         * @return the nominal descriptor
         * @throws NullPointerException if any argument is null
         * @jvms 4.2.2 Unqualified Names
         * @since 12
         */
        public static<E extends Enum<E>> EnumDesc<E> of(ClassDesc enumClass, String constantName) {
            return new EnumDesc<>(enumClass, constantName);
        }

        @Override
        @SuppressWarnings("unchecked")
        public E resolveConstantDesc(MethodHandles.Lookup lookup) throws ReflectiveOperationException {
            return Enum.valueOf((Class<E>) constantType().resolveConstantDesc(lookup), constantName());
        }

        @Override
        public String toString() {
            return String.format("EnumDesc[%s.%s]", constantType().displayName(), constantName());
        }
    }
}

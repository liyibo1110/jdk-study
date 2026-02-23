package com.github.liyibo1110.jdk.java.util;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 有序集合（亦称序列）。该接口的使用者可精确控制每个元素在List中的插入位置。使用者可通过整数索引（List中的位置）访问元素，并在List中搜索元素。
 * 与集合不同，List通常允许重复元素。更正式地说，List通常允许存在元素对e1和e2满足e1.equals(e2)，且若允许null元素则通常允许多个null元素。
 * 虽然有人可能希望通过在用户尝试插入重复元素时抛出运行时异常来实现禁止重复的List，但我们认为这种用法较为罕见。
 *
 * List接口在Collection接口规定的基础上，对迭代器、添加、移除、equals和hashCode方法的契约提出了额外要求。为方便起见，此处还包含了其他继承方法的声明。
 * List接口提供了四种用于按位置（索引）访问List元素的方法。List（如同Java数组）采用零基索引。
 * 需注意，某些实现（例如LinkedList类）中这些操作的执行时间可能与索引值成正比。因此，当调用方不了解具体实现时，遍历List元素通常优于直接索引访问。
 *
 * List接口提供了一种特殊的迭代器，称为ListIterator，它不仅支持迭代器接口提供的常规操作，还允许插入和替换元素，并支持双向访问。
 * 该接口提供了一种方法，可获取从List中指定位置开始的List迭代器。
 * List接口提供了两种搜索指定对象的方法。从性能角度考虑，使用这些方法时应谨慎。在许多实现中，它们会执行耗费资源的线性搜索。
 * List接口提供了两种方法，可高效地在List任意位置插入或移除多个元素。
 *
 * 注意：虽然允许List包含自身作为元素，但建议极其谨慎：对于此类List，equals和hashCode方法将不再具有良好定义。
 * 某些List实现对其可包含的元素存在限制。例如，部分实现禁止包含null元素，另一些则对元素类型设有限制。
 * 尝试添加不符合条件的元素将抛出未检查异常，通常为NullPointerException或ClassCastException。
 * 尝试查询不合格元素的存在性可能抛出异常，也可能直接返回 false；不同实现可能表现为上述两种行为之一。
 * 更普遍而言，对不合格元素执行操作（该操作完成后不会导致不合格元素被插入List）时，实现可选择抛出异常或直接成功。
 * 此类异常在该接口规范中标记为“可选”。
 *
 * Unmodifiable Lists
 *
 * List.of和List.copyOf静态工厂方法提供了创建不可修改List的便捷方式。通过这些方法创建的List实例具有以下特征：
 * 1、它们不可修改。无法添加、删除或替换元素。对List调用任何修改器方法都会抛出UnsupportedOperationException。
 * 但若所含元素本身可变，则可能导致List内容看似发生变化。
 * 2、禁止包含空元素。尝试使用空元素创建List将引发NullPointerException异常。
 * 3、若所有元素均可序列化，则List本身可序列化。
 * 4、List中元素的顺序与传入参数的顺序一致，或与传入数组中元素的顺序一致。
 * 5、List及其子List视图实现RandomAccess接口。
 * 6、List基于值实现,程序员应将相等的实例视为可互换，且不应用于同步操作，否则可能引发不可预测行为。
 * 例如在未来版本中，同步操作可能失败。调用方不应预设返回实例的身份。工厂可自由创建新实例或复用现有实例。
 * 7、序列化方式遵循“序列化形式”页面的规定。
 * @author liyibo
 * @date 2026-02-23 16:41
 */
public interface List<E> extends Collection<E> {

    // Query Operations

    int size();

    boolean isEmpty();

    boolean contains(Object o);

    Iterator<E> iterator();

    Object[] toArray();

    <T> T[] toArray(T[] a);

    // Modification Operations

    boolean add(E e);

    boolean remove(Object o);

    boolean containsAll(Collection<?> c);

    boolean addAll(Collection<? extends E> c);

    boolean addAll(int index, Collection<? extends E> c);

    boolean removeAll(Collection<?> c);

    boolean retainAll(Collection<?> c);

    /**
     * 将此List中的每个元素替换成为对该元素引用Operator后的结果。
     * Operator抛出的错误或运行时异常将转发给调用方。
     */
    default void replaceAll(UnaryOperator<E> operator) {
        Objects.requireNonNull(operator);
        final ListIterator<E> li = this.listIterator();
        while(li.hasNext())
            li.set(operator.apply(li.next()));
    }

    default void sort(Comparator<? super E> c) {
        Object[] a = this.toArray();
        Arrays.sort(a, (Comparator)c);
        ListIterator<E> i = this.listIterator();
        for(Object e : a) {
            i.next();
            i.set((E)e);
        }
    }

    void clear();

    boolean equals(Object o);

    int hashCode();

    E get(int index);

    E set(int index, E element);

    void add(int index, E element);

    E remove(int index);

    // Search Operations

    int indexOf(Object o);

    int lastIndexOf(Object o);

    ListIterator<E> listIterator();

    ListIterator<E> listIterator(int index);

    // View

    List<E> subList(int fromIndex, int toIndex);

    static <E> List<E> of() {
        return (List<E>) ImmutableCollections.EMPTY_LIST;   // ImmutableCollections是package访问权限
    }

    static <E> List<E> of(E e1) {
        return new ImmutableCollections.List12<>(e1);
    }

    static <E> List<E> of(E e1, E e2) {
        return new ImmutableCollections.List12<>(e1, e2);
    }

    static <E> List<E> of(E e1, E e2, E e3) {
        return ImmutableCollections.listFromTrustedArray(e1, e2, e3);
    }

    static <E> List<E> of(E e1, E e2, E e3, E e4) {
        return ImmutableCollections.listFromTrustedArray(e1, e2, e3, e4);
    }

    static <E> List<E> of(E e1, E e2, E e3, E e4, E e5) {
        return ImmutableCollections.listFromTrustedArray(e1, e2, e3, e4, e5);
    }

    static <E> List<E> of(E e1, E e2, E e3, E e4, E e5, E e6) {
        return ImmutableCollections.listFromTrustedArray(e1, e2, e3, e4, e5, e6);
    }

    static <E> List<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7) {
        return ImmutableCollections.listFromTrustedArray(e1, e2, e3, e4, e5, e6, e7);
    }

    static <E> List<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8) {
        return ImmutableCollections.listFromTrustedArray(e1, e2, e3, e4, e5, e6, e7, e8);
    }

    static <E> List<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9) {
        return ImmutableCollections.listFromTrustedArray(e1, e2, e3, e4, e5, e6, e7, e8, e9);
    }

    static <E> List<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9, E e10) {
        return ImmutableCollections.listFromTrustedArray(e1, e2, e3, e4, e5, e6, e7, e8, e9, e10);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    static <E> List<E> of(E... elements) {
        switch(elements.length) {
            case 0:
                @SuppressWarnings("unchecked")
                var list = (List<E>) ImmutableCollections.EMPTY_LIST;
                return list;
            case 1:
                return new ImmutableCollections.List12<>(elements[0]);
            case 2:
                return new ImmutableCollections.List12<>(elements[0], elements[1]);
            default:
                return ImmutableCollections.listFromArray(elements);
        }
    }

    static <E> List<E> copyOf(Collection<? extends E> coll) {
        return ImmutableCollections.listCopy(coll);
    }
}

package com.github.liyibo1110.jdk.java.util;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;

/**
 * 该类提供了Collection接口的骨架实现，以最大限度地减少实现该接口所需的工作量。
 * 要实现不可修改的集合，程序员只需继承该类并为迭代器和size方法提供实现。（由iterator方法返回的迭代器必须实现hasNext和next方法。）
 * 若要实现可修改集合，程序员还需重写本类的add方法（否则将抛出UnsupportedOperationException异常），且迭代器方法返回的迭代器必须额外实现remove方法。
 * 根据Collection接口规范建议，程序员通常应提供void（无参数）和Collection构造函数。
 * 本类中每个非抽象方法的文档均详细描述了其实现方式。若所实现的集合允许更高效的实现方案，这些方法均可被重写。
 * @author liyibo
 * @date 2026-02-23 14:24
 */
public abstract class AbstractCollection<E> implements Collection<E> {

    protected AbstractCollection() {}

    // Query Operations

    public abstract Iterator<E> iterator();

    public abstract int size();

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean contains(Object o) {
        Iterator<E> it = iterator();
        if(o == null) { // 默认允许为null
            while(it.hasNext()) {
                if(it.next() == null)
                    return true;
            }
        }else {
            while(it.hasNext()) {
                if(o.equals(it.next()))
                    return true;
            }
        }
        return false;
    }

    /**
     * 此实现返回一个数组，其中包含该集合迭代器返回的所有元素，且元素顺序与迭代器保持一致，这些元素存储在数组中从索引0开始的连续位置。
     * 返回数组的长度等于迭代器返回的元素数量，即使在迭代过程中集合大小发生变化（例如当集合允许在迭代期间并发修改），该长度仍保持不变。
     * size方法仅作为优化提示调用，即使迭代器返回的元素数量不同，仍会返回正确结果。
     */
    public Object[] toArray() {
        Object[] r = new Object[size()];
        Iterator<E> it = iterator();
        for(int i = 0; i < r.length; i++) {
            if(!it.hasNext())   // 迭代器没有足够的元素，则直接返回全新的数组
                return Arrays.copyOf(r, i);
            r[i] = it.next();
        }
        // 到这里填满了r数组，但是如果迭代器仍然有数据，依然要扩容
        return it.hasNext() ? finishToArray(r, it): r;
    }

    public <T> T[] toArray(T[] a) {
        int size = size();
        T[] r = a.length >= size ? a : (T[])java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
        Iterator<E> it = iterator();

        for(int i = 0; i < r.length; i++) {
            if(!it.hasNext()) {
                if(a == r) {
                    r[i] = null; // null-terminate
                }else if (a.length < i) {
                    return Arrays.copyOf(r, i);
                }else {
                    System.arraycopy(r, 0, a, 0, i);
                    if(a.length > i) {
                        a[i] = null;
                    }
                }
                return a;
            }
            r[i] = (T)it.next();
        }
        return it.hasNext() ? finishToArray(r, it) : r;
    }

    /**
     * 当迭代器返回的元素数量超出预期时，重新分配toArray内部使用的数组，并完成从迭代器填充数组的操作。
     */
    private static <T> T[] finishToArray(T[] r, Iterator<?> it) {
        int len = r.length;
        int i = len;
        while(it.hasNext()) {
            if (i == len) {
                len = ArraysSupport.newLength(len,
                        1,             /* minimum growth */
                        (len >> 1) + 1 /* preferred growth */);
                r = Arrays.copyOf(r, len);
            }
            r[i++] = (T)it.next();
        }
        return (i == len) ? r : Arrays.copyOf(r, i);
    }

    // Modification Operations

    public boolean add(E e) {
        throw new UnsupportedOperationException();
    }

    public boolean remove(Object o) {
        Iterator<E> it = iterator();
        if(o==null) {
            while(it.hasNext()) {
                if(it.next()==null) {
                    it.remove();
                    return true;
                }
            }
        }else {
            while(it.hasNext()) {
                if(o.equals(it.next())) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }

    // Bulk Operations

    public boolean containsAll(Collection<?> c) {
        for(Object e : c) {
            if(!contains(e))
                return false;
        }
        return true;
    }

    public boolean addAll(Collection<? extends E> c) {
        boolean modified = false;
        for(E e : c) {
            if(add(e))
                modified = true;
        }
        return modified;
    }

    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;
        Iterator<?> it = iterator();
        while(it.hasNext()) {
            if(c.contains(it.next())) {
                it.remove();
                modified = true;
            }
        }
        return modified;
    }

    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;
        Iterator<E> it = iterator();
        while(it.hasNext()) {
            if(!c.contains(it.next())) {
                it.remove();
                modified = true;
            }
        }
        return modified;
    }

    public void clear() {
        Iterator<E> it = iterator();
        while(it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    //  String conversion

    public String toString() {
        Iterator<E> it = iterator();
        if(!it.hasNext())
            return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        while(true) {
            E e = it.next();
            sb.append(e == this ? "(this Collection)" : e);
            if(!it.hasNext())
                return sb.append(']').toString();
            sb.append(',').append(' ');
        }
    }
}

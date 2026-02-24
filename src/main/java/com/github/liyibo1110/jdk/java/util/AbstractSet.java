package com.github.liyibo1110.jdk.java.util;

import java.util.Objects;

/**
 * 该类提供了Set接口的骨架实现，以最大限度地减少实现该接口所需的工作量。
 * 通过继承本类实现集合的过程，与通过继承AbstractCollection实现集合的过程完全相同。
 * 但需注意：本类子类的全部方法与构造函数必须遵守Set接口额外施加的约束（例如add方法不得允许向集合中添加同一对象的多个实例）。
 *
 * 需特别说明：本类并未重写AbstractCollection类的任何实现，仅为equals和hashCode方法添加了具体实现。
 * @author liyibo
 * @date 2026-02-24 17:04
 */
public abstract class AbstractSet<E> extends AbstractCollection<E> implements Set<E> {

    protected AbstractSet() {}

    // Comparison and hashing

    public boolean equals(Object o) {
        if(o == this)
            return true;

        if(!(o instanceof Set))
            return false;
        Collection<?> c = (Collection<?>) o;
        if(c.size() != size())
            return false;
        try {
            return containsAll(c);
        } catch (ClassCastException | NullPointerException unused) {
            return false;
        }
    }

    public int hashCode() {
        int h = 0;
        Iterator<E> i = iterator();
        while(i.hasNext()) {
            E obj = i.next();
            if(obj != null)
                h += obj.hashCode();
        }
        return h;
    }

    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;

        if(size() > c.size()) {
            for (Object e : c)
                modified |= remove(e);
        }else {
            for(Iterator<?> i = iterator(); i.hasNext(); ) {
                if(c.contains(i.next())) {
                    i.remove();
                    modified = true;
                }
            }
        }
        return modified;
    }
}

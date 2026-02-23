package com.github.liyibo1110.jdk.java.util;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 本类提供List接口的骨架实现，旨在最大限度减少基于“随机访问”数据存储（如数组）实现该接口所需的工作量。
 * 对于顺序访问数据（如链表），应优先使用AbstractSequentialList类而非本类。
 *
 * 要实现不可修改的列表，程序员只需继承本类并为get(int)和size()方法提供实现即可。
 * 要实现可修改的列表，程序员还必须重写 set(int, E) 方法（否则会抛出 UnsupportedOperationException异常）。
 * 若列表为可变长度，程序员还需重写add(int, E)和remove(int)方法。
 * 根据 Collection 接口规范的建议，程序员通常应提供 void（无参数）构造函数和集合构造函数。
 *
 * 与其他抽象集合实现不同，程序员无需提供迭代器实现。
 * 迭代器和列表迭代器由本类实现，同时提供“随机访问”方法：get(int)、set(int, E)、add(int, E)和remove(int)。
 *
 * 该类中每个非抽象方法的文档都详细描述了其实现方式。若所实现的集合允许更高效的实现方案，这些方法均可被重写。
 * @author liyibo
 * @date 2026-02-23 17:10
 */
public abstract class AbstractList<E> extends AbstractCollection<E> implements List<E> {

    protected AbstractList() {}

    public boolean add(E e) {
        add(size(), e);
        return true;
    }

    public abstract E get(int index);

    public E set(int index, E element) {
        throw new UnsupportedOperationException();
    }

    public void add(int index, E element) {
        throw new UnsupportedOperationException();
    }

    public E remove(int index) {
        throw new UnsupportedOperationException();
    }

    // Search Operations

    public int indexOf(Object o) {
        ListIterator<E> it = listIterator();
        if(o == null) {
            while(it.hasNext()) {
                if(it.next() == null)
                    return it.previousIndex();
            }
        }else {
            while(it.hasNext()) {
                if(o.equals(it.next()))
                    return it.previousIndex();
            }
        }
        return -1;
    }

    public int lastIndexOf(Object o) {
        ListIterator<E> it = listIterator(size());
        if(o==null) {
            while(it.hasPrevious())
                if(it.previous()==null)
                    return it.nextIndex();
        }else {
            while(it.hasPrevious())
                if(o.equals(it.previous()))
                    return it.nextIndex();
        }
        return -1;
    }

    // Bulk Operations

    public void clear() {
        removeRange(0, size());
    }

    public boolean addAll(int index, Collection<? extends E> c) {
        rangeCheckForAdd(index);
        boolean modified = false;
        for(E e : c) {
            add(index++, e);
            modified = true;
        }
        return modified;
    }

    public Iterator<E> iterator() {
        return new Itr();
    }

    public ListIterator<E> listIterator() {
        return listIterator(0);
    }

    public ListIterator<E> listIterator(final int index) {
        rangeCheckForAdd(index);
        return new ListItr(index);
    }

    private class Itr implements Iterator<E> {
        int cursor = 0;
        int lastRet = -1;
        int expectedModCount = modCount;

        public boolean hasNext() {
            return cursor != size();
        }

        public E next() {
            checkForComodification();
            try {
                int i = cursor;
                E next = get(i);
                lastRet = i;
                cursor = i + 1;
                return next;
            } catch (IndexOutOfBoundsException e) {
                checkForComodification();
                throw new NoSuchElementException(e);
            }
        }

        public void remove() {
            if(lastRet < 0)
                throw new IllegalStateException();
            checkForComodification();
        }

        final void checkForComodification() {
            if(modCount != expectedModCount)
                throw new ConcurrentModificationException();

            try {
                AbstractList.this.remove(lastRet);
                if(lastRet < cursor)
                    cursor--;
                lastRet = -1;
                expectedModCount = modCount;
            } catch (IndexOutOfBoundsException e) {
                throw new ConcurrentModificationException();
            }
        }
    }

    private class ListItr extends Itr implements ListIterator<E> {
        ListItr(int index) {
            cursor = index;
        }

        public boolean hasPrevious() {
            return cursor != 0;
        }

        public E previous() {
            checkForComodification();
            try {
                int i = cursor - 1;
                E previous = get(i);
                lastRet = cursor = i;
                return previous;
            } catch (IndexOutOfBoundsException e) {
                checkForComodification();
                throw new NoSuchElementException(e);
            }
        }

        public int nextIndex() {
            return cursor;
        }

        public int previousIndex() {
            return cursor-1;
        }

        public void set(E e) {
            if(lastRet < 0)
                throw new IllegalStateException();
            checkForComodification();

            try {
                AbstractList.this.set(lastRet, e);
                expectedModCount = modCount;
            } catch (IndexOutOfBoundsException ex) {
                throw new ConcurrentModificationException();
            }
        }

        public void add(E e) {
            checkForComodification();

            try {
                int i = cursor;
                AbstractList.this.add(i, e);
                lastRet = -1;
                cursor = i + 1;
                expectedModCount = modCount;
            } catch (IndexOutOfBoundsException ex) {
                throw new ConcurrentModificationException();
            }
        }
    }

    public List<E> subList(int fromIndex, int toIndex) {
        subListRangeCheck(fromIndex, toIndex, size());
        return this instanceof RandomAccess
                ? new RandomAccessSubList<>(this, fromIndex, toIndex)
                : new SubList<>(this, fromIndex, toIndex);
    }

    static void subListRangeCheck(int fromIndex, int toIndex, int size) {
        if(fromIndex < 0)
            throw new IndexOutOfBoundsException("fromIndex = " + fromIndex);
        if(toIndex > size)
            throw new IndexOutOfBoundsException("toIndex = " + toIndex);
        if(fromIndex > toIndex)
            throw new IllegalArgumentException("fromIndex(" + fromIndex + ") > toIndex(" + toIndex + ")");
    }

    // Comparison and hashing

    public boolean equals(Object o) {
        if(o == this)
            return true;
        if(!(o instanceof List))
            return false;

        ListIterator<E> e1 = listIterator();
        ListIterator<?> e2 = ((List<?>) o).listIterator();
        while(e1.hasNext() && e2.hasNext()) {
            E o1 = e1.next();
            Object o2 = e2.next();
            if(!(o1==null ? o2==null : o1.equals(o2)))
                return false;
        }
        return !(e1.hasNext() || e2.hasNext());
    }

    public int hashCode() {
        int hashCode = 1;
        for(E e : this)
            hashCode = 31*hashCode + (e==null ? 0 : e.hashCode());
        return hashCode;
    }

    protected void removeRange(int fromIndex, int toIndex) {
        ListIterator<E> it = listIterator(fromIndex);
        for (int i = 0, n = toIndex - fromIndex; i < n; i++) {
            it.next();
            it.remove();
        }
    }

    /**
     * 此List被结构性修改的次数。结构性修改指改变列表大小，或以其他方式扰动列表，导致正在进行的迭代可能产生错误结果的操作。
     * 该字段由迭代器和列表迭代器实现所使用，这些实现由iterator和listIterator方法返回。
     * 若该字段值发生意外变更，迭代器（或列表迭代器）将在响应next、remove、previous、set或add操作时抛出ConcurrentModificationException异常。
     * 此机制提供快速失败行为，而非在迭代过程中遭遇并发修改时产生非确定性行为。
     *
     * 子类对该字段的使用是可选的。
     * 若子类希望提供快速失败迭代器（及列表迭代器），只需在其重写的 add(int, E) 和 remove(int) 方法（以及任何导致列表结构修改的重写方法）中递增该字段。
     * 每次调用add(int, E)或remove(int)时，对该字段的递增操作不得超过1次，否则迭代器（及列表迭代器）将抛出虚假的并发修改异常。
     * 若实现不提供快速失败迭代器，则可忽略此字段。
     */
    protected transient int modCount = 0;

    private void rangeCheckForAdd(int index) {
        if(index < 0 || index > size())
            throw new IndexOutOfBoundsException(outOfBoundsMsg(index));
    }

    private String outOfBoundsMsg(int index) {
        return "Index: " + index + ", Size: " + size();
    }

    /**
     * 一种基于视图设计模式的实现，解决的问题是，list.subList(from, to)如何做到：
     * 1、不实际复制数据。
     * 2、修改能影响原列表。
     * 3、同时还能支持嵌套subList。
     * 4、还能正确处理并发修改检测。
     */
    private static class SubList<E> extends AbstractList<E> {
        /** 最顶层的原始List */
        private final AbstractList<E> root;

        /** 上一层的subList（即支持list.subList(2, 8).subList(1, 3) ）*/
        private final SubList<E> parent;

        /** 当前subList在root里的起始偏移 */
        private final int offset;

        /** 当前子视图的大小 */
        protected int size;

        public SubList(AbstractList<E> root, int fromIndex, int toIndex) {
            this.root = root;
            this.parent = null;
            this.offset = fromIndex;
            this.size = toIndex - fromIndex;
            this.modCount = root.modCount;
        }

        protected SubList(SubList<E> parent, int fromIndex, int toIndex) {
            this.root = parent.root;
            this.parent = parent;
            this.offset = parent.offset + fromIndex;
            this.size = toIndex - fromIndex;
            this.modCount = root.modCount;
        }

        public E set(int index, E element) {
            Objects.checkIndex(index, size);
            checkForComodification();
            return root.set(offset + index, element);
        }

        public E get(int index) {
            Objects.checkIndex(index, size);
            checkForComodification();
            return root.get(offset + index);
        }

        public int size() {
            checkForComodification();
            return size;
        }

        public void add(int index, E element) {
            rangeCheckForAdd(index);
            checkForComodification();
            root.add(offset + index, element);
            updateSizeAndModCount(1);
        }

        public E remove(int index) {
            Objects.checkIndex(index, size);
            checkForComodification();
            E result = root.remove(offset + index);
            updateSizeAndModCount(-1);
            return result;
        }

        protected void removeRange(int fromIndex, int toIndex) {
            checkForComodification();
            root.removeRange(offset + fromIndex, offset + toIndex);
            updateSizeAndModCount(fromIndex - toIndex);
        }

        public boolean addAll(Collection<? extends E> c) {
            return addAll(size, c);
        }

        public boolean addAll(int index, Collection<? extends E> c) {
            rangeCheckForAdd(index);
            int cSize = c.size();
            if (cSize==0)
                return false;
            checkForComodification();
            root.addAll(offset + index, c);
            updateSizeAndModCount(cSize);
            return true;
        }

        public Iterator<E> iterator() {
            return listIterator();
        }

        public ListIterator<E> listIterator(int index) {
            checkForComodification();
            rangeCheckForAdd(index);

            return new ListIterator<E>() {
                private final ListIterator<E> i =
                        root.listIterator(offset + index);

                public boolean hasNext() {
                    return nextIndex() < size;
                }

                public E next() {
                    if (hasNext())
                        return i.next();
                    else
                        throw new NoSuchElementException();
                }

                public boolean hasPrevious() {
                    return previousIndex() >= 0;
                }

                public E previous() {
                    if (hasPrevious())
                        return i.previous();
                    else
                        throw new NoSuchElementException();
                }

                public int nextIndex() {
                    return i.nextIndex() - offset;
                }

                public int previousIndex() {
                    return i.previousIndex() - offset;
                }

                public void remove() {
                    i.remove();
                    updateSizeAndModCount(-1);
                }

                public void set(E e) {
                    i.set(e);
                }

                public void add(E e) {
                    i.add(e);
                    updateSizeAndModCount(1);
                }
            };
        }

        public List<E> subList(int fromIndex, int toIndex) {
            subListRangeCheck(fromIndex, toIndex, size);
            return new SubList<>(this, fromIndex, toIndex);
        }

        private void rangeCheckForAdd(int index) {
            if (index < 0 || index > size)
                throw new IndexOutOfBoundsException(outOfBoundsMsg(index));
        }

        private String outOfBoundsMsg(int index) {
            return "Index: "+index+", Size: "+size;
        }

        private void checkForComodification() {
            if (root.modCount != this.modCount)
                throw new ConcurrentModificationException();
        }

        private void updateSizeAndModCount(int sizeChange) {
            SubList<E> slist = this;
            do {
                slist.size += sizeChange;
                slist.modCount = root.modCount;
                slist = slist.parent;
            } while (slist != null);
        }
    }

    private static class RandomAccessSubList<E> extends SubList<E> implements RandomAccess {
        RandomAccessSubList(AbstractList<E> root, int fromIndex, int toIndex) {
            super(root, fromIndex, toIndex);
        }

        RandomAccessSubList(RandomAccessSubList<E> parent, int fromIndex, int toIndex) {
            super(parent, fromIndex, toIndex);
        }

        public List<E> subList(int fromIndex, int toIndex) {
            subListRangeCheck(fromIndex, toIndex, size);
            return new RandomAccessSubList<>(this, fromIndex, toIndex);
        }
    }
}

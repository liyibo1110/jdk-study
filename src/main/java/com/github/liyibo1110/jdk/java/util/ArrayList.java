package com.github.liyibo1110.jdk.java.util;

import java.util.Arrays;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 可调整大小数组实现的列表接口。支持所有可选列表操作，允许包含空值元素。
 * 除实现List接口外，该类还提供操作内部存储数组大小的方法（该类大致等同于Vector，但未实现同步）。
 *
 * size、isEmpty、get、set、iterator和listIterator操作均为常数时间。
 * add操作为摊销常数时间，即添加n个元素需O(n)时间。其余操作均为线性时间（大致而言）。其常数因子相较于LinkedList实现更为低廉。
 *
 * 每个ArrayList实例都具有容量属性。该容量即用于存储列表元素的数组大小，其值始终不小于列表实际元素数量。
 * 当向ArrayList添加元素时，其容量会自动增长。除新增元素的摊销时间复杂度恒定外，容量增长策略的具体细节并未明确规定。
 * 应用程序可在添加大量元素前，通过 ensureCapacity 操作预先增加ArrayList实例的容量。此举可减少增量重新分配的次数。
 *
 * 请注意，此实现未进行同步处理。若多个线程并发访问ArrayList实例，且至少有一个线程对列表进行结构性修改，则必须通过外部方式进行同步。
 * （结构性修改包括添加或删除一个或多个元素，或显式调整后备数组大小的操作；单纯设置元素值不属于结构性修改。）
 * 通常可通过对自然封装列表的对象进行同步来实现。若不存在此类对象，则应使用Collections.synchronizedList方法对列表进行“包装”。
 * 最佳实践是在创建时完成包装，以防止意外的非同步访问：
 * List list = Collections.synchronizedList(new ArrayList(...));
 *
 * 该类iterator和listIterator方法返回的迭代器采用快速失败机制：
 * 若迭代器创建后通过非迭代器自身的remove或add方法对列表进行任何结构性修改，迭代器将抛出并发修改异常。
 * 因此，面对并发修改时，迭代器能快速且干净地失败，而非在未来某个未知的时刻冒着出现任意、非确定性行为的风险。
 *
 * 请注意，迭代器的快速失败行为无法得到保证，因为在存在非同步并发修改的情况下，通常无法做出任何硬性保证。
 * 快速失败迭代器仅在尽最大努力的基础上抛出并发修改异常。
 * 因此，编写依赖此异常来保证程序正确性的代码是错误的：迭代器的快速失败行为仅应用于检测错误。
 * @author liyibo
 * @date 2026-02-23 17:52
 */
public class ArrayList<E> extends AbstractList<E>
    implements List<E>, RandomAccess, Cloneable, java.io.Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 8683452581122892189L;

    /** 默认容量 */
    private static final int DEFAULT_CAPACITY = 10;

    /** 用于空实例的共享空数组实例 */
    private static final Object[] EMPTY_ELEMENTDATA = {};

    /**
     * 用于默认大小的空实例的共享空数组实例。
     * 我们将其与EMPTY_ELEMENTDATA区分开来，以便在添加首个元素时确定需要扩展的大小。
     */
    private static final Object[] DEFAULTCAPACITY_EMPTY_ELEMENTDATA = {};

    /**
     * 用于存储实际元素的数组缓冲区，ArrayList的容量即为此数组缓冲区长度。
     * 任何元素数据为DEFAULTCAPACITY_EMPTY_ELEMENTDATA的空ArrayList，在添加首个元素时都会扩展至DEFAULT_CAPACITY
     */
    transient Object[] elementData;

    private int size;

    public ArrayList(int initialCapacity) {
        if(initialCapacity > 0)
            this.elementData = new Object[initialCapacity];
        else if(initialCapacity == 0)
            this.elementData = EMPTY_ELEMENTDATA;
        else
            throw new IllegalArgumentException("Illegal Capacity: "+ initialCapacity);
    }

    public ArrayList() {
        this.elementData = DEFAULTCAPACITY_EMPTY_ELEMENTDATA;
    }

    public ArrayList(Collection<? extends E> c) {
        Object[] a = c.toArray();
        if((size = a.length) == 0) {
            elementData = EMPTY_ELEMENTDATA;
        }else {
            if(c.getClass() == ArrayList.class)
                elementData = a;
            else
                elementData = Arrays.copyOf(a, size, Object[].class);
        }
    }

    /**
     * 将此ArrayList的容量，修剪为List的当前大小。
     * 应用程序可使用此操作来最小化ArrayList的存储空间。
     */
    public void trimToSize() {
        modCount++;
        if(size < elementData.length) { // elementData有多余的空间才会trim
            elementData = (size == 0)
                    ? EMPTY_ELEMENTDATA
                    : Arrays.copyOf(elementData, size);
        }
    }

    /**
     * 必要时增肌此ArrayList的容量，以确保其至少能容纳由最小容量参数指定的元素数量。
     */
    public void ensureCapacity(int minCapacity) {
        /**
         * 1、要求的容量，大于数组实际的长度。
         * 2、elementData不是初始长度，并且minCapacity不能过小。
         */
        if(minCapacity > elementData.length
                && !(elementData == DEFAULTCAPACITY_EMPTY_ELEMENTDATA && minCapacity <= DEFAULT_CAPACITY)) {
            modCount++;
            this.grow(minCapacity);
        }
    }

    /**
     * 增加容量，以确保能至少容纳minCapacity个元素数量。
     */
    private Object[] grow(int minCapacity) {
        int oldCapacity = elementData.length;
        if(oldCapacity > 0 || elementData != DEFAULTCAPACITY_EMPTY_ELEMENTDATA) {
            int newCapacity = ArraysSupport.newLength(oldCapacity,
                    minCapacity - oldCapacity, /* minimum growth */
                    oldCapacity >> 1);
            return elementData = Arrays.copyOf(elementData, newCapacity);
        }else {
            return elementData = new Object[Math.max(DEFAULT_CAPACITY, minCapacity)];
        }
    }

    private Object[] grow() {
        return grow(size + 1);
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    public int indexOf(Object o) {
        return indexOfRange(o, 0, size);
    }

    int indexOfRange(Object o, int start, int end) {
        Object[] es = elementData;
        if(o == null) {
            for(int i = start; i < end; i++) {
                if(es[i] == null)
                    return i;
            }
        }else {
            for(int i = start; i < end; i++) {
                if(o.equals(es[i]))
                    return i;
            }
        }
        return -1;
    }

    public int lastIndexOf(Object o) {
        return lastIndexOfRange(o, 0, size);
    }

    int lastIndexOfRange(Object o, int start, int end) {
        Object[] es = elementData;
        if(o == null) {
            for(int i = end - 1; i >= start; i--) {
                if(es[i] == null)
                    return i;
            }
        }else {
            for(int i = end - 1; i >= start; i--) {
                if(o.equals(es[i]))
                    return i;
            }
        }
        return -1;
    }

    /**
     * 返回此ArrayList的浅COPY（元素本身则不会被复制，意思就是只复制引用）。
     */
    public Object clone() {
        try {
            ArrayList<?> v = (ArrayList<?>)super.clone();
            v.elementData = Arrays.copyOf(elementData, size);
            v.modCount = 0;
            return v;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    public Object[] toArray() {
        return Arrays.copyOf(elementData, size);
    }

    public <T> T[] toArray(T[] a) {
        if (a.length < size)    // 传入的容器，要比实际的数组小，直接返回新数组
            return (T[])Arrays.copyOf(elementData, size, a.getClass());
        System.arraycopy(elementData, 0, a, 0, size);
        if(a.length > size)
            a[size] = null; // 接口语义就是这样要求的
        return a;
    }

    // Positional Access Operations

    E elementData(int index) {
        return (E) elementData[index];
    }

    static <E> E elementAt(Object[] es, int index) {
        return (E) es[index];
    }

    public E get(int index) {
        Objects.checkIndex(index, size);
        return elementData(index);
    }

    public E set(int index, E element) {
        Objects.checkIndex(index, size);
        E oldValue = elementData(index);
        elementData[index] = element;
        return oldValue;
    }

    private void add(E e, Object[] elementData, int s) {
        if(s == elementData.length) // 满了就要扩容
            elementData = grow();
        elementData[s] = e;
        size = s + 1;
    }

    public boolean add(E e) {
        modCount++;
        add(e, elementData, size);
        return true;
    }

    public void add(int index, E element) {
        rangeCheckForAdd(index);
        modCount++;
        final int s;
        Object[] elementData;
        if((s = size) == (elementData = this.elementData).length)   // 满了就要扩容，顺便赋值
            elementData = grow();
        System.arraycopy(elementData, index, elementData, index + 1, s - index);    // 把空位挪出来
        elementData[index] = element;
        size = s + 1;
    }

    public E remove(int index) {
        Objects.checkIndex(index, size);
        final Object[] es = elementData;
        E oldValue = (E) es[index];
        this.fastRemove(es, index);
        return oldValue;
    }

    public boolean equals(Object o) {
        if(o == this)
            return true;
        if(!(o instanceof List))
            return false;

        final int expectedModCount = modCount;
        boolean equal = (o.getClass() == ArrayList.class)
                ? equalsArrayList((ArrayList<?>)o)
                : equalsRange((List<?>)o, 0, size);
        checkForComodification(expectedModCount);
        return equal;
    }

    boolean equalsRange(List<?> other, int from, int to) {
        final Object[] es = elementData;
        if(to > es.length)
            throw new ConcurrentModificationException();
        var oit = other.iterator();
        for(; from < to; from++) {
            if(!oit.hasNext() || !Objects.equals(es[from], oit.next()))
                return false;
        }
        return !oit.hasNext();
    }

    private boolean equalsArrayList(ArrayList<?> other) {
        final int otherModCount = other.modCount;
        final int s = size;
        boolean equal;
        if(equal = (s == other.size)) {
            final Object[] otherEs = other.elementData;
            final Object[] es = elementData;
            if(s > es.length || s > otherEs.length) {
                throw new ConcurrentModificationException();
            }
            for(int i = 0; i < s; i++) {
                if(!Objects.equals(es[i], otherEs[i])) {
                    equal = false;
                    break;
                }
            }
        }
        other.checkForComodification(otherModCount);
        return equal;
    }

    private void checkForComodification(final int expectedModCount) {
        if(modCount != expectedModCount)
            throw new ConcurrentModificationException();
    }

    public int hashCode() {
        int expectedModCount = modCount;
        int hash = hashCodeRange(0, size);
        checkForComodification(expectedModCount);
        return hash;
    }

    int hashCodeRange(int from, int to) {
        final Object[] es = elementData;
        if(to > es.length)
            throw new ConcurrentModificationException();
        int hashCode = 1;
        for(int i = from; i < to; i++) {
            Object e = es[i];
            hashCode = 31 * hashCode + (e == null ? 0 : e.hashCode());
        }
        return hashCode;
    }

   public boolean remove(Object o) {
        final Object[] es = elementData;
        final int size = this.size;
        int i = 0;
        found: {
            if (o == null) {
                for (; i < size; i++)
                    if (es[i] == null)
                        break found;
            } else {
                for (; i < size; i++)
                    if (o.equals(es[i]))
                        break found;
            }
            return false;
        }
        fastRemove(es, i);
        return true;
    }

    /**
     * remove的内部版本，跳过边界检查且不返回被remove的值。
     */
    private void fastRemove(Object[] es, int i) {
        modCount++;
        final int newSize;
        if((newSize = size - 1) > i)    // 删除不是最后一个元素，就要把要删的后面元素，全给左移过来
            System.arraycopy(es, i + i, es, i, newSize - i);
        es[size = newSize] = null;
    }

    public void clear() {
        modCount++;
        final Object[] es = elementData;
        for(int to = size, i = size = 0; i < to; i++)
            es[i] = null;
    }

    public boolean addAll(Collection<? extends E> c) {
        Object[] a = c.toArray();
        modCount++;
        int numNew = a.length;
        if(numNew == 0)
            return false;
        Object[] elementData;
        final int s;
        if(numNew > (elementData = this.elementData).length - (s = size))
            elementData = grow(s + numNew);
        System.arraycopy(a, 0, elementData, s, numNew);
        size = s + numNew;
        return true;
    }

    public boolean addAll(int index, Collection<? extends E> c) {
        rangeCheckForAdd(index);
        Object[] a = c.toArray();
        modCount++;
        int numNew = a.length;
        if(numNew == 0)
            return false;
        Object[] elementData;
        final int s;
        if(numNew > (elementData = this.elementData).length - (s = size))
            elementData = grow(s + numNew);

        int numMoved = s - index;
        if(numMoved > 0)
            System.arraycopy(elementData, index, elementData, index + numNew, numMoved);
        System.arraycopy(a, 0, elementData, index, numNew);
        size = s + numNew;
        return true;
    }

    protected void removeRange(int fromIndex, int toIndex) {
        if(fromIndex > toIndex) {
            throw new IndexOutOfBoundsException(outOfBoundsMsg(fromIndex, toIndex));
        }
        modCount++;
        shiftTailOverGap(elementData, fromIndex, toIndex);
    }

    private void shiftTailOverGap(Object[] es, int lo, int hi) {
        System.arraycopy(es, hi, es, lo, size - hi);
        for(int to = size, i = (size -= hi - lo); i < to; i++)
            es[i] = null;
    }

    private void rangeCheckForAdd(int index) {
        if(index > size || index < 0)
            throw new IndexOutOfBoundsException(outOfBoundsMsg(index));
    }

    private String outOfBoundsMsg(int index) {
        return "Index: "+index+", Size: "+size;
    }

    private static String outOfBoundsMsg(int fromIndex, int toIndex) {
        return "From Index: " + fromIndex + " > To Index: " + toIndex;
    }

    public boolean removeAll(Collection<?> c) {
        return batchRemove(c, false, 0, size);
    }

    public boolean retainAll(Collection<?> c) {
        return batchRemove(c, true, 0, size);
    }

    boolean batchRemove(Collection<?> c, boolean complement, final int from, final int end) {
        Objects.requireNonNull(c);
        final Object[] es = elementData;
        int r;
        // Optimize for initial run of survivors
        for(r = from;; r++) {
            if(r == end)
                return false;
            if(c.contains(es[r]) != complement)
                break;
        }
        int w = r++;
        try {
            for(Object e; r < end; r++)
                if(c.contains(e = es[r]) == complement)
                    es[w++] = e;
        } catch (Throwable ex) {
            // Preserve behavioral compatibility with AbstractCollection,
            // even if c.contains() throws.
            System.arraycopy(es, r, es, w, end - r);
            w += end - r;
            throw ex;
        } finally {
            modCount += end - w;
            shiftTailOverGap(es, w, end);
        }
        return true;
    }

    @java.io.Serial
    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        // Write out element count, and any hidden stuff
        int expectedModCount = modCount;
        s.defaultWriteObject();

        // Write out size as capacity for behavioral compatibility with clone()
        s.writeInt(size);

        // Write out all elements in the proper order.
        for(int i=0; i<size; i++)
            s.writeObject(elementData[i]);

        if(modCount != expectedModCount)
            throw new ConcurrentModificationException();
    }

    @java.io.Serial
    private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {

        // Read in size, and any hidden stuff
        s.defaultReadObject();

        // Read in capacity
        s.readInt(); // ignored

        if(size > 0) {
            // like clone(), allocate array based upon size not capacity
            SharedSecrets.getJavaObjectInputStreamAccess().checkArray(s, Object[].class, size);
            Object[] elements = new Object[size];

            // Read in all elements in the proper order.
            for(int i = 0; i < size; i++)
                elements[i] = s.readObject();

            elementData = elements;
        }else if (size == 0)
            elementData = EMPTY_ELEMENTDATA;
        else
            throw new java.io.InvalidObjectException("Invalid size: " + size);
    }

    public ListIterator<E> listIterator(int index) {
        rangeCheckForAdd(index);
        return new ListItr(index);
    }

    public ListIterator<E> listIterator() {
        return new ListItr(0);
    }

    public java.util.Iterator<E> iterator() {
        return new Itr();
    }

    private class Itr implements Iterator<E> {
        int cursor;       // index of next element to return
        int lastRet = -1; // index of last element returned; -1 if no such
        int expectedModCount = modCount;

        Itr() {
        }

        public boolean hasNext() {
            return cursor != size;
        }

        public E next() {
            checkForComodification();
            int i = cursor;
            if (i >= size)
                throw new NoSuchElementException();
            Object[] elementData = ArrayList.this.elementData;
            if (i >= elementData.length)
                throw new ConcurrentModificationException();
            cursor = i + 1;
            return (E) elementData[lastRet = i];
        }

        public void remove() {
            if (lastRet < 0)
                throw new IllegalStateException();
            checkForComodification();

            try {
                ArrayList.this.remove(lastRet);
                cursor = lastRet;
                lastRet = -1;
                expectedModCount = modCount;
            } catch (IndexOutOfBoundsException ex) {
                throw new ConcurrentModificationException();
            }
        }

        @Override
        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            final int size = ArrayList.this.size;
            int i = cursor;
            if(i < size) {
                final Object[] es = elementData;
                if(i >= es.length)
                    throw new ConcurrentModificationException();
                for(; i < size && modCount == expectedModCount; i++)
                    action.accept(elementAt(es, i));
                // update once at end to reduce heap write traffic
                cursor = i;
                lastRet = i - 1;
                checkForComodification();
            }

            final void checkForComodification() {
                if(modCount != expectedModCount)
                    throw new ConcurrentModificationException();
            }
        }
    }

    private class ListItr extends Itr implements ListIterator<E> {
        ListItr(int index) {
            super();
            cursor = index;
        }

        public boolean hasPrevious() {
            return cursor != 0;
        }

        public int nextIndex() {
            return cursor;
        }

        public int previousIndex() {
            return cursor - 1;
        }

        @SuppressWarnings("unchecked")
        public E previous() {
            checkForComodification();
            int i = cursor - 1;
            if (i < 0)
                throw new NoSuchElementException();
            Object[] elementData = ArrayList.this.elementData;
            if (i >= elementData.length)
                throw new ConcurrentModificationException();
            cursor = i;
            return (E) elementData[lastRet = i];
        }

        public void set(E e) {
            if (lastRet < 0)
                throw new IllegalStateException();
            checkForComodification();

            try {
                ArrayList.this.set(lastRet, e);
            } catch (IndexOutOfBoundsException ex) {
                throw new ConcurrentModificationException();
            }
        }

        public void add(E e) {
            checkForComodification();

            try {
                int i = cursor;
                ArrayList.this.add(i, e);
                cursor = i + 1;
                lastRet = -1;
                expectedModCount = modCount;
            } catch (IndexOutOfBoundsException ex) {
                throw new ConcurrentModificationException();
            }
        }
    }

    public List<E> subList(int fromIndex, int toIndex) {
        subListRangeCheck(fromIndex, toIndex, size);
        return new SubList<>(this, fromIndex, toIndex);
    }

    private static class SubList<E> extends AbstractList<E> implements RandomAccess {
        private final ArrayList<E> root;
        private final SubList<E> parent;
        private final int offset;
        private int size;

        public SubList(ArrayList<E> root, int fromIndex, int toIndex) {
            this.root = root;
            this.parent = null;
            this.offset = fromIndex;
            this.size = toIndex - fromIndex;
            this.modCount = root.modCount;
        }

        private SubList(SubList<E> parent, int fromIndex, int toIndex) {
            this.root = parent.root;
            this.parent = parent;
            this.offset = parent.offset + fromIndex;
            this.size = toIndex - fromIndex;
            this.modCount = parent.modCount;
        }

        public E set(int index, E element) {
            Objects.checkIndex(index, size);
            checkForComodification();
            E oldValue = root.elementData(offset + index);
            root.elementData[offset + index] = element;
            return oldValue;
        }

        public E get(int index) {
            Objects.checkIndex(index, size);
            checkForComodification();
            return root.elementData(offset + index);
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
            return addAll(this.size, c);
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

        public void replaceAll(UnaryOperator<E> operator) {
            root.replaceAllRange(operator, offset, offset + size);
        }

        public boolean removeAll(Collection<?> c) {
            return batchRemove(c, false);
        }

        public boolean retainAll(Collection<?> c) {
            return batchRemove(c, true);
        }

        private boolean batchRemove(Collection<?> c, boolean complement) {
            checkForComodification();
            int oldSize = root.size;
            boolean modified =
                    root.batchRemove(c, complement, offset, offset + size);
            if (modified)
                updateSizeAndModCount(root.size - oldSize);
            return modified;
        }

        public boolean removeIf(Predicate<? super E> filter) {
            checkForComodification();
            int oldSize = root.size;
            boolean modified = root.removeIf(filter, offset, offset + size);
            if (modified)
                updateSizeAndModCount(root.size - oldSize);
            return modified;
        }

        public Object[] toArray() {
            checkForComodification();
            return Arrays.copyOfRange(root.elementData, offset, offset + size);
        }

        @SuppressWarnings("unchecked")
        public <T> T[] toArray(T[] a) {
            checkForComodification();
            if (a.length < size)
                return (T[]) Arrays.copyOfRange(
                        root.elementData, offset, offset + size, a.getClass());
            System.arraycopy(root.elementData, offset, a, 0, size);
            if (a.length > size)
                a[size] = null;
            return a;
        }

        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }

            if (!(o instanceof List)) {
                return false;
            }

            boolean equal = root.equalsRange((List<?>)o, offset, offset + size);
            checkForComodification();
            return equal;
        }

        public int hashCode() {
            int hash = root.hashCodeRange(offset, offset + size);
            checkForComodification();
            return hash;
        }

        public int indexOf(Object o) {
            int index = root.indexOfRange(o, offset, offset + size);
            checkForComodification();
            return index >= 0 ? index - offset : -1;
        }

        public int lastIndexOf(Object o) {
            int index = root.lastIndexOfRange(o, offset, offset + size);
            checkForComodification();
            return index >= 0 ? index - offset : -1;
        }

        public boolean contains(Object o) {
            return indexOf(o) >= 0;
        }

        public Iterator<E> iterator() {
            return listIterator();
        }

        public ListIterator<E> listIterator(int index) {
            checkForComodification();
            rangeCheckForAdd(index);

            return new ListIterator<E>() {
                int cursor = index;
                int lastRet = -1;
                int expectedModCount = SubList.this.modCount;

                public boolean hasNext() {
                    return cursor != SubList.this.size;
                }

                @SuppressWarnings("unchecked")
                public E next() {
                    checkForComodification();
                    int i = cursor;
                    if (i >= SubList.this.size)
                        throw new NoSuchElementException();
                    Object[] elementData = root.elementData;
                    if (offset + i >= elementData.length)
                        throw new ConcurrentModificationException();
                    cursor = i + 1;
                    return (E) elementData[offset + (lastRet = i)];
                }

                public boolean hasPrevious() {
                    return cursor != 0;
                }

                @SuppressWarnings("unchecked")
                public E previous() {
                    checkForComodification();
                    int i = cursor - 1;
                    if (i < 0)
                        throw new NoSuchElementException();
                    Object[] elementData = root.elementData;
                    if (offset + i >= elementData.length)
                        throw new ConcurrentModificationException();
                    cursor = i;
                    return (E) elementData[offset + (lastRet = i)];
                }

                public void forEachRemaining(Consumer<? super E> action) {
                    Objects.requireNonNull(action);
                    final int size = SubList.this.size;
                    int i = cursor;
                    if (i < size) {
                        final Object[] es = root.elementData;
                        if (offset + i >= es.length)
                            throw new ConcurrentModificationException();
                        for (; i < size && root.modCount == expectedModCount; i++)
                            action.accept(elementAt(es, offset + i));
                        // update once at end to reduce heap write traffic
                        cursor = i;
                        lastRet = i - 1;
                        checkForComodification();
                    }
                }

                public int nextIndex() {
                    return cursor;
                }

                public int previousIndex() {
                    return cursor - 1;
                }

                public void remove() {
                    if (lastRet < 0)
                        throw new IllegalStateException();
                    checkForComodification();

                    try {
                        SubList.this.remove(lastRet);
                        cursor = lastRet;
                        lastRet = -1;
                        expectedModCount = SubList.this.modCount;
                    } catch (IndexOutOfBoundsException ex) {
                        throw new ConcurrentModificationException();
                    }
                }

                public void set(E e) {
                    if (lastRet < 0)
                        throw new IllegalStateException();
                    checkForComodification();

                    try {
                        root.set(offset + lastRet, e);
                    } catch (IndexOutOfBoundsException ex) {
                        throw new ConcurrentModificationException();
                    }
                }

                public void add(E e) {
                    checkForComodification();

                    try {
                        int i = cursor;
                        SubList.this.add(i, e);
                        cursor = i + 1;
                        lastRet = -1;
                        expectedModCount = SubList.this.modCount;
                    } catch (IndexOutOfBoundsException ex) {
                        throw new ConcurrentModificationException();
                    }
                }

                final void checkForComodification() {
                    if (root.modCount != expectedModCount)
                        throw new ConcurrentModificationException();
                }
            };
        }

        public List<E> subList(int fromIndex, int toIndex) {
            subListRangeCheck(fromIndex, toIndex, size);
            return new SubList<>(this, fromIndex, toIndex);
        }

        private void rangeCheckForAdd(int index) {
            if (index < 0 || index > this.size)
                throw new IndexOutOfBoundsException(outOfBoundsMsg(index));
        }

        private String outOfBoundsMsg(int index) {
            return "Index: "+index+", Size: "+this.size;
        }

        private void checkForComodification() {
            if (root.modCount != modCount)
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

        public Spliterator<E> spliterator() {
            checkForComodification();

            // ArrayListSpliterator not used here due to late-binding
            return new Spliterator<E>() {
                private int index = offset; // current index, modified on advance/split
                private int fence = -1; // -1 until used; then one past last index
                private int expectedModCount; // initialized when fence set

                private int getFence() { // initialize fence to size on first use
                    int hi; // (a specialized variant appears in method forEach)
                    if ((hi = fence) < 0) {
                        expectedModCount = modCount;
                        hi = fence = offset + size;
                    }
                    return hi;
                }

                public ArrayList<E>.ArrayListSpliterator trySplit() {
                    int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
                    // ArrayListSpliterator can be used here as the source is already bound
                    return (lo >= mid) ? null : // divide range in half unless too small
                            root.new ArrayListSpliterator(lo, index = mid, expectedModCount);
                }

                public boolean tryAdvance(Consumer<? super E> action) {
                    Objects.requireNonNull(action);
                    int hi = getFence(), i = index;
                    if (i < hi) {
                        index = i + 1;
                        @SuppressWarnings("unchecked") E e = (E)root.elementData[i];
                        action.accept(e);
                        if (root.modCount != expectedModCount)
                            throw new ConcurrentModificationException();
                        return true;
                    }
                    return false;
                }

                public void forEachRemaining(Consumer<? super E> action) {
                    Objects.requireNonNull(action);
                    int i, hi, mc; // hoist accesses and checks from loop
                    ArrayList<E> lst = root;
                    Object[] a;
                    if ((a = lst.elementData) != null) {
                        if ((hi = fence) < 0) {
                            mc = modCount;
                            hi = offset + size;
                        }
                        else
                            mc = expectedModCount;
                        if ((i = index) >= 0 && (index = hi) <= a.length) {
                            for (; i < hi; ++i) {
                                @SuppressWarnings("unchecked") E e = (E) a[i];
                                action.accept(e);
                            }
                            if (lst.modCount == mc)
                                return;
                        }
                    }
                    throw new ConcurrentModificationException();
                }

                public long estimateSize() {
                    return getFence() - index;
                }

                public int characteristics() {
                    return Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED;
                }
            };
        }
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        final int expectedModCount = modCount;
        final Object[] es = elementData;
        final int size = this.size;
        for (int i = 0; modCount == expectedModCount && i < size; i++)
            action.accept(elementAt(es, i));
        if (modCount != expectedModCount)
            throw new ConcurrentModificationException();
    }

    @Override
    public Spliterator<E> spliterator() {
        return new ArrayListSpliterator(0, -1, 0);
    }

    final class ArrayListSpliterator implements Spliterator<E> {
        private int index; // current index, modified on advance/split
        private int fence; // -1 until used; then one past last index
        private int expectedModCount; // initialized when fence set

        /** Creates new spliterator covering the given range. */
        ArrayListSpliterator(int origin, int fence, int expectedModCount) {
            this.index = origin;
            this.fence = fence;
            this.expectedModCount = expectedModCount;
        }

        private int getFence() { // initialize fence to size on first use
            int hi; // (a specialized variant appears in method forEach)
            if ((hi = fence) < 0) {
                expectedModCount = modCount;
                hi = fence = size;
            }
            return hi;
        }

        public ArrayListSpliterator trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid) ? null : // divide range in half unless too small
                    new ArrayListSpliterator(lo, index = mid, expectedModCount);
        }

        public boolean tryAdvance(Consumer<? super E> action) {
            if (action == null)
                throw new NullPointerException();
            int hi = getFence(), i = index;
            if (i < hi) {
                index = i + 1;
                @SuppressWarnings("unchecked") E e = (E)elementData[i];
                action.accept(e);
                if (modCount != expectedModCount)
                    throw new ConcurrentModificationException();
                return true;
            }
            return false;
        }

        public void forEachRemaining(Consumer<? super E> action) {
            int i, hi, mc; // hoist accesses and checks from loop
            Object[] a;
            if (action == null)
                throw new NullPointerException();
            if ((a = elementData) != null) {
                if ((hi = fence) < 0) {
                    mc = modCount;
                    hi = size;
                }
                else
                    mc = expectedModCount;
                if ((i = index) >= 0 && (index = hi) <= a.length) {
                    for (; i < hi; ++i) {
                        @SuppressWarnings("unchecked") E e = (E) a[i];
                        action.accept(e);
                    }
                    if (modCount == mc)
                        return;
                }
            }
            throw new ConcurrentModificationException();
        }

        public long estimateSize() {
            return getFence() - index;
        }

        public int characteristics() {
            return Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED;
        }
    }

    // A tiny bit set implementation

    private static long[] nBits(int n) {
        return new long[((n - 1) >> 6) + 1];
    }

    private static void setBit(long[] bits, int i) {
        bits[i >> 6] |= 1L << i;
    }

    private static boolean isClear(long[] bits, int i) {
        return (bits[i >> 6] & (1L << i)) == 0;
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        return removeIf(filter, 0, size);
    }

    boolean removeIf(Predicate<? super E> filter, int i, final int end) {
        Objects.requireNonNull(filter);
        int expectedModCount = modCount;
        final Object[] es = elementData;
        // Optimize for initial run of survivors
        for (; i < end && !filter.test(elementAt(es, i)); i++)
            ;
        // Tolerate predicates that reentrantly access the collection for
        // read (but writers still get CME), so traverse once to find
        // elements to delete, a second pass to physically expunge.
        if (i < end) {
            final int beg = i;
            final long[] deathRow = nBits(end - beg);
            deathRow[0] = 1L;   // set bit 0
            for (i = beg + 1; i < end; i++)
                if (filter.test(elementAt(es, i)))
                    setBit(deathRow, i - beg);
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
            modCount++;
            int w = beg;
            for (i = beg; i < end; i++)
                if (isClear(deathRow, i - beg))
                    es[w++] = es[i];
            shiftTailOverGap(es, w, end);
            return true;
        } else {
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
            return false;
        }
    }

    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        replaceAllRange(operator, 0, size);
        // TODO(8203662): remove increment of modCount from ...
        modCount++;
    }

    private void replaceAllRange(UnaryOperator<E> operator, int i, int end) {
        Objects.requireNonNull(operator);
        final int expectedModCount = modCount;
        final Object[] es = elementData;
        for (; modCount == expectedModCount && i < end; i++)
            es[i] = operator.apply(elementAt(es, i));
        if (modCount != expectedModCount)
            throw new ConcurrentModificationException();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void sort(Comparator<? super E> c) {
        final int expectedModCount = modCount;
        Arrays.sort((E[]) elementData, 0, size, c);
        if (modCount != expectedModCount)
            throw new ConcurrentModificationException();
        modCount++;
    }

    void checkInvariants() {
        // assert size >= 0;
        // assert size == elementData.length || elementData[size] == null;
    }
}

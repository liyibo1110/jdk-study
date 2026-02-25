package com.github.liyibo1110.jdk.java.util;

import java.io.IOException;
import java.util.ConcurrentModificationException;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 实现Map接口和HashMap和链表方案，具有可预测的迭代顺序，实现与HashMap的区别在于：它维护着贯穿所有条目的双向链表。该链表定义了迭代顺序，通常尊孙key插入到Map时的顺序。
 * 需注意：若Key被重新插入到Map中，插入顺序将不受影响。
 * 此实现既避免了HashMap提供的未指定且通常混乱的排序，又未增加TreeMap相关的额外开销。
 *
 * 当模块接收映射作为输入、复制该映射，随后返回结果的顺序取决于副本的顺序时，此技术尤为有效。（客户端通常希望返回结果与输入顺序一致。）
 * 提供了一个特殊构造函数，用于创建迭代顺序为条目最近访问顺序（从最不常访问到最近访问）的链式哈希映射（访问顺序）。
 * 此类映射特别适合构建最近最少使用（LRU）缓存。调用put、putIfAbsent、get、getOrDefault、compute、computeIfAbsent、computeIfPresent或merge方法时，将访问对应条目（前提是调用完成后该条目仍存在）。
 * replace方法仅在替换值时才会访问条目。putAll方法会为指定映射中的每个映射项生成一次条目访问，访问顺序遵循指定映射条目集迭代器提供的键值映射顺序。其他方法均不会产生条目访问。特别地，对集合视图的操作不会影响底层映射的迭代顺序。
 * 可重写removeEldestEntry(Map.Entry)方法，以在向映射添加新条目时自动清除过期映射。
 *
 * 该类提供所有可选的Map操作，并允许包含空元素。与HashMap类似，当哈希函数能合理分散元素至桶中时，其基本操作（添加、包含检测和移除）可实现常数时间性能。
 * 由于维护链表的额外开销，其性能通常略逊于HashMap，但存在一个例外：遍历LinkedHashMap的集合视图所需时间与地图实际大小成正比（而非容量），而遍历HashMap的开销更大，其时间复杂度与容量成正比。
 *
 * 链表哈希表有两个影响其性能的参数：初始容量和负载因子。它们的定义与HashMap完全一致。
 * 但需注意，对于该类而言，选择过高初始容量所带来的性能代价比HashMap要轻微，因为该类的迭代时间不受容量影响。
 * @author liyibo
 * @date 2026-02-25 12:17
 */
public class LinkedHashMap<K, V> extends HashMap<K, V> implements Map<K, V> {

    /**
     * 专用的Entry
     */
    static class Entry<K,V> extends HashMap.Node<K,V> {
        Entry<K,V> before;
        Entry<K,V> after;

        Entry(int hash, K key, V value, Node<K,V> next) {
            super(hash, key, value, next);
        }
    }

    @java.io.Serial
    private static final long serialVersionUID = 3801124242820219131L;

    transient LinkedHashMap.Entry<K,V> head;

    transient LinkedHashMap.Entry<K,V> tail;

    /** 迭代顺序方法：true表示按访问顺序，false表示按插入顺序 */
    final boolean accessOrder;

    // internal utilities

    /**
     * 插入表尾
     */
    private void linkNodeLast(LinkedHashMap.Entry<K,V> p) {
        LinkedHashMap.Entry<K,V> last = tail;
        tail = p;
        if(last == null)
            head = p;
        else{
            p.before = last;
            last.after = p;
        }
    }

    /**
     * 移动Entry
     */
    private void transferLinks(LinkedHashMap.Entry<K,V> src, LinkedHashMap.Entry<K,V> dst) {
        LinkedHashMap.Entry<K,V> b = dst.before = src.before;
        LinkedHashMap.Entry<K,V> a = dst.after = src.after;
        if(b == null)
            head = dst;
        else
            b.after = dst;
        if(a == null)
            tail = dst;
        else
            a.before = dst;
    }

    /**
     * 重写HashMap的钩子
     */
    void reinitialize() {
        super.reinitialize();
        head = tail = null;
    }

    Node<K,V> newNode(int hash, K key, V value, Node<K,V> e) {
        LinkedHashMap.Entry<K,V> p = new LinkedHashMap.Entry<>(hash, key, value, e);
        linkNodeLast(p);
        return p;
    }

    Node<K,V> replacementNode(Node<K,V> p, Node<K,V> next) {
        LinkedHashMap.Entry<K,V> q = (LinkedHashMap.Entry<K,V>)p;
        LinkedHashMap.Entry<K,V> t = new LinkedHashMap.Entry<>(q.hash, q.key, q.value, next);
        transferLinks(q, t);
        return t;
    }

    TreeNode<K,V> newTreeNode(int hash, K key, V value, Node<K,V> next) {
        TreeNode<K,V> p = new TreeNode<>(hash, key, value, next);
        linkNodeLast(p);
        return p;
    }

    TreeNode<K,V> replacementTreeNode(Node<K,V> p, Node<K,V> next) {
        LinkedHashMap.Entry<K,V> q = (LinkedHashMap.Entry<K,V>)p;
        TreeNode<K,V> t = new TreeNode<>(q.hash, q.key, q.value, next);
        transferLinks(q, t);
        return t;
    }

    void afterNodeRemoval(Node<K,V> e) { // unlink
        LinkedHashMap.Entry<K,V> p = (LinkedHashMap.Entry<K,V>)e, b = p.before, a = p.after;
        p.before = p.after = null;
        if(b == null)
            head = a;
        else
            b.after = a;
        if(a == null)
            tail = b;
        else
            a.before = b;
    }

    void afterNodeInsertion(boolean evict) { // possibly remove eldest
        LinkedHashMap.Entry<K,V> first;
        if(evict && (first = head) != null && removeEldestEntry(first)) {
            K key = first.key;
            removeNode(hash(key), key, null, false, true);
        }
    }

    void afterNodeAccess(Node<K,V> e) { // move node to last
        LinkedHashMap.Entry<K,V> last;
        if(accessOrder && (last = tail) != e) {
            LinkedHashMap.Entry<K,V> p =
                    (LinkedHashMap.Entry<K,V>)e, b = p.before, a = p.after;
            p.after = null;
            if(b == null)
                head = a;
            else
                b.after = a;
            if(a != null)
                a.before = b;
            else
                last = b;
            if(last == null)
                head = p;
            else{
                p.before = last;
                last.after = p;
            }
            tail = p;
            ++modCount;
        }
    }

    void internalWriteEntries(java.io.ObjectOutputStream s) throws IOException {
        for (LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after) {
            s.writeObject(e.key);
            s.writeObject(e.value);
        }
    }

    public LinkedHashMap(int initialCapacity, float loadFactor) {
        super(initialCapacity, loadFactor);
        accessOrder = false;
    }

    public LinkedHashMap(int initialCapacity) {
        super(initialCapacity);
        accessOrder = false;
    }

    public LinkedHashMap() {
        super();
        accessOrder = false;
    }

    public LinkedHashMap(Map<? extends K, ? extends V> m) {
        super();
        accessOrder = false;
        putMapEntries(m, false);
    }

    public LinkedHashMap(int initialCapacity, float loadFactor, boolean accessOrder) {
        super(initialCapacity, loadFactor);
        this.accessOrder = accessOrder;
    }

    public boolean containsValue(Object value) {
        for(LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after) {
            V v = e.value;
            if(v == value || (value != null && value.equals(v)))
                return true;
        }
        return false;
    }

    public V get(Object key) {
        Node<K,V> e;
        if((e = getNode(key)) == null)
            return null;
        if(accessOrder)
            afterNodeAccess(e);
        return e.value;
    }

    public V getOrDefault(Object key, V defaultValue) {
        Node<K,V> e;
        if((e = getNode(key)) == null)
            return defaultValue;
        if(accessOrder)
            afterNodeAccess(e);
        return e.value;
    }

    public void clear() {
        super.clear();
        head = tail = null;
    }

    protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
        return false;
    }

    public Set<K> keySet() {
        Set<K> ks = keySet;
        if(ks == null) {
            ks = new LinkedKeySet();
            keySet = ks;
        }
        return ks;
    }

    @Override
    final <T> T[] keysToArray(T[] a) {
        Object[] r = a;
        int idx = 0;
        for(LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after) {
            r[idx++] = e.key;
        }
        return a;
    }

    @Override
    final <T> T[] valuesToArray(T[] a) {
        Object[] r = a;
        int idx = 0;
        for(LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after) {
            r[idx++] = e.value;
        }
        return a;
    }

    final class LinkedKeySet extends AbstractSet<K> {
        public final int size() {
            return size;
        }

        public final void clear() {
            LinkedHashMap.this.clear();
        }

        public final Iterator<K> iterator() {
            return new LinkedKeyIterator();
        }

        public final boolean contains(Object o) {
            return containsKey(o);
        }

        public final boolean remove(Object key) {
            return removeNode(hash(key), key, null, false, true) != null;
        }

        public final Spliterator<K> spliterator()  {
            return Spliterators.spliterator(this, Spliterator.SIZED | Spliterator.ORDERED | Spliterator.DISTINCT);
        }

        public Object[] toArray() {
            return keysToArray(new Object[size]);
        }

        public <T> T[] toArray(T[] a) {
            return keysToArray(prepareArray(a));
        }

        public final void forEach(Consumer<? super K> action) {
            if(action == null)
                throw new NullPointerException();
            int mc = modCount;
            for(LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after)
                action.accept(e.key);
            if(modCount != mc)
                throw new ConcurrentModificationException();
        }
    }

    public Collection<V> values() {
        Collection<V> vs = values;
        if(vs == null) {
            vs = new LinkedValues();
            values = vs;
        }
        return vs;
    }

    final class LinkedValues extends AbstractCollection<V> {
        public final int size() {
            return size;
        }

        public final void clear() {
            LinkedHashMap.this.clear();
        }

        public final Iterator<V> iterator() {
            return new LinkedValueIterator();
        }

        public final boolean contains(Object o) { return containsValue(o); }

        public final Spliterator<V> spliterator() {
            return Spliterators.spliterator(this, Spliterator.SIZED | Spliterator.ORDERED);
        }

        public Object[] toArray() {
            return valuesToArray(new Object[size]);
        }

        public <T> T[] toArray(T[] a) {
            return valuesToArray(prepareArray(a));
        }

        public final void forEach(Consumer<? super V> action) {
            if(action == null)
                throw new NullPointerException();
            int mc = modCount;
            for(LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after)
                action.accept(e.value);
            if(modCount != mc)
                throw new ConcurrentModificationException();
        }
    }

    public Set<Map.Entry<K,V>> entrySet() {
        Set<Map.Entry<K,V>> es;
        return (es = entrySet) == null ? (entrySet = new LinkedEntrySet()) : es;
    }

    final class LinkedEntrySet extends AbstractSet<Map.Entry<K,V>> {

        public final int size() {
            return size;
        }

        public final void clear() {
            LinkedHashMap.this.clear();
        }

        public final java.util.Iterator<Map.Entry<K,V>> iterator() {
            return new LinkedEntryIterator();
        }

        public final boolean contains(Object o) {
            if(!(o instanceof Map.Entry<?, ?> e))
                return false;
            Object key = e.getKey();
            Node<K,V> candidate = getNode(key);
            return candidate != null && candidate.equals(e);
        }

        public final boolean remove(Object o) {
            if(o instanceof Map.Entry<?, ?> e) {
                Object key = e.getKey();
                Object value = e.getValue();
                return removeNode(hash(key), key, value, true, true) != null;
            }
            return false;
        }

        public final Spliterator<Map.Entry<K,V>> spliterator() {
            return Spliterators.spliterator(this, Spliterator.SIZED | Spliterator.ORDERED | Spliterator.DISTINCT);
        }

        public final void forEach(Consumer<? super Map.Entry<K,V>> action) {
            if(action == null)
                throw new NullPointerException();
            int mc = modCount;
            for(LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after)
                action.accept(e);
            if(modCount != mc)
                throw new ConcurrentModificationException();
        }
    }

    // Map overrides

    public void forEach(BiConsumer<? super K, ? super V> action) {
        if(action == null)
            throw new NullPointerException();
        int mc = modCount;
        for(LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after)
            action.accept(e.key, e.value);
        if(modCount != mc)
            throw new ConcurrentModificationException();
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        if(function == null)
            throw new NullPointerException();
        int mc = modCount;
        for(LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after)
            e.value = function.apply(e.key, e.value);
        if(modCount != mc)
            throw new ConcurrentModificationException();
    }

    // Iterators

    abstract class LinkedHashIterator {
        LinkedHashMap.Entry<K,V> next;
        LinkedHashMap.Entry<K,V> current;
        int expectedModCount;

        LinkedHashIterator() {
            next = head;
            expectedModCount = modCount;
            current = null;
        }

        public final boolean hasNext() {
            return next != null;
        }

        final LinkedHashMap.Entry<K,V> nextNode() {
            LinkedHashMap.Entry<K,V> e = next;
            if(modCount != expectedModCount)
                throw new ConcurrentModificationException();
            if(e == null)
                throw new NoSuchElementException();
            current = e;
            next = e.after;
            return e;
        }

        public final void remove() {
            Node<K,V> p = current;
            if(p == null)
                throw new IllegalStateException();
            if(modCount != expectedModCount)
                throw new ConcurrentModificationException();
            current = null;
            removeNode(p.hash, p.key, null, false, false);
            expectedModCount = modCount;
        }
    }

    final class LinkedKeyIterator extends LinkedHashIterator implements Iterator<K> {
        public final K next() {
            return nextNode().getKey();
        }
    }

    final class LinkedValueIterator extends LinkedHashIterator implements Iterator<V> {
        public final V next() {
            return nextNode().value;
        }
    }

    final class LinkedEntryIterator extends LinkedHashIterator implements Iterator<Map.Entry<K,V>> {
        public final Map.Entry<K,V> next() {
            return nextNode();
        }
    }
}

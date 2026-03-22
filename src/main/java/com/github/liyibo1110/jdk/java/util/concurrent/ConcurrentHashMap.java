package com.github.liyibo1110.jdk.java.util.concurrent;

import sun.misc.Unsafe;

import javax.swing.text.Segment;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.LongBinaryOperator;
import java.util.function.Predicate;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntBiFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongBiFunction;
import java.util.function.ToLongFunction;

/**
 * 并发容器的重要组件，主要作用是：在尽量保留HashMap数组 + 桶结构的前提下，让多线程下的读写都尽可能高效，
 * 同时保证扩容、链表树化、删除、计数等过程还能并发写作进行，所以难点并不在如何保证线程安全，而是：
 * 怎么把HashMap这种天然很容易互相干扰的数据结构，改造成一个可以局部加锁、无锁读取、协同扩容、局部树化的并发结构。
 *
 * 新版本CHM的功能特点：
 * 1、读取尽量无锁。
 * 2、写入尽量只锁桶头节点。
 * 3、扩容允许多线程协作搬迁。
 * 4、冲突严重时链表转成红黑树。
 * 5、大量状态通过CAS + volatile + 少量synchronized协作完成。
 * 因为CHM是混合了有锁和无锁功能，属于现实主义的工程折中产物，追求综合性能最优。
 *
 * 和HashMap主要的对比点：
 * 1、hash桶的状态变多了：桶里不仅存数据，还存了并发过程中的一些状态。
 * 2、读操作极度重视无锁化：CHM把并发成本尽量压到写路径和结构变更路径上，而不是让读路径变慢。
 * 3、写操作不是全表锁，而是桶级别局部同步，这意味着：
 *  - 不同桶之间写入可以并行。
 *  - 空桶插入甚至可以完全不加锁，直接CAS。
 *  - 只有发生桶冲突时才进入同步块。
 * 4、扩容不是一个线程单独干完，而是多线程协作：分段迁移 + 多线程协作搬运。
 * 5、支持链表树化，但树化比HashMap更复杂：重新报了一层并发版红黑树桶的管理控制外壳。
 * 6、size统计不是简单的volatile int。
 * 7、非常依赖特殊hash值，来表达控制语义：某些特殊节点会用负的hash来表示控制类型，例如：
 *  - MOVED
 *  - TREEBIN
 *  - RESERVED
 * @author liyibo
 * @date 2026-03-20 18:09
 */
public class ConcurrentHashMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V>, Serializable {
    private static final long serialVersionUID = 7249069246763182397L;

    /* ---------------- Constants -------------- */

    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private static final int DEFAULT_CAPACITY = 16;

    static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    private static final int DEFAULT_CONCURRENCY_LEVEL = 16;

    private static final float LOAD_FACTOR = 0.75f;

    static final int TREEIFY_THRESHOLD = 8;

    static final int UNTREEIFY_THRESHOLD = 6;

    static final int MIN_TREEIFY_CAPACITY = 64;

    private static final int MIN_TRANSFER_STRIDE = 16;

    private static final int RESIZE_STAMP_BITS = 16;

    private static final int MAX_RESIZERS = (1 << (32 - RESIZE_STAMP_BITS)) - 1;

    private static final int RESIZE_STAMP_SHIFT = 32 - RESIZE_STAMP_BITS;

    /** 特殊的hash值 */
    static final int MOVED     = -1;
    static final int TREEBIN   = -2;
    static final int RESERVED  = -3;
    static final int HASH_BITS = 0x7fffffff;

    static final int NCPU = Runtime.getRuntime().availableProcessors();

    private static final ObjectStreamField[] serialPersistentFields = {
            new ObjectStreamField("segments", Segment[].class),
            new ObjectStreamField("segmentMask", Integer.TYPE),
            new ObjectStreamField("segmentShift", Integer.TYPE),
    };

    /* ---------------- Nodes -------------- */

    static class Node<K,V> implements Map.Entry<K,V> {
        final int hash;
        final K key;
        volatile V val;
        volatile Node<K,V> next;

        Node(int hash, K key, V val) {
            this.hash = hash;
            this.key = key;
            this.val = val;
        }

        Node(int hash, K key, V val, Node<K,V> next) {
            this(hash, key, val);
            this.next = next;
        }

        public final K getKey() {
            return key;
        }

        public final V getValue() {
            return val;
        }

        public final int hashCode() {
            return key.hashCode() ^ val.hashCode();
        }

        public final String toString() {
            return Helpers.mapEntryToString(key, val);
        }

        public final V setValue(V value) {
            throw new UnsupportedOperationException();
        }

        public final boolean equals(Object o) {
            Object k, v, u; Map.Entry<?, ?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?, ?>)o).getKey()) != null &&
                    (v = e.getValue()) != null &&
                    (k == key || k.equals(key)) &&
                    (v == (u = val) || v.equals(u)));
        }

        Node<K,V> find(int h, Object k) {
            Node<K,V> e = this;
            if(k != null) {
                do {
                    K ek;
                    if(e.hash == h && ((ek = e.key) == k || (ek != null && k.equals(ek))))
                        return e;
                }while((e = e.next) != null);
            }
            return null;
        }
    }

    /* ---------------- Static utilities -------------- */

    static final int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    private static final int tableSizeFor(int c) {
        int n = -1 >>> Integer.numberOfLeadingZeros(c - 1);
        return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    }

    static Class<?> comparableClassFor(Object x) {
        if(x instanceof Comparable) {
            Class<?> c; Type[] ts, as; ParameterizedType p;
            if((c = x.getClass()) == String.class) // bypass checks
                return c;
            if((ts = c.getGenericInterfaces()) != null) {
                for(Type t : ts) {
                    if((t instanceof ParameterizedType)
                            && ((p = (ParameterizedType)t).getRawType() == Comparable.class)
                            && (as = p.getActualTypeArguments()) != null
                            && as.length == 1 && as[0] == c) // type arg is c
                        return c;
                }
            }
        }
        return null;
    }

    static int compareComparables(Class<?> kc, Object k, Object x) {
        return (x == null || x.getClass() != kc ? 0 : ((Comparable)k).compareTo(x));
    }

    /* ---------------- Table element access -------------- */

    static final <K,V> Node<K,V> tabAt(Node<K,V>[] tab, int i) {
        return (Node<K,V>)U.getReferenceAcquire(tab, ((long)i << ASHIFT) + ABASE);
    }

    static final <K,V> boolean casTabAt(Node<K,V>[] tab, int i, Node<K,V> c, Node<K,V> v) {
        return U.compareAndSetReference(tab, ((long)i << ASHIFT) + ABASE, c, v);
    }

    static final <K,V> void setTabAt(Node<K,V>[] tab, int i, Node<K,V> v) {
        U.putReferenceRelease(tab, ((long)i << ASHIFT) + ABASE, v);
    }

    /* ---------------- Fields -------------- */

    /**
     * 存放桶的数组，每个位置是一个桶入口。
     * 注意桶里可能是链表头、树桶入口，迁移标记节点等。
     * 具有延迟初始化的特性。
     */
    transient volatile Node<K,V>[] table;

    /**
     * 扩容中的新表，当开始扩容时：
     * 1、旧表还是table。
     * 2、新表先放在nextTable。
     * 3、等迁移完成后，再把table指向新表。
     * 说明CHM扩容不是瞬间替换，而是一个过程：
     * 1、先创建新表。
     * 2、多线程逐桶搬迁。
     * 3、搬完后切换正式表引用。
     */
    private transient volatile Node<K,V>[] nextTable;

    /**
     * 元素计数的基础值，作用是：在低竞争时，直接把增减记在这里，类似LongAdder的base。
     * 但是不能把它当作是最终size，真正元素个数统计是：baseCount + 所有的CounterCell的值。
     */
    private transient volatile long baseCount;

    /**
     * CHM的总控开关 / 状态寄存器，常见语义：
     * 1、当table尚未初始化时：可以表示初始化时要用的容量阈值。
     * 2、当table已初始化且未扩容时：通常表示下一次触发扩容的阈值（容量 * 负载因子）。
     * 3、当正在初始化或扩容时：可能是负值，用来编码当前状态（比如表示有线程在初始化，或者表示正在扩容，以及有多少线程参与扩容辅助）。
     */
    private transient volatile int sizeCtl;

    /**
     * 扩容迁移进度控制字段，作用是：扩容时，旧表不是一个桶一个桶随便搬的，而是按区间分任务。
     * transferIndex就像一个尚未分配的最大下标边界，线程来帮忙扩容时，会通过CAS从这里抢一段区间去搬。
     * 在transfer()会使用这个字段。
     */
    private transient volatile int transferIndex;

    /**
     * CounterCell[]初始化或扩容时的一个自旋锁标记。
     * 作用是：当多个线程发现计数竞争激烈，需要创建或扩容CounterCell[]时，不能大家一起改，所以用这个字段做一个很轻量的互斥控制。
     * 这个和Striped64思路有点像，不是通过加锁，而是：
     * 1、通过CAS抢一个代表忙的标记。
     * 2、抢到的线程去初始化或扩容cell数组。
     * 3、其它线程避让或重试。
     */
    private transient volatile int cellsBusy;

    /**
     * 分片计数数组，作用是：高并发下，不然所有线程都争抢baseCount，而是把计数分散到多个槽位里。
     */
    private transient volatile CounterCell[] counterCells;

    // views
    private transient KeySetView<K,V> keySet;
    private transient ValuesView<K,V> values;
    private transient EntrySetView<K,V> entrySet;

    /* ---------------- Public operations -------------- */

    public ConcurrentHashMap() {}

    public ConcurrentHashMap(int initialCapacity) {
        this(initialCapacity, LOAD_FACTOR, 1);
    }

    public ConcurrentHashMap(Map<? extends K, ? extends V> m) {
        this.sizeCtl = DEFAULT_CAPACITY;
        putAll(m);
    }

    public ConcurrentHashMap(int initialCapacity, float loadFactor) {
        this(initialCapacity, loadFactor, 1);
    }

    public ConcurrentHashMap(int initialCapacity, float loadFactor, int concurrencyLevel) {
        if(!(loadFactor > 0.0f) || initialCapacity < 0 || concurrencyLevel <= 0)
            throw new IllegalArgumentException();
        if(initialCapacity < concurrencyLevel)   // Use at least as many bins
            initialCapacity = concurrencyLevel;   // as estimated threads
        long size = (long)(1.0 + (long)initialCapacity / loadFactor);
        int cap = (size >= (long)MAXIMUM_CAPACITY)
                ? MAXIMUM_CAPACITY
                : tableSizeFor((int)size);
        this.sizeCtl = cap;
    }

    // Original (since JDK1.2) Map methods

    public int size() {
        long n = sumCount();
        return ((n < 0L) ? 0 :
                (n > (long)Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int)n);
    }

    public boolean isEmpty() {
        return sumCount() <= 0L;
    }

    /**
     * 流程很简单，其实信息量巨大（比如Node的find多态用法），简单是因为不涉及写数据，不涉及扩容、不涉及计数，不涉及锁。
     * tabAt的实现，可以保证读到的是最新可见的bucket头节点
     */
    public V get(Object key) {
        Node<K, V>[] tab;
        Node<K, V> e;
        Node<K, V> p;
        int n;
        int eh;
        K ek;
        int h = spread(key.hashCode()); // hash扰动，目的是不浪费高位信息，降低冲突概率，注意和HashMap的算法不太一样

        // table是否已经初始化，并且根据hash能找到对应的bucket
        if((tab = table) != null && (n = tab.length) > 0 && (e = tabAt(tab, (n - 1) & h)) != null) {
            if((eh = e.hash) == h) {    // hash和bucket头节点的hash是否一致
                if((ek = e.key) == key || (ek != null && key.equals(ek)))   // bucket头节点是否就是要找的key
                    return e.val;
            }else if(eh < 0) { // bucket头节点是否为特殊node，如果是则交给特定Node的find自己处理（类似多态的功能，复杂度被藏到了这里）
                return (p = e.find(h, key)) != null ? p.val : null;
            }
            while((e = e.next) != null) {   // 遍历bucket
                if(e.hash == h && ((ek = e.key) == key || (ek != null && key.equals(ek))))
                    return e.val;
            }
        }
        return null;
    }

    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    public boolean containsValue(Object value) {
        if(value == null)
            throw new NullPointerException();
        Node<K, V>[] t;
        if((t = table) != null) {
            Traverser<K,V> it = new Traverser<>(t, t.length, 0, t.length);
            for(Node<K,V> p; (p = it.advance()) != null; ) {
                V v;
                if((v = p.val) == value || (v != null && value.equals(v)))
                    return true;
            }
        }
        return false;
    }

    public V put(K key, V value) {
        return putVal(key, value, false);
    }

    /**
     * 在并发环境下，把一个key/value安全地放进hash桶里，如果需要就替换旧值，如果遇到扩容就协助扩容，
     * 最后再决定是否树化和是否扩容，整体流程：
     * 1、参数校验。
     * 2、计算扰动hash。
     * 3、进入自旋主循环。
     * 4、table还没初始化则先初始化。
     * 5、bucket为空就CAS直接插入。
     * 6、bucket在迁移中就帮忙扩容。
     * 7、bucket非空且稳定，就锁桶头处理链表或树的插入。
     * 8、插入完成后做计数、树化判断以及扩容判断。
     */
    final V putVal(K key, V value, boolean onlyIfAbsent) {
        if(key == null || value == null)
            throw new NullPointerException();
        int hash = spread(key.hashCode());
        /**
         * 这次桶操作的结构信息和长度信息。
         * 1、记录bucket的节点数量的大概情况：用来判断后面是否需要树化。
         * 2、作为最后addCount方法的参数2：让后续逻辑知道这次插入发生在什么样的桶里。
         */
        int binCount = 0;
        for(Node<K,V>[] tab = table; ; ) {
            Node<K,V> f;
            int n, i, fh;
            K fk;
            V fv;
            if(tab == null || (n = tab.length) == 0)    // 还没初始化就先初始化
                tab = initTable();
            else if((f = tabAt(tab, i = (n - 1) & hash)) == null) { // 定位的是个空桶
                if(casTabAt(tab, i, null, new Node<>(hash, key, value)))    // 直接不加锁，CAS插入节点
                    break;
            }else if((fh = f.hash) == MOVED)    // bucket正在迁移，要协助扩容（难点）
                tab = helpTransfer(tab, f); // 注意tab已经更新了扩容后的结构了
            /**
             * 桶头快速命中优化，如果来自putIfAbsent，先看看bucket头是不是已经就是我要的key，
             * 如果是，并且value非null，那就直接返回旧值，根本不用加锁。
             */
            else if(onlyIfAbsent
                    && fh == hash
                    && ((fk = f.key) == key || (fk != null && key.equals(fk)))
                    && (fv = f.val) != null)
                return fv;
            else {
                /**
                 * 核心主体区域：锁bucket头处理冲突bucket
                 * 锁bucket头节点的含义：
                 * 1、不同bucket之间互不影响。
                 * 2、同一时刻多个线程可以并发修改不同bucket。
                 * 3、只有冲突到同一个bucket才需要串行。
                 */
                V oldVal = null;
                synchronized(f) {
                    /**
                     * 非常重要的二次确认，没有这步就不安全。
                     */
                    if(tabAt(tab, i) == f) {
                        if(fh >= 0) {   // 普通链表bucket，注意如果是节点是MOVED或者TreeBin，hash值都会是负数
                            binCount = 1;   // bucket头节点f，本身就算一个节点了
                            for(Node<K,V> e = f;; ++binCount) {
                                K ek;
                                if(e.hash == hash && ((ek = e.key) == key || (ek != null && key.equals(ek)))) {
                                    oldVal = e.val;
                                    if(!onlyIfAbsent)
                                        e.val = value;
                                    break;
                                }
                                Node<K,V> pred = e;
                                if((e = e.next) == null) {
                                    pred.next = new Node<>(hash, key, value);   // 尾部插入
                                    break;
                                }
                            }
                        }else if (f instanceof TreeBin) {   // 树桶，要注意树桶的管理入口是TreeBin
                            Node<K,V> p;
                            /**
                             * 这并不是树的真实规模统计，目的主要是让后续逻辑知道：
                             * 1、当前bucket是树结构。
                             * 2、不是那种简单链表长度1的情况。
                             */
                            binCount = 2;
                            if((p = ((TreeBin<K,V>)f).putTreeVal(hash, key, value)) != null) {
                                oldVal = p.val;
                                if(!onlyIfAbsent)
                                    p.val = value;
                            }
                            /**
                             * 这个分支主要用于compute、computeIfAbsent这类复合原子更新场景中，作为一种占位节点。
                             * 可以看作是高级原子更新API的防护机制之一。
                             */
                        }else if(f instanceof ReservationNode)
                            throw new IllegalStateException("Recursive update");
                    }
                }
                /**
                 * 锁释放后的统一收尾逻辑
                 * binCount如果为0，说明刚才虽然进了synchronized，但没有成功处理当前bucket，例如：
                 * 1、tabAt(tab, i) != f
                 * 2、当前锁住的是旧bucket头，检查失败。
                 * 所以只有binCount != 0才说明这次bucket内逻辑真正执行了。
                 */
                if(binCount != 0) {
                    /**
                     * 树化判断：如果链表长度达到阈值，就尝试树化，
                     * 但不是达到阈值就一定真的树化，treeifyBin里面还会再判断表容量是否足够大，
                     * 如果表太小，可能优先扩容而不是树化。
                     */
                    if(binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    /**
                     * 说明这次不是新插入，而是命中了已有key：
                     * 1、可能是put覆盖了旧值。
                     * 2、可能是putIfAbsent发现了旧值。
                     * 这时语义上应该返回旧值，方法结束。
                     */
                    if(oldVal != null)
                        return oldVal;
                    /**
                     * 到这说明这时一次真正的新插入：
                     * 1、插到空bucket里。
                     * 2、插到链表尾。
                     * 3、插到树里。
                     * 跳出for，最后做count增加。
                     */
                    break;
                }
            }
        }
        addCount(1L, binCount);
        return null;    // 原来没值则返回null，原来有值则返回旧值，到这里说明原来没值
    }

    public void putAll(Map<? extends K, ? extends V> m) {
        tryPresize(m.size());
        for(Map.Entry<? extends K, ? extends V> e : m.entrySet())
            putVal(e.getKey(), e.getValue(), false);
    }

    public V remove(Object key) {
        return replaceNode(key, null, null);
    }

    /**
     * 一个统一的：定位key -> 校验旧值条件 -> 决定替换还是删除的bucket内修改器
     * 参数value具有两种语义：
     * 1、value为null：表示这个node要删除。
     * 2、value不为null：表示这个node要替换成这个新值
     * 参数cv是：expected current value，它控制的是：
     * 是否要求当前旧值必须等于某个指定值，才允许更新和删除，也有两种语义：
     * 1、cv为null：不校验旧值，只要key命中就操作。
     * 2、cv不为null：只有当当前node的值等于cv，才允许替换或删除。
     *
     * 方法流程：
     * 1、计算hash。
     * 2、进入外层for自旋。
     * 3、表空 / bucket空，则直接结束。
     * 4、bucket在迁移，则helpTransfer。
     * 5、普通稳定bucket，则锁bucket头。
     * 6、在链表或树中找key，并校验cv。
     * 7、成功替换或删除后返回旧值，删除时再addCount减少数量。
     */
    final V replaceNode(Object key, V value, Object cv) {
        int hash = spread(key.hashCode());
        for(Node<K,V>[] tab = table; ; ) {
            Node<K,V> f; int n, i, fh;
            if(tab == null || (n = tab.length) == 0 || (f = tabAt(tab, i = (n - 1) & hash)) == null)
                break;
            else if((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                V oldVal = null;
                boolean validated = false;
                synchronized(f) {
                    if(tabAt(tab, i) == f) {
                        if(fh >= 0) {
                            validated = true;
                            for(Node<K,V> e = f, pred = null;;) {
                                K ek;
                                if(e.hash == hash && ((ek = e.key) == key || (ek != null && key.equals(ek)))) {
                                    V ev = e.val;
                                    if (cv == null || cv == ev || (ev != null && cv.equals(ev))) {
                                        oldVal = ev;
                                        if(value != null)
                                            e.val = value;
                                        else if (pred != null)
                                            pred.next = e.next;
                                        else
                                            setTabAt(tab, i, e.next);
                                    }
                                    break;
                                }
                                pred = e;
                                if((e = e.next) == null)
                                    break;
                            }
                        }else if (f instanceof TreeBin) {
                            validated = true;
                            TreeBin<K, V> t = (TreeBin<K, V>)f;
                            TreeNode<K, V> r, p;
                            if((r = t.root) != null && (p = r.findTreeNode(hash, key, null)) != null) {
                                V pv = p.val;
                                if(cv == null || cv == pv || (pv != null && cv.equals(pv))) {
                                    oldVal = pv;
                                    if(value != null)
                                        p.val = value;
                                    else if (t.removeTreeNode(p))
                                        setTabAt(tab, i, untreeify(t.first));
                                }
                            }
                        }else if(f instanceof ReservationNode)
                            throw new IllegalStateException("Recursive update");
                    }
                }
                if(validated) {
                    if(oldVal != null) {
                        if(value == null)
                            addCount(-1L, -1);
                        return oldVal;
                    }
                    break;
                }
            }
        }
        return null;
    }

    public void clear() {
        long delta = 0L; // negative number of deletions
        int i = 0;
        Node<K,V>[] tab = table;
        while(tab != null && i < tab.length) {
            int fh;
            Node<K, V> f = tabAt(tab, i);
            if(f == null)
                ++i;
            else if((fh = f.hash) == MOVED) {
                tab = helpTransfer(tab, f);
                i = 0; // restart
            }else {
                synchronized(f) {
                    if(tabAt(tab, i) == f) {
                        Node<K,V> p = (fh >= 0 ? f : (f instanceof TreeBin) ? ((TreeBin<K,V>)f).first : null);
                        while(p != null) {
                            --delta;
                            p = p.next;
                        }
                        setTabAt(tab, i++, null);
                    }
                }
            }
        }
        if(delta != 0L)
            addCount(delta, -1);
    }

    public KeySetView<K, V> keySet() {
        KeySetView<K, V> ks;
        if((ks = keySet) != null)
            return ks;
        return keySet = new KeySetView<K, V>(this, null);
    }

    public Collection<V> values() {
        ValuesView<K, V> vs;
        if((vs = values) != null)
            return vs;
        return values = new ValuesView<K, V>(this);
    }

    public Set<Map.Entry<K, V>> entrySet() {
        EntrySetView<K, V> es;
        if((es = entrySet) != null)
            return es;
        return entrySet = new EntrySetView<K, V>(this);
    }

    public int hashCode() {
        int h = 0;
        Node<K, V>[] t;
        if((t = table) != null) {
            Traverser<K,V> it = new Traverser<>(t, t.length, 0, t.length);
            for(Node<K,V> p; (p = it.advance()) != null; )
                h += p.key.hashCode() ^ p.val.hashCode();
        }
        return h;
    }

    public String toString() {
        Node<K,V>[] t;
        int f = (t = table) == null ? 0 : t.length;
        Traverser<K, V> it = new Traverser<>(t, f, 0, f);
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        Node<K,V> p;
        if((p = it.advance()) != null) {
            for(;;) {
                K k = p.key;
                V v = p.val;
                sb.append(k == this ? "(this Map)" : k);
                sb.append('=');
                sb.append(v == this ? "(this Map)" : v);
                if((p = it.advance()) == null)
                    break;
                sb.append(',').append(' ');
            }
        }
        return sb.append('}').toString();
    }

    public boolean equals(Object o) {
        if(o != this) {
            if(!(o instanceof Map))
                return false;
            Map<?, ?> m = (Map<?, ?>) o;
            Node<K, V>[] t;
            int f = (t = table) == null ? 0 : t.length;
            Traverser<K, V> it = new Traverser<>(t, f, 0, f);
            for(Node<K, V> p; (p = it.advance()) != null; ) {
                V val = p.val;
                Object v = m.get(p.key);
                if(v == null || (v != val && !v.equals(val)))
                    return false;
            }
            for(Map.Entry<?,?> e : m.entrySet()) {
                Object mk, mv, v;
                if((mk = e.getKey()) == null ||
                        (mv = e.getValue()) == null ||
                        (v = get(mk)) == null ||
                        (mv != v && !mv.equals(v)))
                    return false;
            }
        }
        return true;
    }

    static class Segment<K,V> extends ReentrantLock implements Serializable {
        private static final long serialVersionUID = 2249069246763182397L;
        final float loadFactor;
        Segment(float lf) {
            this.loadFactor = lf;
        }
    }

    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        // For serialization compatibility
        // Emulate segment calculation from previous version of this class
        int sshift = 0;
        int ssize = 1;
        while(ssize < DEFAULT_CONCURRENCY_LEVEL) {
            ++sshift;
            ssize <<= 1;
        }
        int segmentShift = 32 - sshift;
        int segmentMask = ssize - 1;
        @SuppressWarnings("unchecked")
        Segment<K,V>[] segments = (Segment<K,V>[]) new Segment<?,?>[DEFAULT_CONCURRENCY_LEVEL];
        for(int i = 0; i < segments.length; ++i)
            segments[i] = new Segment<K,V>(LOAD_FACTOR);
        java.io.ObjectOutputStream.PutField streamFields = s.putFields();
        streamFields.put("segments", segments);
        streamFields.put("segmentShift", segmentShift);
        streamFields.put("segmentMask", segmentMask);
        s.writeFields();

        Node<K,V>[] t;
        if ((t = table) != null) {
            Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
            for (Node<K,V> p; (p = it.advance()) != null; ) {
                s.writeObject(p.key);
                s.writeObject(p.val);
            }
        }
        s.writeObject(null);
        s.writeObject(null);
    }

    private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
        /*
         * To improve performance in typical cases, we create nodes
         * while reading, then place in table once size is known.
         * However, we must also validate uniqueness and deal with
         * overpopulated bins while doing so, which requires
         * specialized versions of putVal mechanics.
         */
        sizeCtl = -1; // force exclusion for table construction
        s.defaultReadObject();
        long size = 0L;
        Node<K,V> p = null;
        for (;;) {
            @SuppressWarnings("unchecked")
            K k = (K) s.readObject();
            @SuppressWarnings("unchecked")
            V v = (V) s.readObject();
            if (k != null && v != null) {
                p = new Node<K,V>(spread(k.hashCode()), k, v, p);
                ++size;
            }
            else
                break;
        }
        if (size == 0L)
            sizeCtl = 0;
        else {
            long ts = (long)(1.0 + size / LOAD_FACTOR);
            int n = (ts >= (long)MAXIMUM_CAPACITY) ?
                    MAXIMUM_CAPACITY : tableSizeFor((int)ts);
            @SuppressWarnings("unchecked")
            Node<K,V>[] tab = (Node<K,V>[])new Node<?,?>[n];
            int mask = n - 1;
            long added = 0L;
            while (p != null) {
                boolean insertAtFront;
                Node<K,V> next = p.next, first;
                int h = p.hash, j = h & mask;
                if ((first = tabAt(tab, j)) == null)
                    insertAtFront = true;
                else {
                    K k = p.key;
                    if (first.hash < 0) {
                        TreeBin<K,V> t = (TreeBin<K,V>)first;
                        if (t.putTreeVal(h, k, p.val) == null)
                            ++added;
                        insertAtFront = false;
                    }
                    else {
                        int binCount = 0;
                        insertAtFront = true;
                        Node<K,V> q; K qk;
                        for (q = first; q != null; q = q.next) {
                            if (q.hash == h &&
                                    ((qk = q.key) == k ||
                                            (qk != null && k.equals(qk)))) {
                                insertAtFront = false;
                                break;
                            }
                            ++binCount;
                        }
                        if (insertAtFront && binCount >= TREEIFY_THRESHOLD) {
                            insertAtFront = false;
                            ++added;
                            p.next = first;
                            TreeNode<K,V> hd = null, tl = null;
                            for (q = p; q != null; q = q.next) {
                                TreeNode<K,V> t = new TreeNode<K,V>
                                        (q.hash, q.key, q.val, null, null);
                                if ((t.prev = tl) == null)
                                    hd = t;
                                else
                                    tl.next = t;
                                tl = t;
                            }
                            setTabAt(tab, j, new TreeBin<K,V>(hd));
                        }
                    }
                }
                if (insertAtFront) {
                    ++added;
                    p.next = first;
                    setTabAt(tab, j, p);
                }
                p = next;
            }
            table = tab;
            sizeCtl = n - (n >>> 2);
            baseCount = added;
        }
    }

    // ConcurrentMap methods

    public V putIfAbsent(K key, V value) {
        return putVal(key, value, true);
    }

    public boolean remove(Object key, Object value) {
        if(key == null)
            throw new NullPointerException();
        return value != null && replaceNode(key, null, value) != null;
    }

    public boolean replace(K key, V oldValue, V newValue) {
        if(key == null || oldValue == null || newValue == null)
            throw new NullPointerException();
        return replaceNode(key, newValue, oldValue) != null;
    }

    public V replace(K key, V value) {
        if(key == null || value == null)
            throw new NullPointerException();
        return replaceNode(key, value, null);
    }

    // Overrides of JDK8+ Map extension method defaults

    public V getOrDefault(Object key, V defaultValue) {
        V v;
        return (v = get(key)) == null ? defaultValue : v;
    }

    public void forEach(BiConsumer<? super K, ? super V> action) {
        if(action == null)
            throw new NullPointerException();
        Node<K, V>[] t;
        if((t = table) != null) {
            Traverser<K, V> it = new Traverser(t, t.length, 0, t.length);
            for(Node<K, V> p; (p = it.advance()) != null; )
                action.accept(p.key, p.val);
        }
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        if(function == null)
            throw new NullPointerException();
        Node<K, V>[] t;
        if((t = table) != null) {
            Traverser<K, V> it = new Traverser<>(t, t.length, 0, t.length);
            for(Node<K, V> p; (p = it.advance()) != null; ) {
                V oldValue = p.val;
                for(K key = p.key;;) {
                    V newValue = function.apply(key, oldValue);
                    if(newValue == null)
                        throw new NullPointerException();
                    if(replaceNode(key, newValue, oldValue) != null || (oldValue = get(key)) == null)
                        break;
                }
            }
        }
    }

    boolean removeEntryIf(Predicate<? super Entry<K, V>> function) {
        if(function == null)
            throw new NullPointerException();
        Node<K, V>[] t;
        boolean removed = false;
        if((t = table) != null) {
            Traverser<K, V> it = new Traverser<>(t, t.length, 0, t.length);
            for(Node<K, V> p; (p = it.advance()) != null; ) {
                K k = p.key;
                V v = p.val;
                Map.Entry<K, V> e = new AbstractMap.SimpleImmutableEntry<>(k, v);
                if(function.test(e) && replaceNode(k, null, v) != null)
                    removed = true;
            }
        }
        return removed;
    }

    boolean removeValueIf(Predicate<? super V> function) {
        if(function == null)
            throw new NullPointerException();
        Node<K, V>[] t;
        boolean removed = false;
        if((t = table) != null) {
            Traverser<K ,V> it = new Traverser<>(t, t.length, 0, t.length);
            for(Node<K, V> p; (p = it.advance()) != null; ) {
                K k = p.key;
                V v = p.val;
                if(function.test(v) && replaceNode(k, null, v) != null)
                    removed = true;
            }
        }
        return removed;
    }

    /**
     * 只说和putVal额外增加的部分：
     * 如果key不存在，需要调用用户传入的Function计算出value，再决定要不要插入，
     * 而且这个计算过程还要防止递归更新和并发重复计算带来的结构混乱。（就是Function里面也可以再更新结构，这是最大的难点）
     * 新增的三个部分：
     * 1、ReservationNode。
     * 2、mappingFunction.apply(key)的调用时机。
     * 3、递归更新检测。
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if(key == null || mappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int binCount = 0;
        for(Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh; K fk; V fv;
            if(tab == null || (n = tab.length) == 0)
                tab = initTable();
            /**
             * 如果是空bucket，不能再像putVal那样直接CAS一个Node，因为还要调用Function算value，有两个风险：
             * 1、计算过程可能比较慢（Lambda类型的参数都有这类问题）：所以不能先无保护地算完再查，因为别的线程可能同时也在算。
             * 2、计算过程可能发生递归或再入map更新。
             * 这里的解决方案是：先往空bucket里放一个占位节点ReservationNode，表示：这个bucket正在计算中。
             */
            else if((f = tabAt(tab, i = (n - 1) & h)) == null) {
                Node<K,V> r = new ReservationNode<>();
                synchronized(r) {
                    if(casTabAt(tab, i, null, r)) {
                        binCount = 1;
                        Node<K, V> node = null;
                        try {
                            if((val = mappingFunction.apply(key)) != null)
                                node = new Node<>(h, key, val);
                        } finally {
                            /**
                             * 不管计算结果成功还是异常，最后都必须把bucket从ReservationNode恢复掉。
                             * 1、结果非null：放正式Node。
                             * 2、结果是null：放回null。
                             * 3、结果是异常：Node还是null，也会清理掉ReservationNode
                             */
                            setTabAt(tab, i, node);
                        }
                    }
                }
                if(binCount != 0)   // 当前线程已经成功占位并完成了本bucket的计算发布，不用再重试了
                    break;
            }else if((fh = f.hash) == MOVED)    // 协助扩容
                tab = helpTransfer(tab, f);
            /**
             * 又是bucket头快速命中的优化：如果bucket就是要的key，而且value非null，说明：
             * 1、key已经存在。
             * 2、根本不需要compute。
             * 3、直接返回旧值。
             */
            else if(fh == h
                    && ((fk = f.key) == key || (fk != null && key.equals(fk)))
                    && (fv = f.val) != null)
                return fv;
            else {
                /**
                 * 非空稳定bucket的真正处理，和putVal基本一致，但是多了个added变量，表示：这一次是否真的新增了一个节点。
                 * 因为computeIfAbsent方法最终有三种结果：
                 * 1、key已存在：直接返回旧值，不新增。
                 * 2、key不存在，但Function计算结果是null：也是不新增（CHM压根就不允许保存value为null的Node）。
                 * 3、key不存在，Function结算结果不是null：新增Node。
                 * 所以只有结果3才需要最后调用addCount(1L, binCount)，因为要额外需要added来区别：这是旧值还是插入的新值。
                 */
                boolean added = false;
                synchronized(f) {
                    if(tabAt(tab, i) == f) {
                        /**
                         * 分支一：普通链表bucket，和putVal有点不同
                         */
                        if(fh >= 0) {
                            binCount = 1;
                            for(Node<K, V> e = f;; ++binCount) {
                                K ek;
                                if(e.hash == h && ((ek = e.key) == key || (ek != null && key.equals(ek)))) {
                                    val = e.val;
                                    break;
                                }
                                Node<K, V> pred = e;
                                /**
                                 * 遍历到链表尾才会计算Function，并且注意它是在最外面的synchronized执行的，
                                 * 因为传入的function应该尽量简单、无副作用、不要做重型工作。
                                 */
                                if((e = e.next) == null) {
                                    if((val = mappingFunction.apply(key)) != null) {
                                        /**
                                         * 非常重要的递归更新检查，因为这段位置：
                                         * 1、当前线程已经遍历到链表尾部。
                                         * 2、认为pred是尾节点。
                                         * 3、正准备把新节点挂到pred.next。
                                         * 如果这里发现pred.next != null，说明发生了异常的情况：尾部又被递归/重入地修改了，
                                         * 很可能是：Function内部又间接对同一个map的同一个bucket做了更新，
                                         * 导致结构在本次计算流程内部被递归改动，处理方式就是直接抛异常。
                                         */
                                        if(pred.next != null)
                                            throw new IllegalStateException("Recursive update");
                                        added = true;
                                        pred.next = new Node<>(h, key, val);
                                    }
                                    break;
                                }
                            }
                        }else if(f instanceof TreeBin) {
                            binCount = 2;
                            TreeBin<K, V> t = (TreeBin<K, V>)f;
                            TreeNode<K, V> r, p;
                            if((r = t.root) != null && (p = r.findTreeNode(h, key, null)) != null)
                                val = p.val;
                            else if((val = mappingFunction.apply(key)) != null) {
                                added = true;
                                t.putTreeVal(h, key, val);
                            }
                        }else if(f instanceof ReservationNode) {
                            /**
                             * 如果进入这个分支则说明：这个bucket处在某次compute类操作的占位计算中，
                             * 此时如果又试图在这个路径上做相关更新，往往意味着递归或非法再入，处理方式是直接抛异常。
                             */
                            throw new IllegalStateException("Recursive update");
                        }

                    }
                }
                /**
                 * 收尾，注意新增了对added的判断，如果为added，就直接返回旧值完事。
                 */
                if(binCount != 0) {
                    if(binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    if(!added)
                        return val;
                    break;
                }
            }
        }
        if (val != null)
            addCount(1L, binCount);
        return val;
    }

    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null)
                break;
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K,V> e = f, pred = null;; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(key, e.val);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        Node<K,V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null)
                                    break;
                            }
                        }
                        else if (f instanceof TreeBin) {
                            binCount = 2;
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> r, p;
                            if ((r = t.root) != null &&
                                    (p = r.findTreeNode(h, key, null)) != null) {
                                val = remappingFunction.apply(key, p.val);
                                if (val != null)
                                    p.val = val;
                                else {
                                    delta = -1;
                                    if (t.removeTreeNode(p))
                                        setTabAt(tab, i, untreeify(t.first));
                                }
                            }
                        }
                        else if (f instanceof ReservationNode)
                            throw new IllegalStateException("Recursive update");
                    }
                }
                if (binCount != 0)
                    break;
            }
        }
        if (delta != 0)
            addCount((long)delta, binCount);
        return val;
    }

    public V compute(K key,
                     BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                Node<K,V> r = new ReservationNode<K,V>();
                synchronized (r) {
                    if (casTabAt(tab, i, null, r)) {
                        binCount = 1;
                        Node<K,V> node = null;
                        try {
                            if ((val = remappingFunction.apply(key, null)) != null) {
                                delta = 1;
                                node = new Node<K,V>(h, key, val);
                            }
                        } finally {
                            setTabAt(tab, i, node);
                        }
                    }
                }
                if (binCount != 0)
                    break;
            }
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K,V> e = f, pred = null;; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(key, e.val);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        Node<K,V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null) {
                                    val = remappingFunction.apply(key, null);
                                    if (val != null) {
                                        if (pred.next != null)
                                            throw new IllegalStateException("Recursive update");
                                        delta = 1;
                                        pred.next = new Node<K,V>(h, key, val);
                                    }
                                    break;
                                }
                            }
                        }
                        else if (f instanceof TreeBin) {
                            binCount = 1;
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> r, p;
                            if ((r = t.root) != null)
                                p = r.findTreeNode(h, key, null);
                            else
                                p = null;
                            V pv = (p == null) ? null : p.val;
                            val = remappingFunction.apply(key, pv);
                            if (val != null) {
                                if (p != null)
                                    p.val = val;
                                else {
                                    delta = 1;
                                    t.putTreeVal(h, key, val);
                                }
                            }
                            else if (p != null) {
                                delta = -1;
                                if (t.removeTreeNode(p))
                                    setTabAt(tab, i, untreeify(t.first));
                            }
                        }
                        else if (f instanceof ReservationNode)
                            throw new IllegalStateException("Recursive update");
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    break;
                }
            }
        }
        if (delta != 0)
            addCount((long)delta, binCount);
        return val;
    }

    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (key == null || value == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                if (casTabAt(tab, i, null, new Node<K,V>(h, key, value))) {
                    delta = 1;
                    val = value;
                    break;
                }
            }
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K,V> e = f, pred = null;; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(e.val, value);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        Node<K,V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null) {
                                    delta = 1;
                                    val = value;
                                    pred.next = new Node<K,V>(h, key, val);
                                    break;
                                }
                            }
                        }
                        else if (f instanceof TreeBin) {
                            binCount = 2;
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> r = t.root;
                            TreeNode<K,V> p = (r == null) ? null :
                                    r.findTreeNode(h, key, null);
                            val = (p == null) ? value :
                                    remappingFunction.apply(p.val, value);
                            if (val != null) {
                                if (p != null)
                                    p.val = val;
                                else {
                                    delta = 1;
                                    t.putTreeVal(h, key, val);
                                }
                            }
                            else if (p != null) {
                                delta = -1;
                                if (t.removeTreeNode(p))
                                    setTabAt(tab, i, untreeify(t.first));
                            }
                        }
                        else if (f instanceof ReservationNode)
                            throw new IllegalStateException("Recursive update");
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    break;
                }
            }
        }
        if (delta != 0)
            addCount((long)delta, binCount);
        return val;
    }

    // Hashtable legacy methods

    public boolean contains(Object value) {
        return containsValue(value);
    }

    public Enumeration<K> keys() {
        Node<K,V>[] t;
        int f = (t = table) == null ? 0 : t.length;
        return new KeyIterator<>(t, f, 0, f, this);
    }

    public Enumeration<V> elements() {
        Node<K, V>[] t;
        int f = (t = table) == null ? 0 : t.length;
        return new ValueIterator<>(t, f, 0, f, this);
    }

    // ConcurrentHashMap-only methods

    public long mappingCount() {
        long n = sumCount();
        return (n < 0L) ? 0L : n; // ignore transient negative values
    }

    public static <K> KeySetView<K,Boolean> newKeySet() {
        return new KeySetView<K,Boolean>(new ConcurrentHashMap<K,Boolean>(), Boolean.TRUE);
    }

    public static <K> KeySetView<K,Boolean> newKeySet(int initialCapacity) {
        return new KeySetView<K,Boolean>(new ConcurrentHashMap<K,Boolean>(initialCapacity), Boolean.TRUE);
    }

    public KeySetView<K,V> keySet(V mappedValue) {
        if (mappedValue == null)
            throw new NullPointerException();
        return new KeySetView<K,V>(this, mappedValue);
    }

    /* ---------------- Special Nodes -------------- */

    /**
     * 正在迁移中
     */
    static final class ForwardingNode<K,V> extends Node<K,V> {
        final Node<K,V>[] nextTable;

        ForwardingNode(Node<K,V>[] tab) {
            super(MOVED, null, null);
            this.nextTable = tab;
        }

        Node<K,V> find(int h, Object k) {
            outer: for(Node<K, V>[] tab = nextTable;;) {
                Node<K, V> e; int n;
                if(k == null || tab == null || (n = tab.length) == 0 || (e = tabAt(tab, (n - 1) & h)) == null)
                    return null;
                while(true) {
                    int eh; K ek;
                    if((eh = e.hash) == h && ((ek = e.key) == k || (ek != null && k.equals(ek))))
                        return e;
                    if(eh < 0) {
                        if(e instanceof ForwardingNode) {
                            tab = ((ForwardingNode<K, V>)e).nextTable;
                            continue outer;
                        }else
                            return e.find(h, k);
                    }
                    if((e = e.next) == null)
                        return null;
                }
            }
        }
    }

    static final class ReservationNode<K, V> extends Node<K, V> {
        ReservationNode() {
            super(RESERVED, null, null);
        }

        Node<K,V> find(int h, Object k) {
            return null;
        }
    }

    /* ---------------- Table Initialization and Resizing -------------- */

    /**
     * Returns the stamp bits for resizing a table of size n.
     * Must be negative when shifted left by RESIZE_STAMP_SHIFT.
     */
    static final int resizeStamp(int n) {
        return Integer.numberOfLeadingZeros(n) | (1 << (RESIZE_STAMP_BITS - 1));
    }

    /**
     * Initializes table, using the size recorded in sizeCtl.
     */
    private final Node<K,V>[] initTable() {
        Node<K,V>[] tab; int sc;
        while ((tab = table) == null || tab.length == 0) {
            if ((sc = sizeCtl) < 0)
                Thread.yield(); // lost initialization race; just spin
            else if (U.compareAndSetInt(this, SIZECTL, sc, -1)) {
                try {
                    if ((tab = table) == null || tab.length == 0) {
                        int n = (sc > 0) ? sc : DEFAULT_CAPACITY;
                        @SuppressWarnings("unchecked")
                        Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n];
                        table = tab = nt;
                        sc = n - (n >>> 2);
                    }
                } finally {
                    sizeCtl = sc;
                }
                break;
            }
        }
        return tab;
    }

    /**
     * Adds to count, and if table is too small and not already
     * resizing, initiates transfer. If already resizing, helps
     * perform transfer if work is available.  Rechecks occupancy
     * after a transfer to see if another resize is already needed
     * because resizings are lagging additions.
     *
     * @param x the count to add
     * @param check if <0, don't check resize, if <= 1 only check if uncontended
     */
    private final void addCount(long x, int check) {
        CounterCell[] cs; long b, s;
        if ((cs = counterCells) != null ||
                !U.compareAndSetLong(this, BASECOUNT, b = baseCount, s = b + x)) {
            CounterCell c; long v; int m;
            boolean uncontended = true;
            if (cs == null || (m = cs.length - 1) < 0 ||
                    (c = cs[ThreadLocalRandom.getProbe() & m]) == null ||
                    !(uncontended =
                            U.compareAndSetLong(c, CELLVALUE, v = c.value, v + x))) {
                fullAddCount(x, uncontended);
                return;
            }
            if (check <= 1)
                return;
            s = sumCount();
        }
        if (check >= 0) {
            Node<K,V>[] tab, nt; int n, sc;
            while (s >= (long)(sc = sizeCtl) && (tab = table) != null &&
                    (n = tab.length) < MAXIMUM_CAPACITY) {
                int rs = resizeStamp(n) << RESIZE_STAMP_SHIFT;
                if (sc < 0) {
                    if (sc == rs + MAX_RESIZERS || sc == rs + 1 ||
                            (nt = nextTable) == null || transferIndex <= 0)
                        break;
                    if (U.compareAndSetInt(this, SIZECTL, sc, sc + 1))
                        transfer(tab, nt);
                }
                else if (U.compareAndSetInt(this, SIZECTL, sc, rs + 2))
                    transfer(tab, null);
                s = sumCount();
            }
        }
    }


    /**
     * 帮忙迁移bucket
     */
    final Node<K,V>[] helpTransfer(Node<K, V>[] tab, Node<K, V> f) {
        Node<K,V>[] nextTab; int sc;
        if (tab != null && (f instanceof ForwardingNode) &&
                (nextTab = ((ForwardingNode<K,V>)f).nextTable) != null) {
            int rs = resizeStamp(tab.length) << RESIZE_STAMP_SHIFT;
            while (nextTab == nextTable && table == tab &&
                    (sc = sizeCtl) < 0) {
                if (sc == rs + MAX_RESIZERS || sc == rs + 1 ||
                        transferIndex <= 0)
                    break;
                if (U.compareAndSetInt(this, SIZECTL, sc, sc + 1)) {
                    transfer(tab, nextTab);
                    break;
                }
            }
            return nextTab;
        }
        return table;
    }

    /**
     * Tries to presize table to accommodate the given number of elements.
     *
     * @param size number of elements (doesn't need to be perfectly accurate)
     */
    private final void tryPresize(int size) {
        int c = (size >= (MAXIMUM_CAPACITY >>> 1)) ? MAXIMUM_CAPACITY :
                tableSizeFor(size + (size >>> 1) + 1);
        int sc;
        while ((sc = sizeCtl) >= 0) {
            Node<K,V>[] tab = table; int n;
            if (tab == null || (n = tab.length) == 0) {
                n = (sc > c) ? sc : c;
                if (U.compareAndSetInt(this, SIZECTL, sc, -1)) {
                    try {
                        if (table == tab) {
                            @SuppressWarnings("unchecked")
                            Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n];
                            table = nt;
                            sc = n - (n >>> 2);
                        }
                    } finally {
                        sizeCtl = sc;
                    }
                }
            }
            else if (c <= sc || n >= MAXIMUM_CAPACITY)
                break;
            else if (tab == table) {
                int rs = resizeStamp(n);
                if (U.compareAndSetInt(this, SIZECTL, sc,
                        (rs << RESIZE_STAMP_SHIFT) + 2))
                    transfer(tab, null);
            }
        }
    }

    /**
     * Moves and/or copies the nodes in each bin to new table. See
     * above for explanation.
     */
    private final void transfer(Node<K,V>[] tab, Node<K,V>[] nextTab) {
        int n = tab.length, stride;
        if ((stride = (NCPU > 1) ? (n >>> 3) / NCPU : n) < MIN_TRANSFER_STRIDE)
            stride = MIN_TRANSFER_STRIDE; // subdivide range
        if (nextTab == null) {            // initiating
            try {
                @SuppressWarnings("unchecked")
                Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n << 1];
                nextTab = nt;
            } catch (Throwable ex) {      // try to cope with OOME
                sizeCtl = Integer.MAX_VALUE;
                return;
            }
            nextTable = nextTab;
            transferIndex = n;
        }
        int nextn = nextTab.length;
        ForwardingNode<K,V> fwd = new ForwardingNode<K,V>(nextTab);
        boolean advance = true;
        boolean finishing = false; // to ensure sweep before committing nextTab
        for (int i = 0, bound = 0;;) {
            Node<K,V> f; int fh;
            while (advance) {
                int nextIndex, nextBound;
                if (--i >= bound || finishing)
                    advance = false;
                else if ((nextIndex = transferIndex) <= 0) {
                    i = -1;
                    advance = false;
                }
                else if (U.compareAndSetInt
                        (this, TRANSFERINDEX, nextIndex,
                                nextBound = (nextIndex > stride ?
                                        nextIndex - stride : 0))) {
                    bound = nextBound;
                    i = nextIndex - 1;
                    advance = false;
                }
            }
            if (i < 0 || i >= n || i + n >= nextn) {
                int sc;
                if (finishing) {
                    nextTable = null;
                    table = nextTab;
                    sizeCtl = (n << 1) - (n >>> 1);
                    return;
                }
                if (U.compareAndSetInt(this, SIZECTL, sc = sizeCtl, sc - 1)) {
                    if ((sc - 2) != resizeStamp(n) << RESIZE_STAMP_SHIFT)
                        return;
                    finishing = advance = true;
                    i = n; // recheck before commit
                }
            }
            else if ((f = tabAt(tab, i)) == null)
                advance = casTabAt(tab, i, null, fwd);
            else if ((fh = f.hash) == MOVED)
                advance = true; // already processed
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        Node<K,V> ln, hn;
                        if (fh >= 0) {
                            int runBit = fh & n;
                            Node<K,V> lastRun = f;
                            for (Node<K,V> p = f.next; p != null; p = p.next) {
                                int b = p.hash & n;
                                if (b != runBit) {
                                    runBit = b;
                                    lastRun = p;
                                }
                            }
                            if (runBit == 0) {
                                ln = lastRun;
                                hn = null;
                            }
                            else {
                                hn = lastRun;
                                ln = null;
                            }
                            for (Node<K,V> p = f; p != lastRun; p = p.next) {
                                int ph = p.hash; K pk = p.key; V pv = p.val;
                                if ((ph & n) == 0)
                                    ln = new Node<K,V>(ph, pk, pv, ln);
                                else
                                    hn = new Node<K,V>(ph, pk, pv, hn);
                            }
                            setTabAt(nextTab, i, ln);
                            setTabAt(nextTab, i + n, hn);
                            setTabAt(tab, i, fwd);
                            advance = true;
                        }
                        else if (f instanceof TreeBin) {
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> lo = null, loTail = null;
                            TreeNode<K,V> hi = null, hiTail = null;
                            int lc = 0, hc = 0;
                            for (Node<K,V> e = t.first; e != null; e = e.next) {
                                int h = e.hash;
                                TreeNode<K,V> p = new TreeNode<K,V>
                                        (h, e.key, e.val, null, null);
                                if ((h & n) == 0) {
                                    if ((p.prev = loTail) == null)
                                        lo = p;
                                    else
                                        loTail.next = p;
                                    loTail = p;
                                    ++lc;
                                }
                                else {
                                    if ((p.prev = hiTail) == null)
                                        hi = p;
                                    else
                                        hiTail.next = p;
                                    hiTail = p;
                                    ++hc;
                                }
                            }
                            ln = (lc <= UNTREEIFY_THRESHOLD) ? untreeify(lo) :
                                    (hc != 0) ? new TreeBin<K,V>(lo) : t;
                            hn = (hc <= UNTREEIFY_THRESHOLD) ? untreeify(hi) :
                                    (lc != 0) ? new TreeBin<K,V>(hi) : t;
                            setTabAt(nextTab, i, ln);
                            setTabAt(nextTab, i + n, hn);
                            setTabAt(tab, i, fwd);
                            advance = true;
                        }
                        else if (f instanceof ReservationNode)
                            throw new IllegalStateException("Recursive update");
                    }
                }
            }
        }
    }

    /* ---------------- Counter support -------------- */

    static final class CounterCell {
        volatile long value;

        CounterCell(long x) {
            value = x;
        }
    }

    /**
     * 计算size总数，公式很简单：baseCount + 所有CounterCell的value。
     */
    final long sumCount() {
        CounterCell[] cs = counterCells;
        long sum = baseCount;
        if(cs != null) {
            for(CounterCell c : cs)
                if(c != null)
                    sum += c.value;
        }
        return sum;
    }

    // See LongAdder version for explanation
    private final void fullAddCount(long x, boolean wasUncontended) {
        int h;
        if ((h = ThreadLocalRandom.getProbe()) == 0) {
            ThreadLocalRandom.localInit();      // force initialization
            h = ThreadLocalRandom.getProbe();
            wasUncontended = true;
        }
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            CounterCell[] cs; CounterCell c; int n; long v;
            if ((cs = counterCells) != null && (n = cs.length) > 0) {
                if ((c = cs[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {            // Try to attach new Cell
                        CounterCell r = new CounterCell(x); // Optimistic create
                        if (cellsBusy == 0 &&
                                U.compareAndSetInt(this, CELLSBUSY, 0, 1)) {
                            boolean created = false;
                            try {               // Recheck under lock
                                CounterCell[] rs; int m, j;
                                if ((rs = counterCells) != null &&
                                        (m = rs.length) > 0 &&
                                        rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                else if (U.compareAndSetLong(c, CELLVALUE, v = c.value, v + x))
                    break;
                else if (counterCells != cs || n >= NCPU)
                    collide = false;            // At max size or stale
                else if (!collide)
                    collide = true;
                else if (cellsBusy == 0 &&
                        U.compareAndSetInt(this, CELLSBUSY, 0, 1)) {
                    try {
                        if (counterCells == cs) // Expand table unless stale
                            counterCells = Arrays.copyOf(cs, n << 1);
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = ThreadLocalRandom.advanceProbe(h);
            }
            else if (cellsBusy == 0 && counterCells == cs &&
                    U.compareAndSetInt(this, CELLSBUSY, 0, 1)) {
                boolean init = false;
                try {                           // Initialize table
                    if (counterCells == cs) {
                        CounterCell[] rs = new CounterCell[2];
                        rs[h & 1] = new CounterCell(x);
                        counterCells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            else if (U.compareAndSetLong(this, BASECOUNT, v = baseCount, v + x))
                break;                          // Fall back on using base
        }
    }

    /* ---------------- Conversion from/to TreeBins -------------- */

    /**
     * Replaces all linked nodes in bin at given index unless table is
     * too small, in which case resizes instead.
     */
    private final void treeifyBin(Node<K,V>[] tab, int index) {
        Node<K,V> b; int n;
        if (tab != null) {
            if ((n = tab.length) < MIN_TREEIFY_CAPACITY)
                tryPresize(n << 1);
            else if ((b = tabAt(tab, index)) != null && b.hash >= 0) {
                synchronized (b) {
                    if (tabAt(tab, index) == b) {
                        TreeNode<K,V> hd = null, tl = null;
                        for (Node<K,V> e = b; e != null; e = e.next) {
                            TreeNode<K,V> p =
                                    new TreeNode<K,V>(e.hash, e.key, e.val,
                                            null, null);
                            if ((p.prev = tl) == null)
                                hd = p;
                            else
                                tl.next = p;
                            tl = p;
                        }
                        setTabAt(tab, index, new TreeBin<K,V>(hd));
                    }
                }
            }
        }
    }

    /**
     * Returns a list of non-TreeNodes replacing those in given list.
     */
    static <K,V> Node<K,V> untreeify(Node<K,V> b) {
        Node<K,V> hd = null, tl = null;
        for (Node<K,V> q = b; q != null; q = q.next) {
            Node<K,V> p = new Node<K,V>(q.hash, q.key, q.val);
            if (tl == null)
                hd = p;
            else
                tl.next = p;
            tl = p;
        }
        return hd;
    }

    /* ---------------- TreeNodes -------------- */

    static final class TreeNode<K,V> extends Node<K,V> {
        TreeNode<K,V> parent;  // red-black tree links
        TreeNode<K,V> left;
        TreeNode<K,V> right;
        TreeNode<K,V> prev;    // needed to unlink next upon deletion
        boolean red;

        TreeNode(int hash, K key, V val, Node<K,V> next,
                 TreeNode<K,V> parent) {
            super(hash, key, val, next);
            this.parent = parent;
        }

        Node<K,V> find(int h, Object k) {
            return findTreeNode(h, k, null);
        }

        /**
         * Returns the TreeNode (or null if not found) for the given key
         * starting at given root.
         */
        final TreeNode<K,V> findTreeNode(int h, Object k, Class<?> kc) {
            if (k != null) {
                TreeNode<K,V> p = this;
                do {
                    int ph, dir; K pk; TreeNode<K,V> q;
                    TreeNode<K,V> pl = p.left, pr = p.right;
                    if ((ph = p.hash) > h)
                        p = pl;
                    else if (ph < h)
                        p = pr;
                    else if ((pk = p.key) == k || (pk != null && k.equals(pk)))
                        return p;
                    else if (pl == null)
                        p = pr;
                    else if (pr == null)
                        p = pl;
                    else if ((kc != null ||
                            (kc = comparableClassFor(k)) != null) &&
                            (dir = compareComparables(kc, k, pk)) != 0)
                        p = (dir < 0) ? pl : pr;
                    else if ((q = pr.findTreeNode(h, k, kc)) != null)
                        return q;
                    else
                        p = pl;
                } while (p != null);
            }
            return null;
        }
    }

    /* ---------------- TreeBins -------------- */

    static final class TreeBin<K,V> extends Node<K,V> {
        TreeNode<K,V> root;
        volatile TreeNode<K,V> first;
        volatile Thread waiter;
        volatile int lockState;
        // values for lockState
        static final int WRITER = 1; // set while holding write lock
        static final int WAITER = 2; // set when waiting for write lock
        static final int READER = 4; // increment value for setting read lock

        /**
         * Tie-breaking utility for ordering insertions when equal
         * hashCodes and non-comparable. We don't require a total
         * order, just a consistent insertion rule to maintain
         * equivalence across rebalancings. Tie-breaking further than
         * necessary simplifies testing a bit.
         */
        static int tieBreakOrder(Object a, Object b) {
            int d;
            if (a == null || b == null ||
                    (d = a.getClass().getName().
                            compareTo(b.getClass().getName())) == 0)
                d = (System.identityHashCode(a) <= System.identityHashCode(b) ?
                        -1 : 1);
            return d;
        }

        /**
         * Creates bin with initial set of nodes headed by b.
         */
        TreeBin(TreeNode<K,V> b) {
            super(TREEBIN, null, null);
            this.first = b;
            TreeNode<K,V> r = null;
            for (TreeNode<K,V> x = b, next; x != null; x = next) {
                next = (TreeNode<K,V>)x.next;
                x.left = x.right = null;
                if (r == null) {
                    x.parent = null;
                    x.red = false;
                    r = x;
                }
                else {
                    K k = x.key;
                    int h = x.hash;
                    Class<?> kc = null;
                    for (TreeNode<K,V> p = r;;) {
                        int dir, ph;
                        K pk = p.key;
                        if ((ph = p.hash) > h)
                            dir = -1;
                        else if (ph < h)
                            dir = 1;
                        else if ((kc == null &&
                                (kc = comparableClassFor(k)) == null) ||
                                (dir = compareComparables(kc, k, pk)) == 0)
                            dir = tieBreakOrder(k, pk);
                        TreeNode<K,V> xp = p;
                        if ((p = (dir <= 0) ? p.left : p.right) == null) {
                            x.parent = xp;
                            if (dir <= 0)
                                xp.left = x;
                            else
                                xp.right = x;
                            r = balanceInsertion(r, x);
                            break;
                        }
                    }
                }
            }
            this.root = r;
            assert checkInvariants(root);
        }

        /**
         * Acquires write lock for tree restructuring.
         */
        private final void lockRoot() {
            if (!U.compareAndSetInt(this, LOCKSTATE, 0, WRITER))
                contendedLock(); // offload to separate method
        }

        /**
         * Releases write lock for tree restructuring.
         */
        private final void unlockRoot() {
            lockState = 0;
        }

        /**
         * Possibly blocks awaiting root lock.
         */
        private final void contendedLock() {
            Thread current = Thread.currentThread(), w;
            for (int s;;) {
                if (((s = lockState) & ~WAITER) == 0) {
                    if (U.compareAndSetInt(this, LOCKSTATE, s, WRITER)) {
                        if (waiter == current)
                            U.compareAndSetReference(this, WAITERTHREAD, current, null);
                        return;
                    }
                }
                else if ((s & WAITER) == 0)
                    U.compareAndSetInt(this, LOCKSTATE, s, s | WAITER);
                else if ((w = waiter) == null)
                    U.compareAndSetReference(this, WAITERTHREAD, null, current);
                else if (w == current)
                    LockSupport.park(this);
            }
        }

        /**
         * Returns matching node or null if none. Tries to search
         * using tree comparisons from root, but continues linear
         * search when lock not available.
         */
        final Node<K,V> find(int h, Object k) {
            if (k != null) {
                for (Node<K,V> e = first; e != null; ) {
                    int s; K ek;
                    if (((s = lockState) & (WAITER|WRITER)) != 0) {
                        if (e.hash == h && ((ek = e.key) == k || (ek != null && k.equals(ek))))
                            return e;
                        e = e.next;
                    }
                    else if (U.compareAndSetInt(this, LOCKSTATE, s, s + READER)) {
                        TreeNode<K,V> r, p;
                        try {
                            p = ((r = root) == null ? null : r.findTreeNode(h, k, null));
                        } finally {
                            Thread w;
                            if (U.getAndAddInt(this, LOCKSTATE, -READER) == (READER|WAITER) && (w = waiter) != null)
                                LockSupport.unpark(w);
                        }
                        return p;
                    }
                }
            }
            return null;
        }

        /**
         * Finds or adds a node.
         * @return null if added
         */
        final TreeNode<K,V> putTreeVal(int h, K k, V v) {
            Class<?> kc = null;
            boolean searched = false;
            for (TreeNode<K,V> p = root;;) {
                int dir, ph; K pk;
                if (p == null) {
                    first = root = new TreeNode<K,V>(h, k, v, null, null);
                    break;
                }
                else if ((ph = p.hash) > h)
                    dir = -1;
                else if (ph < h)
                    dir = 1;
                else if ((pk = p.key) == k || (pk != null && k.equals(pk)))
                    return p;
                else if ((kc == null &&
                        (kc = comparableClassFor(k)) == null) ||
                        (dir = compareComparables(kc, k, pk)) == 0) {
                    if (!searched) {
                        TreeNode<K,V> q, ch;
                        searched = true;
                        if (((ch = p.left) != null &&
                                (q = ch.findTreeNode(h, k, kc)) != null) ||
                                ((ch = p.right) != null &&
                                        (q = ch.findTreeNode(h, k, kc)) != null))
                            return q;
                    }
                    dir = tieBreakOrder(k, pk);
                }

                TreeNode<K,V> xp = p;
                if ((p = (dir <= 0) ? p.left : p.right) == null) {
                    TreeNode<K,V> x, f = first;
                    first = x = new TreeNode<K,V>(h, k, v, f, xp);
                    if (f != null)
                        f.prev = x;
                    if (dir <= 0)
                        xp.left = x;
                    else
                        xp.right = x;
                    if (!xp.red)
                        x.red = true;
                    else {
                        lockRoot();
                        try {
                            root = balanceInsertion(root, x);
                        } finally {
                            unlockRoot();
                        }
                    }
                    break;
                }
            }
            assert checkInvariants(root);
            return null;
        }

        /**
         * Removes the given node, that must be present before this
         * call.  This is messier than typical red-black deletion code
         * because we cannot swap the contents of an interior node
         * with a leaf successor that is pinned by "next" pointers
         * that are accessible independently of lock. So instead we
         * swap the tree linkages.
         *
         * @return true if now too small, so should be untreeified
         */
        final boolean removeTreeNode(TreeNode<K,V> p) {
            TreeNode<K,V> next = (TreeNode<K,V>)p.next;
            TreeNode<K,V> pred = p.prev;  // unlink traversal pointers
            TreeNode<K,V> r, rl;
            if (pred == null)
                first = next;
            else
                pred.next = next;
            if (next != null)
                next.prev = pred;
            if (first == null) {
                root = null;
                return true;
            }
            if ((r = root) == null || r.right == null || // too small
                    (rl = r.left) == null || rl.left == null)
                return true;
            lockRoot();
            try {
                TreeNode<K,V> replacement;
                TreeNode<K,V> pl = p.left;
                TreeNode<K,V> pr = p.right;
                if (pl != null && pr != null) {
                    TreeNode<K,V> s = pr, sl;
                    while ((sl = s.left) != null) // find successor
                        s = sl;
                    boolean c = s.red; s.red = p.red; p.red = c; // swap colors
                    TreeNode<K,V> sr = s.right;
                    TreeNode<K,V> pp = p.parent;
                    if (s == pr) { // p was s's direct parent
                        p.parent = s;
                        s.right = p;
                    }
                    else {
                        TreeNode<K,V> sp = s.parent;
                        if ((p.parent = sp) != null) {
                            if (s == sp.left)
                                sp.left = p;
                            else
                                sp.right = p;
                        }
                        if ((s.right = pr) != null)
                            pr.parent = s;
                    }
                    p.left = null;
                    if ((p.right = sr) != null)
                        sr.parent = p;
                    if ((s.left = pl) != null)
                        pl.parent = s;
                    if ((s.parent = pp) == null)
                        r = s;
                    else if (p == pp.left)
                        pp.left = s;
                    else
                        pp.right = s;
                    if (sr != null)
                        replacement = sr;
                    else
                        replacement = p;
                }
                else if (pl != null)
                    replacement = pl;
                else if (pr != null)
                    replacement = pr;
                else
                    replacement = p;
                if (replacement != p) {
                    TreeNode<K,V> pp = replacement.parent = p.parent;
                    if (pp == null)
                        r = replacement;
                    else if (p == pp.left)
                        pp.left = replacement;
                    else
                        pp.right = replacement;
                    p.left = p.right = p.parent = null;
                }

                root = (p.red) ? r : balanceDeletion(r, replacement);

                if (p == replacement) {  // detach pointers
                    TreeNode<K,V> pp;
                    if ((pp = p.parent) != null) {
                        if (p == pp.left)
                            pp.left = null;
                        else if (p == pp.right)
                            pp.right = null;
                        p.parent = null;
                    }
                }
            } finally {
                unlockRoot();
            }
            assert checkInvariants(root);
            return false;
        }

        /* ------------------------------------------------------------ */
        // Red-black tree methods, all adapted from CLR

        static <K,V> TreeNode<K,V> rotateLeft(TreeNode<K,V> root,
                                              TreeNode<K,V> p) {
            TreeNode<K,V> r, pp, rl;
            if (p != null && (r = p.right) != null) {
                if ((rl = p.right = r.left) != null)
                    rl.parent = p;
                if ((pp = r.parent = p.parent) == null)
                    (root = r).red = false;
                else if (pp.left == p)
                    pp.left = r;
                else
                    pp.right = r;
                r.left = p;
                p.parent = r;
            }
            return root;
        }

        static <K,V> TreeNode<K,V> rotateRight(TreeNode<K,V> root,
                                               TreeNode<K,V> p) {
            TreeNode<K,V> l, pp, lr;
            if (p != null && (l = p.left) != null) {
                if ((lr = p.left = l.right) != null)
                    lr.parent = p;
                if ((pp = l.parent = p.parent) == null)
                    (root = l).red = false;
                else if (pp.right == p)
                    pp.right = l;
                else
                    pp.left = l;
                l.right = p;
                p.parent = l;
            }
            return root;
        }

        static <K,V> TreeNode<K,V> balanceInsertion(TreeNode<K,V> root,
                                                    TreeNode<K,V> x) {
            x.red = true;
            for (TreeNode<K,V> xp, xpp, xppl, xppr;;) {
                if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                }
                else if (!xp.red || (xpp = xp.parent) == null)
                    return root;
                if (xp == (xppl = xpp.left)) {
                    if ((xppr = xpp.right) != null && xppr.red) {
                        xppr.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    }
                    else {
                        if (x == xp.right) {
                            root = rotateLeft(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateRight(root, xpp);
                            }
                        }
                    }
                }
                else {
                    if (xppl != null && xppl.red) {
                        xppl.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    }
                    else {
                        if (x == xp.left) {
                            root = rotateRight(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateLeft(root, xpp);
                            }
                        }
                    }
                }
            }
        }

        static <K,V> TreeNode<K,V> balanceDeletion(TreeNode<K,V> root,
                                                   TreeNode<K,V> x) {
            for (TreeNode<K,V> xp, xpl, xpr;;) {
                if (x == null || x == root)
                    return root;
                else if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                }
                else if (x.red) {
                    x.red = false;
                    return root;
                }
                else if ((xpl = xp.left) == x) {
                    if ((xpr = xp.right) != null && xpr.red) {
                        xpr.red = false;
                        xp.red = true;
                        root = rotateLeft(root, xp);
                        xpr = (xp = x.parent) == null ? null : xp.right;
                    }
                    if (xpr == null)
                        x = xp;
                    else {
                        TreeNode<K,V> sl = xpr.left, sr = xpr.right;
                        if ((sr == null || !sr.red) &&
                                (sl == null || !sl.red)) {
                            xpr.red = true;
                            x = xp;
                        }
                        else {
                            if (sr == null || !sr.red) {
                                if (sl != null)
                                    sl.red = false;
                                xpr.red = true;
                                root = rotateRight(root, xpr);
                                xpr = (xp = x.parent) == null ?
                                        null : xp.right;
                            }
                            if (xpr != null) {
                                xpr.red = (xp == null) ? false : xp.red;
                                if ((sr = xpr.right) != null)
                                    sr.red = false;
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateLeft(root, xp);
                            }
                            x = root;
                        }
                    }
                }
                else { // symmetric
                    if (xpl != null && xpl.red) {
                        xpl.red = false;
                        xp.red = true;
                        root = rotateRight(root, xp);
                        xpl = (xp = x.parent) == null ? null : xp.left;
                    }
                    if (xpl == null)
                        x = xp;
                    else {
                        TreeNode<K,V> sl = xpl.left, sr = xpl.right;
                        if ((sl == null || !sl.red) &&
                                (sr == null || !sr.red)) {
                            xpl.red = true;
                            x = xp;
                        }
                        else {
                            if (sl == null || !sl.red) {
                                if (sr != null)
                                    sr.red = false;
                                xpl.red = true;
                                root = rotateLeft(root, xpl);
                                xpl = (xp = x.parent) == null ?
                                        null : xp.left;
                            }
                            if (xpl != null) {
                                xpl.red = (xp == null) ? false : xp.red;
                                if ((sl = xpl.left) != null)
                                    sl.red = false;
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateRight(root, xp);
                            }
                            x = root;
                        }
                    }
                }
            }
        }

        /**
         * Checks invariants recursively for the tree of Nodes rooted at t.
         */
        static <K,V> boolean checkInvariants(TreeNode<K,V> t) {
            TreeNode<K,V> tp = t.parent, tl = t.left, tr = t.right,
                    tb = t.prev, tn = (TreeNode<K,V>)t.next;
            if (tb != null && tb.next != t)
                return false;
            if (tn != null && tn.prev != t)
                return false;
            if (tp != null && t != tp.left && t != tp.right)
                return false;
            if (tl != null && (tl.parent != t || tl.hash > t.hash))
                return false;
            if (tr != null && (tr.parent != t || tr.hash < t.hash))
                return false;
            if (t.red && tl != null && tl.red && tr != null && tr.red)
                return false;
            if (tl != null && !checkInvariants(tl))
                return false;
            if (tr != null && !checkInvariants(tr))
                return false;
            return true;
        }

        private static final long LOCKSTATE = U.objectFieldOffset(TreeBin.class, "lockState");
        private static final long WAITERTHREAD = U.objectFieldOffset(TreeBin.class, "waiter");
    }

    /* ----------------Table Traversal -------------- */

    /**
     * 记录table的长度以及当前遍历的索引。
     */
    static final class TableStack<K, V> {
        int length;
        int index;
        Node<K, V>[] tab;
        TableStack<K, V> next;
    }

    /**
     * 一个能在扩容过程中不重不漏遍历搜友节点的游标系统，解决的问题有：
     * 1、表会同时在变（table -> nextTable）
     * 2、桶在迁移中。
     * 3、ForwardingNode出现。
     * 4、多层表嵌套。
     * 解决以上问题时仍要保证：
     * 1、不漏数据。
     * 2、不重复数据。
     * 3、不加锁。
     */
    static class Traverser<K, V> {
        /** 当前正在遍历的表 */
        Node<K,V>[] tab;

        /** 当前桶内链表/树的游标 */
        Node<K,V> next;

        TableStack<K,V> stack;

        /** 对象复用，避免频繁new */
        TableStack<K,V> spare;

        /**
         * 以下四个字段组合的作用：
         * 把table拆成：步长为baseSize的多条扫描路径。
         */

        /** 当前正在访问的桶下标 */
        int index;

        /** 当前起始槽位 */
        int baseIndex;

        /** 遍历的终点 */
        int baseLimit;

        /** 初始table的长度 */
        final int baseSize;

        Traverser(Node<K,V>[] tab, int size, int index, int limit) {
            this.tab = tab;
            this.baseSize = size;
            this.baseIndex = this.index = index;
            this.baseLimit = limit;
            this.next = null;   // 注意没有预加载，一上来是null
        }

        /**
         * 返回下一个Node（没有预加载）
         * @return
         */
        final Node<K,V> advance() {
            Node<K, V> e;
            if((e = next) != null)  // 如果不是第一次调用了（上轮已定位到某个桶里），则直接读取下一个Node
                e = e.next;
            while(true){    // 特定的桶走完了
                Node<K, V>[] t;
                int i;
                int n;
                if(e != null)   // 如果有Node则直接返回，说明上一轮拿到了有效的节点
                    return next = e;
                // 边界检查，遍历结束条件
                if(baseIndex >= baseLimit || (t = tab) == null || (n = t.length) <= (i = index) || i < 0)
                    return next = null;
                if((e = tabAt(t, i)) != null && e.hash < 0) {
                    if(e instanceof ForwardingNode) {
                        /**
                         * 切换到新表遍历，但要先保存当前状态，意思是：
                         * 先去新表遍历，完事了再回到旧表遍历
                         */
                        tab = ((ForwardingNode<K, V>)e).nextTable;
                        e = null;
                        pushState(t, i, n); // 保存旧表的状态
                        continue;
                    }else if(e instanceof TreeBin) {
                        e = ((TreeBin<K,V>)e).first;
                    }else {
                        e = null;
                    }
                }
                if(stack != null)  {    // 如果之前进入过新表，现在可能要回来了
                    recoverState(n);
                    /**
                     * 跳跃，如果越界了，换下一条扫描链。
                     * 第一轮：0，16，32...
                     * 第二轮：1，17，33...
                     */
                }else if((index = i + baseSize) >= n) {
                    index = ++baseIndex;
                }
            }
        }

        private void pushState(Node<K,V>[] t, int i, int n) {
            TableStack<K,V> s = spare;  // reuse if possible
            if(s != null)
                spare = s.next;
            else
                s = new TableStack<>();
            s.tab = t;
            s.length = n;
            s.index = i;
            s.next = stack;
            stack = s;
        }

        private void recoverState(int n) {
            TableStack<K,V> s; int len;
            while((s = stack) != null && (index += (len = s.length)) >= n) {
                n = len;
                index = s.index;
                tab = s.tab;
                s.tab = null;
                TableStack<K,V> next = s.next;
                s.next = spare; // save for reuse
                stack = next;
                spare = s;
            }
            if (s == null && (index += baseSize) >= n)
                index = ++baseIndex;
        }
    }

    static class BaseIterator<K,V> extends Traverser<K,V> {
        final ConcurrentHashMap<K,V> map;
        Node<K,V> lastReturned;
        BaseIterator(Node<K,V>[] tab, int size, int index, int limit, ConcurrentHashMap<K,V> map) {
            super(tab, size, index, limit);
            this.map = map;
            advance();
        }

        public final boolean hasNext() {
            return next != null;
        }

        public final boolean hasMoreElements() {
            return next != null;
        }

        public final void remove() {
            Node<K,V> p;
            if ((p = lastReturned) == null)
                throw new IllegalStateException();
            lastReturned = null;
            map.replaceNode(p.key, null, null);
        }
    }

    static final class KeyIterator<K,V> extends BaseIterator<K,V>
            implements Iterator<K>, Enumeration<K> {
        KeyIterator(Node<K,V>[] tab, int size, int index, int limit,
                    ConcurrentHashMap<K,V> map) {
            super(tab, size, index, limit, map);
        }

        public final K next() {
            Node<K,V> p;
            if ((p = next) == null)
                throw new NoSuchElementException();
            K k = p.key;
            lastReturned = p;
            advance();
            return k;
        }

        public final K nextElement() { return next(); }
    }

    static final class ValueIterator<K,V> extends BaseIterator<K,V> implements Iterator<V>, Enumeration<V> {
        ValueIterator(Node<K,V>[] tab, int size, int index, int limit, ConcurrentHashMap<K,V> map) {
            super(tab, size, index, limit, map);
        }

        public final V next() {
            Node<K,V> p;
            if ((p = next) == null)
                throw new NoSuchElementException();
            V v = p.val;
            lastReturned = p;
            advance();
            return v;
        }

        public final V nextElement() { return next(); }
    }

    static final class EntryIterator<K,V> extends BaseIterator<K,V> implements Iterator<Map.Entry<K,V>> {
        EntryIterator(Node<K,V>[] tab, int size, int index, int limit, ConcurrentHashMap<K,V> map) {
            super(tab, size, index, limit, map);
        }

        public final Map.Entry<K,V> next() {
            Node<K,V> p;
            if ((p = next) == null)
                throw new NoSuchElementException();
            K k = p.key;
            V v = p.val;
            lastReturned = p;
            advance();
            return new MapEntry<K,V>(k, v, map);
        }
    }

    /**
     * Exported Entry for EntryIterator.
     */
    static final class MapEntry<K,V> implements Map.Entry<K,V> {
        final K key; // non-null
        V val;       // non-null
        final ConcurrentHashMap<K,V> map;
        MapEntry(K key, V val, ConcurrentHashMap<K,V> map) {
            this.key = key;
            this.val = val;
            this.map = map;
        }
        public K getKey()        { return key; }
        public V getValue()      { return val; }
        public int hashCode()    { return key.hashCode() ^ val.hashCode(); }
        public String toString() {
            return Helpers.mapEntryToString(key, val);
        }

        public boolean equals(Object o) {
            Object k, v; Map.Entry<?,?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?,?>)o).getKey()) != null &&
                    (v = e.getValue()) != null &&
                    (k == key || k.equals(key)) &&
                    (v == val || v.equals(val)));
        }

        /**
         * Sets our entry's value and writes through to the map. The
         * value to return is somewhat arbitrary here. Since we do not
         * necessarily track asynchronous changes, the most recent
         * "previous" value could be different from what we return (or
         * could even have been removed, in which case the put will
         * re-establish). We do not and cannot guarantee more.
         */
        public V setValue(V value) {
            if (value == null) throw new NullPointerException();
            V v = val;
            val = value;
            map.put(key, value);
            return v;
        }
    }

    static final class KeySpliterator<K,V> extends Traverser<K,V>
            implements Spliterator<K> {
        long est;               // size estimate
        KeySpliterator(Node<K,V>[] tab, int size, int index, int limit,
                       long est) {
            super(tab, size, index, limit);
            this.est = est;
        }

        public KeySpliterator<K,V> trySplit() {
            int i, f, h;
            return (h = ((i = baseIndex) + (f = baseLimit)) >>> 1) <= i ? null :
                    new KeySpliterator<K,V>(tab, baseSize, baseLimit = h,
                            f, est >>>= 1);
        }

        public void forEachRemaining(Consumer<? super K> action) {
            if (action == null) throw new NullPointerException();
            for (Node<K,V> p; (p = advance()) != null;)
                action.accept(p.key);
        }

        public boolean tryAdvance(Consumer<? super K> action) {
            if (action == null) throw new NullPointerException();
            Node<K,V> p;
            if ((p = advance()) == null)
                return false;
            action.accept(p.key);
            return true;
        }

        public long estimateSize() { return est; }

        public int characteristics() {
            return Spliterator.DISTINCT | Spliterator.CONCURRENT | Spliterator.NONNULL;
        }
    }

    static final class ValueSpliterator<K,V> extends Traverser<K,V> implements Spliterator<V> {
        long est;               // size estimate
        ValueSpliterator(Node<K,V>[] tab, int size, int index, int limit,
                         long est) {
            super(tab, size, index, limit);
            this.est = est;
        }

        public ValueSpliterator<K,V> trySplit() {
            int i, f, h;
            return (h = ((i = baseIndex) + (f = baseLimit)) >>> 1) <= i ? null :
                    new ValueSpliterator<K,V>(tab, baseSize, baseLimit = h, f, est >>>= 1);
        }

        public void forEachRemaining(Consumer<? super V> action) {
            if (action == null) throw new NullPointerException();
            for (Node<K,V> p; (p = advance()) != null;)
                action.accept(p.val);
        }

        public boolean tryAdvance(Consumer<? super V> action) {
            if (action == null)
                throw new NullPointerException();
            Node<K,V> p;
            if ((p = advance()) == null)
                return false;
            action.accept(p.val);
            return true;
        }

        public long estimateSize() {
            return est;
        }

        public int characteristics() {
            return Spliterator.CONCURRENT | Spliterator.NONNULL;
        }
    }

    static final class EntrySpliterator<K,V> extends Traverser<K,V> implements Spliterator<Map.Entry<K,V>> {
        final ConcurrentHashMap<K,V> map; // To export MapEntry
        long est;               // size estimate
        EntrySpliterator(Node<K,V>[] tab, int size, int index, int limit, long est, ConcurrentHashMap<K,V> map) {
            super(tab, size, index, limit);
            this.map = map;
            this.est = est;
        }

        public EntrySpliterator<K,V> trySplit() {
            int i, f, h;
            return (h = ((i = baseIndex) + (f = baseLimit)) >>> 1) <= i ? null :
                    new EntrySpliterator<>(tab, baseSize, baseLimit = h, f, est >>>= 1, map);
        }

        public void forEachRemaining(Consumer<? super Map.Entry<K,V>> action) {
            if(action == null)
                throw new NullPointerException();
            for(Node<K, V> p; (p = advance()) != null; )
                action.accept(new MapEntry<>(p.key, p.val, map));
        }

        public boolean tryAdvance(Consumer<? super Entry<K,V>> action) {
            if(action == null)
                throw new NullPointerException();
            Node<K, V> p;
            if((p = advance()) == null)
                return false;
            action.accept(new MapEntry<>(p.key, p.val, map));
            return true;
        }

        public long estimateSize() {
            return est;
        }

        public int characteristics() {
            return Spliterator.DISTINCT | Spliterator.CONCURRENT | Spliterator.NONNULL;
        }
    }

    // Parallel bulk operations

    /**
     * 根据用户给定的并行阈值，以及当前的map大小，估算出本次任务的拆分强度batch值。
     */
    final int batchFor(long b) {
        long n;
        if(b == Long.MAX_VALUE || (n = sumCount()) <= 1L || n < b)  // 进入则表示不用并行
            return 0;
        int sp = ForkJoinPool.getCommonPoolParallelism() << 2; // 计算最大拆分强度
        return (b <= 0L || (n /= b) >= sp) ? sp : (int)n;
    }

    public void forEach(long parallelismThreshold, BiConsumer<? super K,? super V> action) {
        if(action == null)
            throw new NullPointerException();
        new ForEachMappingTask<K,V>(null, batchFor(parallelismThreshold), 0, 0, table, action).invoke();
    }

    public <U> void forEach(long parallelismThreshold,
                            BiFunction<? super K, ? super V, ? extends U> transformer,
                            Consumer<? super U> action) {
        if(transformer == null || action == null)
            throw new NullPointerException();
        new ForEachTransformedMappingTask<K,V,U>
                (null, batchFor(parallelismThreshold), 0, 0, table, transformer, action).invoke();
    }

    public <U> U search(long parallelismThreshold,
                        BiFunction<? super K, ? super V, ? extends U> searchFunction) {
        if(searchFunction == null)
            throw new NullPointerException();
        return new SearchMappingsTask<K,V,U>
                (null, batchFor(parallelismThreshold), 0, 0, table, searchFunction, new AtomicReference<U>()).invoke();
    }

    public <U> U reduce(long parallelismThreshold,
                        BiFunction<? super K, ? super V, ? extends U> transformer,
                        BiFunction<? super U, ? super U, ? extends U> reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceMappingsTask<K,V,U>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, reducer).invoke();
    }

    public double reduceToDouble(long parallelismThreshold,
                                 ToDoubleBiFunction<? super K, ? super V> transformer,
                                 double basis,
                                 DoubleBinaryOperator reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceMappingsToDoubleTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, basis, reducer).invoke();
    }

    public long reduceToLong(long parallelismThreshold,
                             ToLongBiFunction<? super K, ? super V> transformer,
                             long basis,
                             LongBinaryOperator reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceMappingsToLongTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, basis, reducer).invoke();
    }

    public int reduceToInt(long parallelismThreshold,
                           ToIntBiFunction<? super K, ? super V> transformer,
                           int basis,
                           IntBinaryOperator reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceMappingsToIntTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, basis, reducer).invoke();
    }

    public void forEachKey(long parallelismThreshold, Consumer<? super K> action) {
        if(action == null)
            throw new NullPointerException();
        /**
         * 参数说明：
         * 1、null、父任务为空，说明这是根任务。
         * 2、batchFor：计算本次任务最多拆多细。
         * 3、两个0：初始index和f。
         * 4、table：当前map的主表。
         * 5、action：用户传入的处理逻辑。
         * ForEachKeyTask是CountedCompleter的子类，所以它本质是一个ForkJoin任务，
         * invoke方法可以简单理解成：把这个根任务提交执行，并等待它整体完成，因此流程是：
         * 1、用户线程调用forEachKey。
         * 2、内部启动一个根ForEachKeyTask。
         * 3、根任务可能继续fork多个子任务。
         * 4、全部完成后invoke才会返回。
         *
         * parallelismThreshold参数表示并行的启动阈值：即当map的预计元素书大于等于这个阈值值，才考虑并行，否则就串行。
         * 注意这个不是什么线程数，也不是batch的大小，是并行拆分的数据量阈值。
         */
        new ForEachKeyTask<>(null, batchFor(parallelismThreshold), 0, 0, table, action).invoke();
    }

    public <U> void forEachKey(long parallelismThreshold,
                               Function<? super K, ? extends U> transformer,
                               Consumer<? super U> action) {
        if(transformer == null || action == null)
            throw new NullPointerException();
        new ForEachTransformedKeyTask<K,V,U>
                (null, batchFor(parallelismThreshold), 0, 0, table, transformer, action).invoke();
    }

    public <U> U searchKeys(long parallelismThreshold,
                            Function<? super K, ? extends U> searchFunction) {
        if(searchFunction == null)
            throw new NullPointerException();
        return new SearchKeysTask<K,V,U>
                (null, batchFor(parallelismThreshold), 0, 0, table, searchFunction, new AtomicReference<U>()).invoke();
    }

    public K reduceKeys(long parallelismThreshold,
                        BiFunction<? super K, ? super K, ? extends K> reducer) {
        if(reducer == null)
            throw new NullPointerException();
        return new ReduceKeysTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, reducer).invoke();
    }

    public <U> U reduceKeys(long parallelismThreshold,
                            Function<? super K, ? extends U> transformer,
                            BiFunction<? super U, ? super U, ? extends U> reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceKeysTask<K,V,U>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, reducer).invoke();
    }

    public double reduceKeysToDouble(long parallelismThreshold,
                                     ToDoubleFunction<? super K> transformer,
                                     double basis,
                                     DoubleBinaryOperator reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceKeysToDoubleTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, basis, reducer).invoke();
    }

    public long reduceKeysToLong(long parallelismThreshold,
                                 ToLongFunction<? super K> transformer,
                                 long basis,
                                 LongBinaryOperator reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceKeysToLongTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, basis, reducer).invoke();
    }

    public int reduceKeysToInt(long parallelismThreshold,
                               ToIntFunction<? super K> transformer,
                               int basis,
                               IntBinaryOperator reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceKeysToIntTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, basis, reducer).invoke();
    }

    public void forEachValue(long parallelismThreshold, Consumer<? super V> action) {
        if(action == null)
            throw new NullPointerException();
        new ForEachValueTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, action).invoke();
    }

    public <U> void forEachValue(long parallelismThreshold,
                                 Function<? super V, ? extends U> transformer,
                                 Consumer<? super U> action) {
        if(transformer == null || action == null)
            throw new NullPointerException();
        new ForEachTransformedValueTask<K,V,U>
                (null, batchFor(parallelismThreshold), 0, 0, table, transformer, action).invoke();
    }

    public <U> U searchValues(long parallelismThreshold,
                              Function<? super V, ? extends U> searchFunction) {
        if(searchFunction == null)
            throw new NullPointerException();
        return new SearchValuesTask<K,V,U>
                (null, batchFor(parallelismThreshold), 0, 0, table, searchFunction, new AtomicReference<U>()).invoke();
    }

    public V reduceValues(long parallelismThreshold,
                          BiFunction<? super V, ? super V, ? extends V> reducer) {
        if(reducer == null)
            throw new NullPointerException();
        return new ReduceValuesTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, reducer).invoke();
    }

    public <U> U reduceValues(long parallelismThreshold,
                              Function<? super V, ? extends U> transformer,
                              BiFunction<? super U, ? super U, ? extends U> reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceValuesTask<K,V,U>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, reducer).invoke();
    }

    public double reduceValuesToDouble(long parallelismThreshold,
                                       ToDoubleFunction<? super V> transformer,
                                       double basis,
                                       DoubleBinaryOperator reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceValuesToDoubleTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, basis, reducer).invoke();
    }

    public long reduceValuesToLong(long parallelismThreshold,
                                   ToLongFunction<? super V> transformer,
                                   long basis,
                                   LongBinaryOperator reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceValuesToLongTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, basis, reducer).invoke();
    }

    public int reduceValuesToInt(long parallelismThreshold,
                                 ToIntFunction<? super V> transformer,
                                 int basis,
                                 IntBinaryOperator reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceValuesToIntTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, basis, reducer).invoke();
    }

    public void forEachEntry(long parallelismThreshold,
                             Consumer<? super Map.Entry<K,V>> action) {
        if(action == null) throw new NullPointerException();
        new ForEachEntryTask<K,V>(null, batchFor(parallelismThreshold), 0, 0, table, action).invoke();
    }

    public <U> void forEachEntry(long parallelismThreshold,
                                 Function<Map.Entry<K,V>, ? extends U> transformer,
                                 Consumer<? super U> action) {
        if(transformer == null || action == null)
            throw new NullPointerException();
        new ForEachTransformedEntryTask<K,V,U>
                (null, batchFor(parallelismThreshold), 0, 0, table, transformer, action).invoke();
    }

    public <U> U searchEntries(long parallelismThreshold,
                               Function<Map.Entry<K,V>, ? extends U> searchFunction) {
        if(searchFunction == null)
            throw new NullPointerException();
        return new SearchEntriesTask<K,V,U>
                (null, batchFor(parallelismThreshold), 0, 0, table, searchFunction, new AtomicReference<U>()).invoke();
    }

    public Map.Entry<K,V> reduceEntries(long parallelismThreshold,
                                        BiFunction<Map.Entry<K,V>, Map.Entry<K,V>, ? extends Map.Entry<K,V>> reducer) {
        if(reducer == null)
            throw new NullPointerException();
        return new ReduceEntriesTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, reducer).invoke();
    }

    public <U> U reduceEntries(long parallelismThreshold,
                               Function<Map.Entry<K,V>, ? extends U> transformer,
                               BiFunction<? super U, ? super U, ? extends U> reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceEntriesTask<K,V,U>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, reducer).invoke();
    }

    public double reduceEntriesToDouble(long parallelismThreshold,
                                        ToDoubleFunction<Map.Entry<K,V>> transformer,
                                        double basis,
                                        DoubleBinaryOperator reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceEntriesToDoubleTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, basis, reducer).invoke();
    }

    public long reduceEntriesToLong(long parallelismThreshold,
                                    ToLongFunction<Map.Entry<K,V>> transformer,
                                    long basis,
                                    LongBinaryOperator reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceEntriesToLongTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, basis, reducer).invoke();
    }

    public int reduceEntriesToInt(long parallelismThreshold,
                                  ToIntFunction<Map.Entry<K,V>> transformer,
                                  int basis,
                                  IntBinaryOperator reducer) {
        if(transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceEntriesToIntTask<K,V>
                (null, batchFor(parallelismThreshold), 0, 0, table, null, transformer, basis, reducer).invoke();
    }

    /* ----------------Views -------------- */

    abstract static class CollectionView<K,V,E>
            implements Collection<E>, java.io.Serializable {
        private static final long serialVersionUID = 7249069246763182397L;
        final ConcurrentHashMap<K,V> map;
        CollectionView(ConcurrentHashMap<K,V> map)  { this.map = map; }

        /**
         * Returns the map backing this view.
         *
         * @return the map backing this view
         */
        public ConcurrentHashMap<K,V> getMap() { return map; }

        /**
         * Removes all of the elements from this view, by removing all
         * the mappings from the map backing this view.
         */
        public final void clear()      { map.clear(); }
        public final int size()        { return map.size(); }
        public final boolean isEmpty() { return map.isEmpty(); }

        // implementations below rely on concrete classes supplying these
        // abstract methods
        /**
         * Returns an iterator over the elements in this collection.
         *
         * <p>The returned iterator is
         * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
         *
         * @return an iterator over the elements in this collection
         */
        public abstract Iterator<E> iterator();
        public abstract boolean contains(Object o);
        public abstract boolean remove(Object o);

        private static final String OOME_MSG = "Required array size too large";

        public final Object[] toArray() {
            long sz = map.mappingCount();
            if (sz > MAX_ARRAY_SIZE)
                throw new OutOfMemoryError(OOME_MSG);
            int n = (int)sz;
            Object[] r = new Object[n];
            int i = 0;
            for (E e : this) {
                if (i == n) {
                    if (n >= MAX_ARRAY_SIZE)
                        throw new OutOfMemoryError(OOME_MSG);
                    if (n >= MAX_ARRAY_SIZE - (MAX_ARRAY_SIZE >>> 1) - 1)
                        n = MAX_ARRAY_SIZE;
                    else
                        n += (n >>> 1) + 1;
                    r = Arrays.copyOf(r, n);
                }
                r[i++] = e;
            }
            return (i == n) ? r : Arrays.copyOf(r, i);
        }

        @SuppressWarnings("unchecked")
        public final <T> T[] toArray(T[] a) {
            long sz = map.mappingCount();
            if (sz > MAX_ARRAY_SIZE)
                throw new OutOfMemoryError(OOME_MSG);
            int m = (int)sz;
            T[] r = (a.length >= m) ? a :
                    (T[])java.lang.reflect.Array
                            .newInstance(a.getClass().getComponentType(), m);
            int n = r.length;
            int i = 0;
            for (E e : this) {
                if (i == n) {
                    if (n >= MAX_ARRAY_SIZE)
                        throw new OutOfMemoryError(OOME_MSG);
                    if (n >= MAX_ARRAY_SIZE - (MAX_ARRAY_SIZE >>> 1) - 1)
                        n = MAX_ARRAY_SIZE;
                    else
                        n += (n >>> 1) + 1;
                    r = Arrays.copyOf(r, n);
                }
                r[i++] = (T)e;
            }
            if (a == r && i < n) {
                r[i] = null; // null-terminate
                return r;
            }
            return (i == n) ? r : Arrays.copyOf(r, i);
        }

        /**
         * Returns a string representation of this collection.
         * The string representation consists of the string representations
         * of the collection's elements in the order they are returned by
         * its iterator, enclosed in square brackets ({@code "[]"}).
         * Adjacent elements are separated by the characters {@code ", "}
         * (comma and space).  Elements are converted to strings as by
         * {@link String#valueOf(Object)}.
         *
         * @return a string representation of this collection
         */
        public final String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            Iterator<E> it = iterator();
            if (it.hasNext()) {
                for (;;) {
                    Object e = it.next();
                    sb.append(e == this ? "(this Collection)" : e);
                    if (!it.hasNext())
                        break;
                    sb.append(',').append(' ');
                }
            }
            return sb.append(']').toString();
        }

        public final boolean containsAll(Collection<?> c) {
            if (c != this) {
                for (Object e : c) {
                    if (e == null || !contains(e))
                        return false;
                }
            }
            return true;
        }

        public boolean removeAll(Collection<?> c) {
            if (c == null) throw new NullPointerException();
            boolean modified = false;
            // Use (c instanceof Set) as a hint that lookup in c is as
            // efficient as this view
            Node<K,V>[] t;
            if ((t = map.table) == null) {
                return false;
            } else if (c instanceof Set<?> && c.size() > t.length) {
                for (Iterator<?> it = iterator(); it.hasNext(); ) {
                    if (c.contains(it.next())) {
                        it.remove();
                        modified = true;
                    }
                }
            } else {
                for (Object e : c)
                    modified |= remove(e);
            }
            return modified;
        }

        public final boolean retainAll(Collection<?> c) {
            if (c == null) throw new NullPointerException();
            boolean modified = false;
            for (Iterator<E> it = iterator(); it.hasNext();) {
                if (!c.contains(it.next())) {
                    it.remove();
                    modified = true;
                }
            }
            return modified;
        }

    }

    public static class KeySetView<K,V> extends CollectionView<K,V,K>
            implements Set<K>, java.io.Serializable {
        private static final long serialVersionUID = 7249069246763182397L;
        @SuppressWarnings("serial") // Conditionally serializable
        private final V value;
        KeySetView(ConcurrentHashMap<K,V> map, V value) {  // non-public
            super(map);
            this.value = value;
        }

        /**
         * Returns the default mapped value for additions,
         * or {@code null} if additions are not supported.
         *
         * @return the default mapped value for additions, or {@code null}
         * if not supported
         */
        public V getMappedValue() { return value; }

        /**
         * {@inheritDoc}
         * @throws NullPointerException if the specified key is null
         */
        public boolean contains(Object o) { return map.containsKey(o); }

        /**
         * Removes the key from this map view, by removing the key (and its
         * corresponding value) from the backing map.  This method does
         * nothing if the key is not in the map.
         *
         * @param  o the key to be removed from the backing map
         * @return {@code true} if the backing map contained the specified key
         * @throws NullPointerException if the specified key is null
         */
        public boolean remove(Object o) { return map.remove(o) != null; }

        /**
         * @return an iterator over the keys of the backing map
         */
        public Iterator<K> iterator() {
            Node<K,V>[] t;
            ConcurrentHashMap<K,V> m = map;
            int f = (t = m.table) == null ? 0 : t.length;
            return new KeyIterator<K,V>(t, f, 0, f, m);
        }

        /**
         * Adds the specified key to this set view by mapping the key to
         * the default mapped value in the backing map, if defined.
         *
         * @param e key to be added
         * @return {@code true} if this set changed as a result of the call
         * @throws NullPointerException if the specified key is null
         * @throws UnsupportedOperationException if no default mapped value
         * for additions was provided
         */
        public boolean add(K e) {
            V v;
            if ((v = value) == null)
                throw new UnsupportedOperationException();
            return map.putVal(e, v, true) == null;
        }

        /**
         * Adds all of the elements in the specified collection to this set,
         * as if by calling {@link #add} on each one.
         *
         * @param c the elements to be inserted into this set
         * @return {@code true} if this set changed as a result of the call
         * @throws NullPointerException if the collection or any of its
         * elements are {@code null}
         * @throws UnsupportedOperationException if no default mapped value
         * for additions was provided
         */
        public boolean addAll(Collection<? extends K> c) {
            boolean added = false;
            V v;
            if ((v = value) == null)
                throw new UnsupportedOperationException();
            for (K e : c) {
                if (map.putVal(e, v, true) == null)
                    added = true;
            }
            return added;
        }

        public int hashCode() {
            int h = 0;
            for (K e : this)
                h += e.hashCode();
            return h;
        }

        public boolean equals(Object o) {
            Set<?> c;
            return ((o instanceof Set) &&
                    ((c = (Set<?>)o) == this ||
                            (containsAll(c) && c.containsAll(this))));
        }

        public Spliterator<K> spliterator() {
            Node<K,V>[] t;
            ConcurrentHashMap<K,V> m = map;
            long n = m.sumCount();
            int f = (t = m.table) == null ? 0 : t.length;
            return new KeySpliterator<K,V>(t, f, 0, f, n < 0L ? 0L : n);
        }

        public void forEach(Consumer<? super K> action) {
            if (action == null) throw new NullPointerException();
            Node<K,V>[] t;
            if ((t = map.table) != null) {
                Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
                for (Node<K,V> p; (p = it.advance()) != null; )
                    action.accept(p.key);
            }
        }
    }

    static final class ValuesView<K,V> extends CollectionView<K,V,V>
            implements Collection<V>, java.io.Serializable {
        private static final long serialVersionUID = 2249069246763182397L;
        ValuesView(ConcurrentHashMap<K,V> map) { super(map); }
        public final boolean contains(Object o) {
            return map.containsValue(o);
        }

        public final boolean remove(Object o) {
            if (o != null) {
                for (Iterator<V> it = iterator(); it.hasNext();) {
                    if (o.equals(it.next())) {
                        it.remove();
                        return true;
                    }
                }
            }
            return false;
        }

        public final Iterator<V> iterator() {
            ConcurrentHashMap<K,V> m = map;
            Node<K,V>[] t;
            int f = (t = m.table) == null ? 0 : t.length;
            return new ValueIterator<K,V>(t, f, 0, f, m);
        }

        public final boolean add(V e) {
            throw new UnsupportedOperationException();
        }
        public final boolean addAll(Collection<? extends V> c) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean removeAll(Collection<?> c) {
            if (c == null) throw new NullPointerException();
            boolean modified = false;
            for (Iterator<V> it = iterator(); it.hasNext();) {
                if (c.contains(it.next())) {
                    it.remove();
                    modified = true;
                }
            }
            return modified;
        }

        public boolean removeIf(Predicate<? super V> filter) {
            return map.removeValueIf(filter);
        }

        public Spliterator<V> spliterator() {
            Node<K,V>[] t;
            ConcurrentHashMap<K,V> m = map;
            long n = m.sumCount();
            int f = (t = m.table) == null ? 0 : t.length;
            return new ValueSpliterator<K,V>(t, f, 0, f, n < 0L ? 0L : n);
        }

        public void forEach(Consumer<? super V> action) {
            if (action == null) throw new NullPointerException();
            Node<K,V>[] t;
            if ((t = map.table) != null) {
                Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
                for (Node<K,V> p; (p = it.advance()) != null; )
                    action.accept(p.val);
            }
        }
    }

    static final class EntrySetView<K,V> extends CollectionView<K,V,Map.Entry<K,V>>
            implements Set<Map.Entry<K,V>>, java.io.Serializable {
        private static final long serialVersionUID = 2249069246763182397L;
        EntrySetView(ConcurrentHashMap<K,V> map) { super(map); }

        public boolean contains(Object o) {
            Object k, v, r; Map.Entry<?,?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?,?>)o).getKey()) != null &&
                    (r = map.get(k)) != null &&
                    (v = e.getValue()) != null &&
                    (v == r || v.equals(r)));
        }

        public boolean remove(Object o) {
            Object k, v; Map.Entry<?,?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?,?>)o).getKey()) != null &&
                    (v = e.getValue()) != null &&
                    map.remove(k, v));
        }

        /**
         * @return an iterator over the entries of the backing map
         */
        public Iterator<Map.Entry<K,V>> iterator() {
            ConcurrentHashMap<K,V> m = map;
            Node<K,V>[] t;
            int f = (t = m.table) == null ? 0 : t.length;
            return new EntryIterator<K,V>(t, f, 0, f, m);
        }

        public boolean add(Entry<K,V> e) {
            return map.putVal(e.getKey(), e.getValue(), false) == null;
        }

        public boolean addAll(Collection<? extends Entry<K,V>> c) {
            boolean added = false;
            for (Entry<K,V> e : c) {
                if (add(e))
                    added = true;
            }
            return added;
        }

        public boolean removeIf(Predicate<? super Entry<K,V>> filter) {
            return map.removeEntryIf(filter);
        }

        public final int hashCode() {
            int h = 0;
            Node<K,V>[] t;
            if ((t = map.table) != null) {
                Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
                for (Node<K,V> p; (p = it.advance()) != null; ) {
                    h += p.hashCode();
                }
            }
            return h;
        }

        public final boolean equals(Object o) {
            Set<?> c;
            return ((o instanceof Set) &&
                    ((c = (Set<?>)o) == this ||
                            (containsAll(c) && c.containsAll(this))));
        }

        public Spliterator<Map.Entry<K,V>> spliterator() {
            Node<K,V>[] t;
            ConcurrentHashMap<K,V> m = map;
            long n = m.sumCount();
            int f = (t = m.table) == null ? 0 : t.length;
            return new EntrySpliterator<K,V>(t, f, 0, f, n < 0L ? 0L : n, m);
        }

        public void forEach(Consumer<? super Map.Entry<K,V>> action) {
            if (action == null) throw new NullPointerException();
            Node<K,V>[] t;
            if ((t = map.table) != null) {
                Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
                for (Node<K,V> p; (p = it.advance()) != null; )
                    action.accept(new MapEntry<K,V>(p.key, p.val, map));
            }
        }
    }

    // -------------------------------------------------------

    /**
     * CHM为了批量遍历、并行函数式操作而准备的一个ForkJoin任务基类，下面有它的一大堆子类，其实干的都是同一类事情：
     * 把整个CHM的遍历范围拆成多个小任务，交给ForkJoin/CountedCompleter并行执行。
     *
     * 可以理解成：可分裂的并行遍历器，具备两种能力：
     * 1、像上面Traverser一样遍历CHM：能跨table遍历、能处理ForwardingNode、能在扩容期间不重不漏地继续走。
     * 2、像ForkJoin任务一样拆分任务：把一个大遍历区间拆成左右两个子任务，子任务再继续拆，最后通过CountedCompleter协调完成。
     * 所以是：Traverser + ForkJoin任务框架的结合体。
     *
     * 最后是官方的注释翻译：
     * 本来遍历逻辑Traverser已经有了，但现在要让它变成ForkJoin里的任务对象，而Java不支持多继承，
     * 所以只能把Traverser的字段和代码再抄一份到BulkTask里。
     *
     * 子类一览：
     * 一、ForEach系列：
     * 1、ForEachKeyTask
     * 2、ForEachValueTask
     * 3、ForEachEntryTask
     * 4、ForEachMappingTask
     * 作用：遍历元素，然后对每个元素执行action。
     *
     * 二、Search系列
     * 1、SearchKeysTask
     * 2、SearchValuesTask
     * 3、SearchEntriesTask
     * 4、SearchMappingsTask
     * 作用：遍历元素，寻找第一个满足条件的结果。
     *
     * 三、Reduce系列
     * 1、ReduceKeysTask
     * 2、ReduceValuesTask
     * 3、ReduceEntriesTask
     * 4、MapReduce...Task
     * 作用：遍历 + 映射 + 聚合
     */
    @SuppressWarnings("serial")
    abstract static class BulkTask<K,V,R> extends CountedCompleter<R> {
        Node<K,V>[] tab;        // same as Traverser
        Node<K,V> next;
        TableStack<K,V> stack, spare;
        int index;
        int baseIndex;
        int baseLimit;
        final int baseSize;

        /**
         * 新增的重要字段：当前任务还能再拆多细，还准备拆多少次的控制参数。
         * 这不是元素个数，也不是批量大小这种直观含义，更像一个任务拆分深度/粒度控制器。
         */
        int batch;

        BulkTask(BulkTask<K,V,?> par, int b, int i, int f, Node<K,V>[] t) {
            super(par);
            this.batch = b;
            this.index = this.baseIndex = i;
            if ((this.tab = t) == null)
                this.baseSize = this.baseLimit = 0;
            else if (par == null)
                this.baseSize = this.baseLimit = t.length;
            else {
                this.baseLimit = f;
                this.baseSize = par.baseSize;
            }
        }

        /**
         * Same as Traverser version.
         */
        final Node<K,V> advance() {
            Node<K,V> e;
            if ((e = next) != null)
                e = e.next;
            for (;;) {
                Node<K,V>[] t; int i, n;
                if (e != null)
                    return next = e;
                if (baseIndex >= baseLimit || (t = tab) == null ||
                        (n = t.length) <= (i = index) || i < 0)
                    return next = null;
                if ((e = tabAt(t, i)) != null && e.hash < 0) {
                    if (e instanceof ForwardingNode) {
                        tab = ((ForwardingNode<K,V>)e).nextTable;
                        e = null;
                        pushState(t, i, n);
                        continue;
                    }
                    else if (e instanceof TreeBin)
                        e = ((TreeBin<K,V>)e).first;
                    else
                        e = null;
                }
                if (stack != null)
                    recoverState(n);
                else if ((index = i + baseSize) >= n)
                    index = ++baseIndex;
            }
        }

        private void pushState(Node<K,V>[] t, int i, int n) {
            TableStack<K,V> s = spare;
            if (s != null)
                spare = s.next;
            else
                s = new TableStack<K,V>();
            s.tab = t;
            s.length = n;
            s.index = i;
            s.next = stack;
            stack = s;
        }

        private void recoverState(int n) {
            TableStack<K,V> s; int len;
            while ((s = stack) != null && (index += (len = s.length)) >= n) {
                n = len;
                index = s.index;
                tab = s.tab;
                s.tab = null;
                TableStack<K,V> next = s.next;
                s.next = spare; // save for reuse
                stack = next;
                spare = s;
            }
            if (s == null && (index += baseSize) >= n)
                index = ++baseIndex;
        }
    }

    /*
     * Task classes. Coded in a regular but ugly format/style to
     * simplify checks that each variant differs in the right way from
     * others. The null screenings exist because compilers cannot tell
     * that we've already null-checked task arguments, so we force
     * simplest hoisted bypass to help avoid convoluted traps.
     */
    @SuppressWarnings("serial")
    static final class ForEachKeyTask<K,V> extends BulkTask<K,V,Void> {
        final Consumer<? super K> action;
        ForEachKeyTask(BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t, Consumer<? super K> action) {
            super(p, b, i, f, t);
            this.action = action;
        }
        public final void compute() {
            final Consumer<? super K> action;
            if((action = this.action) != null) {
                /**
                 * 阶段一：任务拆分循环
                 * batch > 0说明当前任务还允许继续拆，如果batch == 0就不拆了，直接自己干活
                 * (h = ((f = baseLimit) + i) >>> 1) > i这里就是在算中点：
                 * 1、当前任务本来负责区间[baseIndex, baseLimit]
                 * 2、h是中点。
                 * 如果h > i，说明区间至少还能分成两半，否则说明区间太小，不值得再拆。
                 */
                for(int i = baseIndex, f, h; batch > 0 && (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    /**
                     * 准备fork出一个子任务，所以我的待完成子任务数加1。
                     * 父任务不会立刻算自己完成，必须要等子任务也完成。
                     */
                    addToPendingCount(1);
                    /**
                     * 创建并fork一个右半区任务。
                     * 中点是h，原来上界是f，fork出去的子任务处理是[h, f)，当前任务自己留下的是[baseIndex, h]
                     */
                    new ForEachKeyTask<>(this, batch >>>= 1, baseLimit = h, f, tab, action).fork();
                }
                /**
                 * 阶段二：真正遍历并执行action。
                 * 1、调用advance方法，从当前任务负责的范围里拿下一个Node。
                 * 2、拿到Node后，调用用户的Consumer执行逻辑
                 */
                for(Node<K,V> p; (p = advance()) != null;)
                    action.accept(p.key);
                /**
                 * 阶段三：CountedCompleter的收尾，可以理解成：
                 * 我这边干完了，会通知父任务看看它是不是也可以完成了，即子任务完成，需要逐层把完成信号往上传，
                 * 最终根任务的pendingCount清零，整个invoke方法才会返回。
                 */
                propagateCompletion();
            }
        }
    }

    static final class ForEachValueTask<K,V> extends BulkTask<K,V,Void> {
        final Consumer<? super V> action;
        ForEachValueTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 Consumer<? super V> action) {
            super(p, b, i, f, t);
            this.action = action;
        }
        public final void compute() {
            final Consumer<? super V> action;
            if ((action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachValueTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null;)
                    action.accept(p.val);
                propagateCompletion();
            }
        }
    }

    static final class ForEachEntryTask<K,V> extends BulkTask<K,V,Void> {
        final Consumer<? super Entry<K,V>> action;
        ForEachEntryTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 Consumer<? super Entry<K,V>> action) {
            super(p, b, i, f, t);
            this.action = action;
        }
        public final void compute() {
            final Consumer<? super Entry<K,V>> action;
            if ((action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachEntryTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    action.accept(p);
                propagateCompletion();
            }
        }
    }

    static final class ForEachMappingTask<K,V> extends BulkTask<K,V,Void> {
        final BiConsumer<? super K, ? super V> action;
        ForEachMappingTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 BiConsumer<? super K,? super V> action) {
            super(p, b, i, f, t);
            this.action = action;
        }
        public final void compute() {
            final BiConsumer<? super K, ? super V> action;
            if ((action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachMappingTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    action.accept(p.key, p.val);
                propagateCompletion();
            }
        }
    }

    static final class ForEachTransformedKeyTask<K,V,U> extends BulkTask<K,V,Void> {
        final Function<? super K, ? extends U> transformer;
        final Consumer<? super U> action;
        ForEachTransformedKeyTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 Function<? super K, ? extends U> transformer, Consumer<? super U> action) {
            super(p, b, i, f, t);
            this.transformer = transformer; this.action = action;
        }
        public final void compute() {
            final Function<? super K, ? extends U> transformer;
            final Consumer<? super U> action;
            if ((transformer = this.transformer) != null &&
                    (action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachTransformedKeyTask<K,V,U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    transformer, action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.key)) != null)
                        action.accept(u);
                }
                propagateCompletion();
            }
        }
    }

    static final class ForEachTransformedValueTask<K,V,U> extends BulkTask<K,V,Void> {
        final Function<? super V, ? extends U> transformer;
        final Consumer<? super U> action;
        ForEachTransformedValueTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 Function<? super V, ? extends U> transformer, Consumer<? super U> action) {
            super(p, b, i, f, t);
            this.transformer = transformer; this.action = action;
        }
        public final void compute() {
            final Function<? super V, ? extends U> transformer;
            final Consumer<? super U> action;
            if ((transformer = this.transformer) != null &&
                    (action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachTransformedValueTask<K,V,U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    transformer, action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.val)) != null)
                        action.accept(u);
                }
                propagateCompletion();
            }
        }
    }

    static final class ForEachTransformedEntryTask<K,V,U> extends BulkTask<K,V,Void> {
        final Function<Map.Entry<K,V>, ? extends U> transformer;
        final Consumer<? super U> action;
        ForEachTransformedEntryTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 Function<Map.Entry<K,V>, ? extends U> transformer, Consumer<? super U> action) {
            super(p, b, i, f, t);
            this.transformer = transformer; this.action = action;
        }
        public final void compute() {
            final Function<Map.Entry<K,V>, ? extends U> transformer;
            final Consumer<? super U> action;
            if ((transformer = this.transformer) != null &&
                    (action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachTransformedEntryTask<K,V,U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    transformer, action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p)) != null)
                        action.accept(u);
                }
                propagateCompletion();
            }
        }
    }

    static final class ForEachTransformedMappingTask<K,V,U> extends BulkTask<K,V,Void> {
        final BiFunction<? super K, ? super V, ? extends U> transformer;
        final Consumer<? super U> action;
        ForEachTransformedMappingTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 BiFunction<? super K, ? super V, ? extends U> transformer,
                 Consumer<? super U> action) {
            super(p, b, i, f, t);
            this.transformer = transformer; this.action = action;
        }
        public final void compute() {
            final BiFunction<? super K, ? super V, ? extends U> transformer;
            final Consumer<? super U> action;
            if ((transformer = this.transformer) != null &&
                    (action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachTransformedMappingTask<K,V,U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    transformer, action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.key, p.val)) != null)
                        action.accept(u);
                }
                propagateCompletion();
            }
        }
    }

    static final class SearchKeysTask<K,V,U> extends BulkTask<K,V,U> {
        final Function<? super K, ? extends U> searchFunction;
        final AtomicReference<U> result;
        SearchKeysTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 Function<? super K, ? extends U> searchFunction,
                 AtomicReference<U> result) {
            super(p, b, i, f, t);
            this.searchFunction = searchFunction; this.result = result;
        }
        public final U getRawResult() { return result.get(); }
        public final void compute() {
            final Function<? super K, ? extends U> searchFunction;
            final AtomicReference<U> result;
            if ((searchFunction = this.searchFunction) != null &&
                    (result = this.result) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    if (result.get() != null)
                        return;
                    addToPendingCount(1);
                    new SearchKeysTask<K,V,U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    searchFunction, result).fork();
                }
                while (result.get() == null) {
                    U u;
                    Node<K,V> p;
                    if ((p = advance()) == null) {
                        propagateCompletion();
                        break;
                    }
                    if ((u = searchFunction.apply(p.key)) != null) {
                        if (result.compareAndSet(null, u))
                            quietlyCompleteRoot();
                        break;
                    }
                }
            }
        }
    }

    static final class SearchValuesTask<K,V,U> extends BulkTask<K,V,U> {
        final Function<? super V, ? extends U> searchFunction;
        final AtomicReference<U> result;
        SearchValuesTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 Function<? super V, ? extends U> searchFunction,
                 AtomicReference<U> result) {
            super(p, b, i, f, t);
            this.searchFunction = searchFunction; this.result = result;
        }
        public final U getRawResult() { return result.get(); }
        public final void compute() {
            final Function<? super V, ? extends U> searchFunction;
            final AtomicReference<U> result;
            if ((searchFunction = this.searchFunction) != null &&
                    (result = this.result) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    if (result.get() != null)
                        return;
                    addToPendingCount(1);
                    new SearchValuesTask<K,V,U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    searchFunction, result).fork();
                }
                while (result.get() == null) {
                    U u;
                    Node<K,V> p;
                    if ((p = advance()) == null) {
                        propagateCompletion();
                        break;
                    }
                    if ((u = searchFunction.apply(p.val)) != null) {
                        if (result.compareAndSet(null, u))
                            quietlyCompleteRoot();
                        break;
                    }
                }
            }
        }
    }

    static final class SearchEntriesTask<K,V,U> extends BulkTask<K,V,U> {
        final Function<Entry<K,V>, ? extends U> searchFunction;
        final AtomicReference<U> result;
        SearchEntriesTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 Function<Entry<K,V>, ? extends U> searchFunction,
                 AtomicReference<U> result) {
            super(p, b, i, f, t);
            this.searchFunction = searchFunction; this.result = result;
        }
        public final U getRawResult() { return result.get(); }
        public final void compute() {
            final Function<Entry<K,V>, ? extends U> searchFunction;
            final AtomicReference<U> result;
            if ((searchFunction = this.searchFunction) != null &&
                    (result = this.result) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    if (result.get() != null)
                        return;
                    addToPendingCount(1);
                    new SearchEntriesTask<K,V,U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    searchFunction, result).fork();
                }
                while (result.get() == null) {
                    U u;
                    Node<K,V> p;
                    if ((p = advance()) == null) {
                        propagateCompletion();
                        break;
                    }
                    if ((u = searchFunction.apply(p)) != null) {
                        if (result.compareAndSet(null, u))
                            quietlyCompleteRoot();
                        return;
                    }
                }
            }
        }
    }

    static final class SearchMappingsTask<K,V,U> extends BulkTask<K,V,U> {
        final BiFunction<? super K, ? super V, ? extends U> searchFunction;
        final AtomicReference<U> result;
        SearchMappingsTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 BiFunction<? super K, ? super V, ? extends U> searchFunction,
                 AtomicReference<U> result) {
            super(p, b, i, f, t);
            this.searchFunction = searchFunction; this.result = result;
        }
        public final U getRawResult() { return result.get(); }
        public final void compute() {
            final BiFunction<? super K, ? super V, ? extends U> searchFunction;
            final AtomicReference<U> result;
            if ((searchFunction = this.searchFunction) != null &&
                    (result = this.result) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    if (result.get() != null)
                        return;
                    addToPendingCount(1);
                    new SearchMappingsTask<K,V,U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    searchFunction, result).fork();
                }
                while (result.get() == null) {
                    U u;
                    Node<K,V> p;
                    if ((p = advance()) == null) {
                        propagateCompletion();
                        break;
                    }
                    if ((u = searchFunction.apply(p.key, p.val)) != null) {
                        if (result.compareAndSet(null, u))
                            quietlyCompleteRoot();
                        break;
                    }
                }
            }
        }
    }

    static final class ReduceKeysTask<K,V> extends BulkTask<K,V,K> {
        final BiFunction<? super K, ? super K, ? extends K> reducer;
        K result;
        ReduceKeysTask<K,V> rights, nextRight;
        ReduceKeysTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 ReduceKeysTask<K,V> nextRight,
                 BiFunction<? super K, ? super K, ? extends K> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.reducer = reducer;
        }
        public final K getRawResult() { return result; }
        public final void compute() {
            final BiFunction<? super K, ? super K, ? extends K> reducer;
            if ((reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new ReduceKeysTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, reducer)).fork();
                }
                K r = null;
                for (Node<K,V> p; (p = advance()) != null; ) {
                    K u = p.key;
                    r = (r == null) ? u : u == null ? r : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    ReduceKeysTask<K,V>
                            t = (ReduceKeysTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        K tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class ReduceValuesTask<K,V> extends BulkTask<K,V,V> {
        final BiFunction<? super V, ? super V, ? extends V> reducer;
        V result;
        ReduceValuesTask<K,V> rights, nextRight;
        ReduceValuesTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 ReduceValuesTask<K,V> nextRight,
                 BiFunction<? super V, ? super V, ? extends V> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.reducer = reducer;
        }
        public final V getRawResult() { return result; }
        public final void compute() {
            final BiFunction<? super V, ? super V, ? extends V> reducer;
            if ((reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new ReduceValuesTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, reducer)).fork();
                }
                V r = null;
                for (Node<K,V> p; (p = advance()) != null; ) {
                    V v = p.val;
                    r = (r == null) ? v : reducer.apply(r, v);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    ReduceValuesTask<K,V>
                            t = (ReduceValuesTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        V tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class ReduceEntriesTask<K,V> extends BulkTask<K,V,Map.Entry<K,V>> {
        final BiFunction<Map.Entry<K,V>, Map.Entry<K,V>, ? extends Map.Entry<K,V>> reducer;
        Map.Entry<K,V> result;
        ReduceEntriesTask<K,V> rights, nextRight;
        ReduceEntriesTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 ReduceEntriesTask<K,V> nextRight,
                 BiFunction<Entry<K,V>, Map.Entry<K,V>, ? extends Map.Entry<K,V>> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.reducer = reducer;
        }
        public final Map.Entry<K,V> getRawResult() { return result; }
        public final void compute() {
            final BiFunction<Map.Entry<K,V>, Map.Entry<K,V>, ? extends Map.Entry<K,V>> reducer;
            if ((reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new ReduceEntriesTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, reducer)).fork();
                }
                Map.Entry<K,V> r = null;
                for (Node<K,V> p; (p = advance()) != null; )
                    r = (r == null) ? p : reducer.apply(r, p);
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    ReduceEntriesTask<K,V>
                            t = (ReduceEntriesTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        Map.Entry<K,V> tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceKeysTask<K,V,U> extends BulkTask<K,V,U> {
        final Function<? super K, ? extends U> transformer;
        final BiFunction<? super U, ? super U, ? extends U> reducer;
        U result;
        MapReduceKeysTask<K,V,U> rights, nextRight;
        MapReduceKeysTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceKeysTask<K,V,U> nextRight,
                 Function<? super K, ? extends U> transformer,
                 BiFunction<? super U, ? super U, ? extends U> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.reducer = reducer;
        }
        public final U getRawResult() { return result; }
        public final void compute() {
            final Function<? super K, ? extends U> transformer;
            final BiFunction<? super U, ? super U, ? extends U> reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceKeysTask<K,V,U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, reducer)).fork();
                }
                U r = null;
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.key)) != null)
                        r = (r == null) ? u : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceKeysTask<K,V,U>
                            t = (MapReduceKeysTask<K,V,U>)c,
                            s = t.rights;
                    while (s != null) {
                        U tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceValuesTask<K,V,U> extends BulkTask<K,V,U> {
        final Function<? super V, ? extends U> transformer;
        final BiFunction<? super U, ? super U, ? extends U> reducer;
        U result;
        MapReduceValuesTask<K,V,U> rights, nextRight;
        MapReduceValuesTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceValuesTask<K,V,U> nextRight,
                 Function<? super V, ? extends U> transformer,
                 BiFunction<? super U, ? super U, ? extends U> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.reducer = reducer;
        }
        public final U getRawResult() { return result; }
        public final void compute() {
            final Function<? super V, ? extends U> transformer;
            final BiFunction<? super U, ? super U, ? extends U> reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceValuesTask<K,V,U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, reducer)).fork();
                }
                U r = null;
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.val)) != null)
                        r = (r == null) ? u : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceValuesTask<K,V,U>
                            t = (MapReduceValuesTask<K,V,U>)c,
                            s = t.rights;
                    while (s != null) {
                        U tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceEntriesTask<K,V,U> extends BulkTask<K,V,U> {
        final Function<Map.Entry<K,V>, ? extends U> transformer;
        final BiFunction<? super U, ? super U, ? extends U> reducer;
        U result;
        MapReduceEntriesTask<K,V,U> rights, nextRight;
        MapReduceEntriesTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceEntriesTask<K,V,U> nextRight,
                 Function<Map.Entry<K,V>, ? extends U> transformer,
                 BiFunction<? super U, ? super U, ? extends U> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.reducer = reducer;
        }
        public final U getRawResult() { return result; }
        public final void compute() {
            final Function<Map.Entry<K,V>, ? extends U> transformer;
            final BiFunction<? super U, ? super U, ? extends U> reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceEntriesTask<K,V,U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, reducer)).fork();
                }
                U r = null;
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p)) != null)
                        r = (r == null) ? u : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceEntriesTask<K,V,U>
                            t = (MapReduceEntriesTask<K,V,U>)c,
                            s = t.rights;
                    while (s != null) {
                        U tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceMappingsTask<K,V,U> extends BulkTask<K,V,U> {
        final BiFunction<? super K, ? super V, ? extends U> transformer;
        final BiFunction<? super U, ? super U, ? extends U> reducer;
        U result;
        MapReduceMappingsTask<K,V,U> rights, nextRight;
        MapReduceMappingsTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceMappingsTask<K,V,U> nextRight,
                 BiFunction<? super K, ? super V, ? extends U> transformer,
                 BiFunction<? super U, ? super U, ? extends U> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.reducer = reducer;
        }
        public final U getRawResult() { return result; }
        public final void compute() {
            final BiFunction<? super K, ? super V, ? extends U> transformer;
            final BiFunction<? super U, ? super U, ? extends U> reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceMappingsTask<K,V,U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, reducer)).fork();
                }
                U r = null;
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.key, p.val)) != null)
                        r = (r == null) ? u : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceMappingsTask<K,V,U>
                            t = (MapReduceMappingsTask<K,V,U>)c,
                            s = t.rights;
                    while (s != null) {
                        U tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceKeysToDoubleTask<K,V> extends BulkTask<K,V,Double> {
        final ToDoubleFunction<? super K> transformer;
        final DoubleBinaryOperator reducer;
        final double basis;
        double result;
        MapReduceKeysToDoubleTask<K,V> rights, nextRight;
        MapReduceKeysToDoubleTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceKeysToDoubleTask<K,V> nextRight,
                 ToDoubleFunction<? super K> transformer,
                 double basis,
                 DoubleBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Double getRawResult() { return result; }
        public final void compute() {
            final ToDoubleFunction<? super K> transformer;
            final DoubleBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                double r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceKeysToDoubleTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsDouble(r, transformer.applyAsDouble(p.key));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceKeysToDoubleTask<K,V>
                            t = (MapReduceKeysToDoubleTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsDouble(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceValuesToDoubleTask<K,V> extends BulkTask<K,V,Double> {
        final ToDoubleFunction<? super V> transformer;
        final DoubleBinaryOperator reducer;
        final double basis;
        double result;
        MapReduceValuesToDoubleTask<K,V> rights, nextRight;
        MapReduceValuesToDoubleTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceValuesToDoubleTask<K,V> nextRight,
                 ToDoubleFunction<? super V> transformer,
                 double basis,
                 DoubleBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Double getRawResult() { return result; }
        public final void compute() {
            final ToDoubleFunction<? super V> transformer;
            final DoubleBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                double r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceValuesToDoubleTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsDouble(r, transformer.applyAsDouble(p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceValuesToDoubleTask<K,V>
                            t = (MapReduceValuesToDoubleTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsDouble(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceEntriesToDoubleTask<K,V> extends BulkTask<K,V,Double> {
        final ToDoubleFunction<Map.Entry<K,V>> transformer;
        final DoubleBinaryOperator reducer;
        final double basis;
        double result;
        MapReduceEntriesToDoubleTask<K,V> rights, nextRight;
        MapReduceEntriesToDoubleTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceEntriesToDoubleTask<K,V> nextRight,
                 ToDoubleFunction<Map.Entry<K,V>> transformer,
                 double basis,
                 DoubleBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Double getRawResult() { return result; }
        public final void compute() {
            final ToDoubleFunction<Map.Entry<K,V>> transformer;
            final DoubleBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                double r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceEntriesToDoubleTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsDouble(r, transformer.applyAsDouble(p));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceEntriesToDoubleTask<K,V>
                            t = (MapReduceEntriesToDoubleTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsDouble(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceMappingsToDoubleTask<K,V> extends BulkTask<K,V,Double> {
        final ToDoubleBiFunction<? super K, ? super V> transformer;
        final DoubleBinaryOperator reducer;
        final double basis;
        double result;
        MapReduceMappingsToDoubleTask<K,V> rights, nextRight;
        MapReduceMappingsToDoubleTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceMappingsToDoubleTask<K,V> nextRight,
                 ToDoubleBiFunction<? super K, ? super V> transformer,
                 double basis,
                 DoubleBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Double getRawResult() { return result; }
        public final void compute() {
            final ToDoubleBiFunction<? super K, ? super V> transformer;
            final DoubleBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                double r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceMappingsToDoubleTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsDouble(r, transformer.applyAsDouble(p.key, p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceMappingsToDoubleTask<K,V>
                            t = (MapReduceMappingsToDoubleTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsDouble(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceKeysToLongTask<K,V> extends BulkTask<K,V,Long> {
        final ToLongFunction<? super K> transformer;
        final LongBinaryOperator reducer;
        final long basis;
        long result;
        MapReduceKeysToLongTask<K,V> rights, nextRight;
        MapReduceKeysToLongTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceKeysToLongTask<K,V> nextRight,
                 ToLongFunction<? super K> transformer,
                 long basis,
                 LongBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Long getRawResult() { return result; }
        public final void compute() {
            final ToLongFunction<? super K> transformer;
            final LongBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                long r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceKeysToLongTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsLong(r, transformer.applyAsLong(p.key));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceKeysToLongTask<K,V>
                            t = (MapReduceKeysToLongTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsLong(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceValuesToLongTask<K,V> extends BulkTask<K,V,Long> {
        final ToLongFunction<? super V> transformer;
        final LongBinaryOperator reducer;
        final long basis;
        long result;
        MapReduceValuesToLongTask<K,V> rights, nextRight;
        MapReduceValuesToLongTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceValuesToLongTask<K,V> nextRight,
                 ToLongFunction<? super V> transformer,
                 long basis,
                 LongBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Long getRawResult() { return result; }
        public final void compute() {
            final ToLongFunction<? super V> transformer;
            final LongBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                long r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceValuesToLongTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsLong(r, transformer.applyAsLong(p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceValuesToLongTask<K,V>
                            t = (MapReduceValuesToLongTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsLong(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceEntriesToLongTask<K,V> extends BulkTask<K,V,Long> {
        final ToLongFunction<Map.Entry<K,V>> transformer;
        final LongBinaryOperator reducer;
        final long basis;
        long result;
        MapReduceEntriesToLongTask<K,V> rights, nextRight;
        MapReduceEntriesToLongTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceEntriesToLongTask<K,V> nextRight,
                 ToLongFunction<Map.Entry<K,V>> transformer,
                 long basis,
                 LongBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Long getRawResult() { return result; }
        public final void compute() {
            final ToLongFunction<Map.Entry<K,V>> transformer;
            final LongBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                long r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceEntriesToLongTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsLong(r, transformer.applyAsLong(p));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceEntriesToLongTask<K,V>
                            t = (MapReduceEntriesToLongTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsLong(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceMappingsToLongTask<K,V> extends BulkTask<K,V,Long> {
        final ToLongBiFunction<? super K, ? super V> transformer;
        final LongBinaryOperator reducer;
        final long basis;
        long result;
        MapReduceMappingsToLongTask<K,V> rights, nextRight;
        MapReduceMappingsToLongTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceMappingsToLongTask<K,V> nextRight,
                 ToLongBiFunction<? super K, ? super V> transformer,
                 long basis,
                 LongBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Long getRawResult() { return result; }
        public final void compute() {
            final ToLongBiFunction<? super K, ? super V> transformer;
            final LongBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                long r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceMappingsToLongTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsLong(r, transformer.applyAsLong(p.key, p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceMappingsToLongTask<K,V>
                            t = (MapReduceMappingsToLongTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsLong(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceKeysToIntTask<K,V> extends BulkTask<K,V,Integer> {
        final ToIntFunction<? super K> transformer;
        final IntBinaryOperator reducer;
        final int basis;
        int result;
        MapReduceKeysToIntTask<K,V> rights, nextRight;
        MapReduceKeysToIntTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceKeysToIntTask<K,V> nextRight,
                 ToIntFunction<? super K> transformer,
                 int basis,
                 IntBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Integer getRawResult() { return result; }
        public final void compute() {
            final ToIntFunction<? super K> transformer;
            final IntBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                int r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceKeysToIntTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsInt(r, transformer.applyAsInt(p.key));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceKeysToIntTask<K,V>
                            t = (MapReduceKeysToIntTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsInt(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceValuesToIntTask<K,V> extends BulkTask<K,V,Integer> {
        final ToIntFunction<? super V> transformer;
        final IntBinaryOperator reducer;
        final int basis;
        int result;
        MapReduceValuesToIntTask<K,V> rights, nextRight;
        MapReduceValuesToIntTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceValuesToIntTask<K,V> nextRight,
                 ToIntFunction<? super V> transformer,
                 int basis,
                 IntBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Integer getRawResult() { return result; }
        public final void compute() {
            final ToIntFunction<? super V> transformer;
            final IntBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                int r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceValuesToIntTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsInt(r, transformer.applyAsInt(p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceValuesToIntTask<K,V>
                            t = (MapReduceValuesToIntTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsInt(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceEntriesToIntTask<K,V> extends BulkTask<K,V,Integer> {
        final ToIntFunction<Map.Entry<K,V>> transformer;
        final IntBinaryOperator reducer;
        final int basis;
        int result;
        MapReduceEntriesToIntTask<K,V> rights, nextRight;
        MapReduceEntriesToIntTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceEntriesToIntTask<K,V> nextRight,
                 ToIntFunction<Map.Entry<K,V>> transformer,
                 int basis,
                 IntBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Integer getRawResult() { return result; }
        public final void compute() {
            final ToIntFunction<Map.Entry<K,V>> transformer;
            final IntBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                int r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceEntriesToIntTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsInt(r, transformer.applyAsInt(p));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceEntriesToIntTask<K,V>
                            t = (MapReduceEntriesToIntTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsInt(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    static final class MapReduceMappingsToIntTask<K,V> extends BulkTask<K,V,Integer> {
        final ToIntBiFunction<? super K, ? super V> transformer;
        final IntBinaryOperator reducer;
        final int basis;
        int result;
        MapReduceMappingsToIntTask<K,V> rights, nextRight;
        MapReduceMappingsToIntTask
                (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
                 MapReduceMappingsToIntTask<K,V> nextRight,
                 ToIntBiFunction<? super K, ? super V> transformer,
                 int basis,
                 IntBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Integer getRawResult() { return result; }
        public final void compute() {
            final ToIntBiFunction<? super K, ? super V> transformer;
            final IntBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                int r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceMappingsToIntTask<K,V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsInt(r, transformer.applyAsInt(p.key, p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceMappingsToIntTask<K,V>
                            t = (MapReduceMappingsToIntTask<K,V>)c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsInt(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    // Unsafe mechanics
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long SIZECTL = U.objectFieldOffset(ConcurrentHashMap.class, "sizeCtl");
    private static final long TRANSFERINDEX = U.objectFieldOffset(ConcurrentHashMap.class, "transferIndex");
    private static final long BASECOUNT = U.objectFieldOffset(ConcurrentHashMap.class, "baseCount");
    private static final long CELLSBUSY = U.objectFieldOffset(ConcurrentHashMap.class, "cellsBusy");
    private static final long CELLVALUE = U.objectFieldOffset(CounterCell.class, "value");
    private static final int ABASE = U.arrayBaseOffset(Node[].class);
    private static final int ASHIFT;

    static {
        int scale = U.arrayIndexScale(Node[].class);
        if ((scale & (scale - 1)) != 0)
            throw new ExceptionInInitializerError("array index scale not a power of two");
        ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);

        // Reduce the risk of rare disastrous classloading in first call to
        // LockSupport.park: https://bugs.openjdk.java.net/browse/JDK-8074773
        Class<?> ensureLoaded = LockSupport.class;

        // Eager class load observed to help JIT during startup
        ensureLoaded = ReservationNode.class;
    }
}

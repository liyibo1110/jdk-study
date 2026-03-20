package com.github.liyibo1110.jdk.java.util.concurrent;

import sun.misc.Unsafe;

import javax.swing.text.Segment;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

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

    public V get(Object key) {
        Node<K, V>[] tab;
        Node<K, V> e;
        Node<K, V> p;
        int n;
        int eh;
        K ek;
        int h = spread(key.hashCode()); // 算hash
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

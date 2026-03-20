package com.github.liyibo1110.jdk.java.util.concurrent;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedMap;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 一个线程安全的、按key有序的、基于跳表实现的NavigableMap，解决的问题是：需要能按key排序，并且支持NavigableMap接口方法，还要线程安全：
 * 1、ConcurrentHashMap：key是无序的。
 * 2、TreeMap：不是线程安全。
 * 3、Collections.synchronizedSortedMap方法：性能差，迭代和范围查询支持不好。
 *
 * 主要特点：
 * 1、有序：可以自然排序，也可以传Comparator。
 * 2、线程安全：实现方式不是用一个锁把整个结果锁死。
 * 3、迭代器弱一致性：和ConcurrentLinkedQueue差不多，不会fail-fast、不要求强一致、可以看到部分并发修改的结果。
 * 4、适合范围查询：也是相比较于ConcurrentHashMap的最大价值之一，比如：
 *  - 最近时间段的数据。
 *  - 某个key区间的数据。
 *  - 排行榜区间。
 *  - 有序任务表。
 * 5、单个操作高并发，但复合操作不是事务性的，因为要尽量使用类似putIfAbsent这种原子性API。
 *
 * 为什么用跳表实现，而不是红黑树
 * Head nodes          Index nodes
 * +-+    right        +-+                      +-+
 * |2|---------------->| |--------------------->| |->null
 * +-+                 +-+                      +-+
 *  | down              |                        |
 *  v                   v                        v
 * +-+            +-+  +-+       +-+            +-+       +-+
 * |1|----------->| |->| |------>| |----------->| |------>| |->null
 * +-+            +-+  +-+       +-+            +-+       +-+
 *  v              |    |         |              |         |
 * Nodes  next     v    v         v              v         v
 * +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+
 * | |->|A|->|B|->|C|->|D|->|E|->|F|->|G|->|H|->|I|->|J|->|K|->null
 * +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+
 * 以上是跳表结构，查找时：
 * 1、先从高层快速跳。
 * 2、发现再跳就过头了，就往下一层。
 * 3、一层层降落到底层。
 * 4、最终找到目标。
 *
 * 红黑树的问题是：
 * 1、插入和删除是要旋转。
 * 2、旋转会影响多个节点关系。
 * 3、并发控制复杂。
 *
 * 跳表更适合并发：
 * 1、底层本身还是链表。
 * 2、插入和删除主要是局部链接的修改。
 * 3、更容易做CAS和局部修复。
 * 因此跳表在并发环境下，比平衡树更容易做出高性能实现。
 *
 * 包含了两套节点体系：
 * 1、底层数据节点：Node。
 * 2、上层索引节点：Index / HeadIndex
 *
 * 要记住的五个学习重点：
 * 1、底层数据在Node，不在Index。
 * 2、查找永远是：先再上层跳，再到底层落。
 * 3、插入顺序是：先底层，再上层索引。
 * 4、删除是：先逻辑，再物理。
 * 5、跳表不是树，是多层链表。
 * @author liyibo
 * @date 2026-03-19 18:01
 */
public class ConcurrentSkipListMap<K, V> extends AbstractMap<K, V> implements ConcurrentNavigableMap<K,V>, Cloneable, Serializable {
    private static final long serialVersionUID = -8627078645895051609L;

    final Comparator<? super K> comparator;

    /**
     * 整个跳表的入口，即最顶层索引塔的头节点，查找时通常从head开始，从高层一路往下：
     * head -> 最高层索引 -> 中间层索引 -> 底层数据链表
     */
    private transient Index<K, V> head;

    private transient LongAdder adder;

    private transient KeySet<K,V> keySet;

    private transient Values<K,V> values;

    private transient EntrySet<K,V> entrySet;

    private transient SubMap<K,V> descendingMap;

    /**
     * 底层真实数据节点，真正的数据都在底层Node链表里。
     */
    static final class Node<K,V> {
        final K key;
        V val;

        /** 底层有序链表的下一个节点 */
        Node<K, V> next;

        Node(K key, V value, Node<K, V> next) {
            this.key = key;
            this.val = value;
            this.next = next;
        }
    }

    /**
     * 索引层节点，负责快速跳跃，不负责存储真实值。
     */
    static final class Index<K,V> {

        /** 指向哪个底层数据节点 */
        final Node<K,V> node;

        /** 下一层的索引节点 */
        final Index<K,V> down;

        /** 同层右侧的索引节点 */
        Index<K,V> right;

        Index(Node<K,V> node, Index<K,V> down, Index<K,V> right) {
            this.node = node;
            this.down = down;
            this.right = right;
        }
    }

    /* ----------------  Utilities -------------- */

    static int cpr(Comparator c, Object x, Object y) {
        return (c != null) ? c.compare(x, y) : ((Comparable)x).compareTo(y);
    }

    final Node<K, V> baseHead() {
        Index<K, V> h;
        VarHandle.acquireFence();
        return ((h = head) == null) ? null : h.node;
    }

    /**
     * 先标记n已删除，再尝试把它从链表中跳过去。
     * b -> n -> f 变成 b -> f
     * @param b n的前驱
     * @param n 要unlink的node
     */
    static <K, V> void unlinkNode(Node<K, V> b, Node<K, V> n) {
        if(b != null && n != null) {
            Node<K, V> f;
            Node<K, V> p;
            /**
             * 给n加标记，代表：这个节点正在被删除，作用是：
             * 1、防止其它线程继续插入到n的后面。
             * 2、帮助其它线程识别删除状态。
             */
            while(true) {
                if((f = n.next) != null && f.key == null) { // n已经加了标记了，即b -> n -> marker -> p
                    p = f.next;
                    break;
                }else if(NEXT.compareAndSet(n, f, new Node<>(null, null, f))) { // 没有marker，变成b -> n -> marker -> f
                    p = f;
                    break;
                }
            }
            NEXT.compareAndSet(b, n, p);
        }
    }

    private void addCount(long c) {
        LongAdder a;
        // 如果计数器还没初始化，就先初始化
        do {
            // nothing to do
        }while ((a = adder) == null && !ADDER.compareAndSet(this, null, a = new LongAdder()));
        a.add(c);
    }

    final long getAdderCount() {
        LongAdder a;
        long c;
        // 如果计数器还没初始化，就先初始化
        do {
            // nothing to do
        }while((a = adder) == null && !ADDER.compareAndSet(this, null, a = new LongAdder()));
        return ((c = a.sum()) <= 0L) ? 0L : c;
    }

    /* ---------------- Traversal -------------- */

    private Node<K,V> findPredecessor(Object key, Comparator<? super K> cmp) {
        Index<K,V> q;
        VarHandle.acquireFence();
        if((q = head) == null || key == null)
            return null;
        else {
            for(Index<K, V> r, d; ; ) {
                while((r = q.right) != null) {  // 横向移动，尽可能往右，直到不能再走
                    Node<K,V> p;
                    K k;
                    /**
                     * 这个索引指向的节点无效（被删除），说明node被删或者正在删除，直接CAS把再后面的接进来，相当于顺便清理了无效节点。
                     */
                    if((p = r.node) == null || (k = p.key) == null || p.val == null) {
                        RIGHT.compareAndSet(q, r, r.right);
                    }else if(cpr(cmp, key, k) > 0) {    // 目标key > 当前节点key，还要往右找
                        q = r;
                    }else { // 说明cpr结果<=0了，再往右就会超过key了，准备向下
                        break;
                    }
                }
                // 一层都找完了，要向下一层了
                if((d = q.down) != null)
                    q = d;
                else    // 已经在底层了，q就是要找的node
                    return q.node;
            }
        }
    }

    private Node<K,V> findNode(Object key) {
        if(key == null)
            throw new NullPointerException();
        Comparator<? super K> cmp = comparator;
        Node<K,V> b;    // key对应node的前驱
        outer: while((b = findPredecessor(key, cmp)) != null) {
            while(true) {
                Node<K,V> n;
                K k;
                V v;
                int c;
                if((n = b.next) == null)    // 链表扫到头了，key不存在，直接返回null
                    break outer;
                else if((k = n.key) == null)    // 前驱是无效的（n是marker节点），会再次调用findPredecessor找新的前驱（里面会清理）
                    break;
                else if((v = n.val) == null)    // key对应节点本身是无效的，直接摘除并继续循环，因为链表结构变了，需要继续检查
                    unlinkNode(b, n);
                else if((c = cpr(cmp, key, k)) > 0) // key大于目前找到的key，要向右走一格
                    b = n;
                else if(c == 0) // 终于找到了，直接返回这个node
                    return n;
                else    // cpr < 0，说明已经跳过了目标位置，说明key还是不存在，直接返回null
                    break outer;
            }
        }
        return null;
    }

    /**
     * 流程看findPredecessor和findNode就行了，差不多
     */
    private V doGet(Object key) {
        Index<K,V> q;
        VarHandle.acquireFence();
        if(key == null)
            throw new NullPointerException();
        Comparator<? super K> cmp = comparator;
        V result = null;

        // 开始爬塔
        if((q = head) != null) {
            outer: for(Index<K, V> r, d; ; ) {
                while((r = q.right) != null) {
                    Node<K, V> p;
                    K k;
                    V v;
                    int c;
                    if((p = r.node) == null || (k = p.key) == null || (v = p.val) == null)
                        RIGHT.compareAndSet(q, r, r.right);
                    else if((c = cpr(cmp, key, k)) > 0) // 不是这个key
                        q = r;
                    else if(c == 0) {   // 找到了
                        result = v;
                        break outer;
                    }else
                        break;
                }
                if((d = q.down) != null)
                    q = d;
                else{
                    Node<K,V> b, n;
                    if((b = q.node) != null) {
                        while((n = b.next) != null) {
                            V v; int c;
                            K k = n.key;
                            if((v = n.val) == null || k == null || (c = cpr(cmp, key, k)) > 0)
                                b = n;
                            else {
                                if(c == 0)
                                    result = v;
                                break;
                            }
                        }
                    }
                    break;
                }
            }
        }
        return result;
    }

    /* ---------------- Insertion -------------- */

    /**
     * 先在底层有序链表里把新节点插进去，再按随机结果决定要不要给这个节点补上上层索引。
     */
    private V doPut(K key, V value, boolean onlyIfAbsent) {
        if(key == null)
            throw new NullPointerException();
        Comparator<? super K> cmp = comparator;
        while(true) {
            Index<K, V> h;
            Node<K, V> b;
            VarHandle.acquireFence();
            int levels = 0; // 已下降的层数
            /**
             * 步骤一：尝试找到前驱b
             */
            if((h = head) == null)  {   // 空链表，需要初始化
                Node<K, V> base = new Node<>(null, null, null);
                h = new Index<>(base, null, null);
                b = (HEAD.compareAndSet(this, null, h)) ? base : null;
            }else {
                for(Index<K,V> q = h, r, d; ;) {
                    while((r = q.right) != null) {
                        Node<K,V> p;
                        K k;
                        if((p = r.node) == null || (k = p.key) == null || p.val == null)
                            RIGHT.compareAndSet(q, r, r.right);
                        else if(cpr(cmp, key, k) > 0)
                            q = r;
                        else
                            break;
                    }
                    if((d = q.down) != null) {
                        ++levels;
                        q = d;
                    }else {
                        b = q.node;
                        break;
                    }
                }
            }

            if(b != null) {
                Node<K,V> z = null; // 最终真正插入成功的新底层节点
                /**
                 * 步骤二：在底层链表里精确找到插入点，并尝试插入新节点。
                 */
                while(true) {
                    Node<K, V> n;
                    Node<K, V> p;
                    K k;
                    V v;
                    int c;
                    /**
                     * 到链表尾部了，如果前驱的key是null，会额外调用一次cpr，为了在空表/哨兵场景下尽早做key的可比较性检查。
                     * 然后强制c是-1，代表：当前应当插入。
                     */
                    if((n = b.next) == null) {
                        if(b.key == null)
                            cpr(cmp, key, key);
                        c = -1;
                    }else if((k = n.key) == null)   // 说明n的mark节点，b这个位置也不稳定了，所以要跳出内层for，重新寻找前驱
                        break;
                    else if((v = n.val) == null) {  // 说明n是逻辑删除节点，需要摘除，c = 1意思是本轮不插入，继续下一轮扫描
                        unlinkNode(b, n);
                        c = 1;
                    }else if ((c = cpr(cmp, key, k)) > 0)   // key比当前遍历的节点大，要继续往右走
                        b = n;
                    else if(c == 0 && (onlyIfAbsent || VAL.compareAndSet(n, v, value))) // 如果找到了相同key，按照onlyIfAbsent来更新
                        return v;

                    /**
                     * 到这里如果c < 0，说明z应该插在b和n之间，即原来结构是b -> n，插入后变成b -> p -> n
                     */
                    if(c < 0 && NEXT.compareAndSet(b, n, p = new Node<>(key, value, n))) {
                        z = p;
                        break;
                    }
                }

                /**
                 * 步骤三：如果插入成功，按概率决定是否建立索引层。
                 */
                if (z != null) {
                    int lr = ThreadLocalRandom.nextSecondarySeed();
                    if((lr & 0x3) == 0) {       // 1/4的概率能进来，说明要建立上层索引
                        /**
                         * 构建一个以z为底的索引链。
                         */
                        int hr = ThreadLocalRandom.nextSecondarySeed();
                        long rnd = ((long)hr << 32) | ((long)lr & 0xffffffffL);
                        /**
                         * 非常重要，表示：从当前head下来时经历了多少层，这些层先跳过不加
                         * 也就是说，新建的索引塔不一定从head的最顶层就开始接入，可能会先下几层再挂。
                         * 这个设计是为了配合当前已有跳表的高度。
                         */
                        int skips = levels;
                        Index<K, V> x = null;
                        while(true) {               // create at most 62 indices
                            x = new Index<>(z, x, null);
                            if(rnd >= 0L || --skips < 0)
                                break;
                            else
                                rnd <<= 1;
                        }
                        /**
                         * 步骤四：把新建的索引塔接入现有的跳表。
                         */
                        if(addIndices(h, skips, x, cmp) && skips < 0 && head == h) {
                            Index<K, V> hx = new Index<>(z, x, null);
                            Index<K, V> nh = new Index<>(h.node, h, hx);
                            HEAD.compareAndSet(this, h, nh);
                        }
                        if(z.val == null)       // deleted while adding indices
                            findPredecessor(key, cmp); // clean
                    }
                    addCount(1L);
                    return null;
                }
            }
        }
    }

    static <K, V> boolean addIndices(Index<K, V> q, int skips, Index<K,V> x, Comparator<? super K> cmp) {
        Node<K,V> z; K key;
        if (x != null && (z = x.node) != null && (key = z.key) != null &&
                q != null) {                            // hoist checks
            boolean retrying = false;
            for (;;) {                              // find splice point
                Index<K,V> r, d; int c;
                if ((r = q.right) != null) {
                    Node<K,V> p; K k;
                    if ((p = r.node) == null || (k = p.key) == null ||
                            p.val == null) {
                        RIGHT.compareAndSet(q, r, r.right);
                        c = 0;
                    }
                    else if ((c = cpr(cmp, key, k)) > 0)
                        q = r;
                    else if (c == 0)
                        break;                      // stale
                }
                else
                    c = -1;

                if (c < 0) {
                    if ((d = q.down) != null && skips > 0) {
                        --skips;
                        q = d;
                    }
                    else if (d != null && !retrying &&
                            !addIndices(d, 0, x.down, cmp))
                        break;
                    else {
                        x.right = r;
                        if (RIGHT.compareAndSet(q, r, x))
                            return true;
                        else
                            retrying = true;         // re-find splice point
                    }
                }
            }
        }
        return false;
    }

    /* ---------------- Deletion -------------- */

    final V doRemove(Object key, Object value) {
        if(key == null)
            throw new NullPointerException();
        Comparator<? super K> cmp = comparator;
        V result = null;
        Node<K, V> b;
        outer: while ((b = findPredecessor(key, cmp)) != null &&
                result == null) {
            while(true) {
                Node<K, V> n; K k; V v; int c;
                if((n = b.next) == null)
                    break outer;
                else if((k = n.key) == null)
                    break;
                else if((v = n.val) == null)
                    unlinkNode(b, n);
                else if((c = cpr(cmp, key, k)) > 0)
                    b = n;
                else if(c < 0)
                    break outer;
                else if(value != null && !value.equals(v))
                    break outer;
                else if(VAL.compareAndSet(n, v, null)) {
                    result = v;
                    unlinkNode(b, n);
                    break; // loop to clean up
                }
            }
        }
        if(result != null) {
            tryReduceLevel();
            addCount(-1L);
        }
        return result;
    }

    private void tryReduceLevel() {
        Index<K,V> h, d, e;
        if ((h = head) != null && h.right == null &&
                (d = h.down) != null && d.right == null &&
                (e = d.down) != null && e.right == null &&
                HEAD.compareAndSet(this, h, d) &&
                h.right != null)   // recheck
            HEAD.compareAndSet(this, d, h);  // try to backout
    }

    /* ---------------- Finding and removing first element -------------- */

    final Node<K,V> findFirst() {
        Node<K,V> b, n;
        if((b = baseHead()) != null) {
            while((n = b.next) != null) {
                if(n.val == null)
                    unlinkNode(b, n);
                else
                    return n;
            }
        }
        return null;
    }

    final AbstractMap.SimpleImmutableEntry<K,V> findFirstEntry() {
        Node<K,V> b, n; V v;
        if((b = baseHead()) != null) {
            while((n = b.next) != null) {
                if((v = n.val) == null)
                    unlinkNode(b, n);
                else
                    return new AbstractMap.SimpleImmutableEntry<K,V>(n.key, v);
            }
        }
        return null;
    }

    private AbstractMap.SimpleImmutableEntry<K,V> doRemoveFirstEntry() {
        Node<K,V> b, n; V v;
        if((b = baseHead()) != null) {
            while((n = b.next) != null) {
                if((v = n.val) == null || VAL.compareAndSet(n, v, null)) {
                    K k = n.key;
                    unlinkNode(b, n);
                    if(v != null) {
                        tryReduceLevel();
                        findPredecessor(k, comparator); // clean index
                        addCount(-1L);
                        return new AbstractMap.SimpleImmutableEntry<K,V>(k, v);
                    }
                }
            }
        }
        return null;
    }

    /* ---------------- Finding and removing last element -------------- */

    final Node<K,V> findLast() {
        outer: for (;;) {
            Index<K,V> q; Node<K,V> b;
            VarHandle.acquireFence();
            if ((q = head) == null)
                break;
            for (Index<K,V> r, d;;) {
                while ((r = q.right) != null) {
                    Node<K,V> p;
                    if ((p = r.node) == null || p.val == null)
                        RIGHT.compareAndSet(q, r, r.right);
                    else
                        q = r;
                }
                if ((d = q.down) != null)
                    q = d;
                else {
                    b = q.node;
                    break;
                }
            }
            if (b != null) {
                for (;;) {
                    Node<K,V> n;
                    if ((n = b.next) == null) {
                        if (b.key == null) // empty
                            break outer;
                        else
                            return b;
                    }
                    else if (n.key == null)
                        break;
                    else if (n.val == null)
                        unlinkNode(b, n);
                    else
                        b = n;
                }
            }
        }
        return null;
    }

    final AbstractMap.SimpleImmutableEntry<K,V> findLastEntry() {
        for(;;) {
            Node<K,V> n; V v;
            if((n = findLast()) == null)
                return null;
            if((v = n.val) != null)
                return new AbstractMap.SimpleImmutableEntry<K,V>(n.key, v);
        }
    }

    private Map.Entry<K,V> doRemoveLastEntry() {
        outer: for (;;) {
            Index<K, V> q; Node<K, V> b;
            VarHandle.acquireFence();
            if ((q = head) == null)
                break;
            for (;;) {
                Index<K,V> d, r; Node<K,V> p;
                while ((r = q.right) != null) {
                    if ((p = r.node) == null || p.val == null)
                        RIGHT.compareAndSet(q, r, r.right);
                    else if (p.next != null)
                        q = r;  // continue only if a successor
                    else
                        break;
                }
                if ((d = q.down) != null)
                    q = d;
                else {
                    b = q.node;
                    break;
                }
            }
            if (b != null) {
                for (;;) {
                    Node<K,V> n; K k; V v;
                    if ((n = b.next) == null) {
                        if (b.key == null) // empty
                            break outer;
                        else
                            break; // retry
                    }
                    else if ((k = n.key) == null)
                        break;
                    else if ((v = n.val) == null)
                        unlinkNode(b, n);
                    else if (n.next != null)
                        b = n;
                    else if (VAL.compareAndSet(n, v, null)) {
                        unlinkNode(b, n);
                        tryReduceLevel();
                        findPredecessor(k, comparator); // clean index
                        addCount(-1L);
                        return new AbstractMap.SimpleImmutableEntry<K,V>(k, v);
                    }
                }
            }
        }
        return null;
    }

    /* ---------------- Relational operations -------------- */

    private static final int EQ = 1;
    private static final int LT = 2;
    private static final int GT = 0;

    final Node<K,V> findNear(K key, int rel, Comparator<? super K> cmp) {
        if (key == null)
            throw new NullPointerException();
        Node<K,V> result;
        outer: for (Node<K,V> b;;) {
            if ((b = findPredecessor(key, cmp)) == null) {
                result = null;
                break;                   // empty
            }
            for (;;) {
                Node<K,V> n; K k; int c;
                if ((n = b.next) == null) {
                    result = ((rel & LT) != 0 && b.key != null) ? b : null;
                    break outer;
                }
                else if ((k = n.key) == null)
                    break;
                else if (n.val == null)
                    unlinkNode(b, n);
                else if (((c = cpr(cmp, key, k)) == 0 && (rel & EQ) != 0) ||
                        (c < 0 && (rel & LT) == 0)) {
                    result = n;
                    break outer;
                }
                else if (c <= 0 && (rel & LT) != 0) {
                    result = (b.key != null) ? b : null;
                    break outer;
                }
                else
                    b = n;
            }
        }
        return result;
    }

    final AbstractMap.SimpleImmutableEntry<K,V> findNearEntry(K key, int rel, Comparator<? super K> cmp) {
        for (;;) {
            Node<K,V> n; V v;
            if((n = findNear(key, rel, cmp)) == null)
                return null;
            if((v = n.val) != null)
                return new AbstractMap.SimpleImmutableEntry<K,V>(n.key, v);
        }
    }

    /* ---------------- Constructors -------------- */

    public ConcurrentSkipListMap() {
        this.comparator = null;
    }

    public ConcurrentSkipListMap(Comparator<? super K> comparator) {
        this.comparator = comparator;
    }

    public ConcurrentSkipListMap(Map<? extends K, ? extends V> m) {
        this.comparator = null;
        putAll(m);
    }

    public ConcurrentSkipListMap(SortedMap<K, ? extends V> m) {
        this.comparator = m.comparator();
        buildFromSorted(m); // initializes transients
    }

    public java.util.concurrent.ConcurrentSkipListMap<K,V> clone() {
        try {
            @SuppressWarnings("unchecked")
            ConcurrentSkipListMap<K,V> clone = (ConcurrentSkipListMap<K,V>) super.clone();
            clone.keySet = null;
            clone.entrySet = null;
            clone.values = null;
            clone.descendingMap = null;
            clone.adder = null;
            clone.buildFromSorted(this);
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new InternalError();
        }
    }

    private void buildFromSorted(SortedMap<K, ? extends V> map) {
        if(map == null)
            throw new NullPointerException();
        Iterator<? extends Entry<? extends K, ? extends V>> it = map.entrySet().iterator();

        /*
         * Add equally spaced indices at log intervals, using the bits
         * of count during insertion. The maximum possible resulting
         * level is less than the number of bits in a long (64). The
         * preds array tracks the current rightmost node at each
         * level.
         */
        @SuppressWarnings("unchecked")
        Index<K, V>[] preds = (Index<K, V>[])new Index<?, ?>[64];
        Node<K, V> bp = new Node<>(null, null, null);
        Index<K, V> h = preds[0] = new Index<>(bp, null, null);
        long count = 0;

        while (it.hasNext()) {
            Map.Entry<? extends K, ? extends V> e = it.next();
            K k = e.getKey();
            V v = e.getValue();
            if (k == null || v == null)
                throw new NullPointerException();
            Node<K,V> z = new Node<>(k, v, null);
            bp = bp.next = z;
            if ((++count & 3L) == 0L) {
                long m = count >>> 2;
                int i = 0;
                Index<K,V> idx = null, q;
                do {
                    idx = new Index<K,V>(z, idx, null);
                    if ((q = preds[i]) == null)
                        preds[i] = h = new Index<K,V>(h.node, h, idx);
                    else
                        preds[i] = q.right = idx;
                } while (++i < preds.length && ((m >>>= 1) & 1L) != 0L);
            }
        }
        if (count != 0L) {
            VarHandle.releaseFence(); // emulate volatile stores
            addCount(count);
            head = h;
            VarHandle.fullFence();
        }
    }

    /* ---------------- Serialization -------------- */

    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        // Write out the Comparator and any hidden stuff
        s.defaultWriteObject();

        // Write out keys and values (alternating)
        Node<K,V> b, n; V v;
        if ((b = baseHead()) != null) {
            while ((n = b.next) != null) {
                if ((v = n.val) != null) {
                    s.writeObject(n.key);
                    s.writeObject(v);
                }
                b = n;
            }
        }
        s.writeObject(null);
    }

    private void readObject(final java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        // Read in the Comparator and any hidden stuff
        s.defaultReadObject();

        // Same idea as buildFromSorted
        @SuppressWarnings("unchecked")
        Index<K,V>[] preds = (Index<K,V>[])new Index<?,?>[64];
        Node<K,V> bp = new Node<K,V>(null, null, null);
        Index<K,V> h = preds[0] = new Index<K,V>(bp, null, null);
        Comparator<? super K> cmp = comparator;
        K prevKey = null;
        long count = 0;

        for (;;) {
            K k = (K)s.readObject();
            if (k == null)
                break;
            V v = (V)s.readObject();
            if (v == null)
                throw new NullPointerException();
            if (prevKey != null && cpr(cmp, prevKey, k) > 0)
                throw new IllegalStateException("out of order");
            prevKey = k;
            Node<K,V> z = new Node<K,V>(k, v, null);
            bp = bp.next = z;
            if ((++count & 3L) == 0L) {
                long m = count >>> 2;
                int i = 0;
                Index<K,V> idx = null, q;
                do {
                    idx = new Index<K,V>(z, idx, null);
                    if ((q = preds[i]) == null)
                        preds[i] = h = new Index<K,V>(h.node, h, idx);
                    else
                        preds[i] = q.right = idx;
                } while (++i < preds.length && ((m >>>= 1) & 1L) != 0L);
            }
        }
        if (count != 0L) {
            VarHandle.releaseFence();
            addCount(count);
            head = h;
            VarHandle.fullFence();
        }
    }

    /* ------ Map API methods ------ */

    public boolean containsKey(Object key) {
        return doGet(key) != null;
    }

    public V get(Object key) {
        return doGet(key);
    }

    public V getOrDefault(Object key, V defaultValue) {
        V v;
        return (v = doGet(key)) == null ? defaultValue : v;
    }

    public V put(K key, V value) {
        if(value == null)
            throw new NullPointerException();
        return doPut(key, value, false);
    }

    public V remove(Object key) {
        return doRemove(key, null);
    }

    public boolean containsValue(Object value) {
        if(value == null)
            throw new NullPointerException();
        Node<K,V> b, n; V v;
        if((b = baseHead()) != null) {
            while((n = b.next) != null) {
                if((v = n.val) != null && value.equals(v))
                    return true;
                else
                    b = n;
            }
        }
        return false;
    }

    public int size() {
        long c;
        return ((baseHead() == null) ? 0 :
                ((c = getAdderCount()) >= Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) c);
    }

    public boolean isEmpty() {
        return findFirst() == null;
    }

    public void clear() {
        Index<K,V> h, r, d; Node<K,V> b;
        VarHandle.acquireFence();
        while ((h = head) != null) {
            if ((r = h.right) != null)        // remove indices
                RIGHT.compareAndSet(h, r, null);
            else if ((d = h.down) != null)    // remove levels
                HEAD.compareAndSet(this, h, d);
            else {
                long count = 0L;
                if ((b = h.node) != null) {    // remove nodes
                    Node<K,V> n; V v;
                    while ((n = b.next) != null) {
                        if ((v = n.val) != null && VAL.compareAndSet(n, v, null)) {
                            --count;
                            v = null;
                        }
                        if (v == null)
                            unlinkNode(b, n);
                    }
                }
                if (count != 0L)
                    addCount(count);
                else
                    break;
            }
        }
    }

    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if(key == null || mappingFunction == null)
            throw new NullPointerException();
        V v, p, r;
        if((v = doGet(key)) == null && (r = mappingFunction.apply(key)) != null)
            v = (p = doPut(key, r, true)) == null ? r : p;
        return v;
    }

    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if(key == null || remappingFunction == null)
            throw new NullPointerException();
        Node<K,V> n; V v;
        while((n = findNode(key)) != null) {
            if((v = n.val) != null) {
                V r = remappingFunction.apply(key, v);
                if(r != null) {
                    if(VAL.compareAndSet(n, v, r))
                        return r;
                }else if(doRemove(key, v) != null)
                    break;
            }
        }
        return null;
    }

    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if(key == null || remappingFunction == null)
            throw new NullPointerException();
        for (;;) {
            Node<K,V> n; V v; V r;
            if((n = findNode(key)) == null) {
                if((r = remappingFunction.apply(key, null)) == null)
                    break;
                if(doPut(key, r, true) == null)
                    return r;
            }else if((v = n.val) != null) {
                if((r = remappingFunction.apply(key, v)) != null) {
                    if(VAL.compareAndSet(n, v, r))
                        return r;
                }else if(doRemove(key, v) != null)
                    break;
            }
        }
        return null;
    }

    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if(key == null || value == null || remappingFunction == null)
            throw new NullPointerException();
        for (;;) {
            Node<K,V> n; V v; V r;
            if((n = findNode(key)) == null) {
                if(doPut(key, value, true) == null)
                    return value;
            }else if((v = n.val) != null) {
                if((r = remappingFunction.apply(v, value)) != null) {
                    if(VAL.compareAndSet(n, v, r))
                        return r;
                }else if(doRemove(key, v) != null)
                    return null;
            }
        }
    }

    /* ---------------- View methods -------------- */

    public NavigableSet<K> keySet() {
        KeySet<K,V> ks;
        if((ks = keySet) != null)
            return ks;
        return keySet = new KeySet<>(this);
    }

    public NavigableSet<K> navigableKeySet() {
        KeySet<K,V> ks;
        if((ks = keySet) != null)
            return ks;
        return keySet = new KeySet<>(this);
    }

    public Collection<V> values() {
        Values<K,V> vs;
        if((vs = values) != null)
            return vs;
        return values = new Values<>(this);
    }

    public Set<Entry<K,V>> entrySet() {
        EntrySet<K,V> es;
        if((es = entrySet) != null)
            return es;
        return entrySet = new EntrySet<K,V>(this);
    }

    public ConcurrentNavigableMap<K,V> descendingMap() {
        ConcurrentNavigableMap<K,V> dm;
        if((dm = descendingMap) != null)
            return dm;
        return descendingMap = new SubMap<K,V>(this, null, false, null, false, true);
    }

    public NavigableSet<K> descendingKeySet() {
        return descendingMap().navigableKeySet();
    }

    /* ---------------- AbstractMap Overrides -------------- */

    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof Map))
            return false;
        Map<?,?> m = (Map<?,?>) o;
        try {
            Comparator<? super K> cmp = comparator;
            // See JDK-8223553 for Iterator type wildcard rationale
            Iterator<? extends Map.Entry<?,?>> it = m.entrySet().iterator();
            if (m instanceof SortedMap && ((SortedMap<?,?>)m).comparator() == cmp) {
                Node<K,V> b, n;
                if ((b = baseHead()) != null) {
                    while ((n = b.next) != null) {
                        K k; V v;
                        if ((v = n.val) != null && (k = n.key) != null) {
                            if (!it.hasNext())
                                return false;
                            Map.Entry<?,?> e = it.next();
                            Object mk = e.getKey();
                            Object mv = e.getValue();
                            if (mk == null || mv == null)
                                return false;
                            try {
                                if (cpr(cmp, k, mk) != 0)
                                    return false;
                            } catch (ClassCastException cce) {
                                return false;
                            }
                            if (!mv.equals(v))
                                return false;
                        }
                        b = n;
                    }
                }
                return !it.hasNext();
            }
            else {
                while (it.hasNext()) {
                    V v;
                    Map.Entry<?,?> e = it.next();
                    Object mk = e.getKey();
                    Object mv = e.getValue();
                    if (mk == null || mv == null || (v = get(mk)) == null || !v.equals(mv))
                        return false;
                }
                Node<K,V> b, n;
                if ((b = baseHead()) != null) {
                    K k; V v; Object mv;
                    while ((n = b.next) != null) {
                        if ((v = n.val) != null && (k = n.key) != null && ((mv = m.get(k)) == null || !mv.equals(v)))
                            return false;
                        b = n;
                    }
                }
                return true;
            }
        } catch (ClassCastException | NullPointerException unused) {
            return false;
        }
    }

    /* ------ ConcurrentMap API methods ------ */

    public V putIfAbsent(K key, V value) {
        if(value == null)
            throw new NullPointerException();
        return doPut(key, value, true);
    }

    public boolean remove(Object key, Object value) {
        if(key == null)
            throw new NullPointerException();
        return value != null && doRemove(key, value) != null;
    }

    public boolean replace(K key, V oldValue, V newValue) {
        if(key == null || oldValue == null || newValue == null)
            throw new NullPointerException();
        for (;;) {
            Node<K,V> n; V v;
            if((n = findNode(key)) == null)
                return false;
            if((v = n.val) != null) {
                if(!oldValue.equals(v))
                    return false;
                if(VAL.compareAndSet(n, v, newValue))
                    return true;
            }
        }
    }

    public V replace(K key, V value) {
        if(key == null || value == null)
            throw new NullPointerException();
        for (;;){
            Node<K,V> n; V v;
            if((n = findNode(key)) == null)
                return null;
            if((v = n.val) != null && VAL.compareAndSet(n, v, value))
                return v;
        }
    }

    /* ------ SortedMap API methods ------ */

    public Comparator<? super K> comparator() {
        return comparator;
    }

    public K firstKey() {
        Node<K,V> n = findFirst();
        if(n == null)
            throw new NoSuchElementException();
        return n.key;
    }

    public K lastKey() {
        Node<K,V> n = findLast();
        if(n == null)
            throw new NoSuchElementException();
        return n.key;
    }

    public ConcurrentNavigableMap<K,V> subMap(K fromKey, boolean fromInclusive,
                                              K toKey, boolean toInclusive) {
        if(fromKey == null || toKey == null)
            throw new NullPointerException();
        return new SubMap<K,V>(this, fromKey, fromInclusive, toKey, toInclusive, false);
    }

    public ConcurrentNavigableMap<K,V> headMap(K toKey, boolean inclusive) {
        if(toKey == null)
            throw new NullPointerException();
        return new SubMap<K,V>(this, null, false, toKey, inclusive, false);
    }

    public ConcurrentNavigableMap<K,V> tailMap(K fromKey, boolean inclusive) {
        if(fromKey == null)
            throw new NullPointerException();
        return new SubMap<K,V>(this, fromKey, inclusive, null, false, false);
    }

    public ConcurrentNavigableMap<K,V> subMap(K fromKey, K toKey) {
        return subMap(fromKey, true, toKey, false);
    }

    public ConcurrentNavigableMap<K,V> headMap(K toKey) {
        return headMap(toKey, false);
    }

    public ConcurrentNavigableMap<K,V> tailMap(K fromKey) {
        return tailMap(fromKey, true);
    }

    /* ---------------- Relational operations -------------- */

    public Map.Entry<K,V> lowerEntry(K key) {
        return findNearEntry(key, LT, comparator);
    }

    public K lowerKey(K key) {
        Node<K,V> n = findNear(key, LT, comparator);
        return (n == null) ? null : n.key;
    }

    public Map.Entry<K,V> floorEntry(K key) {
        return findNearEntry(key, LT|EQ, comparator);
    }

    public K floorKey(K key) {
        Node<K,V> n = findNear(key, LT|EQ, comparator);
        return (n == null) ? null : n.key;
    }

    public Map.Entry<K,V> ceilingEntry(K key) {
        return findNearEntry(key, GT|EQ, comparator);
    }

    public K ceilingKey(K key) {
        Node<K,V> n = findNear(key, GT|EQ, comparator);
        return (n == null) ? null : n.key;
    }

    public Map.Entry<K,V> higherEntry(K key) {
        return findNearEntry(key, GT, comparator);
    }

    public K higherKey(K key) {
        Node<K,V> n = findNear(key, GT, comparator);
        return (n == null) ? null : n.key;
    }

    public Map.Entry<K,V> firstEntry() {
        return findFirstEntry();
    }

    public Map.Entry<K,V> lastEntry() {
        return findLastEntry();
    }

    public Map.Entry<K,V> pollFirstEntry() {
        return doRemoveFirstEntry();
    }

    public Map.Entry<K,V> pollLastEntry() {
        return doRemoveLastEntry();
    }

    /* ---------------- Iterators -------------- */

    abstract class Iter<T> implements Iterator<T> {
        /** the last node returned by next() */
        Node<K,V> lastReturned;
        /** the next node to return from next(); */
        Node<K,V> next;
        /** Cache of next value field to maintain weak consistency */
        V nextValue;

        /** Initializes ascending iterator for entire range. */
        Iter() {
            advance(baseHead());
        }

        public final boolean hasNext() {
            return next != null;
        }

        /** Advances next to higher entry. */
        final void advance(Node<K,V> b) {
            Node<K,V> n = null;
            V v = null;
            if ((lastReturned = b) != null) {
                while ((n = b.next) != null && (v = n.val) == null)
                    b = n;
            }
            nextValue = v;
            next = n;
        }

        public final void remove() {
            Node<K,V> n; K k;
            if ((n = lastReturned) == null || (k = n.key) == null)
                throw new IllegalStateException();
            // It would not be worth all of the overhead to directly
            // unlink from here. Using remove is fast enough.
            ConcurrentSkipListMap.this.remove(k);
            lastReturned = null;
        }
    }

    final class ValueIterator extends Iter<V> {
        public V next() {
            V v;
            if((v = nextValue) == null)
                throw new NoSuchElementException();
            advance(next);
            return v;
        }
    }

    final class KeyIterator extends Iter<K> {
        public K next() {
            Node<K,V> n;
            if((n = next) == null)
                throw new NoSuchElementException();
            K k = n.key;
            advance(n);
            return k;
        }
    }

    final class EntryIterator extends Iter<Entry<K,V>> {
        public Map.Entry<K,V> next() {
            Node<K,V> n;
            if((n = next) == null)
                throw new NoSuchElementException();
            K k = n.key;
            V v = nextValue;
            advance(n);
            return new AbstractMap.SimpleImmutableEntry<K,V>(k, v);
        }
    }

    /* ---------------- View Classes -------------- */

    static final <E> List<E> toList(Collection<E> c) {
        // Using size() here would be a pessimization.
        ArrayList<E> list = new ArrayList<E>();
        for(E e : c)
            list.add(e);
        return list;
    }

    static final class KeySet<K,V> extends AbstractSet<K> implements NavigableSet<K> {
        final ConcurrentNavigableMap<K,V> m;
        KeySet(ConcurrentNavigableMap<K,V> map) { m = map; }
        public int size() { return m.size(); }
        public boolean isEmpty() { return m.isEmpty(); }
        public boolean contains(Object o) { return m.containsKey(o); }
        public boolean remove(Object o) { return m.remove(o) != null; }
        public void clear() { m.clear(); }
        public K lower(K e) { return m.lowerKey(e); }
        public K floor(K e) { return m.floorKey(e); }
        public K ceiling(K e) { return m.ceilingKey(e); }
        public K higher(K e) { return m.higherKey(e); }
        public Comparator<? super K> comparator() { return m.comparator(); }
        public K first() { return m.firstKey(); }
        public K last() { return m.lastKey(); }
        public K pollFirst() {
            Map.Entry<K,V> e = m.pollFirstEntry();
            return (e == null) ? null : e.getKey();
        }
        public K pollLast() {
            Map.Entry<K,V> e = m.pollLastEntry();
            return (e == null) ? null : e.getKey();
        }
        public Iterator<K> iterator() {
            return (m instanceof ConcurrentSkipListMap)
                    ? ((ConcurrentSkipListMap<K,V>)m).new KeyIterator()
                    : ((SubMap<K,V>)m).new SubMapKeyIterator();
        }
        public boolean equals(Object o) {
            if (o == this)
                return true;
            if (!(o instanceof Set))
                return false;
            Collection<?> c = (Collection<?>) o;
            try {
                return containsAll(c) && c.containsAll(this);
            } catch (ClassCastException | NullPointerException unused) {
                return false;
            }
        }
        public Object[] toArray()     { return toList(this).toArray();  }
        public <T> T[] toArray(T[] a) { return toList(this).toArray(a); }
        public Iterator<K> descendingIterator() {
            return descendingSet().iterator();
        }
        public NavigableSet<K> subSet(K fromElement, boolean fromInclusive,
                                      K toElement, boolean toInclusive) {
            return new KeySet<>(m.subMap(fromElement, fromInclusive, toElement, toInclusive));
        }
        public NavigableSet<K> headSet(K toElement, boolean inclusive) {
            return new KeySet<>(m.headMap(toElement, inclusive));
        }
        public NavigableSet<K> tailSet(K fromElement, boolean inclusive) {
            return new KeySet<>(m.tailMap(fromElement, inclusive));
        }
        public NavigableSet<K> subSet(K fromElement, K toElement) {
            return subSet(fromElement, true, toElement, false);
        }
        public NavigableSet<K> headSet(K toElement) {
            return headSet(toElement, false);
        }
        public NavigableSet<K> tailSet(K fromElement) {
            return tailSet(fromElement, true);
        }
        public NavigableSet<K> descendingSet() {
            return new KeySet<>(m.descendingMap());
        }

        public Spliterator<K> spliterator() {
            return (m instanceof ConcurrentSkipListMap)
                    ? ((ConcurrentSkipListMap<K,V>)m).keySpliterator()
                    : ((SubMap<K,V>)m).new SubMapKeyIterator();
        }
    }

    static final class Values<K,V> extends AbstractCollection<V> {
        final ConcurrentNavigableMap<K,V> m;
        Values(ConcurrentNavigableMap<K,V> map) {
            m = map;
        }
        public Iterator<V> iterator() {
            return (m instanceof ConcurrentSkipListMap)
                    ? ((ConcurrentSkipListMap<K,V>)m).new ValueIterator()
                    : ((SubMap<K,V>)m).new SubMapValueIterator();
        }
        public int size() { return m.size(); }
        public boolean isEmpty() { return m.isEmpty(); }
        public boolean contains(Object o) { return m.containsValue(o); }
        public void clear() { m.clear(); }
        public Object[] toArray()     { return toList(this).toArray();  }
        public <T> T[] toArray(T[] a) { return toList(this).toArray(a); }

        public Spliterator<V> spliterator() {
            return (m instanceof ConcurrentSkipListMap)
                    ? ((ConcurrentSkipListMap<K,V>)m).valueSpliterator()
                    : ((SubMap<K,V>)m).new SubMapValueIterator();
        }

        public boolean removeIf(Predicate<? super V> filter) {
            if (filter == null)
                throw new NullPointerException();
            if (m instanceof ConcurrentSkipListMap)
                return ((ConcurrentSkipListMap<K,V>)m).removeValueIf(filter);
            // else use iterator
            Iterator<Map.Entry<K,V>> it = ((SubMap<K,V>)m).new SubMapEntryIterator();
            boolean removed = false;
            while (it.hasNext()) {
                Map.Entry<K,V> e = it.next();
                V v = e.getValue();
                if (filter.test(v) && m.remove(e.getKey(), v))
                    removed = true;
            }
            return removed;
        }
    }

    static final class EntrySet<K,V> extends AbstractSet<Map.Entry<K,V>> {
        final ConcurrentNavigableMap<K,V> m;
        EntrySet(ConcurrentNavigableMap<K,V> map) {
            m = map;
        }
        public Iterator<Map.Entry<K,V>> iterator() {
            return (m instanceof ConcurrentSkipListMap)
                    ? ((ConcurrentSkipListMap<K,V>)m).new EntryIterator()
                    : ((SubMap<K,V>)m).new SubMapEntryIterator();
        }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?,?> e = (Map.Entry<?,?>)o;
            V v = m.get(e.getKey());
            return v != null && v.equals(e.getValue());
        }
        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?,?> e = (Map.Entry<?,?>)o;
            return m.remove(e.getKey(), e.getValue());
        }
        public boolean isEmpty() {
            return m.isEmpty();
        }
        public int size() {
            return m.size();
        }
        public void clear() {
            m.clear();
        }
        public boolean equals(Object o) {
            if (o == this)
                return true;
            if (!(o instanceof Set))
                return false;
            Collection<?> c = (Collection<?>) o;
            try {
                return containsAll(c) && c.containsAll(this);
            } catch (ClassCastException | NullPointerException unused) {
                return false;
            }
        }
        public Object[] toArray()     { return toList(this).toArray();  }
        public <T> T[] toArray(T[] a) { return toList(this).toArray(a); }

        public Spliterator<Map.Entry<K,V>> spliterator() {
            return (m instanceof ConcurrentSkipListMap)
                    ? ((ConcurrentSkipListMap<K,V>)m).entrySpliterator()
                    : ((SubMap<K,V>)m).new SubMapEntryIterator();
        }
        public boolean removeIf(Predicate<? super Entry<K,V>> filter) {
            if (filter == null) throw new NullPointerException();
            if (m instanceof ConcurrentSkipListMap)
                return ((ConcurrentSkipListMap<K,V>)m).removeEntryIf(filter);
            // else use iterator
            Iterator<Map.Entry<K,V>> it = ((SubMap<K,V>)m).new SubMapEntryIterator();
            boolean removed = false;
            while (it.hasNext()) {
                Map.Entry<K,V> e = it.next();
                if (filter.test(e) && m.remove(e.getKey(), e.getValue()))
                    removed = true;
            }
            return removed;
        }
    }

    static final class SubMap<K,V> extends AbstractMap<K,V> implements ConcurrentNavigableMap<K,V>, Serializable {
        private static final long serialVersionUID = -7647078645895051609L;

        /** Underlying map */
        final java.util.concurrent.ConcurrentSkipListMap<K,V> m;
        /** lower bound key, or null if from start */
        @SuppressWarnings("serial") // Conditionally serializable
        private final K lo;
        /** upper bound key, or null if to end */
        @SuppressWarnings("serial") // Conditionally serializable
        private final K hi;
        /** inclusion flag for lo */
        private final boolean loInclusive;
        /** inclusion flag for hi */
        private final boolean hiInclusive;
        /** direction */
        final boolean isDescending;

        // Lazily initialized view holders
        private transient KeySet<K,V> keySetView;
        private transient Values<K,V> valuesView;
        private transient EntrySet<K,V> entrySetView;

        /**
         * Creates a new submap, initializing all fields.
         */
        SubMap(java.util.concurrent.ConcurrentSkipListMap<K,V> map,
               K fromKey, boolean fromInclusive,
               K toKey, boolean toInclusive,
               boolean isDescending) {
            Comparator<? super K> cmp = map.comparator;
            if (fromKey != null && toKey != null &&
                    cpr(cmp, fromKey, toKey) > 0)
                throw new IllegalArgumentException("inconsistent range");
            this.m = map;
            this.lo = fromKey;
            this.hi = toKey;
            this.loInclusive = fromInclusive;
            this.hiInclusive = toInclusive;
            this.isDescending = isDescending;
        }

        /* ----------------  Utilities -------------- */

        boolean tooLow(Object key, Comparator<? super K> cmp) {
            int c;
            return (lo != null && ((c = cpr(cmp, key, lo)) < 0 ||
                    (c == 0 && !loInclusive)));
        }

        boolean tooHigh(Object key, Comparator<? super K> cmp) {
            int c;
            return (hi != null && ((c = cpr(cmp, key, hi)) > 0 ||
                    (c == 0 && !hiInclusive)));
        }

        boolean inBounds(Object key, Comparator<? super K> cmp) {
            return !tooLow(key, cmp) && !tooHigh(key, cmp);
        }

        void checkKeyBounds(K key, Comparator<? super K> cmp) {
            if (key == null)
                throw new NullPointerException();
            if (!inBounds(key, cmp))
                throw new IllegalArgumentException("key out of range");
        }

        /**
         * Returns true if node key is less than upper bound of range.
         */
        boolean isBeforeEnd(Node<K,V> n, Comparator<? super K> cmp) {
            if (n == null)
                return false;
            if (hi == null)
                return true;
            K k = n.key;
            if (k == null) // pass by markers and headers
                return true;
            int c = cpr(cmp, k, hi);
            return c < 0 || (c == 0 && hiInclusive);
        }

        /**
         * Returns lowest node. This node might not be in range, so
         * most usages need to check bounds.
         */
        Node<K,V> loNode(Comparator<? super K> cmp) {
            if (lo == null)
                return m.findFirst();
            else if (loInclusive)
                return m.findNear(lo, GT|EQ, cmp);
            else
                return m.findNear(lo, GT, cmp);
        }

        /**
         * Returns highest node. This node might not be in range, so
         * most usages need to check bounds.
         */
        Node<K,V> hiNode(Comparator<? super K> cmp) {
            if (hi == null)
                return m.findLast();
            else if (hiInclusive)
                return m.findNear(hi, LT|EQ, cmp);
            else
                return m.findNear(hi, LT, cmp);
        }

        /**
         * Returns lowest absolute key (ignoring directionality).
         */
        K lowestKey() {
            Comparator<? super K> cmp = m.comparator;
            Node<K,V> n = loNode(cmp);
            if (isBeforeEnd(n, cmp))
                return n.key;
            else
                throw new NoSuchElementException();
        }

        /**
         * Returns highest absolute key (ignoring directionality).
         */
        K highestKey() {
            Comparator<? super K> cmp = m.comparator;
            Node<K,V> n = hiNode(cmp);
            if (n != null) {
                K last = n.key;
                if (inBounds(last, cmp))
                    return last;
            }
            throw new NoSuchElementException();
        }

        Map.Entry<K,V> lowestEntry() {
            Comparator<? super K> cmp = m.comparator;
            for (;;) {
                Node<K,V> n; V v;
                if ((n = loNode(cmp)) == null || !isBeforeEnd(n, cmp))
                    return null;
                else if ((v = n.val) != null)
                    return new AbstractMap.SimpleImmutableEntry<>(n.key, v);
            }
        }

        Map.Entry<K,V> highestEntry() {
            Comparator<? super K> cmp = m.comparator;
            for (;;) {
                Node<K,V> n; V v;
                if ((n = hiNode(cmp)) == null || !inBounds(n.key, cmp))
                    return null;
                else if ((v = n.val) != null)
                    return new AbstractMap.SimpleImmutableEntry<>(n.key, v);
            }
        }

        Map.Entry<K,V> removeLowest() {
            Comparator<? super K> cmp = m.comparator;
            for (;;) {
                Node<K,V> n; K k; V v;
                if ((n = loNode(cmp)) == null)
                    return null;
                else if (!inBounds((k = n.key), cmp))
                    return null;
                else if ((v = m.doRemove(k, null)) != null)
                    return new AbstractMap.SimpleImmutableEntry<K,V>(k, v);
            }
        }

        Map.Entry<K,V> removeHighest() {
            Comparator<? super K> cmp = m.comparator;
            for (;;) {
                Node<K,V> n; K k; V v;
                if ((n = hiNode(cmp)) == null)
                    return null;
                else if (!inBounds((k = n.key), cmp))
                    return null;
                else if ((v = m.doRemove(k, null)) != null)
                    return new AbstractMap.SimpleImmutableEntry<K,V>(k, v);
            }
        }

        /**
         * Submap version of ConcurrentSkipListMap.findNearEntry.
         */
        Map.Entry<K,V> getNearEntry(K key, int rel) {
            Comparator<? super K> cmp = m.comparator;
            if (isDescending) { // adjust relation for direction
                if ((rel & LT) == 0)
                    rel |= LT;
                else
                    rel &= ~LT;
            }
            if (tooLow(key, cmp))
                return ((rel & LT) != 0) ? null : lowestEntry();
            if (tooHigh(key, cmp))
                return ((rel & LT) != 0) ? highestEntry() : null;
            AbstractMap.SimpleImmutableEntry<K,V> e = m.findNearEntry(key, rel, cmp);
            if (e == null || !inBounds(e.getKey(), cmp))
                return null;
            else
                return e;
        }

        // Almost the same as getNearEntry, except for keys
        K getNearKey(K key, int rel) {
            Comparator<? super K> cmp = m.comparator;
            if (isDescending) { // adjust relation for direction
                if ((rel & LT) == 0)
                    rel |= LT;
                else
                    rel &= ~LT;
            }
            if (tooLow(key, cmp)) {
                if ((rel & LT) == 0) {
                    Node<K,V> n = loNode(cmp);
                    if (isBeforeEnd(n, cmp))
                        return n.key;
                }
                return null;
            }
            if (tooHigh(key, cmp)) {
                if ((rel & LT) != 0) {
                    Node<K,V> n = hiNode(cmp);
                    if (n != null) {
                        K last = n.key;
                        if (inBounds(last, cmp))
                            return last;
                    }
                }
                return null;
            }
            for (;;) {
                Node<K,V> n = m.findNear(key, rel, cmp);
                if (n == null || !inBounds(n.key, cmp))
                    return null;
                if (n.val != null)
                    return n.key;
            }
        }

        /* ----------------  Map API methods -------------- */

        public boolean containsKey(Object key) {
            if(key == null)
                throw new NullPointerException();
            return inBounds(key, m.comparator) && m.containsKey(key);
        }

        public V get(Object key) {
            if(key == null)
                throw new NullPointerException();
            return (!inBounds(key, m.comparator)) ? null : m.get(key);
        }

        public V put(K key, V value) {
            checkKeyBounds(key, m.comparator);
            return m.put(key, value);
        }

        public V remove(Object key) {
            return (!inBounds(key, m.comparator)) ? null : m.remove(key);
        }

        public int size() {
            Comparator<? super K> cmp = m.comparator;
            long count = 0;
            for (Node<K,V> n = loNode(cmp);
                 isBeforeEnd(n, cmp);
                 n = n.next) {
                if (n.val != null)
                    ++count;
            }
            return count >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)count;
        }

        public boolean isEmpty() {
            Comparator<? super K> cmp = m.comparator;
            return !isBeforeEnd(loNode(cmp), cmp);
        }

        public boolean containsValue(Object value) {
            if (value == null)
                throw new NullPointerException();
            Comparator<? super K> cmp = m.comparator;
            for (Node<K,V> n = loNode(cmp);
                 isBeforeEnd(n, cmp);
                 n = n.next) {
                V v = n.val;
                if (v != null && value.equals(v))
                    return true;
            }
            return false;
        }

        public void clear() {
            Comparator<? super K> cmp = m.comparator;
            for (Node<K,V> n = loNode(cmp);
                 isBeforeEnd(n, cmp);
                 n = n.next) {
                if (n.val != null)
                    m.remove(n.key);
            }
        }

        /* ----------------  ConcurrentMap API methods -------------- */

        public V putIfAbsent(K key, V value) {
            checkKeyBounds(key, m.comparator);
            return m.putIfAbsent(key, value);
        }

        public boolean remove(Object key, Object value) {
            return inBounds(key, m.comparator) && m.remove(key, value);
        }

        public boolean replace(K key, V oldValue, V newValue) {
            checkKeyBounds(key, m.comparator);
            return m.replace(key, oldValue, newValue);
        }

        public V replace(K key, V value) {
            checkKeyBounds(key, m.comparator);
            return m.replace(key, value);
        }

        /* ----------------  SortedMap API methods -------------- */

        public Comparator<? super K> comparator() {
            Comparator<? super K> cmp = m.comparator();
            if (isDescending)
                return Collections.reverseOrder(cmp);
            else
                return cmp;
        }

        /**
         * Utility to create submaps, where given bounds override
         * unbounded(null) ones and/or are checked against bounded ones.
         */
        SubMap<K,V> newSubMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            Comparator<? super K> cmp = m.comparator;
            if (isDescending) { // flip senses
                K tk = fromKey;
                fromKey = toKey;
                toKey = tk;
                boolean ti = fromInclusive;
                fromInclusive = toInclusive;
                toInclusive = ti;
            }
            if (lo != null) {
                if (fromKey == null) {
                    fromKey = lo;
                    fromInclusive = loInclusive;
                }
                else {
                    int c = cpr(cmp, fromKey, lo);
                    if (c < 0 || (c == 0 && !loInclusive && fromInclusive))
                        throw new IllegalArgumentException("key out of range");
                }
            }
            if (hi != null) {
                if (toKey == null) {
                    toKey = hi;
                    toInclusive = hiInclusive;
                }
                else {
                    int c = cpr(cmp, toKey, hi);
                    if (c > 0 || (c == 0 && !hiInclusive && toInclusive))
                        throw new IllegalArgumentException("key out of range");
                }
            }
            return new SubMap<>(m, fromKey, fromInclusive, toKey, toInclusive, isDescending);
        }

        public SubMap<K,V> subMap(K fromKey, boolean fromInclusive,
                                  K toKey, boolean toInclusive) {
            if (fromKey == null || toKey == null)
                throw new NullPointerException();
            return newSubMap(fromKey, fromInclusive, toKey, toInclusive);
        }

        public SubMap<K,V> headMap(K toKey, boolean inclusive) {
            if (toKey == null)
                throw new NullPointerException();
            return newSubMap(null, false, toKey, inclusive);
        }

        public SubMap<K,V> tailMap(K fromKey, boolean inclusive) {
            if (fromKey == null)
                throw new NullPointerException();
            return newSubMap(fromKey, inclusive, null, false);
        }

        public SubMap<K,V> subMap(K fromKey, K toKey) {
            return subMap(fromKey, true, toKey, false);
        }

        public SubMap<K,V> headMap(K toKey) {
            return headMap(toKey, false);
        }

        public SubMap<K,V> tailMap(K fromKey) {
            return tailMap(fromKey, true);
        }

        public SubMap<K,V> descendingMap() {
            return new SubMap<>(m, lo, loInclusive, hi, hiInclusive, !isDescending);
        }

        /* ----------------  Relational methods -------------- */

        public Map.Entry<K,V> ceilingEntry(K key) {
            return getNearEntry(key, GT|EQ);
        }

        public K ceilingKey(K key) {
            return getNearKey(key, GT|EQ);
        }

        public Map.Entry<K,V> lowerEntry(K key) {
            return getNearEntry(key, LT);
        }

        public K lowerKey(K key) {
            return getNearKey(key, LT);
        }

        public Map.Entry<K,V> floorEntry(K key) {
            return getNearEntry(key, LT|EQ);
        }

        public K floorKey(K key) {
            return getNearKey(key, LT|EQ);
        }

        public Map.Entry<K,V> higherEntry(K key) {
            return getNearEntry(key, GT);
        }

        public K higherKey(K key) {
            return getNearKey(key, GT);
        }

        public K firstKey() {
            return isDescending ? highestKey() : lowestKey();
        }

        public K lastKey() {
            return isDescending ? lowestKey() : highestKey();
        }

        public Map.Entry<K,V> firstEntry() {
            return isDescending ? highestEntry() : lowestEntry();
        }

        public Map.Entry<K,V> lastEntry() {
            return isDescending ? lowestEntry() : highestEntry();
        }

        public Map.Entry<K,V> pollFirstEntry() {
            return isDescending ? removeHighest() : removeLowest();
        }

        public Map.Entry<K,V> pollLastEntry() {
            return isDescending ? removeLowest() : removeHighest();
        }

        /* ---------------- Submap Views -------------- */

        public NavigableSet<K> keySet() {
            KeySet<K,V> ks;
            if((ks = keySetView) != null)
                return ks;
            return keySetView = new KeySet<>(this);
        }

        public NavigableSet<K> navigableKeySet() {
            KeySet<K,V> ks;
            if((ks = keySetView) != null)
                return ks;
            return keySetView = new KeySet<>(this);
        }

        public Collection<V> values() {
            Values<K,V> vs;
            if((vs = valuesView) != null)
                return vs;
            return valuesView = new Values<>(this);
        }

        public Set<Map.Entry<K,V>> entrySet() {
            EntrySet<K,V> es;
            if((es = entrySetView) != null)
                return es;
            return entrySetView = new EntrySet<>(this);
        }

        public NavigableSet<K> descendingKeySet() {
            return descendingMap().navigableKeySet();
        }

        /**
         * Variant of main Iter class to traverse through submaps.
         * Also serves as back-up Spliterator for views.
         */
        abstract class SubMapIter<T> implements Iterator<T>, Spliterator<T> {
            /** the last node returned by next() */
            Node<K,V> lastReturned;
            /** the next node to return from next(); */
            Node<K,V> next;
            /** Cache of next value field to maintain weak consistency */
            V nextValue;

            SubMapIter() {
                VarHandle.acquireFence();
                Comparator<? super K> cmp = m.comparator;
                for (;;) {
                    next = isDescending ? hiNode(cmp) : loNode(cmp);
                    if (next == null)
                        break;
                    V x = next.val;
                    if (x != null) {
                        if (! inBounds(next.key, cmp))
                            next = null;
                        else
                            nextValue = x;
                        break;
                    }
                }
            }

            public final boolean hasNext() {
                return next != null;
            }

            final void advance() {
                if (next == null)
                    throw new NoSuchElementException();
                lastReturned = next;
                if (isDescending)
                    descend();
                else
                    ascend();
            }

            private void ascend() {
                Comparator<? super K> cmp = m.comparator;
                for (;;) {
                    next = next.next;
                    if (next == null)
                        break;
                    V x = next.val;
                    if (x != null) {
                        if (tooHigh(next.key, cmp))
                            next = null;
                        else
                            nextValue = x;
                        break;
                    }
                }
            }

            private void descend() {
                Comparator<? super K> cmp = m.comparator;
                for (;;) {
                    next = m.findNear(lastReturned.key, LT, cmp);
                    if (next == null)
                        break;
                    V x = next.val;
                    if (x != null) {
                        if (tooLow(next.key, cmp))
                            next = null;
                        else
                            nextValue = x;
                        break;
                    }
                }
            }

            public void remove() {
                Node<K,V> l = lastReturned;
                if (l == null)
                    throw new IllegalStateException();
                m.remove(l.key);
                lastReturned = null;
            }

            public Spliterator<T> trySplit() {
                return null;
            }

            public boolean tryAdvance(Consumer<? super T> action) {
                if (hasNext()) {
                    action.accept(next());
                    return true;
                }
                return false;
            }

            public void forEachRemaining(Consumer<? super T> action) {
                while (hasNext())
                    action.accept(next());
            }

            public long estimateSize() {
                return Long.MAX_VALUE;
            }

        }

        final class SubMapValueIterator extends SubMap.SubMapIter<V> {
            public V next() {
                V v = nextValue;
                advance();
                return v;
            }
            public int characteristics() {
                return 0;
            }
        }

        final class SubMapKeyIterator extends SubMap.SubMapIter<K> {
            public K next() {
                Node<K,V> n = next;
                advance();
                return n.key;
            }
            public int characteristics() {
                return Spliterator.DISTINCT | Spliterator.ORDERED |
                        Spliterator.SORTED;
            }
            public final Comparator<? super K> getComparator() {
                return SubMap.this.comparator();
            }
        }

        final class SubMapEntryIterator extends SubMap.SubMapIter<Entry<K,V>> {
            public Map.Entry<K,V> next() {
                Node<K,V> n = next;
                V v = nextValue;
                advance();
                return new AbstractMap.SimpleImmutableEntry<>(n.key, v);
            }
            public int characteristics() {
                return Spliterator.DISTINCT;
            }
        }
    }

    // default Map method overrides

    public void forEach(BiConsumer<? super K, ? super V> action) {
        if (action == null) throw new NullPointerException();
        Node<K,V> b, n; V v;
        if ((b = baseHead()) != null) {
            while ((n = b.next) != null) {
                if ((v = n.val) != null)
                    action.accept(n.key, v);
                b = n;
            }
        }
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        if (function == null) throw new NullPointerException();
        Node<K,V> b, n; V v;
        if ((b = baseHead()) != null) {
            while ((n = b.next) != null) {
                while ((v = n.val) != null) {
                    V r = function.apply(n.key, v);
                    if (r == null)
                        throw new NullPointerException();
                    if (VAL.compareAndSet(n, v, r))
                        break;
                }
                b = n;
            }
        }
    }

    boolean removeEntryIf(Predicate<? super Entry<K,V>> function) {
        if (function == null)
            throw new NullPointerException();
        boolean removed = false;
        Node<K,V> b, n; V v;
        if ((b = baseHead()) != null) {
            while ((n = b.next) != null) {
                if ((v = n.val) != null) {
                    K k = n.key;
                    Map.Entry<K,V> e = new AbstractMap.SimpleImmutableEntry<>(k, v);
                    if (function.test(e) && remove(k, v))
                        removed = true;
                }
                b = n;
            }
        }
        return removed;
    }

    boolean removeValueIf(Predicate<? super V> function) {
        if (function == null)
            throw new NullPointerException();
        boolean removed = false;
        Node<K,V> b, n; V v;
        if ((b = baseHead()) != null) {
            while ((n = b.next) != null) {
                if ((v = n.val) != null && function.test(v) && remove(n.key, v))
                    removed = true;
                b = n;
            }
        }
        return removed;
    }

    abstract static class CSLMSpliterator<K,V> {
        final Comparator<? super K> comparator;
        final K fence;     // exclusive upper bound for keys, or null if to end
        Index<K,V> row;    // the level to split out
        Node<K,V> current; // current traversal node; initialize at origin
        long est;          // size estimate
        CSLMSpliterator(Comparator<? super K> comparator, Index<K,V> row, Node<K,V> origin, K fence, long est) {
            this.comparator = comparator; this.row = row;
            this.current = origin; this.fence = fence; this.est = est;
        }

        public final long estimateSize() {
            return est;
        }
    }

    static final class KeySpliterator<K,V> extends CSLMSpliterator<K,V>
            implements Spliterator<K> {
        KeySpliterator(Comparator<? super K> comparator, Index<K,V> row,
                       Node<K,V> origin, K fence, long est) {
            super(comparator, row, origin, fence, est);
        }

        public KeySpliterator<K,V> trySplit() {
            Node<K,V> e; K ek;
            Comparator<? super K> cmp = comparator;
            K f = fence;
            if ((e = current) != null && (ek = e.key) != null) {
                for (Index<K,V> q = row; q != null; q = row = q.down) {
                    Index<K,V> s; Node<K,V> b, n; K sk;
                    if ((s = q.right) != null && (b = s.node) != null &&
                            (n = b.next) != null && n.val != null &&
                            (sk = n.key) != null && cpr(cmp, sk, ek) > 0 &&
                            (f == null || cpr(cmp, sk, f) < 0)) {
                        current = n;
                        Index<K,V> r = q.down;
                        row = (s.right != null) ? s : s.down;
                        est -= est >>> 2;
                        return new KeySpliterator<K,V>(cmp, r, e, sk, est);
                    }
                }
            }
            return null;
        }

        public void forEachRemaining(Consumer<? super K> action) {
            if (action == null)
                throw new NullPointerException();
            Comparator<? super K> cmp = comparator;
            K f = fence;
            Node<K,V> e = current;
            current = null;
            for (; e != null; e = e.next) {
                K k;
                if ((k = e.key) != null && f != null && cpr(cmp, f, k) <= 0)
                    break;
                if (e.val != null)
                    action.accept(k);
            }
        }

        public boolean tryAdvance(Consumer<? super K> action) {
            if (action == null) throw new NullPointerException();
            Comparator<? super K> cmp = comparator;
            K f = fence;
            Node<K,V> e = current;
            for (; e != null; e = e.next) {
                K k;
                if ((k = e.key) != null && f != null && cpr(cmp, f, k) <= 0) {
                    e = null;
                    break;
                }
                if (e.val != null) {
                    current = e.next;
                    action.accept(k);
                    return true;
                }
            }
            current = e;
            return false;
        }

        public int characteristics() {
            return Spliterator.DISTINCT | Spliterator.SORTED | Spliterator.ORDERED | Spliterator.CONCURRENT | Spliterator.NONNULL;
        }

        public final Comparator<? super K> getComparator() {
            return comparator;
        }
    }

    // factory method for KeySpliterator

    final KeySpliterator<K,V> keySpliterator() {
        Index<K,V> h; Node<K,V> n; long est;
        VarHandle.acquireFence();
        if ((h = head) == null) {
            n = null;
            est = 0L;
        }
        else {
            n = h.node;
            est = getAdderCount();
        }
        return new KeySpliterator<K,V>(comparator, h, n, null, est);
    }

    static final class ValueSpliterator<K,V> extends CSLMSpliterator<K,V> implements Spliterator<V> {
        ValueSpliterator(Comparator<? super K> comparator, Index<K,V> row, Node<K,V> origin, K fence, long est) {
            super(comparator, row, origin, fence, est);
        }

        public ValueSpliterator<K,V> trySplit() {
            Node<K,V> e; K ek;
            Comparator<? super K> cmp = comparator;
            K f = fence;
            if ((e = current) != null && (ek = e.key) != null) {
                for (Index<K,V> q = row; q != null; q = row = q.down) {
                    Index<K,V> s; Node<K,V> b, n; K sk;
                    if ((s = q.right) != null && (b = s.node) != null &&
                            (n = b.next) != null && n.val != null &&
                            (sk = n.key) != null && cpr(cmp, sk, ek) > 0 &&
                            (f == null || cpr(cmp, sk, f) < 0)) {
                        current = n;
                        Index<K,V> r = q.down;
                        row = (s.right != null) ? s : s.down;
                        est -= est >>> 2;
                        return new ValueSpliterator<K,V>(cmp, r, e, sk, est);
                    }
                }
            }
            return null;
        }

        public void forEachRemaining(Consumer<? super V> action) {
            if (action == null)
                throw new NullPointerException();
            Comparator<? super K> cmp = comparator;
            K f = fence;
            Node<K,V> e = current;
            current = null;
            for (; e != null; e = e.next) {
                K k; V v;
                if ((k = e.key) != null && f != null && cpr(cmp, f, k) <= 0)
                    break;
                if ((v = e.val) != null)
                    action.accept(v);
            }
        }

        public boolean tryAdvance(Consumer<? super V> action) {
            if (action == null)
                throw new NullPointerException();
            Comparator<? super K> cmp = comparator;
            K f = fence;
            Node<K,V> e = current;
            for (; e != null; e = e.next) {
                K k; V v;
                if ((k = e.key) != null && f != null && cpr(cmp, f, k) <= 0) {
                    e = null;
                    break;
                }
                if ((v = e.val) != null) {
                    current = e.next;
                    action.accept(v);
                    return true;
                }
            }
            current = e;
            return false;
        }

        public int characteristics() {
            return Spliterator.CONCURRENT | Spliterator.ORDERED |
                    Spliterator.NONNULL;
        }
    }

    // Almost the same as keySpliterator()

    final ValueSpliterator<K,V> valueSpliterator() {
        Index<K,V> h; Node<K,V> n; long est;
        VarHandle.acquireFence();
        if ((h = head) == null) {
            n = null;
            est = 0L;
        }
        else {
            n = h.node;
            est = getAdderCount();
        }
        return new ValueSpliterator<>(comparator, h, n, null, est);
    }

    static final class EntrySpliterator<K,V> extends CSLMSpliterator<K,V> implements Spliterator<Map.Entry<K,V>> {
        EntrySpliterator(Comparator<? super K> comparator, Index<K,V> row, Node<K,V> origin, K fence, long est) {
            super(comparator, row, origin, fence, est);
        }

        public EntrySpliterator<K,V> trySplit() {
            Node<K,V> e; K ek;
            Comparator<? super K> cmp = comparator;
            K f = fence;
            if ((e = current) != null && (ek = e.key) != null) {
                for (Index<K,V> q = row; q != null; q = row = q.down) {
                    Index<K,V> s; Node<K,V> b, n; K sk;
                    if ((s = q.right) != null && (b = s.node) != null &&
                            (n = b.next) != null && n.val != null &&
                            (sk = n.key) != null && cpr(cmp, sk, ek) > 0 &&
                            (f == null || cpr(cmp, sk, f) < 0)) {
                        current = n;
                        Index<K,V> r = q.down;
                        row = (s.right != null) ? s : s.down;
                        est -= est >>> 2;
                        return new EntrySpliterator<K,V>(cmp, r, e, sk, est);
                    }
                }
            }
            return null;
        }

        public void forEachRemaining(Consumer<? super Map.Entry<K,V>> action) {
            if (action == null)
                throw new NullPointerException();
            Comparator<? super K> cmp = comparator;
            K f = fence;
            Node<K,V> e = current;
            current = null;
            for (; e != null; e = e.next) {
                K k; V v;
                if ((k = e.key) != null && f != null && cpr(cmp, f, k) <= 0)
                    break;
                if ((v = e.val) != null) {
                    action.accept(new AbstractMap.SimpleImmutableEntry<K,V>(k, v));
                }
            }
        }

        public boolean tryAdvance(Consumer<? super Map.Entry<K,V>> action) {
            if (action == null)
                throw new NullPointerException();
            Comparator<? super K> cmp = comparator;
            K f = fence;
            Node<K,V> e = current;
            for (; e != null; e = e.next) {
                K k; V v;
                if ((k = e.key) != null && f != null && cpr(cmp, f, k) <= 0) {
                    e = null;
                    break;
                }
                if ((v = e.val) != null) {
                    current = e.next;
                    action.accept
                            (new AbstractMap.SimpleImmutableEntry<>(k, v));
                    return true;
                }
            }
            current = e;
            return false;
        }

        public int characteristics() {
            return Spliterator.DISTINCT | Spliterator.SORTED | Spliterator.ORDERED | Spliterator.CONCURRENT | Spliterator.NONNULL;
        }

        public final Comparator<Map.Entry<K,V>> getComparator() {
            // Adapt or create a key-based comparator
            if (comparator != null) {
                return Map.Entry.comparingByKey(comparator);
            }
            else {
                return (Comparator<Map.Entry<K,V>> & Serializable) (e1, e2) -> {
                    @SuppressWarnings("unchecked")
                    Comparable<? super K> k1 = (Comparable<? super K>) e1.getKey();
                    return k1.compareTo(e2.getKey());
                };
            }
        }
    }

    // Almost the same as keySpliterator()

    final EntrySpliterator<K,V> entrySpliterator() {
        Index<K,V> h; Node<K,V> n; long est;
        VarHandle.acquireFence();
        if ((h = head) == null) {
            n = null;
            est = 0L;
        }
        else {
            n = h.node;
            est = getAdderCount();
        }
        return new EntrySpliterator<>(comparator, h, n, null, est);
    }

    // VarHandle mechanics
    private static final VarHandle HEAD;
    private static final VarHandle ADDER;
    private static final VarHandle NEXT;
    private static final VarHandle VAL;
    private static final VarHandle RIGHT;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            HEAD = l.findVarHandle(ConcurrentSkipListMap.class, "head", Index.class);
            ADDER = l.findVarHandle(ConcurrentSkipListMap.class, "adder", LongAdder.class);
            NEXT = l.findVarHandle(Node.class, "next", Node.class);
            VAL = l.findVarHandle(Node.class, "val", Object.class);
            RIGHT = l.findVarHandle(Index.class, "right", Index.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

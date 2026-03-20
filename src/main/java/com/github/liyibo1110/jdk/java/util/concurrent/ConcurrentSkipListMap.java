package com.github.liyibo1110.jdk.java.util.concurrent;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.atomic.LongAdder;

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

    private V doGet(Object key) {
        
    }

    /* ---------------- Insertion -------------- */









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

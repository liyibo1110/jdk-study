package com.github.liyibo1110.jdk.java.util.concurrent;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.Helpers;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * LinkedBlockingQueue的兄弟类，特点如下：
 * 1、完全无锁：CAS + volatile，基于Michael-Scott无锁队列（MS Queue）
 * 2、永不阻塞：只有offer和poll。
 * 3、无界队列（不能设为有界，因此用不好会有OOM的风险）。
 * 4、弱一致性更强（size不准确，iterator不精确，poll可能看到null）。
 * 5、线程不会停，只会重试。
 * 本质是用复杂性换吞吐和并发能力，但ConcurrentLinkedQueue不能用于生产者-消费者阻塞模型。
 *
 * 需要注意的特点：
 * 1、tail可能不是最后一个节点（只是个hint）。
 * 2、head可能指向已删除的节点（item为null的节点还在链表里）。
 * 3、poll可能返回null，但队列其实并不为空（因为中间节点已被删除，但还没修复）。
 *
 * 性能对比：
 * 1、LinkedBlockingQueue在低并发里更稳定，在高并发里锁竞争会明显。
 * 2、ConcurrentLinkedQueue在低并发略慢（CAS开销），在高并发里优势很大。
 * @author liyibo
 * @date 2026-03-18 22:11
 */
public class ConcurrentLinkedQueue<E> extends AbstractQueue<E> implements Queue<E>, Serializable {
    private static final long serialVersionUID = 196745693267521676L;

    static final class Node<E> {
        volatile E item;
        volatile Node<E> next;

        Node(E item) {
            ITEM.set(this, item);
        }

        /**
         * dummy node
         */
        Node() {}

        /**
         * 方法名里面的relaxed代表：非严格同步，因为调用这个方法，没有并发问题，没有CAS，也没有竞争问题，
         * 所以可以用更轻量的写操作。
         *
         */
        void appendRelaxed(Node<E> next) {
            // assert next != null;
            // assert this.next == null;
            NEXT.set(this, next);
        }

        boolean casItem(E cmp, E val) {
            // assert item == cmp || item == null;
            // assert cmp != null;
            // assert val == null;
            return ITEM.compareAndSet(this, cmp, val);
        }
    }

    /**
     * 注意和LBQ的head不一样，CLQ的head可能是dummy，也可能是正常数据，可以理解成：一个尽量靠前的起点，而不是严格的哨兵节点。
     */
    transient volatile Node<E> head;

    private transient volatile Node<E> tail;

    public ConcurrentLinkedQueue() {
        head = tail = new Node<>();
    }

    public ConcurrentLinkedQueue(Collection<? extends E> c) {
        Node<E> h = null;
        Node<E> t = null;
        for(E e : c) {
            Node<E> newNode = new Node<>(Objects.requireNonNull(e));
            if(h == null)
                h = t = newNode;
            else
                t.appendRelaxed(t = newNode);  // 注意实际是先调用了t.appendRelaxed(newNode);然后t = newNode;
        }
        if(h == null)
            h = t = new Node<>();
        head = h;
        tail = t;
    }

    public boolean add(E e) {
        return offer(e);
    }

    /**
     * 用来减少废弃节点的链长度
     */
    final void updateHead(Node<E> h, Node<E> p) {
        // assert h != null && p != null && (h == p || h.item == null);
        /**
         * if里面这句等同于h.next = h; release内存语义就是：我之前的所有写操作，对其它线程可见，如果不这样就会：
         * 线程A：
         * head = p; （已经对外可见）
         * h.next = h;  （还没刷新）
         * 线程B：
         * 看到了新的head是p，但还是看到了旧的h.next指向了原来的p，会导致链表结构部分可见。
         * 同时这样用，比直接用volatile关键字更轻量
         * 最后这个h.next = h的作用，和LBQ是差不多的：
         * 1、帮助GC。
         * 2、作为已废弃节点的标记。
         * 后面会有类似这样的判断：if(p == p.next)，这样表示这个节点已经出队了，即被丢弃。
         * 3、避免遍历死循环，配合下面的succ和条约逻辑来使用。
         * 不直接写成h.next = null的原因是：这样就无法区分，是尾节点还是已移除节点了。
         */
        if(h != p && HEAD.compareAndSet(this, h, p))    // head指向p
            NEXT.setRelease(h, h);

    }

    /**
     * 和LBQ方法一样
     */
    final Node<E> succ(Node<E> p) {
        if(p == (p = p.next))
            p = head;
        return p;
    }

    /**
     * 跳过dead区间：CAS(pred.next, c, p)
     */
    private boolean tryCasSuccessor(Node<E> pred, Node<E> c, Node<E> p) {
        // assert p != null;
        // assert c.item == null;
        // assert c != p;
        if(pred != null)
            return NEXT.compareAndSet(pred, c, p);
        if(HEAD.compareAndSet(this, c, p)) {
            NEXT.setRelease(c, c);
            return true;
        }
        return false;
    }

    /**
     * pred：前一个活节点。
     * c：dead区间的起点。
     * p：dead区间的当前扫描节点。
     * q：dead区间后的第一个活节点。
     * 功能是把pred -> c -> ... -> p -> q变成pred -> q，其中c到p都是空节点
     */
    private Node<E> skipDeadNodes(Node<E> pred, Node<E> c, Node<E> p, Node<E> q) {
        // assert pred != c;
        // assert p != q;
        // assert c.item == null;
        // assert p.item == null;
        if(q == null) { // 说明p是尾节点，不能删除p
            if(c == p)
                return pred;
            q = p;
        }
        return (tryCasSuccessor(pred, c, q) && (pred == null || ITEM.get(pred) != null))
                ? pred : p;
    }

    /**
     * 找到一个：看起来是尾巴的节点p
     * 如果p.next == null，则CAS(p.next, null, newNode)，成功就结束。
     * 否则继续往后找。
     */
    public boolean offer(E e) {
        final Node<E> newNode = new Node<>(Objects.requireNonNull(e));
        /**
         * t：当前认为的tail（注意tail不一定真是最后一个节点，只是一个提示位置）。
         * p：当前正在尝试的节点（相当于游标）
         */
        for(Node<E> t = tail, p = t; ; ) {
            Node<E> q = p.next;
            if(q == null) {
                if(NEXT.compareAndSet(p, null, newNode)) {
                    /**
                     * CAS写入成功后，顺便检查：
                     * 如果我们不是从当前tail开始的，要顺便更新一下tail，这个操作是可以失败的，所以是个优化逻辑。
                     */
                    if(p != t)
                        TAIL.weakCompareAndSet(this, t, newNode);
                    return true;
                }
            }else if(p == q) {  // 自环节点，说明已废弃
                /**
                 * 优先跳到最新的tail，否则跳到head。
                 * 因为当前p已经不在链表的有效节点上了，必须重新找个入口。
                 */
                p = (t != (t = tail)) ? t : head;
            }else { // 普通情况，p在链表中间
                /**
                 * 如果tail发生变化了，则优先跳到t，否则向右移动一格
                 */
                p = (p != t && t != (t = tail)) ? t : q;
            }
        }
    }

    /**
     * 从head开始找出第一个item != null的节点，然后用CAS把item改成null，必要时前移head。
     * 要注意在CLQ中，删除不等于直接锻炼，而是只会把item改成null，所以节点还在。
     */
    public E poll() {
        // 外层循环：遇到异常情况则从head重来
        restartFromHead: while(true) {
            /**
             * 沿着链表往后扫描
             * h：当前head。
             * p：当前扫描节点。
             * q：p.next。
             */
            for(Node<E> h = head, p = h, q; ; p = q) {
                final E item;
                // 检查是福哦为可删除的节点，并且尝试置null
                if((item = p.item) != null && p.casItem(item, null)) {
                    /**
                     * 如果删除的不是head，则尝试把head往前推进，因为head可能落后了，例如：
                     * head -> x(null) -> y(null) -> a(有效)，最终应该删除的是a
                     */
                    if(p != h)
                        updateHead(h, ((q = p.next) != null) ? q : p);
                    return item;
                }else if((q = p.next) == null) {
                    /**
                     * 进入这里说明已经走到了链表尾了（p已经是最后一个节点，并且item是null），说明整个队列是空的。
                     * 把head推到最后一个位置，然后返回null。
                     */
                    updateHead(h, p);
                    return null;
                }else if(p == q)
                    continue restartFromHead;
                /**
                 * 如果进入上面的分支，说明p又是自环节点，说明节点已废弃，当前遍历路径不可信，直接从head重新开始
                 */
            }
        }
    }

    public E peek() {
        restartFromHead: while(true) {
            for(Node<E> h = head, p = h, q; ; p = q) {
                final E item;
                if((item = p.item) != null || (q = p.next) == null) {
                    updateHead(h, p);
                    return item;
                }else if (p == q)
                    continue restartFromHead;
            }
        }
    }

    Node<E> first() {
        restartFromHead: while(true) {
            for(Node<E> h = head, p = h, q; ; p = q) {
                boolean hasItem = (p.item != null);
                if(hasItem || (q = p.next) == null) {
                    updateHead(h, p);
                    return hasItem ? p : null;
                }else if (p == q)
                    continue restartFromHead;
            }
        }
    }

    public boolean isEmpty() {
        return first() == null;
    }

    public int size() {
        restartFromHead: while(true) {
            int count = 0;
            for(Node<E> p = first(); p != null;) {
                if(p.item != null)
                    if(++count == Integer.MAX_VALUE)
                        break;  // @see Collection.size()
                if(p == (p = p.next))
                    continue restartFromHead;
            }
            return count;
        }
    }

    public boolean contains(Object o) {
        if(o == null)
            return false;
        restartFromHead: while(true) {
            for(Node<E> p = head, pred = null; p != null; ) {
                Node<E> q = p.next;
                final E item;
                if((item = p.item) != null) {
                    if(o.equals(item))
                        return true;
                    pred = p;
                    p = q;
                    continue;
                }
                // p.item == null，顺便清理链表
                for(Node<E> c = p;; q = p.next) {
                    if(q == null || q.item != null) {   // 是否找到了dead区间的结束
                        pred = skipDeadNodes(pred, c, p, q);
                        p = q;
                        break;
                    }
                    if(p == (p = q))
                        continue restartFromHead;
                }
            }
            return false;
        }
    }

    public boolean remove(Object o) {
        if(o == null)
            return false;
        restartFromHead: while(true) {
            for(Node<E> p = head, pred = null; p != null; ) {
                Node<E> q = p.next;
                final E item;
                if ((item = p.item) != null) {
                    if(o.equals(item) && p.casItem(item, null)) {
                        skipDeadNodes(pred, p, p, q);
                        return true;
                    }
                    pred = p;
                    p = q;
                    continue;
                }
                for(Node<E> c = p;; q = p.next) {
                    if(q == null || q.item != null) {
                        pred = skipDeadNodes(pred, c, p, q);
                        p = q;
                        break;
                    }
                    if(p == (p = q))
                        continue restartFromHead;
                }
            }
            return false;
        }
    }

    public boolean addAll(Collection<? extends E> c) {
        if (c == this)
            // As historically specified in AbstractQueue#addAll
            throw new IllegalArgumentException();

        // Copy c into a private chain of Nodes
        Node<E> beginningOfTheEnd = null;
        Node<E> last = null;
        for(E e : c) {
            Node<E> newNode = new Node<>(Objects.requireNonNull(e));
            if(beginningOfTheEnd == null)
                beginningOfTheEnd = last = newNode;
            else
                last.appendRelaxed(last = newNode);
        }
        if(beginningOfTheEnd == null)
            return false;

        // Atomically append the chain at the tail of this collection
        for(Node<E> t = tail, p = t; ;) {
            Node<E> q = p.next;
            if(q == null) {
                // p is last node
                if(NEXT.compareAndSet(p, null, beginningOfTheEnd)) {
                    // Successful CAS is the linearization point
                    // for all elements to be added to this queue.
                    if(!TAIL.weakCompareAndSet(this, t, last)) {
                        // Try a little harder to update tail,
                        // since we may be adding many elements.
                        t = tail;
                        if(last.next == null)
                            TAIL.weakCompareAndSet(this, t, last);
                    }
                    return true;
                }
                // Lost CAS race to another thread; re-read next
            }
            else if(p == q)
                // We have fallen off list.  If tail is unchanged, it
                // will also be off-list, in which case we need to
                // jump to head, from which all live nodes are always
                // reachable.  Else the new tail is a better bet.
                p = (t != (t = tail)) ? t : head;
            else
                // Check for tail updates after two hops.
                p = (p != t && t != (t = tail)) ? t : q;
        }
    }

    public String toString() {
        String[] a = null;
        restartFromHead: while(true) {
            int charLength = 0;
            int size = 0;
            for(Node<E> p = first(); p != null;) {
                final E item;
                if((item = p.item) != null) {
                    if(a == null)
                        a = new String[4];
                    else if(size == a.length)
                        a = Arrays.copyOf(a, 2 * size);
                    String s = item.toString();
                    a[size++] = s;
                    charLength += s.length();
                }
                if(p == (p = p.next))
                    continue restartFromHead;
            }

            if(size == 0)
                return "[]";

            return Helpers.toString(a, size, charLength);
        }
    }

    private Object[] toArrayInternal(Object[] a) {
        Object[] x = a;
        restartFromHead: while(true) {
            int size = 0;
            for(Node<E> p = first(); p != null;) {
                final E item;
                if((item = p.item) != null) {
                    if(x == null)
                        x = new Object[4];
                    else if(size == x.length)
                        x = Arrays.copyOf(x, 2 * (size + 4));
                    x[size++] = item;
                }
                if(p == (p = p.next))
                    continue restartFromHead;
            }
            if(x == null)
                return new Object[0];
            else if(a != null && size <= a.length) {
                if(a != x)
                    System.arraycopy(x, 0, a, 0, size);
                if(size < a.length)
                    a[size] = null;
                return a;
            }
            return (size == x.length) ? x : Arrays.copyOf(x, size);
        }
    }

    public Object[] toArray() {
        return toArrayInternal(null);
    }

    public <T> T[] toArray(T[] a) {
        Objects.requireNonNull(a);
        return (T[])toArrayInternal(a);
    }

    public Iterator<E> iterator() {
        return new Itr();
    }

    private class Itr implements Iterator<E> {
        private Node<E> nextNode;
        private E nextItem;
        private Node<E> lastRet;

        Itr() {
            restartFromHead: while(true) {
                Node<E> h;
                Node<E> p;
                Node<E> q;
                for(p = h = head; ; p = q) {
                    final E item;
                    if((item = p.item) != null) {   // 预先加载第一个数据
                        nextNode = p;
                        nextItem = item;
                        break;
                    }else if((q = p.next) == null)  // 队列为空
                        break;
                    else if(p == q) // 遇到了自环节点
                        continue restartFromHead;
                }
                updateHead(h, p);
                return;
            }
        }

        public boolean hasNext() {
            return nextItem != null;
        }

        public E next() {
            final Node<E> pred = nextNode;
            if(pred == null)
                throw new NoSuchElementException();
            // assert nextItem != null;
            lastRet = pred;
            E item = null;

            /** 预先寻找并加载下一个元素节点p */
            for(Node<E> p = succ(pred), q;; p = q) {
                if(p == null || (item = p.item) != null) {
                    nextNode = p;
                    E x = nextItem;
                    nextItem = item;
                    return x;
                }
                // unlink deleted nodes
                if((q = succ(p)) != null)
                    NEXT.compareAndSet(pred, p, q);
            }
        }

        public void remove() {
            Node<E> l = lastRet;
            if(l == null)
                throw new IllegalStateException();
            // rely on a future traversal to relink.
            l.item = null;
            lastRet = null;
        }

        // forEachRemaining方法用Iterator接口默认的实现即可，没有必要重写
    }

    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        // Write out any hidden stuff
        s.defaultWriteObject();

        // Write out all elements in the proper order.
        for(Node<E> p = first(); p != null; p = succ(p)) {
            final E item;
            if((item = p.item) != null)
                s.writeObject(item);
        }

        // Use trailing null as sentinel
        s.writeObject(null);
    }

    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();

        // Read in elements until trailing null sentinel found
        Node<E> h = null, t = null;
        for(Object item; (item = s.readObject()) != null; ) {
            @SuppressWarnings("unchecked")
            Node<E> newNode = new Node<>((E) item);
            if(h == null)
                h = t = newNode;
            else
                t.appendRelaxed(t = newNode);
        }
        if (h == null)
            h = t = new Node<E>();
        head = h;
        tail = t;
    }

    final class CLQSpliterator implements Spliterator<E> {
        static final int MAX_BATCH = 1 << 25;  // max batch array size;
        Node<E> current;    // current node; null until initialized
        int batch;          // batch size for splits
        boolean exhausted;  // true when no more nodes

        public Spliterator<E> trySplit() {
            Node<E> p, q;
            if((p = current()) == null || (q = p.next) == null)
                return null;
            int i = 0, n = batch = Math.min(batch + 1, MAX_BATCH);
            Object[] a = null;
            do {
                final E e;
                if((e = p.item) != null) {
                    if(a == null)
                        a = new Object[n];
                    a[i++] = e;
                }
                if(p == (p = q))
                    p = first();
            }while (p != null && (q = p.next) != null && i < n);
            setCurrent(p);
            return (i == 0) ? null :
                    Spliterators.spliterator(a, 0, i, (Spliterator.ORDERED |
                            Spliterator.NONNULL |
                            Spliterator.CONCURRENT));
        }

        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            final Node<E> p;
            if((p = current()) != null) {
                current = null;
                exhausted = true;
                forEachFrom(action, p);
            }
        }

        public boolean tryAdvance(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            Node<E> p;
            if((p = current()) != null) {
                E e;
                do {
                    e = p.item;
                    if(p == (p = p.next))
                        p = first();
                }while (e == null && p != null);
                setCurrent(p);
                if(e != null) {
                    action.accept(e);
                    return true;
                }
            }
            return false;
        }

        private void setCurrent(Node<E> p) {
            if((current = p) == null)
                exhausted = true;
        }

        private Node<E> current() {
            Node<E> p;
            if((p = current) == null && !exhausted)
                setCurrent(p = first());
            return p;
        }

        public long estimateSize() {
            return Long.MAX_VALUE;
        }

        public int characteristics() {
            return (Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.CONCURRENT);
        }
    }

    @Override
    public Spliterator<E> spliterator() {
        return new CLQSpliterator();
    }

    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        return bulkRemove(filter);
    }

    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> c.contains(e));
    }

    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> !c.contains(e));
    }

    public void clear() {
        bulkRemove(e -> true);
    }

    private boolean bulkRemove(Predicate<? super E> filter) {
        boolean removed = false;
        restartFromHead: while(true) {
            int hops = MAX_HOPS;
            // c will be CASed to collapse intervening dead nodes between
            // pred (or head if null) and p.
            for(Node<E> p = head, c = p, pred = null, q; p != null; p = q) {
                q = p.next;
                final E item; boolean pAlive;
                if(pAlive = ((item = p.item) != null)) {
                    if(filter.test(item)) {
                        if(p.casItem(item, null))
                            removed = true;
                        pAlive = false;
                    }
                }
                if(pAlive || q == null || --hops == 0) {
                    // p might already be self-linked here, but if so:
                    // - CASing head will surely fail
                    // - CASing pred's next will be useless but harmless.
                    if((c != p && !tryCasSuccessor(pred, c, c = p)) || pAlive) {
                        // if CAS failed or alive, abandon old pred
                        hops = MAX_HOPS;
                        pred = p;
                        c = q;
                    }
                }else if (p == q)
                    continue restartFromHead;
            }
            return removed;
        }
    }

    void forEachFrom(Consumer<? super E> action, Node<E> p) {
        for(Node<E> pred = null; p != null; ) {
            Node<E> q = p.next;
            final E item;
            if((item = p.item) != null) {
                action.accept(item);
                pred = p;
                p = q;
                continue;
            }
            for(Node<E> c = p;; q = p.next) {
                if (q == null || q.item != null) {
                    pred = skipDeadNodes(pred, c, p, q);
                    p = q;
                    break;
                }
                if(p == (p = q)) {
                    pred = null;
                    p = head;
                    break;
                }
            }
        }
    }

    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        forEachFrom(action, head);
    }

    private static final int MAX_HOPS = 8;

    // VarHandle mechanics
    private static final VarHandle HEAD;
    private static final VarHandle TAIL;
    static final VarHandle ITEM;
    static final VarHandle NEXT;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            HEAD = l.findVarHandle(ConcurrentLinkedQueue.class, "head", Node.class);
            TAIL = l.findVarHandle(ConcurrentLinkedQueue.class, "tail", Node.class);
            ITEM = l.findVarHandle(Node.class, "item", Object.class);
            NEXT = l.findVarHandle(Node.class, "next", Node.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

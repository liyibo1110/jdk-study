package com.github.liyibo1110.jdk.java.lang;

import jdk.internal.misc.TerminatingThreadLocal;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 该类提供线程局部变量。这些变量与普通变量的区别在于：每个访问该变量（通过其get或set方法）的线程都拥有独立初始化的变量副本。
 * ThreadLocal实例通常作为私有静态字段存在于需要将状态与线程关联的类中（例如用户ID或事务ID）。
 *
 * ThreadLocal设计本质就是：把一些特殊数据存到Thread对象里，而不是存到共享对象里（线程作用域的存储机制）。
 * 通常用途基本是以下四种：
 * 1、保存当前请求的上下文：也是Web系统最常见的用途，例如用户ID、traceId、当前租户ID、登录信息。
 * 好处是不用一层一层手动传参数，每一层都可以直接拿到当前用户，诸如Spring Security、MyBatis、logging MDC等都使用了ThreadLocal。
 * 2、数据库连接 / Session：一些ORM框架会用ThreadLocal来保存数据库连接、事务上下文以及session。
 * 例如TransactionSynchronizationManager，在Spring里就是ThreadLocal
 * 3、避免共享对象的并发问题：例如SimpleDateFormat，这个类本身不是线程安全的，传统解决方法是每次new新的，或者用synchronized，
 * 用ThreadLocal可以这样：ThreadLocal<SimpleDateFormat> formatter = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
 * 4、日志TraceId：很多日志系统用ThreadLocal来存traceId、requestId，这样日志就可以打印这些字段和值
 *
 * 注意ThreadLocal本身并不保存数据，数据实际保存在Thread里面（在ThreadLocalMap里，key是ThreadLocal，value是实际值），ThreadLocal本身只是个key。
 * 之所以这样设计（而不是ThreadLocal里面直接保存一个Map，key是Thread，value是实际值），是为了避免内存泄漏和性能问题，
 * 如果像上面括号里那样的结构，ThreadLocal会持有大量Thread引用，而实际结构中，Thread结束了被GC，里面的ThreadLocalMap自然就没有了。
 * 还有就是ThreadLocalMap的key，其实是个WeakReference，里面才是ThreadLocal，但是尽管如此，value还是强引用，
 * 如果ThreadLocal被GC，value仍然还在，会导致value无法被释放，所以使用ThreadLocal一定要：
 * try {
 *     threadLocal.set(xxx);
 * } finally {
 *     threadLocal.remove();
 * }
 * @author liyibo
 * @date 2026-03-10 13:07
 */
public class ThreadLocal<T> {

    /**
     * 线程局部变量依赖于附加在每个线程上的线程级线性探测hash表（Thread.threadLocals和inheritableThreadLocals）。
     * ThreadLocal对象充当键值，通过threadLocalHashCode进行检索。
     * 该hash值为定制实现（仅在ThreadLocalMaps中有效），在常见场景下能消除连续创建的线程局部变量被同一线程使用时的冲突，同时在较少出现的特殊情况下仍能保持良好行为。
     */
    private final int threadLocalHashCode = nextHashCode();

    /** 下一个待分配的hash值，原子更新，从0开始 */
    private static AtomicInteger nextHashCode = new AtomicInteger();

    /**
     * 连续生成的hash值之间的差异——将隐含的顺序线程局部ID转化为近乎最优分布的乘法hash值，适用于2的幂次方大小的表格。
     * 就是一个黄金分割数
     */
    private static final int HASH_INCREMENT = 0x61c88647;

    /**
     * 返回下一个hash值
     */
    private static int nextHashCode() {
        return nextHashCode.getAndAdd(HASH_INCREMENT);
    }

    /**
     * 返回当前线程对此线程局部变量的“初始值”。
     * 当线程首次通过get方法访问该变量时将调用此方法，除非该线程此前已调用过set方法——此时不会为该线程调用initialValue方法。
     * 通常每个线程最多调用此方法一次，但在后续调用remove方法后紧接着调用get方法时可能再次调用。
     *
     * 本实现仅返回null；若需为线程局部变量设置非null初始值，必须继承ThreadLocal并重写此方法。通常会使用匿名内部类实现。
     */
    protected T initialValue() {
        return null;
    }

    public ThreadLocal() {}

    /**
     * 返回当前线程中该线程局部变量的副本值。如果该变量在当前线程中没有值，则首先通过调用initialValue方法返回的值进行初始化。
     */
    public T get() {
        Thread t = Thread.currentThread();
        ThreadLocal.ThreadLocalMap map = getMap(t);
        if(map != null) {
            ThreadLocal.ThreadLocalMap.Entry e = map.getEntry(this);
            if(e != null) {
                T result = (T)e.value;
                return result;
            }
        }
    }

    boolean isPresent() {
        Thread t = Thread.currentThread();
        ThreadLocal.ThreadLocalMap map = getMap(t);
        return map != null && map.getEntry(this) != null;
    }

    /**
     * set()的变体，用于设定初始值。
     * 当用户已重写set()方法时，可替代set()使用。
     */
    private T setInitialValue() {
        T value = initialValue();
        Thread t = Thread.currentThread();
        ThreadLocal.ThreadLocalMap map = getMap(t);
        if(map == null)
            createMap(t, value);
        else
            map.set(this, value);
        if(this instanceof TerminatingThreadLocal)
            TerminatingThreadLocal.register((TerminatingThreadLocal<?>)this);
        return value;
    }

    /**
     * 将当前线程对此线程局部变量的副本设置为指定值。
     * 大多数子类无需重写此方法，仅需依赖initialValue方法来设置线程局部变量的值。
     */
    public void set(T value) {
        Thread t = Thread.currentThread();
        ThreadLocal.ThreadLocalMap map = getMap(t);
        if(map == null)
            createMap(t, value);
        else
            map.set(this, value);
    }

    /**
     * 清除当前线程对此线程局部变量的值。
     * 若当前线程随后读取该线程局部变量，其值将通过调用initialValue方法重新初始化，除非在此期间该值已被当前线程设置。
     * 这可能导致当前线程多次调用initialValue方法。
     */
    public void remove() {
        ThreadLocal.ThreadLocalMap m = getMap(Thread.currentThread());
        if(m != null)
            m.remove(this);
    }

    ThreadLocal.ThreadLocalMap getMap(Thread t) {
        return t.threadLocals;
    }

    /**
     * 重要方法，是在这里给thread的threadLocals赋值的。
     */
    void createMap(Thread t, T firstValue) {
        t.threadLocals = new ThreadLocalMap(this, firstValue);
    }

    static ThreadLocal.ThreadLocalMap createInheritedMap(ThreadLocal.ThreadLocalMap parentMap) {
        return new ThreadLocal.ThreadLocalMap(parentMap);
    }

    T childValue(T parentValue) {
        throw new UnsupportedOperationException();
    }

    /**
     * ThreadLocal的扩展，其初始值由指定的Supplier获取
     */
    static final class SuppliedThreadLocal<T> extends ThreadLocal<T> {
        private final Supplier<? extends T> supplier;

        SuppliedThreadLocal(Supplier<? extends T> supplier) {
            this.supplier = Objects.requireNonNull(supplier);
        }

        @Override
        protected T initialValue() {
            return supplier.get();
        }
    }

    /**
     * ThreadLocalMap 是一种定制化的hash映射，仅适用于维护线程局部值。
     * 所有操作均未在ThreadLocal类外部公开。该类采用包私有访问修饰符，以便在Thread类中声明字段。
     * 为应对超大容量和长期存活的使用场景，哈希表条目采用弱引用作为键值。
     * 但由于未使用引用队列机制，过期条目仅在表空间即将耗尽时才会被保证清除。
     *
     * 内部实现其实还挺复杂的，原因在于当key被GC，value还会依然存在，这种Entry叫做stale entry，后面要单独尝试清理。
     *
     * 同时要注意里面的一个关键要点：从hash算出的位置开始，到第一个null之前的连续区域，叫cluster或者run，
     * 例如[A, B, C, null]，这一段都是hash冲突产生的元素，如果删除其中一个变成[A, null, C, null]，查找算法会被破坏了（要重新hash后面的所有元素）
     */
    static class ThreadLocalMap {

        /**
         * 此哈希表中的条目继承自WeakReference类，其主引用字段作为键（该字段始终为ThreadLocal对象）。
         * 需注意，当键为空（即entry.get() == null）时，表示该键已不再被引用，因此可将条目从表中清除。
         * 后续代码中将此类条目称为“过期条目”。
         */
        static class Entry extends WeakReference<java.lang.ThreadLocal<?>> {
            Object value;

            Entry(ThreadLocal<?> k, Object v) {
                super(k);
                value = v;
            }
        }

        private static final int INITIAL_CAPACITY = 16;

        private Entry[] table;

        private int size = 0;

        /**
         * 下一个需要调整大小的尺寸值，默认为0
         */
        private int threshold;

        /**
         * 将threshold设置为至少保持2/3的负载因子。
         */
        private void setThreshold(int len) {
            threshold = len * 2 / 3;
        }

        /**
         * 返回下一个index
         */
        private static int nextIndex(int i, int len) {
            return i + 1 < len ? i + 1 : 0;
        }

        private static int prevIndex(int i, int len) {
            return i - 1 >= 0 ? i - 1 : len - 1;
        }

        ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
            table = new Entry[INITIAL_CAPACITY];
            // 找index
            int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
            table[i] = new Entry(firstKey, firstValue);
            size = 1;
            setThreshold(INITIAL_CAPACITY);
        }

        private ThreadLocalMap(ThreadLocal.ThreadLocalMap parentMap) {
            Entry[] parentTable = parentMap.table;
            int len = parentTable.length;
            setThreshold(len);
            table = new Entry[len];

            for(Entry e : parentTable) {
                if(e != null) {
                    @SuppressWarnings("unchecked")
                    ThreadLocal<Object> key = (ThreadLocal<Object>) e.get();
                    if(key != null) {
                        Object value = key.childValue(e.value);
                        Entry c = new Entry(key, value);
                        int h = key.threadLocalHashCode & (len - 1);
                        while(table[h] != null)
                            h = nextIndex(h, len);
                        table[h] = c;
                        size++;
                    }
                }
            }
        }

        private Entry getEntry(ThreadLocal<?> key) {
            int i = key.threadLocalHashCode & table.length - 1;
            Entry e = table[i];
            if(e != null && e.refersTo(key))    // hash没有冲突，且直接命中则返回，
                return e;
            else    // 没命中
                return getEntryAfterMiss(key, i, e);
        }

        /**
         * 当key未在其直接hash槽中找到时使用的getEntry方法版本
         *
         * 非常核心的方法，也是官方注释写的：线性探测查找，说白了就是每个slot按个遍历
         */
        private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e) {
            Entry[] tab = table;
            int len = tab.length;

            while(e != null) {
                if(e.refersTo(key))    // key匹配则直接返回
                    return e;
                if(e.refersTo(null))    // key是null则要清理这个slot
                    expungeStaleEntry(i);
                else    // 否则到下一个slot继续找
                    i = nextIndex(i, len);
                e = tab[i];
            }
            return null;
        }

        private void set(ThreadLocal<?> key, Object value) {
            Entry[] tab = table;
            int len = tab.length;
            int i = key.threadLocalHashCode & (len-1);  // 根据hash计算slot

            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                if(e.refersTo(key)) {   // key已存在并且相等，则直接更新entry
                    e.value = value;
                    return;
                }

                if (e.refersTo(null)) { // key为null，则走replaceStaleEntry逻辑
                    replaceStaleEntry(key, value, i);
                    return;
                }
            }

            tab[i] = new Entry(key, value); // 到这里说明slot是空的，直接写入即可
            int sz = ++size;
            if (!cleanSomeSlots(i, sz) && sz >= threshold)
                rehash();
        }

        private void remove(java.lang.ThreadLocal<?> key) {
            Entry[] tab = table;
            int len = tab.length;
            int i = key.threadLocalHashCode & (len-1);
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                if(e.refersTo(key)) {
                    e.clear();
                    expungeStaleEntry(i);
                    return;
                }
            }
        }

        /**
         * 这个方法之所以复杂，除了它给stale slot插入新值，还负责顺便清理整个run，算法流程：
         * 例如结构：[A, B, C, D, null]
         * 1、找run的开始
         * 2、找run的结束
         * 3、插入新值
         * 4、清理所有stale entry
         * 为了避免：GC一次释放很多ThreadLocal导致大量stale entry
         */
        private void replaceStaleEntry(ThreadLocal<?> key, Object value, int staleSlot) {
            Entry[] tab = table;
            int len = tab.length;
            Entry e;

            // Back up to check for prior stale entry in current run.
            // We clean out whole runs at a time to avoid continual
            // incremental rehashing due to garbage collector freeing
            // up refs in bunches (i.e., whenever the collector runs).
            int slotToExpunge = staleSlot;
            for (int i = prevIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = prevIndex(i, len))
                if (e.refersTo(null))
                    slotToExpunge = i;

            // Find either the key or trailing null slot of run, whichever
            // occurs first
            for (int i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = nextIndex(i, len)) {
                // If we find key, then we need to swap it
                // with the stale entry to maintain hash table order.
                // The newly stale slot, or any other stale slot
                // encountered above it, can then be sent to expungeStaleEntry
                // to remove or rehash all of the other entries in run.
                if (e.refersTo(key)) {
                    e.value = value;

                    tab[i] = tab[staleSlot];
                    tab[staleSlot] = e;

                    // Start expunge at preceding stale entry if it exists
                    if (slotToExpunge == staleSlot)
                        slotToExpunge = i;
                    cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
                    return;
                }

                // If we didn't find stale entry on backward scan, the
                // first stale entry seen while scanning for key is the
                // first still present in the run.
                if (e.refersTo(null) && slotToExpunge == staleSlot)
                    slotToExpunge = i;
            }

            // If key not found, put new entry in stale slot
            tab[staleSlot].value = null;
            tab[staleSlot] = new Entry(key, value);

            // If there are any other stale entries in run, expunge them
            if (slotToExpunge != staleSlot)
                cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
        }

        private int expungeStaleEntry(int staleSlot) {
            Entry[] tab = table;
            int len = tab.length;

            tab[staleSlot].value = null;    // 重要：清理value
            tab[staleSlot] = null;  // 重要：清空slot
            size--;

            /**
             * 重新hash后面的所有元素，否则会出现问题，举个例子：
             * slot0 -> A
             * slot1 -> B
             * slot2 -> C
             * slot3 -> null
             * 如果删除了B，则变成：
             * slot0 -> A
             * slot1 -> null
             * slot2 -> C
             * 会找不到C（在slot1就停止查找了），所以在这里，要把C从slot2挪到slot1。
             */
            Entry e;
            int i;
            for (i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();
                if (k == null) {
                    e.value = null;
                    tab[i] = null;
                    size--;
                } else {
                    int h = k.threadLocalHashCode & (len - 1);
                    if (h != i) {
                        tab[i] = null;

                        // Unlike Knuth 6.4 Algorithm R, we must scan until
                        // null because multiple entries could have been stale.
                        while (tab[h] != null)
                            h = nextIndex(h, len);
                        tab[h] = e;
                    }
                }
            }
            return i;
        }

        /**
         * 启发式清理算法，只扫描一部分slot然后清理（折中设计）
         */
        private boolean cleanSomeSlots(int i, int n) {
            boolean removed = false;
            Entry[] tab = table;
            int len = tab.length;
            do {
                i = nextIndex(i, len);
                Entry e = tab[i];
                if(e != null && e.refersTo(null)) {
                    n = len;
                    removed = true;
                    i = expungeStaleEntry(i);
                }
            } while ( (n >>>= 1) != 0);
            return removed;
        }

        /**
         * 1、先清理所有stale entry。
         * 2、如果size依然很大，则resize扩容。
         */
        private void rehash() {
            expungeStaleEntries();
            if(size >= threshold - threshold / 4)   // 还大则扩容
                resize();
        }

        /**
         * 扩容，容量翻倍，重新hash所有entry
         */
        private void resize() {
            Entry[] oldTab = table;
            int oldLen = oldTab.length;
            int newLen = oldLen * 2;
            Entry[] newTab = new Entry[newLen];
            int count = 0;

            for (Entry e : oldTab) {
                if (e != null) {
                    ThreadLocal<?> k = e.get();
                    if (k == null) {
                        e.value = null; // Help the GC
                    } else {
                        int h = k.threadLocalHashCode & (newLen - 1);
                        while (newTab[h] != null)
                            h = nextIndex(h, newLen);
                        newTab[h] = e;
                        count++;
                    }
                }
            }

            setThreshold(newLen);
            size = count;
            table = newTab;
        }

        /**
         * 清除表中所有过期Entry。
         */
        private void expungeStaleEntries() {
            Entry[] tab = table;
            int len = tab.length;
            for(int j = 0; j < len; j++) {
                Entry e = tab[j];
                if (e != null && e.refersTo(null))
                    expungeStaleEntry(j);
            }
        }
    }
}

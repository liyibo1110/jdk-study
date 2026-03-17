package com.github.liyibo1110.jdk.java.util.concurrent.atomic;

import java.io.Serializable;

/**
 * 代替AtomicInteger的进阶版，AtomicInteger在高并发下，CAS会出现大量的失败，即CPU会被浪费在总线竞争和自旋重试上。
 * 至于解决的思路则比较简单：把一个计数器变量拆成多个计数器变量，结构变成了：base + cells[]，
 * 更新流程是：
 * 低竞争：更新base。
 * 高竞争：更新cell[i]。
 * 最后统计时：sum = base + ∑cells，这就是Striped Counter（分条计数）的思想。
 *
 * 适合：统计、指标、监控、计数。
 * 不适合：银行余额、库存、订单数量
 * 原因在于sum方法返回的总数并不精确。
 * @author liyibo
 * @date 2026-03-16 16:39
 */
public class LongAdder extends Striped64 implements Serializable {
    private static final long serialVersionUID = 7249069246863182397L;

    public LongAdder() {}

    /**
     * 增加给定值
     */
    public void add(long x) {
        /**
         * 1、尝试CAS更新base。
         * 2、如果失败说明有竞争，则进入cells逻辑。
         * 3、根据线程probe找到对应的cell。
         * 4、CAS更新cell。
         * 5、如果冲突严重则扩容cells。
         */
        Cell[] cs;
        long b;
        long v;
        int m;
        Cell c;

        /** cells为空，或者casBase不成功（cas成功则直接退出，因为caseBase这一步已经把内部计数值改过了 */
        if((cs = cells) != null || !casBase(b = base, b + x)) {
            int index = getProbe(); // 获取对应cell下标
            boolean uncontended = true;
            if(cs == null || (m = cs.length - 1) < 0 || (c = cs[index & m]) == null || !(uncontended = c.cas(v = c.value, v + x))) {
                longAccumulate(x, null, uncontended, index);    // cells初始化或者扩容
            }
        }
    }

    public void increment() {
        add(1L);
    }

    public void decrement() {
        add(-1L);
    }

    /**
     * 返回计数值，就是简单地累加，所以这个返回值可能略微不准确，
     * 符合LongAdder的设计取舍：牺牲强一致，换取高吞吐
     */
    public long sum() {
        Cell[] cs = cells;
        long sum = base;
        if(cs != null) {
            for(Cell c : cs) {
                if(c != null)
                    sum += c.value;
            }
        }
        return sum;
    }

    public void reset() {
        Cell[] cs = cells;
        base = 0L;
        if(cs != null) {
            for(Cell c : cs) {
                if(c != null)
                    c.reset();
            }
        }
    }

    public long sumThenReset() {
        Cell[] cs = cells;
        long sum = getAndSetBase(0L);
        if(cs != null) {
            for(Cell c : cs) {
                if(c != null)
                    sum += c.getAndSet(0L);
            }
        }
        return sum;
    }

    public String toString() {
        return Long.toString(sum());
    }

    public long longValue() {
        return sum();
    }

    public int intValue() {
        return (int)sum();
    }

    public float floatValue() {
        return (float)sum();
    }

    public double doubleValue() {
        return (double)sum();
    }

    private static class SerializationProxy implements Serializable {
        private static final long serialVersionUID = 7249069246863182397L;

        private final long value;

        SerializationProxy(LongAdder a) {
            value = a.sum();
        }

        private Object readResolve() {
            LongAdder a = new LongAdder();
            a.base = value;
            return a;
        }
    }

    private Object writeReplace() {
        return new SerializationProxy(this);
    }

    private void readObject(java.io.ObjectInputStream s) throws java.io.InvalidObjectException {
        throw new java.io.InvalidObjectException("Proxy required");
    }
}

package com.github.liyibo1110.jdk.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.function.LongBinaryOperator;

/**
 * LongAdder可以看作是LongAccumulator的一种特殊化版本，也就是LongAdder相当于LongAccumulator的一个固定函数实现，即
 * 1、LongAdder的函数是加法。
 * 2、LongAccumulator的函数是用户提供的。
 * 因此LongAccumulator提供给用户，以自定义方式做聚合的方式，例如：
 * 1、最大值。
 * 2、最小值。
 * 3、乘法。
 * 4、自定义聚合。
 * @author liyibo
 * @date 2026-03-16 17:13
 */
public class LongAccumulator extends Striped64 implements Serializable {
    private static final long serialVersionUID = 7249069246863182397L;

    /** 用户的自定义聚合函数 */
    private final LongBinaryOperator function;

    /** 聚合初始值 */
    private final long identity;

    public LongAccumulator(LongBinaryOperator accumulatorFunction, long identity) {
        this.function = accumulatorFunction;
        base = this.identity = identity;
    }

    public void accumulate(long x) {
        Cell[] cs;
        long b;
        long v;
        long r;
        int m;
        Cell c;
        if((cs = cells) != null || ((r = function.applyAsLong(b = base, x)) != b && !casBase(b, r))) {
            int index = getProbe();
            boolean uncontended = true;
            if(cs == null
                    || (m = cs.length - 1) < 0
                    || (c = cs[index & m]) == null
                    || !(uncontended = (r = function.applyAsLong(v = c.value, x)) == v || c.cas(v, r))) {
                longAccumulate(x, function, uncontended, index);
            }
        }
    }

    /**
     * 聚合返回最终结果
     */
    public long get() {
        Cell[] cs = cells;
        long result = base;
        if(cs != null) {
            for(Cell c : cs) {
                if(c != null)
                    result = function.applyAsLong(result, c.value);
            }
        }
        return result;
    }

    public void reset() {
        Cell[] cs = cells;
        base = identity;
        if(cs != null) {
            for(Cell c : cs) {
                if(c != null)
                    c.reset(identity);
            }
        }
    }

    public long getThenReset() {
        Cell[] cs = cells;
        long result = getAndSetBase(identity);
        if(cs != null) {
            for(Cell c : cs) {
                if(c != null) {
                    long v = c.getAndSet(identity);
                    result = function.applyAsLong(result, v);
                }
            }
        }
        return result;
    }

    public String toString() {
        return Long.toString(get());
    }

    public long longValue() {
        return get();
    }

    public int intValue() {
        return (int)get();
    }

    public float floatValue() {
        return (float)get();
    }

    public double doubleValue() {
        return (double)get();
    }

    private static class SerializationProxy implements Serializable {
        private static final long serialVersionUID = 7249069246863182397L;

        private final long value;

        private final LongBinaryOperator function;

        private final long identity;

        SerializationProxy(long value, LongBinaryOperator function, long identity) {
            this.value = value;
            this.function = function;
            this.identity = identity;
        }

        private Object readResolve() {
            LongAccumulator a = new LongAccumulator(function, identity);
            a.base = value;
            return a;
        }
    }

    private Object writeReplace() {
        return new SerializationProxy(get(), function, identity);
    }

    private void readObject(java.io.ObjectInputStream s) throws java.io.InvalidObjectException {
        throw new java.io.InvalidObjectException("Proxy required");
    }
}

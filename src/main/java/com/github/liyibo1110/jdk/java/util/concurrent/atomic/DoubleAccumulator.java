package com.github.liyibo1110.jdk.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.function.DoubleBinaryOperator;

import static java.lang.Double.doubleToRawLongBits;
import static java.lang.Double.longBitsToDouble;

/**
 * @author liyibo
 * @date 2026-03-16 17:25
 */
public class DoubleAccumulator extends Striped64 implements Serializable {
    private static final long serialVersionUID = 7249069246863182397L;

    private final DoubleBinaryOperator function;

    private final long identity;

    public DoubleAccumulator(DoubleBinaryOperator accumulatorFunction, double identity) {
        this.function = accumulatorFunction;
        base = this.identity = doubleToRawLongBits(identity);
    }

    public void accumulate(double x) {
        Cell[] cs;
        long b;
        long v;
        long r;
        int m;
        Cell c;
        if((cs = cells) != null || ((r = doubleToRawLongBits(function.applyAsDouble(longBitsToDouble(b = base), x))) != b && !casBase(b, r))) {
            int index = getProbe();
            boolean uncontended = true;
            if (cs == null
                    || (m = cs.length - 1) < 0
                    || (c = cs[index & m]) == null
                    || !(uncontended = ((r = doubleToRawLongBits(function.applyAsDouble(longBitsToDouble(v = c.value), x))) == v) || c.cas(v, r)))
                doubleAccumulate(x, function, uncontended, index);
        }
    }

    public double get() {
        Cell[] cs = cells;
        double result = longBitsToDouble(base);
        if(cs != null) {
            for(Cell c : cs) {
                if(c != null)
                    result = function.applyAsDouble(result, longBitsToDouble(c.value));
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

    public double getThenReset() {
        Cell[] cs = cells;
        double result = longBitsToDouble(getAndSetBase(identity));
        if(cs != null) {
            for(Cell c : cs) {
                if(c != null) {
                    double v = longBitsToDouble(c.getAndSet(identity));
                    result = function.applyAsDouble(result, v);
                }
            }
        }
        return result;
    }

    public String toString() {
        return Double.toString(get());
    }

    public double doubleValue() {
        return get();
    }

    public long longValue() {
        return (long)get();
    }

    public int intValue() {
        return (int)get();
    }

    public float floatValue() {
        return (float)get();
    }

    private static class SerializationProxy implements Serializable {
        private static final long serialVersionUID = 7249069246863182397L;

        private final double value;

        private final DoubleBinaryOperator function;

        private final long identity;

        SerializationProxy(double value, DoubleBinaryOperator function, long identity) {
            this.value = value;
            this.function = function;
            this.identity = identity;
        }

        private Object readResolve() {
            double d = longBitsToDouble(identity);
            DoubleAccumulator a = new DoubleAccumulator(function, d);
            a.base = doubleToRawLongBits(value);
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

package com.github.liyibo1110.jdk.java.util.concurrent;

/**
 * @author liyibo
 * @date 2026-03-23 10:32
 */
public abstract class RecursiveTask<V> extends ForkJoinTask<V> {
    private static final long serialVersionUID = 5232453952276485270L;

    public RecursiveTask() {}

    V result;

    /**
     * 子类要实现这个干活方法。
     */
    protected abstract V compute();

    @Override
    public final V getRawResult() {
        return result;
    }

    @Override
    protected final void setRawResult(V value) {
        result = value;
    }

    @Override
    protected final boolean exec() {
        result = compute();
        return true;
    }
}

package com.github.liyibo1110.jdk.java.util.concurrent;

/**
 * @author liyibo
 * @date 2026-03-23 10:31
 */
public abstract class RecursiveAction extends ForkJoinTask<Void> {
    private static final long serialVersionUID = 5232453952276485070L;

    public RecursiveAction() {}

    /**
     * 子类要实现这个干活方法。
     */
    protected abstract void compute();

    @Override
    public final Void getRawResult() {
        return null;
    }

    @Override
    protected final void setRawResult(Void mustBeNull) {}

    @Override
    protected final boolean exec() {
        compute();
        return true;
    }
}

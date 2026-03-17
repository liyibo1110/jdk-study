package com.github.liyibo1110.jdk.java.util.concurrent.atomic;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * 和AtomicStampedReference类似，使用了boolean来代替stamp字段。
 * @author liyibo
 * @date 2026-03-16 15:55
 */
public class AtomicMarkableReference<V> {

    private static class Pair<T> {
        final T reference;

        final boolean mark;

        private Pair(T reference, boolean mark) {
            this.reference = reference;
            this.mark = mark;
        }
        static <T> Pair<T> of(T reference, boolean mark) {
            return new Pair<T>(reference, mark);
        }
    }

    private volatile Pair<V> pair;

    public AtomicMarkableReference(V initialRef, boolean initialMark) {
        pair = Pair.of(initialRef, initialMark);
    }

    public V getReference() {
        return pair.reference;
    }

    public boolean isMarked() {
        return pair.mark;
    }

    public V get(boolean[] markHolder) {
        Pair<V> pair = this.pair;
        markHolder[0] = pair.mark;
        return pair.reference;
    }

    public boolean weakCompareAndSet(V expectedReference, V newReference, boolean expectedMark, boolean newMark) {
        return compareAndSet(expectedReference, newReference, expectedMark, newMark);
    }

    public boolean compareAndSet(V expectedReference, V newReference, boolean expectedMark, boolean newMark) {
        Pair<V> current = pair;
        return expectedReference == current.reference
                && expectedMark == current.mark
                && ((newReference == current.reference && newMark == current.mark) || casPair(current, Pair.of(newReference, newMark)));
    }

    public void set(V newReference, boolean newMark) {
        Pair<V> current = pair;
        if(newReference != current.reference || newMark != current.mark)
            this.pair = Pair.of(newReference, newMark);
    }

    public boolean attemptMark(V expectedReference, boolean newMark) {
        Pair<V> current = pair;
        return expectedReference == current.reference
                && (newMark == current.mark || casPair(current, Pair.of(expectedReference, newMark)));
    }

    // VarHandle mechanics

    private static final VarHandle PAIR;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            PAIR = l.findVarHandle(java.util.concurrent.atomic.AtomicMarkableReference.class, "pair", Pair.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private boolean casPair(Pair<V> cmp, Pair<V> val) {
        return PAIR.compareAndSet(this, cmp, val);
    }
}

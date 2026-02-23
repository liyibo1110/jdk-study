package com.github.liyibo1110.jdk.java.util;

/**
 * 一种用于List的迭代器，允许双向遍历列表，在迭代过程中修改列表，并获取迭代器在列表中的当前位置。
 * ListIterator不包含当前元素，其光标位置始终位于previous方法将返回的元素与next方法将返回的元素之间。
 * 长度为n的ListIterator具有n+1个可能的cursor位置。
 * 需注意：remove和set(Object)方法并非基于cursor位置定义，而是作用于next或previous调用返回的最后一个元素。
 * @author liyibo
 * @date 2026-02-23 17:00
 */
public interface ListIterator<E> extends Iterator<E> {

    boolean hasNext();
    E next();
    boolean hasPrevious();
    E previous();
    int nextIndex();
    int previousIndex();
    void remove();
    void set(E e);
    void add(E e);

}

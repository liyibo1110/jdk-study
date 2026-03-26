package com.github.liyibo1110.jdk.java.util;

import jdk.internal.access.SharedSecrets;

import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 和EnumSet有很多相同之处，相同点这里不再说了，下面只说差异。
 *
 * EnumMap关心：某个枚举值，要对应什么数据，而不只是在不在集合里。
 * 底层数据结构：用枚举的ordinal直接索引数组的专用Map，以下是一般用法：
 *
 * 1、状态到数据的映射
 * 希望表达每个状态对应的说明文案、处理器、颜色、计数器、配置对象等。
 * enum State {
 *     INIT, RUNNING, SUCCESS, FAILED
 * }
 *
 * EnumMap<State, String> messages = new EnumMap<>(State.class);
 * messages.put(State.INIT, "初始化");
 * messages.put(State.RUNNING, "执行中");
 * messages.put(State.SUCCESS, "成功");
 * messages.put(State.FAILED, "失败");
 *
 * 2、策略分发表
 * enum OpType {
 *     CREATE, UPDATE, DELETE
 * }
 * EnumMap<OpType, Handler> handlerMap = new EnumMap<>(OpType.class);
 * 使得每种枚举值都能映射到一个处理器对象，比if-else/switch更利于组织代码。
 *
 * 3、固定类别统计
 * EnumMap<Sentiment, Integer> counts = new EnumMap<>(Sentiment.class);
 * 用来统计：
 * POSITIVE → 120
 * NEGATIVE → 35
 * NEUTRAL → 48
 *
 * 4、配置表、元数据表
 *
 * 底层结构：
 * 1、一个保存所有value的数组。
 * 2、数组的下标 = 枚举的ordinal。
 * Status.INIT.ordinal() == 0
 * Status.RUNNING.ordinal() == 1
 * Status.SUCCESS.ordinal() == 2
 * Status.FAILED.ordinal() == 3
 * 对应底层数组：
 * values[0] = ...
 * values[1] = ...
 * values[2] = ...
 * values[3] = ...
 *
 * @author liyibo
 * @date 2026-03-26 12:36
 */
public class EnumMap<K extends Enum<K>, V> extends AbstractMap<K, V> implements Serializable, Cloneable {

    /** key的枚举类型 */
    private final Class<K> keyType;

    /** key的所有枚举实例 */
    private transient K[] keyUniverse;

    /** 存value的数组 */
    private transient Object[] vals;

    private transient int size = 0;

    /**
     * 在value数组里，需要用这个对象来表示key存在但value值是null，正常的null只能代表key也不存在（被remove了）。
     */
    private static final Object NULL = new Object() {
        public int hashCode() {
            return 0;
        }

        public String toString() {
            return "java.util.EnumMap.NULL";
        }
    };

    /**
     * 给定的value转换成value里面的null，如果value不为null则返回原值
     */
    private Object maskNull(Object value) {
        return value == null ? NULL : value;
    }

    /**
     * 和maskNull是相反的操作，如果给定值的特殊的Null，则转换成普通的null返回，否则原样返回。
     */
    @SuppressWarnings("unchecked")
    private V unmaskNull(Object value) {
        return (V)(value == NULL ? null : value);
    }

    public EnumMap(Class<K> keyType) {
        this.keyType = keyType;
        keyUniverse = getKeyUniverse(keyType);
        vals = new Object[keyUniverse.length];
    }

    public EnumMap(EnumMap<K, ? extends V> m) {
        keyType = m.keyType;
        keyUniverse = m.keyUniverse;
        vals = m.vals.clone();
        size = m.size;
    }

    public EnumMap(Map<K, ? extends V> m) {
        if(m instanceof EnumMap) {
            EnumMap<K, ? extends V> em = (EnumMap<K, ? extends V>) m;
            keyType = em.keyType;
            keyUniverse = em.keyUniverse;
            vals = em.vals.clone();
            size = em.size;
        }else {
            if(m.isEmpty())
                throw new IllegalArgumentException("Specified map is empty");
            keyType = m.keySet().iterator().next().getDeclaringClass();
            keyUniverse = getKeyUniverse(keyType);
            vals = new Object[keyUniverse.length];
            putAll(m);
        }
    }

    // Query Operations

    public int size() {
        return size;
    }

    public boolean containsValue(Object value) {
        value = maskNull(value);

        for(Object val : vals)
            if(value.equals(val))
                return true;

        return false;
    }

    /**
     * 把key的ordinal当作数组索引直接查即可
     */
    public boolean containsKey(Object key) {
        return isValidKey(key) && vals[((Enum<?>)key).ordinal()] != null;
    }

    private boolean containsMapping(Object key, Object value) {
        return isValidKey(key) && maskNull(value).equals(vals[((Enum<?>)key).ordinal()]);
    }

    public V get(Object key) {
        return (isValidKey(key) ? unmaskNull(vals[((Enum<?>)key).ordinal()]) : null);
    }

    // Modification Operations

    public V put(K key, V value) {
        typeCheck(key);

        int index = key.ordinal();
        Object oldValue = vals[index];
        vals[index] = maskNull(value);
        if(oldValue == null)
            size++;
        return unmaskNull(oldValue);
    }

    public V remove(Object key) {
        if(!isValidKey(key))
            return null;
        int index = ((Enum<?>)key).ordinal();
        Object oldValue = vals[index];
        vals[index] = null;
        if (oldValue != null)
            size--;
        return unmaskNull(oldValue);
    }

    private boolean removeMapping(Object key, Object value) {
        if(!isValidKey(key))
            return false;
        int index = ((Enum<?>)key).ordinal();
        if(maskNull(value).equals(vals[index])) {
            vals[index] = null;
            size--;
            return true;
        }
        return false;
    }

    private boolean isValidKey(Object key) {
        if(key == null)
            return false;
        // Cheaper than instanceof Enum followed by getDeclaringClass
        Class<?> keyClass = key.getClass();
        return keyClass == keyType || keyClass.getSuperclass() == keyType;
    }

    // Bulk Operations

    public void putAll(Map<? extends K, ? extends V> m) {
        if(m instanceof EnumMap<?, ?> em) {
            if(em.keyType != keyType) {
                if(em.isEmpty())
                    return;
                throw new ClassCastException(em.keyType + " != " + keyType);
            }

            for(int i = 0; i < keyUniverse.length; i++) {
                Object emValue = em.vals[i];
                if(emValue != null) {
                    if(vals[i] == null)
                        size++;
                    vals[i] = emValue;
                }
            }
        }else {
            super.putAll(m);
        }
    }

    public void clear() {
        Arrays.fill(vals, null);
        size = 0;
    }

    // Views

    private transient Set<Entry<K,V>> entrySet;

    public Set<K> keySet() {
        Set<K> ks = keySet;
        if(ks == null) {
            ks = new KeySet();
            keySet = ks;
        }
        return ks;
    }

    private class KeySet extends AbstractSet<K> {
        public Iterator<K> iterator() {
            return new KeyIterator();
        }
        public int size() {
            return size;
        }
        public boolean contains(Object o) {
            return containsKey(o);
        }
        public boolean remove(Object o) {
            int oldSize = size;
            EnumMap.this.remove(o);
            return size != oldSize;
        }
        public void clear() {
            EnumMap.this.clear();
        }
    }

    public java.util.Collection<V> values() {
        Collection<V> vs = values;
        if(vs == null) {
            vs = new Values();
            values = vs;
        }
        return vs;
    }

    private class Values extends AbstractCollection<V> {
        public Iterator<V> iterator() {
            return new ValueIterator();
        }
        public int size() {
            return size;
        }
        public boolean contains(Object o) {
            return containsValue(o);
        }
        public boolean remove(Object o) {
            o = maskNull(o);

            for (int i = 0; i < vals.length; i++) {
                if (o.equals(vals[i])) {
                    vals[i] = null;
                    size--;
                    return true;
                }
            }
            return false;
        }
        public void clear() {
            EnumMap.this.clear();
        }
    }

    public Set<Map.Entry<K,V>> entrySet() {
        Set<Map.Entry<K,V>> es = entrySet;
        if(es != null)
            return es;
        else
            return entrySet = new EntrySet();
    }

    private class EntrySet extends AbstractSet<Map.Entry<K,V>> {
        public Iterator<Map.Entry<K,V>> iterator() {
            return new EntryIterator();
        }

        public boolean contains(Object o) {
            return o instanceof Map.Entry<?, ?> entry && containsMapping(entry.getKey(), entry.getValue());
        }
        public boolean remove(Object o) {
            return o instanceof Map.Entry<?, ?> entry && removeMapping(entry.getKey(), entry.getValue());
        }
        public int size() {
            return size;
        }
        public void clear() {
            EnumMap.this.clear();
        }
        public Object[] toArray() {
            return fillEntryArray(new Object[size]);
        }
        @SuppressWarnings("unchecked")
        public <T> T[] toArray(T[] a) {
            int size = size();
            if(a.length < size)
                a = (T[])java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
            if(a.length > size)
                a[size] = null;
            return (T[]) fillEntryArray(a);
        }
        private Object[] fillEntryArray(Object[] a) {
            int j = 0;
            for(int i = 0; i < vals.length; i++)
                if(vals[i] != null)
                    a[j++] = new AbstractMap.SimpleEntry<>(keyUniverse[i], unmaskNull(vals[i]));
            return a;
        }
    }

    private abstract class EnumMapIterator<T> implements Iterator<T> {
        // Lower bound on index of next element to return
        int index = 0;

        // Index of last returned element, or -1 if none
        int lastReturnedIndex = -1;

        public boolean hasNext() {
            while(index < vals.length && vals[index] == null)
                index++;
            return index != vals.length;
        }

        public void remove() {
            checkLastReturnedIndex();

            if(vals[lastReturnedIndex] != null) {
                vals[lastReturnedIndex] = null;
                size--;
            }
            lastReturnedIndex = -1;
        }

        private void checkLastReturnedIndex() {
            if(lastReturnedIndex < 0)
                throw new IllegalStateException();
        }
    }

    private class KeyIterator extends EnumMapIterator<K> {
        public K next() {
            if(!hasNext())
                throw new NoSuchElementException();
            lastReturnedIndex = index++;
            return keyUniverse[lastReturnedIndex];
        }
    }

    private class ValueIterator extends EnumMapIterator<V> {
        public V next() {
            if(!hasNext())
                throw new NoSuchElementException();
            lastReturnedIndex = index++;
            return unmaskNull(vals[lastReturnedIndex]);
        }
    }

    private class EntryIterator extends EnumMapIterator<Entry<K,V>> {
        private EntryIterator.Entry lastReturnedEntry;

        public Map.Entry<K,V> next() {
            if (!hasNext())
                throw new NoSuchElementException();
            lastReturnedEntry = new EntryIterator.Entry(index++);
            return lastReturnedEntry;
        }

        public void remove() {
            lastReturnedIndex =
                    ((null == lastReturnedEntry) ? -1 : lastReturnedEntry.index);
            super.remove();
            lastReturnedEntry.index = lastReturnedIndex;
            lastReturnedEntry = null;
        }

        private class Entry implements Map.Entry<K,V> {
            private int index;

            private Entry(int index) {
                this.index = index;
            }

            public K getKey() {
                checkIndexForEntryUse();
                return keyUniverse[index];
            }

            public V getValue() {
                checkIndexForEntryUse();
                return unmaskNull(vals[index]);
            }

            public V setValue(V value) {
                checkIndexForEntryUse();
                V oldValue = unmaskNull(vals[index]);
                vals[index] = maskNull(value);
                return oldValue;
            }

            public boolean equals(Object o) {
                if(index < 0)
                    return o == this;

                if(!(o instanceof Map.Entry<?, ?> e))
                    return false;

                V ourValue = unmaskNull(vals[index]);
                Object hisValue = e.getValue();
                return (e.getKey() == keyUniverse[index] &&
                        (ourValue == hisValue || (ourValue != null && ourValue.equals(hisValue))));
            }

            public int hashCode() {
                if(index < 0)
                    return super.hashCode();

                return entryHashCode(index);
            }

            public String toString() {
                if(index < 0)
                    return super.toString();

                return keyUniverse[index] + "=" + unmaskNull(vals[index]);
            }

            private void checkIndexForEntryUse() {
                if(index < 0)
                    throw new IllegalStateException("Entry was removed");
            }
        }
    }

    // Comparison and hashing

    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o instanceof EnumMap)
            return equals(o);
        if (!(o instanceof Map<?, ?> m))
            return false;

        if (size != m.size())
            return false;

        for (int i = 0; i < keyUniverse.length; i++) {
            if (null != vals[i]) {
                K key = keyUniverse[i];
                V value = unmaskNull(vals[i]);
                if (null == value) {
                    if (!((null == m.get(key)) && m.containsKey(key)))
                        return false;
                } else {
                    if (!value.equals(m.get(key)))
                        return false;
                }
            }
        }

        return true;
    }

    private boolean equals(EnumMap<?,?> em) {
        if (em.size != size)
            return false;

        if (em.keyType != keyType)
            return size == 0;

        // Key types match, compare each value
        for (int i = 0; i < keyUniverse.length; i++) {
            Object ourValue = vals[i];
            Object hisValue = em.vals[i];
            if (hisValue != ourValue && (hisValue == null || !hisValue.equals(ourValue)))
                return false;
        }
        return true;
    }

    public int hashCode() {
        int h = 0;
        for(int i = 0; i < keyUniverse.length; i++) {
            if(null != vals[i]) {
                h += entryHashCode(i);
            }
        }
        return h;
    }

    private int entryHashCode(int index) {
        return (keyUniverse[index].hashCode() ^ vals[index].hashCode());
    }

    public EnumMap<K, V> clone() {
        EnumMap<K, V> result = null;
        try {
            result = (EnumMap<K, V>) super.clone();
        } catch(CloneNotSupportedException e) {
            throw new AssertionError();
        }
        result.vals = result.vals.clone();
        result.entrySet = null;
        return result;
    }

    private void typeCheck(K key) {
        Class<?> keyClass = key.getClass();
        if(keyClass != keyType && keyClass.getSuperclass() != keyType)
            throw new ClassCastException(keyClass + " != " + keyType);
    }

    private static <K extends Enum<K>> K[] getKeyUniverse(Class<K> keyType) {
        return SharedSecrets.getJavaLangAccess().getEnumConstantsShared(keyType);
    }

    @java.io.Serial
    private static final long serialVersionUID = 458661240069192865L;

    @java.io.Serial
    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        // Write out the key type and any hidden stuff
        s.defaultWriteObject();

        // Write out size (number of Mappings)
        s.writeInt(size);

        // Write out keys and values (alternating)
        int entriesToBeWritten = size;
        for (int i = 0; entriesToBeWritten > 0; i++) {
            if (null != vals[i]) {
                s.writeObject(keyUniverse[i]);
                s.writeObject(unmaskNull(vals[i]));
                entriesToBeWritten--;
            }
        }
    }

    @java.io.Serial
    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        // Read in the key type and any hidden stuff
        s.defaultReadObject();

        keyUniverse = getKeyUniverse(keyType);
        vals = new Object[keyUniverse.length];

        // Read in size (number of Mappings)
        int size = s.readInt();

        // Read the keys and values, and put the mappings in the HashMap
        for (int i = 0; i < size; i++) {
            K key = (K) s.readObject();
            V value = (V) s.readObject();
            put(key, value);
        }
    }
}

package com.github.liyibo1110.jdk.java.util;

import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 该类为Map接口提供了一个骨架实现，以最大限度地减少实现该接口所需的工作量。
 * 要实现不可修改的映射，程序员只需继承此类并为entrySet方法提供实现，该方法返回映射关系的集合视图。
 * 通常，返回的集合将基于AbstractSet实现。该集合不应支持add或remove方法，其迭代器也不应支持remove方法。
 *
 * 若要实现可修改的映射，程序员还需重写本类的put方法（否则将抛出 UnsupportedOperationException），且entrySet().iterator()返回的迭代器必须额外实现其remove方法。
 * 根据Map接口规范的建议，程序员通常应提供void（无参数）和map构造函数。
 * 本类中每个非抽象方法的文档均详细描述了其实现方式。若所实现的映射支持更高效的实现方案，这些方法均可被覆盖。
 * @author liyibo
 * @date 2026-02-24 19:06
 */
public abstract class AbstractMap<K, V> implements Map<K, V> {

    protected AbstractMap() {}

    // Query Operations

    public int size() {
        return entrySet().size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean containsValue(Object value) {
        Iterator<Entry<K,V>> iter = entrySet().iterator();
        if(value == null) {
            while(iter.hasNext()) {
                Entry<K,V> e = iter.next();
                if(e.getValue()==null)
                    return true;
            }
        }else {
            while(iter.hasNext()) {
                Entry<K,V> e = iter.next();
                if(value.equals(e.getValue()))
                    return true;
            }
        }
        return false;
    }

    public boolean containsKey(Object key) {
        Iterator<Map.Entry<K,V>> iter = entrySet().iterator();
        if (key==null) {
            while(iter.hasNext()) {
                Entry<K,V> e = iter.next();
                if(e.getKey()==null)
                    return true;
            }
        }else {
            while(iter.hasNext()) {
                Entry<K,V> e = iter.next();
                if(key.equals(e.getKey()))
                    return true;
            }
        }
        return false;
    }

    public V get(Object key) {
        Iterator<Entry<K,V>> iter = entrySet().iterator();
        if (key==null) {
            while(iter.hasNext()) {
                Entry<K,V> e = iter.next();
                if(e.getKey()==null)
                    return e.getValue();
            }
        } else {
            while(iter.hasNext()) {
                Entry<K,V> e = iter.next();
                if(key.equals(e.getKey()))
                    return e.getValue();
            }
        }
        return null;
    }

    // Modification Operations

    public V put(K key, V value) {
        throw new UnsupportedOperationException();
    }

    public V remove(Object key) {
        Iterator<Entry<K,V>> iter = entrySet().iterator();
        Entry<K,V> correctEntry = null;
        if(key == null) {
            while(correctEntry == null && iter.hasNext()) {
                Entry<K,V> e = iter.next();
                if(e.getKey() == null)
                    correctEntry = e;
            }
        }else {
            while (correctEntry==null && iter.hasNext()) {
                Entry<K,V> e = iter.next();
                if(key.equals(e.getKey()))
                    correctEntry = e;
            }
        }

        V oldValue = null;
        if(correctEntry != null) {
            oldValue = correctEntry.getValue();
            iter.remove();
        }
        return oldValue;
    }

    // Bulk Operations

    public void putAll(Map<? extends K, ? extends V> m) {
        for(Map.Entry<? extends K, ? extends V> e : m.entrySet())
            put(e.getKey(), e.getValue());
    }

    public void clear() {
        entrySet().clear();
    }

    // Views

    transient Set<K> keySet;
    transient Collection<V> values;

    public java.util.Set<K> keySet() {
        Set<K> ks = keySet;
        if(ks == null) {
            ks = new AbstractSet<K>() {
                @Override
                public java.util.Iterator<K> iterator() {
                    return new java.util.Iterator<>() {
                        private java.util.Iterator<Entry<K,V>> iter = entrySet().iterator();

                        @Override
                        public boolean hasNext() {
                            return iter.hasNext();
                        }

                        @Override
                        public K next() {
                            return iter.next().getKey();
                        }

                        @Override
                        public void remove() {
                            iter.remove();
                        }
                    };
                }

                @Override
                public int size() {
                    return AbstractMap.this.size();
                }

                @Override
                public boolean isEmpty() {
                    return AbstractMap.this.isEmpty();
                }

                @Override
                public void clear() {
                    AbstractMap.this.clear();
                }

                @Override
                public boolean contains(Object k) {
                    return AbstractMap.this.containsKey(k);
                }
            };
            keySet = ks;
        }
        return ks;
    }

    public Collection<V> values() {
        Collection<V> vals = values;
        if (vals == null) {
            vals = new AbstractCollection<V>() {
                public java.util.Iterator<V> iterator() {
                    return new java.util.Iterator<V>() {
                        private java.util.Iterator<Entry<K,V>> iter = entrySet().iterator();

                        @Override
                        public boolean hasNext() {
                            return iter.hasNext();
                        }

                        @Override
                        public V next() {
                            return iter.next().getValue();
                        }

                        @Override
                        public void remove() {
                            iter.remove();
                        }
                    };
                }

                @Override
                public int size() {
                    return AbstractMap.this.size();
                }

                @Override
                public boolean isEmpty() {
                    return AbstractMap.this.isEmpty();
                }

                @Override
                public void clear() {
                    AbstractMap.this.clear();
                }

                @Override
                public boolean contains(Object v) {
                    return AbstractMap.this.containsValue(v);
                }
            };
            values = vals;
        }
        return vals;
    }

    public abstract Set<Entry<K,V>> entrySet();

    // Comparison and hashing

    public boolean equals(Object o) {
        if(o == this)
            return true;

        if(!(o instanceof Map<?, ?> m))
            return false;
        if(m.size() != size())
            return false;

        try {
            for(Entry<K, V> e : entrySet()) {
                K key = e.getKey();
                V value = e.getValue();
                if(value == null) {
                    if(!(m.get(key) == null && m.containsKey(key)))
                        return false;
                }else {
                    if(!value.equals(m.get(key)))
                        return false;
                }
            }
        } catch (ClassCastException unused) {
            return false;
        } catch (NullPointerException unused) {
            return false;
        }

        return true;
    }

    public int hashCode() {
        int h = 0;
        for(Entry<K, V> entry : entrySet())
            h += entry.hashCode();
        return h;
    }

    public String toString() {
        java.util.Iterator<Entry<K,V>> i = entrySet().iterator();
        if(!i.hasNext())
            return "{}";

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for(;;) {
            Entry<K,V> e = i.next();
            K key = e.getKey();
            V value = e.getValue();
            sb.append(key == this ? "(this Map)" : key);
            sb.append('=');
            sb.append(value == this ? "(this Map)" : value);
            if(!i.hasNext())
                return sb.append('}').toString();
            sb.append(',').append(' ');
        }
    }

    protected Object clone() throws CloneNotSupportedException {
        AbstractMap<?,?> result = (AbstractMap<?,?>)super.clone();
        result.keySet = null;
        result.values = null;
        return result;
    }

    private static boolean eq(Object o1, Object o2) {
        return o1 == null ? o2 == null : o1.equals(o2);
    }

    /**
     * Entry实现，用于维护key和value，可以通过setValue方法修改value。
     * 该类的实例不与任何Map的Entry视图相关联。
     */
    public static class SimpleEntry<K, V> implements Entry<K, V>, Serializable {
        private static final long serialVersionUID = -8499721149061103585L;

        private final K key;
        private V value;

        public SimpleEntry(K key, V value) {
            this.key   = key;
            this.value = value;
        }

        public SimpleEntry(Entry<? extends K, ? extends V> entry) {
            this.key   = entry.getKey();
            this.value = entry.getValue();
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public V setValue(V value) {
            V oldValue = this.value;
            this.value = value;
            return oldValue;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Map.Entry<?, ?> e && eq(key, e.getKey()) && eq(value, e.getValue());
        }

        @Override
        public int hashCode() {
            return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }

    /**
     * 一种不可修改的Entry实现，用于维护key和value，该类不支持setValue。
     * 该类的实例不与任何Map的Entry视图相关联。
     */
    public static class SimpleImmutableEntry<K, V> implements Entry<K, V>, Serializable {
        private static final long serialVersionUID = 7138329143949025153L;

        private final K key;
        private final V value;

        public SimpleImmutableEntry(K key, V value) {
            this.key   = key;
            this.value = value;
        }

        public SimpleImmutableEntry(Entry<? extends K, ? extends V> entry) {
            this.key   = entry.getKey();
            this.value = entry.getValue();
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public V setValue(V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Map.Entry<?, ?> e && eq(key, e.getKey()) && eq(value, e.getValue());
        }

        @Override
        public int hashCode() {
            return (key   == null ? 0 :   key.hashCode()) ^ (value == null ? 0 : value.hashCode());
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }
}

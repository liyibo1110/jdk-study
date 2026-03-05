package com.github.liyibo1110.jdk.java.util;

import java.util.Arrays;

/**
 * 用于构建由分隔符分隔的字符序列，可选地以指定的前缀开头并以指定的后缀结尾。
 * 在向StringJoiner添加内容之前，其sj.toString()方法默认会返回前缀 + 后缀。但若调用setEmptyValue方法，则会返回指定的空值。
 * 例如在使用集合表示法创建字符串时，可通过此特性表示空集合（即“{}”）：此时前缀为“{”，后缀为“}”，且无需向StringJoiner添加任何内容。
 * @author liyibo
 * @date 2026-03-04 15:14
 */
public final class StringJoiner {
    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private final String prefix;
    private final String delimiter;
    private final String suffix;

    /** 包含一直以来添加的所有字符串 */
    private String[] elts;

    /** 就是elts的size */
    private int size;

    /** elts里每个字符串的总长度 */
    private int len;

    /**
     * 当用户通过setEmptyValue(CharSequence)方法将其覆盖为非空值时，此字符串表示尚未添加任何元素时toString()方法返回的值。
     * 若为 null，则使用前缀 + 后缀作为空值。
     */
    private String emptyValue;

    public StringJoiner(CharSequence delimiter) {
        this(delimiter, "", "");
    }

    public StringJoiner(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
        Objects.requireNonNull(prefix, "The prefix must not be null");
        Objects.requireNonNull(delimiter, "The delimiter must not be null");
        Objects.requireNonNull(suffix, "The suffix must not be null");
        this.prefix = prefix.toString();
        this.delimiter = delimiter.toString();
        this.suffix = suffix.toString();
        checkAddLength(0, 0);
    }

    /**
     * 设置在确定此字符串连接器字符串表示形式时使用的字符序列，即在尚未添加任何元素（空状态）时使用。为此会复制空值参数。请注意，一旦调用添加方法，字符串连接器将不再被视为空状态，即使添加的元素对应于空字符串也是如此。
     */
    public StringJoiner setEmptyValue(CharSequence emptyValue) {
        this.emptyValue = Objects.requireNonNull(emptyValue, "The empty value must not be null").toString();
        return this;
    }

    @Override
    public String toString() {
        final int size = this.size;
        var elts = this.elts;
        if(size == 0) {
            if(emptyValue != null)
                return emptyValue;
            elts = EMPTY_STRING_ARRAY;
        }
        return JLA.join(prefix, suffix, delimiter, elts, size);
    }

    public StringJoiner add(CharSequence newElement) {
        final String elt = String.valueOf(newElement);
        if(elts == null) {
            elts = new String[8];
        }else {
            if(size == elts.length) // 扩容
                elts = Arrays.copyOf(elts, 2 * size);
            len = checkAddLength(len, delimiter.length());
        }
        len = checkAddLength(len, elt.length());
        elts[size++] = elt;
        return this;
    }

    private int checkAddLength(int oldLen, int inc) {
        long newLen = (long)oldLen + (long)inc;
        long tmpLen = newLen + (long)prefix.length() + (long)suffix.length();
        if(tmpLen != (int)tmpLen)
            throw new OutOfMemoryError("Requested array size exceeds VM limit");
        return (int)newLen;
    }

    public StringJoiner merge(StringJoiner other) {
        Objects.requireNonNull(other);
        if(other.size == 0)
            return this;
        other.compactElts();
        return add(other.elts[0]);
    }

    /**
     * elts里面的元素，压缩成1个元素
     */
    private void compactElts() {
        int sz = size;
        if(sz > 1) {
            elts[0] = JLA.join("", "", delimiter, elts, sz);
            Arrays.fill(elts, 1, sz, null);
            size = 1;
        }
    }

    public int length() {
        return (size == 0 && emptyValue != null)
                ? emptyValue.length()
                : len + prefix.length() + suffix.length();
    }

    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
}

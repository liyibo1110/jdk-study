package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * 从字符输入流中读取文本，通过缓冲字符来高效读取字符、数组和行。
 * 缓冲区大小可以指定，也可以使用默认大小，默认值对大多数用途而言已足够大。
 * 通常对Reader的每次读取请求都会触发底层字符流或字节流的对应读取操作，因此建议将BufferedReader封装在可能存在高开销读取操作的Reader
 * （如FileReader和InputStreamReader）外层，例如：
 * BufferedReader in = new BufferedReader(new FileReader(“foo.in”));
 *
 * 若不启用缓冲，每次调用read()或readLine()都需要从文件读取字节、转换为字符再返回，效率很低。
 * 使用DataInputStream进行文本输入的程序，可通过将每个DataInputStream替换为适配的BufferedReader实现本地化处理。
 * @author liyibo
 * @date 2026-02-28 13:15
 */
public class BufferedReader extends Reader {

    /** 下层委托的Reader，一般就是InputStreamReader */
    private Reader in;

    /** 核心字符缓冲区，默认8K */
    private char[] cb;

    /** cb里面有效字符数量 */
    private int nChars;

    /** cb下一个要读的位置 */
    private int nextChar;

    /** 固定值-2，代表mark失效 */
    private static final int INVALIDATED = -2;

    /** 固定值-1，代表没设置mark */
    private static final int UNMARKED = -1;

    /** mark的当前位置 */
    private int markedChar = UNMARKED;

    /** 代表最多允许读多少个字符，仍然能reset，例如调用了mark(100)，就是最多读100个字符，还能执行reset */
    private int readAheadLimit = 0;

    /** 是否跳过下一个换行符（line feed）, 因为windows的换行是\r\n，如果readLine遇到了\r，就要结束行，同时要跳过后面的\n */
    private boolean skipLF = false;

    /** 在执行mark方法的同时记录skipLF的值，并在执行reset时恢复 */
    private boolean markedSkipLF = false;

    /** 默认cb的容量 */
    private static int defaultCharBufferSize = 8192;

    /** 默认readLine里StringBuilder的容量，默认80个字符 */
    private static int defaultExpectedLineLength = 80;

    public BufferedReader(Reader in, int sz) {
        super(in);
        if(sz <= 0)
            throw new IllegalArgumentException("Buffer size <= 0");
        this.in = in;
        cb = new char[sz];
        this.nChars = 0;
        this.nextChar = 0;
    }

    public BufferedReader(Reader in) {
        this(in, defaultCharBufferSize);
    }

    private void ensureOpen() throws IOException {
        if(in == null)
            throw new IOException("Stream closed");
    }

    /**
     * 负责从Reader读取字符，重新填充cb（可能扩容）。
     */
    private void fill() throws IOException {
        int dst;
        if(markedChar <= UNMARKED) {    // 没有开启mark
            dst = 0;
        }else {
            int delta = nextChar - markedChar;  // mark之后又读进来的字符数
            if(delta >= readAheadLimit) {   // 超过readAheadLimit了，mark标记为无效
                markedChar = INVALIDATED;
                readAheadLimit = 0;
                dst = 0;
            }else { // 没超过readAheadLimit
                if(readAheadLimit <= cb.length) {   // 将mark部分的字符移到cb最前面予以保留
                    System.arraycopy(cb, markedChar, cb, 0, delta);
                    markedChar = 0; // mark下标变成0了
                    dst = delta;
                }else {     // readAheadLimit过大，则直接重新构建足够大的cb
                    char[] ncb = new char[readAheadLimit];
                    System.arraycopy(cb, markedChar, ncb, 0, delta);
                    cb = ncb;
                    markedChar = 0;
                    dst = delta;
                }
                // cb发生了数据变化，重新修改2个核心字段的对应数目和位置
                nChars = delta;
                nextChar = delta;
            }
            dst = 0;
        }

        int n;
        do {
            n = in.read(cb, dst, cb.length - dst);
        } while(n == 0);
        if(n > 0) {
            nChars = dst + n;
            nextChar = dst;
        }
    }

    /**
     * 读取1个字符（实际是char，会以int形式返回）
     */
    public int read() throws IOException {
        synchronized(lock) {
            ensureOpen();
            while(true) {
                if(nextChar >= nChars) {    // 这个判断的是，cb是否为空
                    fill();
                    if(nextChar >= nChars)  // 底层也没新数据了
                        return -1;
                }
                if(skipLF) {    // 如果开启了skipLF，下一个读到的字符如果是\n，则直接丢弃再继续读一次（说明之前读到的是\r）
                    skipLF = false;
                    if(cb[nextChar] == '\n') {
                        nextChar++;
                        continue;
                    }
                }
                return cb[nextChar++];
            }
        }
    }

    private int read1(char[] cbuf, int off, int len) throws IOException {
        if(nextChar >= nChars) {   // cb为空
            if(len >= cb.length && markedChar <= UNMARKED && !skipLF)   // 如果len过大就直接从底层流读，不走cbuf中转了
                return in.read(cbuf, off, len);
            fill();
        }
        if(nextChar >= nChars)  // 底层流没有数据可读了
            return -1;
        if(skipLF) {
            skipLF = false;
            if(cb[nextChar] == '\n') {
                nextChar++;
                if(nextChar >= nChars)
                    fill();
                if(nextChar >= nChars)
                    return -1;
            }
        }
        int n = Math.min(len, nChars - nextChar);
        System.arraycopy(cb, nextChar, cbuf, off, n);
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        synchronized(lock) {
            ensureOpen();
            Objects.checkFromIndexSize(off, len, cbuf.length);
            if(len == 0)
                return 0;

            int n = read1(cbuf, off, len);
            if(n <= 0)
                return n;
            while(n < len && in.ready()) {
                int n1 = read1(cbuf, off + n, len - n);
                if(n1 < 0)
                    break;
                n += n1;
            }
            return n;
        }
    }

    /**
     * 读取一行文本（不含换行符），行结束标志包括换行符\n，回车符\r，回车符后紧跟换行符，或达到文件末尾EOF。
     * @param ignoreLF 下一次如果看到\n就跳过（常见于处理\r\n时）
     * @param term 这一行是否遇到了行终止符（\n或\r），true代表这一行是被换行符截断的，false代表这一行是EOF阶段的（文件末尾没有换行符）
     */
    String readLine(boolean ignoreLF, boolean[] term) throws IOException {
        StringBuilder sb = null;    // 一行跨越了多个buffer读取才会使用
        int startChar;  // 本轮在cb里，当前读取片段的起点

        synchronized(lock) {
            ensureOpen();
            boolean omitLF = ignoreLF || skipLF;    // 本轮扫描之前，是否要丢掉开头的\n
            if(term != null)
                term[0] = false;

            bufferLoop:
            while(true) {   // while循环负责fill和扫描，即实现了跨buffer的readLine行为
                if(nextChar >= nChars)  // cbuf为空
                    fill();
                if(nextChar >= nChars) {    // EOF
                    if(sb != null && sb.length() > 0)
                        return sb.toString();
                    else
                        return null;
                }

                boolean eol = false;    // 本轮buffer扫描是否遇到了换行符
                char c = 0; // 命中的换行字符
                int i;  // 扫描下标

                /**
                 * omitLF为true，则说明：
                 * 1、上次看到了\r(skipLF=true)，或调用者传的ignoreLF=true
                 * 2、这次如果紧接着是\n，它不应该算新的一行的开始，应该丢掉
                 * 注意只跳过1个\n，不是跳过所有的\n
                 */
                if(omitLF && (cb[nextChar] == '\n'))
                    nextChar++;
                skipLF = false;
                omitLF = false;

                charLoop:
                for(i = nextChar; i < nChars; i++) {    // 在当前buffer中扫描\n或\r
                    c = cb[i];
                    if(c == '\n' || c == '\r') {
                        if(term != null)
                            term[0] = true;
                        eol = true;
                        break charLoop; // 跳出for循环
                    }
                }

                startChar = nextChar;
                nextChar = i;

                if(eol) {   // 本轮遇到了eol，拼接并返回
                    String str;
                    if(sb == null)  // 说明整行都在当前buffer内，不跨buffer，所以直接不使用StringBuilder
                        str = new String(cb, startChar, i - startChar);
                    else {  // 说明之前已经跨buffer累积过了
                        sb.append(cb, startChar, i - startChar);
                        str = sb.toString();
                    }
                    nextChar++; // 之前nextChar就是i，而i是换行符，需要跳过

                    /**
                     * 如果命中的是\r，则设置skipLF，这是为了处理CRLF的关键
                     * 1、本行遇到\r结束
                     * 2、下一次readLine/read进入时，如果紧跟一个\n，应该跳过
                     * 3、所以要设置skipLF=true
                     */
                    if(c == '\r')
                        skipLF = true;
                    return str;
                }

                // 到这里说明本轮没有没有eol，然后buffer读完了，说明这行要跨越buffer，这里才会初始化StringBuilder
                if(sb == null)
                    sb = new StringBuilder(defaultExpectedLineLength);
                sb.append(cb, startChar, i - startChar);
            }
        }
    }

    public String readLine() throws IOException {
        return readLine(false, null);
    }

    public long skip(long n) throws IOException {
        if(n < 0L)
            throw new IllegalArgumentException("skip value is negative");
        synchronized (lock) {
            ensureOpen();
            long r = n; // r表示确实skip的字符数
            while(r > 0) {
                if(nextChar >= nChars)  // buffer没得读了
                    fill();
                if(nextChar >= nChars)  // EOF
                    break;
                if(skipLF) {
                    skipLF = false;
                    if(cb[nextChar] == '\n')
                        nextChar++;
                }
                long d = nChars - nextChar;
                if(r <= d) {   // buffer里有足够要skip了，直接skip完事
                    nextChar += r;
                    r = 0;
                    break;
                }else { // buffer里没有足够要skip，还剩多少就先skip多少，然后进行下一轮fill
                    r -= d;
                    nextChar = nChars;
                }
            }
            return n - r;
        }
    }

    /**
     * 指示该流是否已准备好被读取，若buffer不为空，或底层字符流已经ready，则返回true
     */
    public boolean ready() throws IOException {
        synchronized(lock) {
            ensureOpen();
            if(skipLF) {    // 特殊情况，刚刚读走了1行，要判断下一个字符是否是换行符，如果是就要顺便先跳过
                if(nextChar >= nChars && in.ready())
                    fill();
                if(nextChar < nChars) {
                    if(cb[nextChar] == '\n')
                        nextChar++;
                    skipLF = false;
                }
            }
            return nextChar < nChars || in.ready();
        }
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) throws IOException {
        if(readAheadLimit < 0)
            throw new IllegalArgumentException("Read-ahead limit < 0");
        synchronized(lock) {
            ensureOpen();
            this.readAheadLimit = readAheadLimit;
            markedChar = nextChar;
            markedSkipLF = skipLF;
        }
    }

    public void reset() throws IOException {
        synchronized(lock) {
            ensureOpen();
            if(markedChar < 0)
                throw new IOException((markedChar == INVALIDATED)
                        ? "Mark invalid"
                        : "Stream not marked");
            nextChar = markedChar;
            skipLF = markedSkipLF;
        }
    }

    public void close() throws IOException {
        synchronized(lock) {
            if(in == null)
                return;
            try {
                in.close();
            } finally {
                in = null;
                cb = null;
            }
        }
    }

    public Stream<String> lines() {
        Iterator<String> iter = new Iterator<>() {
            String nextLine = null;

            @Override
            public boolean hasNext() {
                if(nextLine != null) {
                    return true;
                }else {
                    try {
                        nextLine = readLine();
                        return nextLine != null;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }

            @Override
            public String next() {
                if(nextLine != null || hasNext()) {
                    String line = nextLine;
                    nextLine = null;
                    return line;
                }else {
                    throw new NoSuchElementException();
                }
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iter,
                Spliterator.ORDERED | Spliterator.NONNULL), false);
    }
}



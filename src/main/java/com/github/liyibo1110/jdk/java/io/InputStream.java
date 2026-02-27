package com.github.liyibo1110.jdk.java.io;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 所有表示字节输入流类的基类。
 * 需要定义InputStream子类的应用程序必须始终提供一个返回下一个输入字节的方法。
 *
 * 个人注：字节输入流的顶层抽象。
 * @author liyibo
 * @date 2026-02-26 00:48
 */
public abstract class InputStream implements Closeable {

    /** 用于确定跳过操作时使用的最大缓冲区大小。 */
    private static final int MAX_SKIP_BUFFER_SIZE = 2048;

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    public InputStream() {}

    /**
     * 从输入流中读取下一个字节数据，返回值为字节值，作为0到255范围内的整数，若因到达流尾而无可用字节，则返回-1。
     * 该方法将阻塞直到满足以下任一条件：
     * 1、输入数据可用。
     * 2、检测到流尾。
     * 3、抛出了异常。
     * 子类必须提供此方法的实现。
     */
    public abstract int read() throws IOException;

    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    /**
     * 从输入流中读取最多len个字节的数据到b中，该方法尝试读取最多len个字节，但实际读取的字节数可能少于此值，实际读取的字节数将作为整数返回。
     * 该方法将阻塞直到满足以下任一条件：
     * 1、输入数据可用。
     * 2、检测到流尾。
     * 3、抛出了异常。
     *
     * 若len为零，则不读取任何字节并返回0，否则将尝试至少读取1个字节，若因流已达到文件末尾而无字节可读，则返回-1，否则至少读取1个字节并存储至b中。
     * 首次读取的字节存储于元素b[off]，次字节存储与b[off+1]，依此类推，读取字节数最多等于len。
     * 设k为实际读取的字节数，这些字节将存储与b[off]到b[off+k-1]元素，而b[off+k]到b[off+len-1]元素保持不变。
     * 在所有情况下，元素b[0]到b[off-1]以及元素b[off+len]到b[b.length-1]均不受影响。
     *
     * InputStream类的read(b, off, len)方法仅重复调用read()方法，若首次调用导致IOException，则该异常将从read(b, off, len)方法调用中返回。
     * 若后续任何调用导致IOException，则捕获该异常并视为文件结束，此时读取的字节将存储到b中，并返回异常发生前读取的字节数。
     * 该方法的默认实现会阻塞直到满足以下任一条件：
     * 1、读取完指定长度len的输入数据。
     * 2、检测到文件结束。
     * 3、检测到异常。
     * 鼓励子类提供更搞高效的实现。
     */
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if(len == 0)
            return 0;

        int c = read(); // 先读1个字节，为了看会不会有内容或者有异常
        if(c == -1)
            return -1;
        b[off] = (byte)c;   // 存1个字节

        int i = 1;
        try {
            for(; i < len; i++) {   // 从第2个字节继续循环读
                c = read();
                if(c == -1)
                    break;
                b[off + i] = (byte)c;
            }
        } catch (IOException e) {
            // nothing to do
        }
        return i;
    }

    /** 分配数组的最大尺寸，某些虚拟机会在数组中预留若干头部字节。 */
    private static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    public byte[] readAllBytes() throws IOException {
        return readNBytes(Integer.MAX_VALUE);
    }

    /**
     * 从输入流中读取最多指定字节数的数据，该方法会阻塞，直到读取到要求的字节数、检测到流结束或抛出异常为止。此方法不会关闭输入流。
     * 返回数组的长度等于从流中读取的字节数，若len为零，则未读取任何字节，返回空数组，否则，最多读取len个字节，若遇到流尾，实际读取字节数可能少于len。
     * 当该流到达流尾时，后续调用此方法将返回空数组。
     *
     * 请注意，本方法适用于简单场景，即需要将指定字节数读入数组的情况，该方法分配的内存总量与从流中读取的字节数成正比，
     * 且受len限制，因此只要内存充足，即使len值极大仍可安全调用本方法。
     *
     * 当输入流被异步关闭或读取过程中线程被中断时，其行为高度依赖输入流特性，故未作规范说明。
     * 若从输入流读取时发生I/O错误，可能仅读取部分字节（而非全部），此时输入流可能未到达流尾，且可能处于不一致状态，强烈建议在发生I/O错误时立即关闭该流。
     *
     * 这是在java11新增的方法
     */
    public byte[] readNBytes(int len) throws IOException {
        if(len < 0)
            throw new IllegalArgumentException("len < 0");

        List<byte[]> bufs = null;
        byte[] result = null;
        int total = 0;  // 总共读取到的字节数
        int remaining = len;    // 剩余还要读的字节数（不是实际文件的字节数，可能是Integer.MAX_VALUE）
        int n;
        do {
            byte[] buf = new byte[Math.min(remaining, DEFAULT_BUFFER_SIZE)];
            int nread = 0;  // 本轮已读取的字节数

            while((n = read(buf, nread, Math.min(buf.length - nread, remaining))) > 0) {
                nread += n;
                remaining -= n;
            }

            if(nread > 0) {
                if(MAX_BUFFER_SIZE - total < nread)
                    throw new OutOfMemoryError("Required array size too large");
                if(nread < buf.length)
                    buf = Arrays.copyOfRange(buf, 0, nread);
                total += nread;
                if(result == null) {
                    result = buf;
                }else {
                    if(bufs == null) {
                        bufs = new ArrayList<>();
                        bufs.add(result);
                    }
                    bufs.add(buf);
                }
            }

        } while(n >= 0 && remaining > 0);

        if(bufs == null) {  // 就读取了一轮就完了
            if(result == null)
                return new byte[0];
            return result.length == total ? result : Arrays.copyOf(result, total);
        }

        // bufs最终再全部复制到result里面去
        result = new byte[total];
        int offset = 0;
        remaining = total;
        for(byte[] b : bufs) {
            int count = Math.min(b.length, remaining);
            System.arraycopy(b, 0, result, offset, count);
            offset += count;
            remaining -= count;
        }

        return result;
    }

    public int readNBytes(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);

        int n = 0;
        while(n < len) {
            int count = read(b, off + n, len - n);
            if(count < 0)
                break;
            n += count;
        }
        return n;
    }

    /**
     * 跳过并丢弃输入流中的n个字节数据，由于各种原因，skip方法最终可能跳过的字节数少于n，甚至为0.
     * 可能由多种情况导致，在跳过n个字节之前到达文件末尾只有其中一种可能性，实际跳过的字节数将被返回。
     * 若n为负值，InputStream类的skip方法始终返回0，且不跳过任何字节，子类可对负值进行不同的处理。
     *
     * 本类的skip方法实现会创建byte数组，然后反复读取直至读满n字节或到达流尾，
     * 鼓励子类提供更高效的实现方案，例如可依赖于定位功能。
     */
    public long skip(long n) throws IOException {
        long remaining = n;
        int nr; // 当前读取到的字节数

        if(n <= 0)
            return 0;

        int size = (int)Math.min(MAX_SKIP_BUFFER_SIZE, remaining);
        byte[] skipBuffer = new byte[size];
        while(remaining > 0) {
            nr = read(skipBuffer, 0, (int)Math.min(size, remaining));
            if(nr < 0)
                break;
            remaining -= nr;
        }
        return n - remaining;
    }

    /**
     * 跳过并丢弃此输入流中恰好n个字节的数据。若n为零，则不跳过任何字节。若n为负数，则不跳过任何字节。子类可对负值进行不同处理。
     * 这是在java12新增的方法
     */
    public void skipNBytes(long n) throws IOException {
        while(n > 0) {
            long ns = skip(n);
            if(ns > 0 && ns <= n) {
                n -= ns;    // 精确调整
            }else if(ns == 0) { // 没有字节可以skip
                if(read() == -1)    // 尝试读1个字节，判定是否EOF
                    throw new EOFException();
                n--;
            }else {
                throw new IOException("Unable to skip exactly");
            }
        }
    }

    /**
     * 返回可从输入流中读取（或跳过）而不阻塞的字节数估计值，该值可能为0，或在检测到流尾时为0.
     * 读取操作可能在当前线程或另一个线程中执行，单次读取或跳过此数量的字节不会阻塞，但实际读取或跳过的字节数可能少于估计值。
     *
     * 请注意，虽然某些InputStream实现会返回流中总字节数，但多数实现不会。绝不应使用本方法的返回值来分配缓冲区以容纳流中所有数据。
     * 若输入流已被close()方法关闭，子类的实现可选择抛出IOException异常。
     * InputStream的available方法始终返回0，子类应该重写此方法。
     */
    public int available() throws IOException {
        return 0;
    }

    /**
     * 关闭此输入流并释放与该流关联的所有系统资源。
     */
    public void close() throws IOException {}

    /**
     * 标记当前输入流的位置，后续调用重置方法将使该流重新定位到最后标记的位置，以便后续读取操作重新读取相同的字节。
     * readlimit参数指示该输入流允许读取指定数量的字节后，标记位置失效。
     *
     * mark方法的一般契约是：若markSupported方法返回true，则流会以某种方式记住mark调用后读取的所有字节，并在reset方法被调用时随时准备再次提供这些相同的字节。
     * 然而，如果在调用reset之前从流中读取的字节超过readlimit，则流无需记住任何数据。
     *
     * 对已关闭的流进行标记不应产生任何效果，InputStream的mark方法不执行任何操作。
     */
    public synchronized void mark(int readlimit) {}

    /**
     * 将此流重置为上次对该输入流调用mark方法时的状态，reset方法的一般契约如下：
     * 如果方法markSupported返回true，则：
     *      如果自流创建以来未调用过mark方法，或自上次调用mark以来从流中读取的字节数大于上次调用mark时的参数值，则可能抛出IOException。
     *      若未抛出此类IOException，则流将重置为以下状态：自最后一次调用mark以来（或若未调用mark则自文件开头以来）读取的所有字节将重新提供给后续调用read方法的对象，随后提供在调用reset时本应作为下一个输入数据的任何字节。
     * 如果方法markSupported返回false，则：
     *      reset调用可能会抛出IOException异常。
     *      如果未抛出IOException异常，则流将重置为固定状态，该状态取决于输入流的具体类型及其创建方式。后续调用read方法时所获取的字节数据，取决于输入流的具体类型。
     *
     * InputStream类的reset方法除了抛出IOException异常外，不执行任何操作。
     */
    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    /**
     * 测试此输入流是否支持标记和重置方法。
     * 标记和重置的支持与否是特定输入流实例的不变属性，InputStream的markSupported方法返回false。
     */
    public boolean markSupported() {
        return false;
    }

    /**
     * 从该输入流读取所有字节，并按读取顺序将字节写入指定的输出流。返回时，该输入流将处于流尾状态。此方法不会关闭任一数据流。
     * 该方法可能无限期阻塞输入流的读取或输出流的写入操作。当输入流和/或输出流被异步关闭，或传输过程中线程被中断时，其行为高度依赖于输入输出流的特性，因此未作具体规定。
     * 若从输入流读取或向输出流写入时发生I/O错误，该错误可能在读取或写入若干字节后才触发。
     * 因此输入流可能未处于流尾状态，且一个或两个流可能处于不一致状态。强烈建议在发生I/O错误时立即关闭两个流。
     * 这是在java9新增的方法。
     */
    public long transferTo(OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out");
        long transferred = 0;
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int read;
        while((read = this.read(buffer, 0, DEFAULT_BUFFER_SIZE)) >= 0) {
            out.write(buffer, 0, read);
            transferred += read;
        }
        return transferred;
    }
}

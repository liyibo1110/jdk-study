package com.github.liyibo1110.jdk.java.io;

import sun.misc.Unsafe;

import java.io.IOException;
import java.io.InputStream;

/**
 * BufferedInputStream为其他输入流添加了功能——即缓冲输入的能力，并支持标记和重置方法。
 * 创建BufferedInputStream时，会生成一个内部缓冲区数组。当从流中读取或跳过字节时，内部缓冲区会根据需要从包含的输入流中逐批重新填充字节。
 * mark操作用于标记输入流中的特定位置，reset操作则会重新读取自上次mark操作以来读取的所有字节，之后才从包含的输入流中获取新字节。
 *
 * 属于装饰器组件。
 * @author liyibo
 * @date 2026-02-26 16:34
 */
public class BufferedInputStream extends FilterInputStream {
    private static int DEFAULT_BUFFER_SIZE = 8192;

    private static final Unsafe U = Unsafe.getUnsafe();

    private static final long BUF_OFFSET = U.objectFieldOffset(BufferedInputStream.class, "buf");

    /** 用于存储数据的内部缓冲数组，必要时，可将其替换为不同大小的另一个数组。 */
    protected volatile byte[] buf;

    /**
     * 该索引比缓冲区中最后一个有效字节的索引大1。
     * 该值始终在0到buf.length范围内，元素buf[0]到buf[count-1]包含从底层输入流获取的缓冲输入数据。
     */
    protected int count;

    /**
     * 缓冲区中当前的位置，这是从buf数组中读取下一个字符的的索引。
     * 该值始终在0到count范围内，若小于count，则buf[pos]是下一个待输入的字节，若等于count，则下一次读取或跳过操作需要从包含的输入流中读取更多字节。
     */
    protected int pos;

    /**
     * 在最后一次调用mark方法时，pos字段的值。
     * 该值始终在-1到pos的范围内。如果输入流中没有标记位置，则此字段为-1。
     * 如果输入流中存在标记位置，则buf[markpos]是重置操作后作为输入提供的第一个字节。
     * 若markpos不为-1，则从buf[markpos]至buf[pos-1]的所有字节必须保留在缓冲区数组中（尽管它们可能被移动到缓冲区数组的其他位置，同时需相应调整count、pos和markpos的值）；
     * 除非pos与markpos之间的差值超过marklimit，否则不得丢弃这些字节。
     */
    protected int markpos = -1;

    /**
     * 调用标记方法后允许的最大预读取量，超过此值后续调用重置方法将失败。
     * 当位置与标记位置的差值超过标记限制时，可通过将标记位置设为-1来丢弃该标记。
     */
    protected int marklimit;

    /**
     * 检查确保底层输入流未因关闭操作而被置为空；若未关闭则返回该流；
     */
    private java.io.InputStream getInIfOpen() throws IOException {
        InputStream input = in;
        if (input == null)
            throw new IOException("Stream closed");
        return input;
    }

    private byte[] getBufIfOpen() throws IOException {
        byte[] buffer = buf;
        if(buffer == null)
            throw new IOException("Stream closed");
        return buffer;
    }

    public BufferedInputStream(InputStream in) {
        this(in, DEFAULT_BUFFER_SIZE);
    }

    public BufferedInputStream(InputStream in, int size) {
        super(in);
        if(size <= 0)
            throw new IllegalArgumentException("Buffer size <= 0");
        buf = new byte[size];
    }

    /**
     * 向内部缓冲区填充更多数据，同时考虑shuffling及其其他处理mark的技巧。
     * 假定该方法由同步方法调用，此方法还假定所有数据已读取完毕，因此pos > count。
     */
    private void fill() throws IOException {
        byte[] buffer = getBufIfOpen();
        if(markpos < 0) // 没有设置markPos，就直接把pos重置
            pos = 0;
        else if(pos >= buffer.length)   {   // 有markPos并且buffer满了，
            if(markpos > 0) {   // markPos后面的要保留，不能清空
                int sz = pos - markpos;
                System.arraycopy(buffer, markpos, buffer, 0, sz);   // 重要：将markPos后面的数据往前移（留出后面的位置）
                pos = sz;
                markpos = 0;
            }else if(buffer.length >= marklimit) {  // marklimit（reset时能回退最多readlimit个字节）设定的太大了，markPos要丢弃了
                markpos = -1;
                pos = 0;
            }else { // 如果markpos=0并且buffer满并且buffer < marklimit，则扩容buffer（mark需要更大的buffer）
                int nsz = ArraysSupport.newLength(pos,
                        1,  /* minimum growth */
                        pos /* preferred growth */);
                if(nsz > marklimit)
                    nsz = marklimit;
                byte[] nbuf = new byte[nsz];
                System.arraycopy(buffer, 0, nbuf, 0, pos);
                // close()可能会异步执行（会执行buf = null），所以CAS要确保buf没被close影响
                if(!U.compareAndSetReference(this, BUF_OFFSET, buffer, nbuf)) {
                    throw new IOException("Stream closed");
                }
                buffer = nbuf;
            }
        }
        count = pos;
        int n = getInIfOpen().read(buffer, pos, buffer.length - pos);   // 从底层尽量读
        if(n > 0)
            count = n + pos;
    }

    public synchronized int read() throws IOException {
        if(pos >= count) {
            fill();
            if(pos >= count)
                return -1;
        }
        return getBufIfOpen()[pos++] & 0xFF;
    }

    /**
     * 将字符读取数组的某一部分，必要时最多从底层流读取一次。
     */
    private int read1(byte[] b, int off, int len) throws IOException {
        int avail = count - pos; // buf中剩余的容量
        if(avail <= 0) {    // 内部buf没有可读的新数据了，需要补充底层数据
            /**
             * 大块读优化，如果用户传的len大于内部buff的容量，并且markPos没有设置，
             * 则直接一次性从底层把用户传来的b给读满，根本不走内部buf了
             */
            if(len >= getBufIfOpen().length && markpos < 0)
                return getInIfOpen().read(b, off, len);
            fill();
            avail = count - pos;
            if(avail <= 0)
                return -1;
        }
        int cnt = (avail < len) ? avail : len;
        System.arraycopy(getBufIfOpen(), pos, b, off, cnt); // 从内部buf向外部b复制数据
        pos += cnt;
        return cnt;
    }

    /**
     * 从该字节输入流中读取字节，填入指定的字节数组，从给定的偏移量开始。
     * 此方法实现了InputStream类对应read方法的通用契约。
     * 作为额外便利，它通过反复调用底层流的read方法尝试读取尽可能多的字节。这种迭代读取将持续进行，直至满足以下任一条件：
     * 1、已读取指定字节数。
     * 2、底层流的读取方法返回-1，表示文件结束。
     * 3、底层流的可用方法返回零，表示后续输入请求将阻塞。
     * 如果对底层流的首次读取返回 -1 表示文件结束，则本方法返回 -1。否则，本方法返回实际读取的字节数。
     * 鼓励（但非强制要求）本类的子类以相同方式尝试读取尽可能多的字节。
     */
    public synchronized int read(byte b[], int off, int len) throws IOException {
        getBufIfOpen();
        /**
         * 位运算等价：
         * 1、off < 0
         * 2、len < 0
         * 3、off + len > b.length
         */
        if((off | len | (off + len) | (b.length - (off + len))) < 0) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        int n = 0;
        while(true) {
            /**
             * 不断调用read1，直到：
             * 1、已读够len字节。
             * 2、read1返回EOF。
             * 3、底层流会阻塞。
             */
            int nread = read1(b, off + n, len - n);
            if(nread <= 0)
                return n == 0 ? nread : n;
            n += nread;
            if(n >= len)
                return n;
            InputStream input = in;
            if(input != null && input.available() <= 0)
                return n;
        }
    }

    public synchronized long skip(long n) throws IOException {
        getBufIfOpen();
        if(n <= 0)
            return 0;
        long avail = count - pos;
        if(avail <= 0) {    // buffer没数据可读了
            if(markpos < 0)
                return getInIfOpen().skip(n);   // 直接skip底层数据即可，不动buffer
            fill(); // 尝试移动位置，或者扩容
            avail = count - pos;
            if(avail <= 0)  // fill完了还是没可用，只能返回0
                return 0;
        }

        long skipped = avail < n ? avail : n;
        pos += skipped; // 操作buffer直接跳过
        return skipped;
    }

    public synchronized int available() throws IOException {
        int n = count - pos;
        int avail = getInIfOpen().available();
        return n > (Integer.MAX_VALUE - avail) ? Integer.MAX_VALUE : n + avail;
    }

    public synchronized void mark(int readlimit) {
        marklimit = readlimit;
        markpos = pos;
    }

    public synchronized void reset() throws IOException {
        getBufIfOpen();
        if(markpos < 0) // 必须调用过mark方法
            throw new IOException("Resetting to invalid mark");
        pos = markpos;  // pos直接回退
    }

    public boolean markSupported() {
        return true;
    }

    public void close() throws IOException {
        byte[] buffer;
        while((buffer = buf) != null) {
            if(U.compareAndSetReference(this, BUF_OFFSET, buffer, null)) {  // buf = null
                InputStream input = in;
                in = null;
                if(input != null)
                    input.close();
                return;
            }
        }
    }
}

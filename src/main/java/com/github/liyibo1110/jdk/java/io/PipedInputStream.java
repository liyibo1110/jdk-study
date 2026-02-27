package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedOutputStream;

/**
 * PipedInputStream应连接至PipedOutputStream，通常数据由一个线程从PipedInputStream对象读取，并由另一个线程写入对应的PipedOutputStream。
 * 不建议单线程同时操作这两个对象，否则可能导致线程死锁。
 * PipedInputStream包含缓冲区，在一定范围内实现读写操作的解耦，若向连接的PipedOutputStream提供数据字节的线程终止运行，则该管道被视为已断开。
 *
 * 属于底层干活流（实际和数据来源打交道），不是装饰器流，挺古老的组件了，但是环形数组 + 线程的配合还是值得学习的，
 * 建议先学习PipedOutputStream，首先代码量更少，而且这里面的一些方法实际是由PipedOutputStream一侧触发而来的，光看这个类可能看不太懂。
 * @author liyibo
 * @date 2026-02-26 22:44
 */
public class PipedInputStream extends InputStream {
    boolean closedByWriter;
    volatile boolean closedByReader;
    boolean connected;

    Thread readSide;
    Thread writeSide;

    private static final int DEFAULT_PIPE_SIZE = 1024;

    protected static final int PIPE_SIZE = DEFAULT_PIPE_SIZE;

    protected byte buffer[];

    /**
     * 当in < 0时表示缓冲区为空，当in == out时表示缓冲区已满，其它情况则表示正常有数据。
     * 即下一个字节要写入的位置。
     */
    protected int in = -1;

    /**
     * 该PipedInputStream将在环形缓冲区中读取下一个字节数据的位置索引。
     *
     * 即下一个字节要读取的位置
     */
    protected int out = 0;

    public PipedInputStream(PipedOutputStream src) throws IOException {
        this(src, DEFAULT_PIPE_SIZE);
    }

    public PipedInputStream(PipedOutputStream src, int pipeSize)
            throws IOException {
        initPipe(pipeSize);
        connect(src);
    }

    public PipedInputStream() {
        initPipe(DEFAULT_PIPE_SIZE);
    }

    public PipedInputStream(int pipeSize) {
        initPipe(pipeSize);
    }

    private void initPipe(int pipeSize) {
        if(pipeSize <= 0)
            throw new IllegalArgumentException("Pipe Size <= 0");
        buffer = new byte[pipeSize];
    }

    public void connect(PipedOutputStream src) throws IOException {
        src.connect(this);
    }

    /**
     * 接收1个字节的数据，若无可用输入，方法将阻塞。
     * 这个方法是由PipedOutputStream的write直接调用过来的。
     */
    protected synchronized void receive(int b) throws IOException {
        checkStateForReceive();
        writeSide = Thread.currentThread(); // 给读端检测：写端死没死（后面读会用到）
        if(in == out)   // 队列满了，需要等待读取才能继续写入
            awaitSpace();
        if(in < 0) {    // 说明队列是空，先变成满状态
            in = 0;
            out = 0;
        }
        buffer[in++] = (byte)(b & 0xFF);
        if(in <= buffer.length) // 到最后一个位置了，要从头开始存（因为是环形buffer）
            in = 0;
    }

    /**
     * 其实是从PipedOutputStream调用而来，只会修改in值，不会改out的值（因为是写数据）
     */
    synchronized void receive(byte b[], int off, int len)  throws IOException {
        checkStateForReceive();
        writeSide = Thread.currentThread(); // 给读端检测：写端死没死（后面读会用到）
        int bytesToTransfer = len;  // 实际传来的字节数
        while(bytesToTransfer > 0) {
            if(in == out)   // 队列满了，需要等待读取才能继续写入
                awaitSpace();
            int nextTransferAmount = 0; // 最终算出实际传来的字节数
            if(out < in) {
                nextTransferAmount = buffer.length - in;    // 当前最多还能存多少个字节
            }else if (in < out) {
                if(in == -1) {  // 队列是空的
                    in = out = 0;
                    nextTransferAmount = buffer.length - in;    // 当前最多还能存多少个字节，在这里就是buffer.length
                }else {
                    nextTransferAmount = out - in;
                }
            }
            if(nextTransferAmount > bytesToTransfer)
                nextTransferAmount = bytesToTransfer;
            assert(nextTransferAmount > 0);
            System.arraycopy(b, off, buffer, in, nextTransferAmount);   // 写入内部buffer
            bytesToTransfer -= nextTransferAmount;
            off += nextTransferAmount;
            in += nextTransferAmount;
            if(in >= buffer.length)
                in = 0;
        }
    }

    private void checkStateForReceive() throws IOException {
        if(!connected)
            throw new IOException("Pipe not connected");
        else if(closedByWriter || closedByReader)
            throw new IOException("Pipe closed");
        else if(readSide != null && !readSide.isAlive())
            throw new IOException("Read end dead");
    }

    private void awaitSpace() throws IOException {
        while(in == out) {  // 缓冲区满了，需要读取线程把数据读走
            checkStateForReceive();
            notifyAll();
            try {
                wait(1000); // 等读取线程把数据读走
            } catch (InterruptedException e) {
                throw new java.io.InterruptedIOException();
            }
        }
    }

    /**
     * 由PipedOutputStream的close方法里面调用的
     */
    synchronized void receivedLast() {
        closedByWriter = true;
        notifyAll();
    }

    /**
     * 这个方法是read线程自己调用的了
     */
    public synchronized int read()  throws IOException {
        if(!connected)
            throw new IOException("Pipe not connected");
        else if (closedByReader)
            throw new IOException("Pipe closed");
        else if (writeSide != null && !writeSide.isAlive() && !closedByWriter && (in < 0))
            throw new IOException("Write end dead");

        readSide = Thread.currentThread();
        int trials = 2;
        while(in < 0) { // 内部buffer没有数据可以读了
            if(closedByWriter) {
                /* closed by writer, return EOF */
                return -1;
            }
            if((writeSide != null) && (!writeSide.isAlive()) && (--trials < 0))
                throw new IOException("Pipe broken");

            /* might be a writer waiting */
            notifyAll();
            try {
                wait(1000);
            } catch (InterruptedException ex) {
                throw new java.io.InterruptedIOException();
            }
        }
        int ret = buffer[out++] & 0xFF;
        if(out >= buffer.length) {
            out = 0;
        }
        /** 非常重要的逻辑，如果in和out下标一样了，说明此时buffer数据全被读完了，要把in改回-1来表示数据全被读完了或者压根就没有数据 */
        if(in == out) {
            /* now empty */
            in = -1;
        }

        return ret;
    }

    /**
     * 这个方法是read线程自己调用的了
     */
    public synchronized int read(byte b[], int off, int len)  throws IOException {
        if (b == null)
            throw new NullPointerException();
        else if (off < 0 || len < 0 || len > b.length - off)
            throw new IndexOutOfBoundsException();
        else if (len == 0)
            return 0;

        /* possibly wait on the first character */
        int c = read();
        if(c < 0)
            return -1;

        b[off] = (byte) c;
        int rlen = 1;
        while((in >= 0) && (len > 1)) {
            int available;
            if(in > out)    // 说明还有没读的新字节
                available = Math.min((buffer.length - out), (in - out));
            else    // 已经写满了，必须得读了
                available = buffer.length - out;

            // A byte is read beforehand outside the loop
            if(available > (len - 1)) {
                available = len - 1;
            }
            System.arraycopy(buffer, out, b, off + rlen, available);
            out += available;
            rlen += available;
            len -= available;

            if(out >= buffer.length)
                out = 0;

            /** 非常重要的逻辑，如果in和out下标一样了，说明此时buffer数据全被读完了，要把in改回-1来表示数据全被读完了或者压根就没有数据 */
            if(in == out) {
                /* now empty */
                in = -1;
            }
        }
        return rlen;
    }

    /**
     * 当前buffer可以直接待读取的字节数（但指的不是剩余数量，指的是全部字节数）
     */
    public synchronized int available() throws IOException {
        if(in < 0)
            return 0;
        else if(in == out)
            return buffer.length;
        else if (in > out)
            return in - out;
        else    // 最复杂的在这里，说明数据发生了环绕
            return in + buffer.length - out;
    }

    public void close()  throws IOException {
        closedByReader = true;
        synchronized (this) {
            in = -1;
        }
    }
}

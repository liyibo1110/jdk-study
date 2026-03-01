package com.github.liyibo1110.jdk.java.io;

import java.io.IOException;

/**
 * 待缓冲的字符输入流，用于记录行号。
 * 该类定义了setLineHumber(int)和getLineNumber()方法，分别用于设置和获取当前行号。
 * 默认情况下，行号从0开始计数，数据读取时，每遇到行结束符，行号即递增，若流末尾的最后一个字符非行结束符，则在流结束时递增。
 * 可以通过setLineNumber修改行号，但需注意，setLineNumber不会实际改变流中的当前位置，仅改变getLineNumber返回的值。
 * 行结束标志包括：\r、\n、\r\n，上述任一结束符后紧接流结束符，或未被其他结束符前置的流结束符。
 * @author liyibo
 * @date 2026-03-01 21:58
 */
public class LineNumberReader extends BufferedReader {
    private static final int NONE = 0;  // 没有上一个字符
    private static final int CHAR = 1;  // 非换行符
    private static final int EOL = 2;   // 换行符
    private static final int EOF  = 3;  // 文件结束符

    /**上一个字符的类型 */
    private int prevChar = NONE;

    private int lineNumber = 0;

    private int markedLineNumber;

    private boolean skipLF;

    private boolean markedSkipLF;

    public LineNumberReader(Reader in) {
        super(in);
    }

    public LineNumberReader(Reader in, int sz) {
        super(in, sz);
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    /**
     * 读取单个字符，行结束符会被压缩为单个换行符（\n）。
     * 每次读取行结束符时，或当到达流尾且流中的最后一个字符不是行结束符时，当前行号都会递增。
     */
    public int read() throws IOException {
        synchronized(lock) {
            int c = super.read();
            if(skipLF) {    // 开启了，就再读1个，如果时\n丢弃
                if(c == '\n')
                    c = super.read();
                skipLF = false;
            }
            switch(c) {
                case '\r':  // 只打标记，不增加行号
                    skipLF = true;
                case '\n':  // 增加行号
                    lineNumber++;
                    prevChar = EOL;
                    return '\n';
                case -1:    // EOF
                    if(prevChar == CHAR)
                        lineNumber++;
                    prevChar = EOF;
                    break;
                default:    // 不是\r\n，也不是-1，说明是普通字符
                    prevChar = CHAR;
                    break;
            }
            return c;
        }
    }

    /**
     * 将字符读入数组的一部分，该方法将阻塞直到有输入可用、发生I/O错误或到达流尾。
     * 若长度为零，则不读取任何字符并返回0，否则将尝试读取至少一个字符，若流已到达末尾而无字符可用，则返回值为-1，否则至少读取一个字符并存储至cbuf。
     * 行终止符将压缩为单个换行符（\n）。每次读取行终止符时，或当流末尾处读取的最后一个字符非行终止符时，当前行号将递增。
     */
    public int read(char cbuf[], int off, int len) throws IOException {
        synchronized(lock) {
            int n = super.read(cbuf, off, len);
            if(n == -1) {
                if(prevChar == CHAR)
                    lineNumber++;
                prevChar = EOF;
                return -1;
            }

            for(int i = off; i < off + n; i++) {
                int c = cbuf[i];
                if(skipLF) {
                    skipLF = false;
                    if(c == '\n')
                        continue;
                }
                switch(c) {
                    case '\r':
                        skipLF = true;
                    case '\n':
                        lineNumber++;
                        break;
                }
            }

            if(n > 0) {
                switch((int)cbuf[off + n - 1]) {
                    case '\r':
                    case '\n':
                        prevChar = EOL;
                        break;
                    default:
                        prevChar = CHAR;
                        break;
                }
                return n;
            }
            return n;
        }
    }

    public String readLine() throws IOException {
        synchronized(lock) {
            boolean[] term = new boolean[1];
            String str = super.readLine(skipLF, term);
            skipLF = false;
            if(str != null) {
                lineNumber++;
                prevChar = term[0] ? EOL : EOF;
            }else {
                if(prevChar == CHAR)
                    lineNumber++;
                prevChar = EOF;
            }
            return str;
        }
    }

    private static final int maxSkipBufferSize = 8192;
    private char skipBuffer[] = null;

    public long skip(long n) throws IOException {
        if(n < 0)
            throw new IllegalArgumentException("skip() value is negative");
        int nn = (int)Math.min(n, maxSkipBufferSize);
        synchronized(lock) {
            if(skipBuffer == null || skipBuffer.length < nn)
                skipBuffer = new char[nn];
            long r = n;
            while(r > 0) {
                int nc = read(skipBuffer, 0, (int)Math.min(r, nn));
                if(nc == -1)
                    break;
                r -= nc;
            }
            if(n - r > 0)
                prevChar = NONE;
            return n - r;
        }
    }

    public void mark(int readAheadLimit) throws IOException {
        synchronized(lock) {
            if(skipLF)
                readAheadLimit++;
            super.mark(readAheadLimit);
            markedLineNumber = lineNumber;
            markedSkipLF = skipLF;
        }
    }

    public void reset() throws IOException {
        synchronized(lock) {
            super.reset();
            lineNumber = markedLineNumber;
            skipLF = markedSkipLF;
        }
    }
}

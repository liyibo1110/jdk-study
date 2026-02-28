package com.github.liyibo1110.jdk.java.io;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Formatter;
import java.util.Locale;

/**
 * PrintStream为其他输出流增添了功能，即能够便捷地打印各种数据值的表示形式，它还提供了另外两项特性：
 * 1、与其他输出流不同，PrintStream不会抛出IOException，异常情况仅会设置一个内部标志，可通过checkError方法进行检测。
 * 2、PrintStream可选配自动刷新机制，当写入字节数组，调用println方法或写入换行符（\n）时，底层输出流的flush方法将自动触发。
 *
 * 所有通过PrintStream输出的字符均会根据指定编码或字符集转换为字节，若未指定则采用平台默认字符编码，当需要写入字符而非字节时，应使用PrintWriter类。
 *
 * 该类会将格式错误且无法映射的字符序列始终替换为字符集的默认替换字符串，若需更精细地控制编码过程，应使用java.nio.charset.CharsetEncoder类。
 *
 * 这个组件可以同时处理字节和字符，以用户输入String为例，以下是整个调用链：
 * String -> BufferedWriter -> OutputStreamWriter -> CharsetEncoder（在这一步将字符转换成了字节） -> byte[] -> OutputStream
 * @author liyibo
 * @date 2026-02-28 13:24
 */
public class PrintStream extends FilterOutputStream implements Appendable, Closeable {

    private final boolean autoFlush;

    /** PrintStream不会抛出IOException，会把这个字段设置为true来表示出现了异常 */
    private boolean trouble = false;
    private Formatter formatter;

    private BufferedWriter textOut;
    private OutputStreamWriter charOut;

    private static <T> T requireNonNull(T obj, String message) {
        if (obj == null)
            throw new NullPointerException(message);
        return obj;
    }

    private static Charset toCharset(String csn) throws UnsupportedEncodingException {
        requireNonNull(csn, "charsetName");
        try {
            return Charset.forName(csn);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException unused) {
            throw new UnsupportedEncodingException(csn);
        }
    }

    private PrintStream(boolean autoFlush, OutputStream out) {
        super(out);
        this.autoFlush = autoFlush;
        this.charOut = new OutputStreamWriter(this);
        this.textOut = new BufferedWriter(charOut);
    }

    private PrintStream(boolean autoFlush, Charset charset, OutputStream out) {
        this(out, autoFlush, charset);
    }

    public PrintStream(OutputStream out) {
        this(out, false);
    }

    public PrintStream(OutputStream out, boolean autoFlush) {
        this(autoFlush, requireNonNull(out, "Null output stream"));
    }

    public PrintStream(OutputStream out, boolean autoFlush, String encoding) throws UnsupportedEncodingException {
        this(requireNonNull(out, "Null output stream"), autoFlush, toCharset(encoding));
    }

    public PrintStream(OutputStream out, boolean autoFlush, Charset charset) {
        super(out);
        this.autoFlush = autoFlush;
        this.charOut = new OutputStreamWriter(this, charset);
        this.textOut = new BufferedWriter(charOut);
    }

    public PrintStream(String fileName) throws FileNotFoundException {
        this(false, new FileOutputStream(fileName));
    }

    public PrintStream(String fileName, String csn) throws FileNotFoundException, UnsupportedEncodingException {
        this(false, toCharset(csn), new FileOutputStream(fileName));
    }

    public PrintStream(String fileName, Charset charset) throws IOException {
        this(false, requireNonNull(charset, "charset"), new FileOutputStream(fileName));
    }

    public PrintStream(File file) throws FileNotFoundException {
        this(false, new FileOutputStream(file));
    }

    public PrintStream(File file, String csn) throws FileNotFoundException, UnsupportedEncodingException {
        this(false, toCharset(csn), new FileOutputStream(file));
    }

    public PrintStream(File file, Charset charset) throws IOException {
        this(false, requireNonNull(charset, "charset"), new FileOutputStream(file));
    }

    private void ensureOpen() throws IOException {
        if(out == null)
            throw new IOException("Stream closed");
    }

    @Override
    public void flush() {
        synchronized (this) {
            try {
                ensureOpen();
                out.flush();
            }
            catch (IOException x) {
                trouble = true;
            }
        }
    }

    private boolean closing = false;

    @Override
    public void close() {
        synchronized(this) {
            if(!closing) {
                closing = true;
                try {
                    textOut.close();
                    out.close();
                } catch (IOException e) {
                    trouble =true;
                }
                textOut = null;
                charOut = null;
                out = null;
            }
        }
    }

    /**
     * 刷新流并检查其错误状态，当底层输出流抛出除InterruptedIOException之外的IOException时，或调用setError方法时，内部错误状态会被设置为true。
     * 若底层输出流的操作抛出InterruptedIOException，则PrintStream会通过以下方式将异常转换回中断：
     * Thread.currentThread().interrupt()或等效操作。
     */
    public boolean checkError() {
        if(out != null)
            flush();
        if(out instanceof PrintStream ps)
            return ps.checkError();
        return trouble;
    }

    protected void setError() {
        trouble = true;
    }

    protected void clearError() {
        trouble = false;
    }

    /**
     * 将指定字节写入此流，若该字节为换行符且启动了autoFlush，则会调用底层输出流的flush方法。
     * 请注意，字节将按原样写入，若需写入根据平台默认字符编码进行转换的字符，请使用print(char)或者println(char)方法。
     */
    @Override
    public void write(int b) {
        try {
            synchronized(this) {
                ensureOpen();
                out.write(b);
                if((b == '\n') && autoFlush)
                    out.flush();
            }
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ioe) {
            trouble = true;
        }
    }

    /**
     * 从指定字节数组中写入长度为len的字节至该流，起始偏移量为offset，若开始autoFlush，则会调用底层流的flush方法。
     * 请注意字节将按原始形式写入；若需写入根据平台默认字符编码转换的字符，请使用 print(char) 或 println(char) 方法。
     */
    @Override
    public void write(byte buf[], int off, int len) {
        try {
            synchronized(this) {
                ensureOpen();
                out.write(buf, off, len);
                if (autoFlush)
                    out.flush();
            }
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ioe) {
            trouble = true;
        }
    }

    @Override
    public void write(byte buf[]) throws IOException {
        this.write(buf, 0, buf.length);
    }

    public void writeBytes(byte buf[]) {
        this.write(buf, 0, buf.length);
    }

    private void write(char[] buf) {
        try {
            synchronized(this) {
                ensureOpen();
                textOut.write(buf); // 写入BufferedWriter
                /** textOut的flushBuffer最终写入charOut，也就是OutputStreamWriter，这时就会转成字节了。*/
                textOut.flushBuffer();
                /** charOut的flushBuffer最终会调用PrintStream本身的write(byte[])方法（注意这个是重点，调用又回来了）最终再调用底层的OutputStream输出字节 */
                charOut.flushBuffer();
                if(autoFlush) {
                    for(int i = 0; i < buf.length; i++) {
                        if(buf[i] == '\n') {
                            out.flush();
                            break;
                        }
                    }
                }
            }
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ioe) {
            trouble = true;
        }
    }

    private void writeln(char[] buf) {
        try {
            synchronized (this) {
                ensureOpen();
                textOut.write(buf);
                textOut.newLine();
                textOut.flushBuffer();
                charOut.flushBuffer();
                if(autoFlush)
                    out.flush();
            }
        }
        catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
        }
        catch (IOException ioe) {
            trouble = true;
        }
    }

    private void write(String s) {
        try {
            synchronized (this) {
                ensureOpen();
                textOut.write(s);
                textOut.flushBuffer();
                charOut.flushBuffer();
                if(autoFlush && (s.indexOf('\n') >= 0))
                    out.flush();
            }
        }
        catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
        }
        catch (IOException ioe) {
            trouble = true;
        }
    }

    private void writeln(String s) {
        try {
            synchronized (this) {
                ensureOpen();
                textOut.write(s);
                textOut.newLine();
                textOut.flushBuffer();
                charOut.flushBuffer();
                if(autoFlush)
                    out.flush();
            }
        }
        catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
        }
        catch (IOException ioe) {
            trouble = true;
        }
    }

    private void newLine() {
        try {
            synchronized (this) {
                ensureOpen();
                textOut.newLine();
                textOut.flushBuffer();
                charOut.flushBuffer();
                if (autoFlush)
                    out.flush();
            }
        }
        catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
        }
        catch (IOException ioe) {
            trouble = true;
        }
    }

    public void print(boolean b) {
        write(String.valueOf(b));
    }

    public void print(char c) {
        write(String.valueOf(c));
    }

    public void print(int i) {
        write(String.valueOf(i));
    }

    public void print(long l) {
        write(String.valueOf(l));
    }

    public void print(float f) {
        write(String.valueOf(f));
    }

    public void print(double d) {
        write(String.valueOf(d));
    }

    public void print(char s[]) {
        write(s);
    }

    public void print(String s) {
        write(String.valueOf(s));
    }

    public void print(Object obj) {
        write(String.valueOf(obj));
    }

    public void println() {
        newLine();
    }

    public void println(boolean x) {
        if(getClass() == PrintStream.class) {
            writeln(String.valueOf(x));
        }else {
            synchronized(this) {
                print(x);
                newLine();
            }
        }
    }

    public void println(char x) {
        if(getClass() == PrintStream.class) {
            writeln(String.valueOf(x));
        }else {
            synchronized(this) {
                print(x);
                newLine();
            }
        }
    }

    public void println(int x) {
        if(getClass() == PrintStream.class) {
            writeln(String.valueOf(x));
        }else {
            synchronized(this) {
                print(x);
                newLine();
            }
        }
    }

    public void println(long x) {
        if(getClass() == PrintStream.class) {
            writeln(String.valueOf(x));
        }else {
            synchronized(this) {
                print(x);
                newLine();
            }
        }
    }

    public void println(float x) {
        if(getClass() == PrintStream.class) {
            writeln(String.valueOf(x));
        }else {
            synchronized(this) {
                print(x);
                newLine();
            }
        }
    }

    public void println(double x) {
        if(getClass() == PrintStream.class) {
            writeln(String.valueOf(x));
        }else {
            synchronized(this) {
                print(x);
                newLine();
            }
        }
    }

    public void println(char[] x) {
        if(getClass() == PrintStream.class) {
            writeln(x);
        }else {
            synchronized(this) {
                print(x);
                newLine();
            }
        }
    }

    public void println(String x) {
        if(getClass() == PrintStream.class) {
            writeln(String.valueOf(x));
        }else {
            synchronized(this) {
                print(x);
                newLine();
            }
        }
    }

    public void println(Object x) {
        String s = String.valueOf(x);
        if(getClass() == PrintStream.class) {
            // need to apply String.valueOf again since first invocation
            // might return null
            writeln(String.valueOf(s));
        }else {
            synchronized(this) {
                print(s);
                newLine();
            }
        }
    }

    public PrintStream printf(String format, Object ... args) {
        return format(format, args);
    }

    public PrintStream printf(Locale l, String format, Object ... args) {
        return format(l, format, args);
    }

    public PrintStream format(String format, Object ... args) {
        try {
            synchronized(this) {
                ensureOpen();
                if(format == null || formatter.locale() != Locale.getDefault(Locale.Category.FORMAT))
                    formatter = new Formatter(this);
                /**
                 * 最重要的语句在这里，format会解析format和args，形成最后的字符串。
                 * 最后内部会调用构造formatter的PrintStream的append方法（
                 * 也就是当前这个PrintStream里面的append，因为上面构造方法传的是this），最终重新回到print(String)这里面
                 */
                formatter.format(Locale.getDefault(Locale.Category.FORMAT), format, args);
            }
        }catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ioe) {
            trouble = true;
        }
        return this;
    }

    public PrintStream format(Locale l, String format, Object ... args) {
        try {
            synchronized (this) {
                ensureOpen();
                if(formatter == null || formatter.locale() != l)
                    formatter = new Formatter(this, l);
                formatter.format(l, format, args);
            }
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ioe) {
            trouble = true;
        }
        return this;
    }

    public PrintStream append(CharSequence csq) {
        print(String.valueOf(csq));
        return this;
    }

    public PrintStream append(CharSequence csq, int start, int end) {
        if(csq == null)
            csq = "null";
        return append(csq.subSequence(start, end));
    }

    public PrintStream append(char c) {
        print(c);
        return this;
    }
}

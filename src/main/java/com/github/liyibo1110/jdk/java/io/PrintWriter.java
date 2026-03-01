package com.github.liyibo1110.jdk.java.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Locale;
import java.util.Objects;

/**
 * 和PrintStream几乎一样，只是换成了字符版本，底层输出换成了PrintStream
 * @author liyibo
 * @date 2026-03-01 21:38
 */
public class PrintWriter extends Writer {
    protected Writer out;

    private final boolean autoFlush;
    private boolean trouble = false;
    private Formatter formatter;
    private PrintStream psOut = null;

    private static Charset toCharset(String csn) throws UnsupportedEncodingException {
        Objects.requireNonNull(csn, "charsetName");
        try {
            return Charset.forName(csn);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException unused) {
            throw new UnsupportedEncodingException(csn);
        }
    }

    public PrintWriter (Writer out) {
        this(out, false);
    }

    public PrintWriter(Writer out, boolean autoFlush) {
        super(out);
        this.out = out;
        this.autoFlush = autoFlush;
    }

    public PrintWriter(OutputStream out) {
        this(out, false);
    }

    public PrintWriter(OutputStream out, boolean autoFlush) {
        this(out, autoFlush, Charset.defaultCharset());
    }

    public PrintWriter(OutputStream out, boolean autoFlush, Charset charset) {
        this(new BufferedWriter(new OutputStreamWriter(out, charset)), autoFlush);
        if(out instanceof java.io.PrintStream)
            psOut = (PrintStream)out;
    }

    public PrintWriter(String fileName) throws FileNotFoundException {
        this(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fileName))), false);
    }

    private PrintWriter(Charset charset, File file) throws FileNotFoundException {
        this(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), charset)), false);
    }

    public PrintWriter(String fileName, String csn) throws FileNotFoundException, UnsupportedEncodingException {
        this(toCharset(csn), new File(fileName));
    }

    public PrintWriter(String fileName, Charset charset) throws IOException {
        this(Objects.requireNonNull(charset, "charset"), new File(fileName));
    }

    public PrintWriter(File file) throws FileNotFoundException {
        this(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file))), false);
    }

    public PrintWriter(File file, String csn) throws FileNotFoundException, UnsupportedEncodingException {
        this(toCharset(csn), file);
    }

    public PrintWriter(File file, Charset charset) throws IOException {
        this(Objects.requireNonNull(charset, "charset"), file);
    }

    private void ensureOpen() throws IOException {
        if(out == null)
            throw new IOException("Stream closed");
    }

    public void flush() {
        try {
            synchronized(lock) {
                ensureOpen();
                out.flush();
            }
        } catch (IOException x) {
            trouble = true;
        }
    }

    public void close() {
        try {
            synchronized(lock) {
                if(out == null)
                    return;
                out.close();
                out = null;
            }
        } catch (IOException x) {
            trouble = true;
        }
    }

    public boolean checkError() {
        if(out != null)
            flush();

        if(out instanceof PrintWriter pw)
            return pw.checkError();
        else if (psOut != null)
            return psOut.checkError();
        return trouble;
    }

    protected void setError() {
        trouble = true;
    }

    protected void clearError() {
        trouble = false;
    }

    public void write(int c) {
        try {
            synchronized(lock) {
                ensureOpen();
                out.write(c);
            }
        } catch (InterruptedIOException x) {
            Thread.currentThread().interrupt();
        } catch (IOException x) {
            trouble = true;
        }
    }

    public void write(char buf[], int off, int len) {
        try {
            synchronized(lock) {
                ensureOpen();
                out.write(buf, off, len);
            }
        } catch (InterruptedIOException x) {
            Thread.currentThread().interrupt();
        } catch (IOException x) {
            trouble = true;
        }
    }

    public void write(char buf[]) {
        write(buf, 0, buf.length);
    }

    public void write(String s, int off, int len) {
        try {
            synchronized(lock) {
                ensureOpen();
                out.write(s, off, len);
            }
        } catch (InterruptedIOException x) {
            Thread.currentThread().interrupt();
        } catch (IOException x) {
            trouble = true;
        }
    }

    public void write(String s) {
        write(s, 0, s.length());
    }

    private void newLine() {
        try {
            synchronized (lock) {
                ensureOpen();
                out.write(System.lineSeparator());
                if(autoFlush)
                    out.flush();
            }
        } catch (InterruptedIOException x) {
            Thread.currentThread().interrupt();
        } catch (IOException x) {
            trouble = true;
        }
    }

    public void print(boolean b) {
        write(String.valueOf(b));
    }

    public void print(char c) {
        write(c);
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
        synchronized(lock) {
            print(x);
            println();
        }
    }

    public void println(char x) {
        synchronized(lock) {
            print(x);
            println();
        }
    }

    public void println(int x) {
        synchronized(lock) {
            print(x);
            println();
        }
    }

    public void println(long x) {
        synchronized(lock) {
            print(x);
            println();
        }
    }

    public void println(float x) {
        synchronized(lock) {
            print(x);
            println();
        }
    }

    public void println(double x) {
        synchronized(lock) {
            print(x);
            println();
        }
    }

    public void println(char x[]) {
        synchronized(lock) {
            print(x);
            println();
        }
    }

    public void println(String x) {
        synchronized(lock) {
            print(x);
            println();
        }
    }

    public void println(Object x) {
        String s = String.valueOf(x);
        synchronized(lock) {
            print(s);
            println();
        }
    }

    public PrintWriter printf(String format, Object ... args) {
        return format(format, args);
    }

    public PrintWriter printf(Locale l, String format, Object ... args) {
        return format(l, format, args);
    }

    public PrintWriter format(String format, Object ... args) {
        try {
            synchronized (lock) {
                ensureOpen();
                if(formatter == null || formatter.locale() != Locale.getDefault())
                    formatter = new Formatter(this);
                formatter.format(Locale.getDefault(), format, args);
                if(autoFlush)
                    out.flush();
            }
        } catch (InterruptedIOException x) {
            Thread.currentThread().interrupt();
        } catch (IOException x) {
            trouble = true;
        }
        return this;
    }

    public PrintWriter format(Locale l, String format, Object ... args) {
        try {
            synchronized (lock) {
                ensureOpen();
                if(formatter == null || formatter.locale() != l)
                    formatter = new Formatter(this, l);
                formatter.format(l, format, args);
                if(autoFlush)
                    out.flush();
            }
        } catch (InterruptedIOException x) {
            Thread.currentThread().interrupt();
        } catch (IOException x) {
            trouble = true;
        }
        return this;
    }

    public PrintWriter append(CharSequence csq) {
        write(String.valueOf(csq));
        return this;
    }

    public PrintWriter append(CharSequence csq, int start, int end) {
        if(csq == null)
            csq = "null";
        return append(csq.subSequence(start, end));
    }

    public PrintWriter append(char c) {
        write(c);
        return this;
    }
}

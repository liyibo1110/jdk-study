package com.github.liyibo1110.jdk.java.lang;

import jdk.internal.util.StaticProperty;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 代表着一个已经启动好的子进程，在JVM里的句柄或代理对象，该Process实例一般由ProcessBuilder来创建的。
 * 当获取Process实例后，代表已经成功启动了一个OS子进程，并且拿到了与它交互的通道，包括：
 * 1、往子进程标准输入写数据。
 * 2、读取子进程标准输出。
 * 3、读取子进程标准错误。
 * 4、等待子进程结束。
 * 5、获取子进程退出码。
 * 6、关闭子进程。
 * 注意Process是个抽象类，具体的子类，会对应不同的OS平台。
 *
 * 主要用途：
 * 1、执行外部程序后，与它交互，例如：
 * - 调用shell / bat / python / ffmpeg / git / curl
 * - 读取命令执行结果
 * - 给命令写输入
 *
 * 2、等待子进程结束并获取退出码，例如：
 * - 脚本执行是否成功
 * - 外部工具编译是否成功
 * - 某个系统命令执行有没有报错
 *
 * 3、中止子进程，例如：
 * - 超时就关闭
 * - 程序关闭时，把外部进程也清理掉
 *
 * 4、做进程级管道交互，例如：
 * - Java程序向子进程stdin输入数据
 * - 再从stdout / stderr中取回结果
 *
 * Process的三要素：进程状态 +三个标准流 + 生命周期控制
 * 状态：
 * 1、是否还活着
 * 2、是否已退出
 * 3、退出码是什么
 *
 * 三个标准流：
 * 1、getOutputStream()：给子进程stdin写
 * 2、getInputStream()：读子进程stdout
 * 3、getErrorStream()：读子进程stderr
 *
 * 生命周期控制：
 * 1、waitFor()
 * 2、destroy()
 * 3、destroyForcibly()
 *
 * @author liyibo
 * @date 2026-03-26 14:51
 */
public abstract class Process {

    /** 给子进程stdin写 */
    private BufferedWriter outputWriter;
    private Charset outputCharset;

    /** 读子进程stdout */
    private BufferedReader inputReader;
    private Charset inputCharset;

    /** 读子进程stderr */
    private BufferedReader errorReader;
    private Charset errorCharset;

    public Process() {}

    public abstract OutputStream getOutputStream();

    public abstract InputStream getInputStream();

    public abstract InputStream getErrorStream();

    public final BufferedReader inputReader() {
        return inputReader(CharsetHolder.nativeCharset());
    }

    /**
     * 将getInputStream()返回的字节流，包装成字符流
     */
    public final BufferedReader inputReader(Charset charset) {
        Objects.requireNonNull(charset, "charset");
        synchronized (this) {
            if (inputReader == null) {
                inputCharset = charset;
                inputReader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            } else {
                if (!inputCharset.equals(charset))
                    throw new IllegalStateException("BufferedReader was created with charset: " + inputCharset);
            }
            return inputReader;
        }
    }

    public final BufferedReader errorReader() {
        return errorReader(CharsetHolder.nativeCharset());
    }

    /**
     * 将getErrorStream()返回的字节流，包装成字符流
     */
    public final BufferedReader errorReader(Charset charset) {
        Objects.requireNonNull(charset, "charset");
        synchronized (this) {
            if (errorReader == null) {
                errorCharset = charset;
                errorReader = new BufferedReader(new InputStreamReader(getErrorStream(), charset));
            } else {
                if (!errorCharset.equals(charset))
                    throw new IllegalStateException("BufferedReader was created with charset: " + errorCharset);
            }
            return errorReader;
        }
    }

    public final BufferedWriter outputWriter() {
        return outputWriter(CharsetHolder.nativeCharset());
    }

    /**
     * 将getOutputStream()返回的字节流，包装成字符流
     */
    public final BufferedWriter outputWriter(Charset charset) {
        Objects.requireNonNull(charset, "charset");
        synchronized (this) {
            if (outputWriter == null) {
                outputCharset = charset;
                outputWriter = new BufferedWriter(new OutputStreamWriter(getOutputStream(), charset));
            } else {
                if (!outputCharset.equals(charset))
                    throw new IllegalStateException("BufferedWriter was created with charset: " + outputCharset);
            }
            return outputWriter;
        }
    }

    /**
     * 一直阻塞等待直到子进程结束，最后返回退出码
     */
    public abstract int waitFor() throws InterruptedException;

    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        long remainingNanos = unit.toNanos(timeout); // throw NPE before other conditions
        if (hasExited())
            return true;
        if (timeout <= 0)
            return false;

        long deadline = System.nanoTime() + remainingNanos;
        do {
            Thread.sleep(Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1, 100));
            if (hasExited())
                return true;
            remainingNanos = deadline - System.nanoTime();
        } while (remainingNanos > 0);

        return false;
    }

    /**
     * 如果进程已经退出，则直接返回退出码，否则会抛异常（注意不是阻塞）。
     * 和waitFor相比，这个方法是：直接查看结果，不然就报错。
     */
    public abstract int exitValue();

    /**
     * 尝试终止子进程（注意是尝试，具体实现和平台有关）
     */
    public abstract void destroy();

    public Process destroyForcibly() {
        destroy();
        return this;
    }

    public boolean supportsNormalTermination() {
        throw new UnsupportedOperationException(this.getClass() + ".supportsNormalTermination() not supported" );
    }

    /**
     * 当前子进程是否还在运行中。
     */
    public boolean isAlive() {
        return !hasExited();
    }

    /**
     * 通过调用exitValue()来判断子进程是否已运行完成。
     */
    private boolean hasExited() {
        try {
            exitValue();
            return true;
        } catch (IllegalThreadStateException e) {
            return false;
        }
    }

    /**
     * 获取子进程在OS中的pid。
     */
    public long pid() {
        return toHandle().pid();
    }

    /**
     * 提供了异步等待进程结束的方法，会返回一个CompletableFuture。
     */
    public CompletableFuture<Process> onExit() {
        return CompletableFuture.supplyAsync(this::waitForInternal);
    }

    private Process waitForInternal() {
        boolean interrupted = false;
        while(true) {
            try {
                ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                    @Override
                    public boolean block() throws InterruptedException {
                        waitFor();
                        return true;
                    }

                    @Override
                    public boolean isReleasable() {
                        return !isAlive();
                    }
                });
                break;
            } catch (InterruptedException x) {
                interrupted = true;
            }
        }
        if(interrupted)
            Thread.currentThread().interrupt();
        return this;
    }

    /**
     * JDK新版本增加的功能，会把Process转换成ProcessHandle，后者提供了更现代、更丰富的进程管理接口，增加了可观测功能。
     */
    public ProcessHandle toHandle() {
        throw new UnsupportedOperationException(this.getClass() + ".toHandle() not supported");
    }

    public ProcessHandle.Info info() {
        return toHandle().info();
    }

    public Stream<ProcessHandle> children() {
        return toHandle().children();
    }

    public Stream<ProcessHandle> descendants() {
        return toHandle().descendants();
    }

    static class PipeInputStream extends FileInputStream {

        PipeInputStream(FileDescriptor fd) {
            super(fd);
        }

        @Override
        public long skip(long n) throws IOException {
            long remaining = n;
            int nr;

            if(n <= 0)
                return 0;

            int size = (int)Math.min(2048, remaining);
            byte[] skipBuffer = new byte[size];
            while(remaining > 0) {
                nr = read(skipBuffer, 0, (int)Math.min(size, remaining));
                if(nr < 0)
                    break;
                remaining -= nr;
            }

            return n - remaining;
        }
    }

    /**
     * 用来获取OS平台的字符集
     */
    private static class CharsetHolder {
        private final static Charset nativeCharset;
        static {
            Charset cs;
            try {
                cs = Charset.forName(StaticProperty.nativeEncoding());
            } catch (UnsupportedCharsetException uce) {
                cs = Charset.defaultCharset();
            }
            nativeCharset = cs;
        }

        /**
         * Charset for the native encoding or {@link Charset#defaultCharset().
         */
        static Charset nativeCharset() {
            return nativeCharset;
        }
    }
}

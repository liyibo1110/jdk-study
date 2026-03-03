package com.github.liyibo1110.jdk.java.nio.channels.spi;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.InterruptibleChannel;

/**
 * InterruptibleChannel的实现类。
 * 该类封装了实现通道异步关闭和中断所需的底层机制。具体通道类必须在调用可能无限期阻塞的I/O操作之前调用begin方法，之后调用end方法。
 * 为确保end方法始终被调用，应在try...finally代码块内使用这些方法：
 *
 * 通俗地说：如果有线程阻塞在read()上时，如果线程interrupt了，或者channel调用了close，该如何立即退出阻塞。
 * 在老IO中（InputStream.read），如果线程interrupt了，方法不会立即退出。
 * 而NIO中（channel.read(buffer)），如果线程interrupt了，方法会立即退出并且抛异常。
 * 以上就是AbstractInterruptibleChannel抽象类提供的功能，因为它是所有阻塞IO Channel的基础核心类。
 * @author liyibo
 * @date 2026-03-02 20:02
 */
public abstract class AbstractInterruptibleChannel implements Channel, InterruptibleChannel {
    private final Object closeLock = new Object();  // 保证close方法只会被执行一次
    private volatile boolean closed;

    protected AbstractInterruptibleChannel() {}

    public final void close() throws IOException {
        synchronized(closeLock) {
            if(closed)
                return;
            closed = true;
            implCloseChannel(); // 模板方法模式，子类实现这种closeChannel逻辑
        }
    }

    /**
     * 此方法由close方法调用，以执行实际关闭通道的工作。仅当通道尚未关闭时才会调用此方法，且每次通道关闭操作仅调用一次。
     * 此方法的实现必须确保任何因I/O操作而阻塞在该通道上的线程立即返回，具体可通过抛出异常或正常返回实现。
     */
    protected abstract void implCloseChannel() throws IOException;

    public final boolean isOpen() {
        return !closed;
    }

    // -- Interruption machinery --

    /** 如何中断线程阻塞IO */
    private Interruptible interruptor;

    /** 哪个线程被interrupt */
    private volatile Thread interrupted;

    /**
     * 标记可能无限期阻塞的I/O操作的开始。
     * 应与end方法配合调用此方法，并使用如上所示的try...finally代码块，以实现该通道的异步关闭和中断功能。
     */
    protected final void begin() {
        if(interruptor == null) {   // 首次调用，会创建interruptor
            interruptor = new Interruptible() {
                public void interrupt(Thread target) {  // 如果线程被interrupt，会执行这个方法
                    synchronized (closeLock) {
                        if(closed)
                            return;
                        closed = true;
                        interrupted = target;   // 记录线程
                        try {
                            AbstractInterruptibleChannel.this.implCloseChannel();   // 关闭channel，这里会导致read阻塞方法立即返回
                        } catch (IOException e) {

                        }
                    }
                }};
        }
        /** 告诉JVM，当前线程阻塞在IO上，相当于绑定注册了interruptor，JVM会调用interruptor的interrupt方法 */
        blockedOn(interruptor);
        Thread me = Thread.currentThread();
        if (me.isInterrupted()) // 如果线程已经被interrupt了，立即关闭channel
            interruptor.interrupt(me);
    }

    protected final void end(boolean completed) throws AsynchronousCloseException {
        blockedOn(null);    // 清理interruptor绑定
        Thread interrupted = this.interrupted;
        // 如果线程被interrupt了，直接抛ClosedByInterruptException，这是NIO的标准行为（即线程A read()，线程A interrupt()）
        if(interrupted != null && interrupted == Thread.currentThread()) {
            this.interrupted = null;
            throw new ClosedByInterruptException();
        }
        // IO还没完成，并且channel已经关闭了，说明是另一个线程调用close了（即线程A read()，线程B close(channel)）
        if(!completed && closed)
            throw new AsynchronousCloseException();
    }

    // -- jdk.internal.access.SharedSecrets --
    static void blockedOn(Interruptible intr) {         // package-private
        SharedSecrets.getJavaLangAccess().blockedOn(intr);
    }
}

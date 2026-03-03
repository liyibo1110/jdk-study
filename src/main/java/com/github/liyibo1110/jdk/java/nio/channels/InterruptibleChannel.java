package com.github.liyibo1110.jdk.java.nio.channels;

import java.io.IOException;

/**
 * 可异步关闭和中断的通道。
 * 实现此接口的通道支持异步关闭：若线程在InterruptibleChannel的I/O操作中被阻塞，则其他线程可调用该通道的close方法。这将导致被阻塞的线程收到AsynchronousCloseException。
 *
 * 实现此接口的通道同时具备可中断特性：若线程在InterruptibleChannel上进行I/O操作时被阻塞，则其他线程可调用该阻塞线程的中断方法。
 * 此操作将导致通道关闭，阻塞线程收到ClosedByInterruptException异常，并设置阻塞线程的中断状态。
 *
 * 若线程的中断状态已设置，且其在通道上执行阻塞式I/O操作，则通道将被关闭，该线程会立即收到ClosedByInterruptException异常；其中断状态仍保持有效。
 * 通道仅当实现此接口时才支持异步关闭与中断。必要时可通过instanceof运算符在运行时进行检测。
 * @author liyibo
 * @date 2026-03-02 19:57
 */
public interface InterruptibleChannel extends Channel {
    /**
     * 关闭此通道，任何当前因I/O操作而阻塞在此通道上的线程都将收到一个AsynchronousCloseException异常。
     * 除此之外，此方法的行为完全符合Channel接口的规范。
     */
    void close() throws IOException;
}

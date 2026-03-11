package com.github.liyibo1110.jdk.java.util.concurrent.locks;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Condition变量将对象监视器方法（wait、notify和notifyAll）提取为独立对象，通过结合任意锁实现的使用，实现每个对象拥有多个等待集的效果。
 * 正如锁替代了同步方法和语句的使用，Condition变量则替代了对象监视器方法的使用。
 *
 * Condition（亦称Condition队列或Condition变量）为线程提供了一种暂停执行（即“等待”）的机制，直至收到其他线程的通知表明某个状态条件已满足。
 * 由于不同线程会访问该共享状态信息，因此必须对其进行保护，故条件会关联某种形式的锁。条件等待的核心特性在于：它能像Object.wait一样，以原子操作释放关联锁并挂起当前线程。
 *
 * Condition实例与锁存在内在绑定关系。获取特定Lock实例的Condition实例需调用其newCondition()方法。
 *
 * 例如，假设我们有一个支持put和take方法的有界缓冲区。若在空缓冲区尝试取项，线程将阻塞直至项可用；若在满缓冲区尝试存项，线程将阻塞直至空间可用。
 * 我们希望将等待存项的线程与等待取项的线程分别放入不同的等待集，以便在缓冲区出现可用项或空间时，仅需通知单个线程即可实现优化。
 * 这可通过使用两个条件实例来实现。
 *
 * class BoundedBuffer<E> {
 *     final Lock lock = new ReentrantLock();
 *     final Condition notFull  = lock.newCondition();
 *     final Condition notEmpty = lock.newCondition();
 *
 *     final Object[] items = new Object[100];
 *     int putptr, takeptr, count;
 *
 *     public void put(E x) throws InterruptedException {
 *       lock.lock();
 *       try {
 *         while (count == items.length)
 *           notFull.await();
 *         items[putptr] = x;
 *         if (++putptr == items.length) putptr = 0;
 *         ++count;
 *         notEmpty.signal();
 *       } finally {
 *         lock.unlock();
 *       }
 *     }
 *
 *     public E take() throws InterruptedException {
 *       lock.lock();
 *       try {
 *         while (count == 0)
 *           notEmpty.await();
 *         E x = (E) items[takeptr];
 *         if (++takeptr == items.length) takeptr = 0;
 *         --count;
 *         notFull.signal();
 *         return x;
 *       } finally {
 *         lock.unlock();
 *       }
 *     }
 *   }
 *
 * Condition的实现可提供与对象监视器方法不同的行为和语义，例如保证通知的顺序性，或执行通知时无需持有锁。
 * 若实现提供了此类特殊语义，则必须对这些语义进行文档说明。
 * 请注意，Condition实例本身是普通对象，可作为同步语句的目标对象，其自身的监视器等待和通知方法也可被调用。
 * 获取Condition实例的监视器锁，或使用其监视器方法，与获取该条件关联的锁或使用其等待和信号方法之间并无明确关联。
 * 为避免混淆，建议除自身实现内部外，切勿以这种方式使用条件实例。除非另有说明，向任何参数传递空值都将抛出空指针异常。
 *
 * Implementation Considerations
 *
 * 在等待条件时，通常允许发生“虚假唤醒”现象，这是对底层平台语义的一种妥协。
 * 这对大多数应用程序影响甚微，因为条件等待应始终在循环中进行，并持续检测所等待的状态谓词。
 * 实现方可自由消除虚假唤醒的可能性，但建议应用程序开发者始终假设其可能发生，因此应始终采用循环等待方式。
 *
 * 三种条件等待形式（可中断、不可中断和定时）在某些平台上的实现难易度及性能特征可能存在差异。
 * 尤其在提供这些功能的同时保持特定语义（如顺序保证）时可能面临困难。此外，在所有平台上实现中断线程实际挂起状态的功能未必总是可行。
 * 因此，实现方案无需为三种等待形式提供完全相同的保证或语义，亦无需支持中断线程实际挂起状态。
 *
 * 实现方案必须明确记录每种等待方法提供的语义与保证；若支持中断线程挂起，则必须遵循本接口定义的中断语义。
 * 由于中断通常意味着取消，且中断检查频率较低，实现可优先响应中断而非正常方法返回。
 * 即使能证明中断发生在可能已解除线程阻塞的其他操作之后，此优先级仍成立。实现应记录此行为。
 * @author liyibo
 * @date 2026-03-10 17:12
 */
public interface Condition {

    /**
     * 当前线程将保持等待状态，直至收到信号或被中断。
     * 与该条件关联的锁将被原子性释放，当前线程将被禁用（用于线程调度），并保持休眠状态直至满足以下四种情况之一：
     * 1、其他线程调用此条件对象的signal方法，且当前线程恰好被选中唤醒。
     * 2、其他线程调用此条件对象的signalAll方法。
     * 3、其他线程中断当前线程（且系统支持中断挂起线程）。
     * 4、发生“虚假唤醒”事件。
     *
     * 在所有情况下，本方法返回前，当前线程必须重新获取与该条件关联的锁。当线程返回时，保证持有该锁。
     * 若当前线程：
     * 1、在进入本方法时其中断状态已被设置。
     * 2、在等待期间被中断且支持中断线程挂起状态。
     * 则抛出InterruptedException并清除当前线程的中断状态。第一种情形下，未明确规定中断检测是否在释放锁之前进行。
     *
     * Implementation Considerations
     *
     * 当前线程在调用此方法时，被假定持有与该条件相关的锁。具体是否成立由实现决定，若不成立则需处理相应情况。
     * 通常会抛出异常（如非法监视状态异常），且实现必须对此进行文档说明。
     * 实现可选择在响应信号时优先处理中断而非正常方法返回。此时实现必须确保将信号重定向至其他等待线程（若存在）。
     */
    void await() throws InterruptedException;

    /**
     * 导致当前线程等待直至收到信号。
     * 与该条件关联的锁将被原子释放，当前线程将被禁用（用于线程调度），处于休眠状态直至满足以下任一条件：
     * 1、其他线程调用此条件的signal方法，且当前线程恰好被选中唤醒。
     * 2、其他线程调用此条件的signalAll方法。
     * 3、发生“虚假唤醒”。
     * 所有情况下，本方法返回前当前线程必须重新获取该条件锁。线程返回时必将持有此锁。
     * 若当前线程进入本方法时处于中断状态，或在等待期间被中断，则将继续等待直至收到信号。最终返回时其中断状态仍将保持。
     *
     * Implementation Considerations
     *
     * 当前线程在调用此方法时，被假定持有与该条件相关的锁。
     * 具体是否成立由实现决定，若未持有则需处理相应情况。通常会抛出异常（如非法监视状态异常），且实现必须对此进行文档说明。
     */
    void awaitUninterruptibly();

    long awaitNanos(long nanosTimeout) throws InterruptedException;

    boolean await(long time, TimeUnit unit) throws InterruptedException;

    boolean awaitUntil(Date deadline) throws InterruptedException;

    /**
     * 唤醒一个等待的线程。
     * 如果有多个线程处于此条件等待状态，则随机选取一个进行唤醒。该线程必须在从await返回前重新获取锁。
     */
    void signal();

    /**
     * 唤醒所有等待的线程。
     * 如果存在任何线程处于此条件等待状态，则所有线程均会被唤醒。每个线程必须重新获取锁后才能从await返回。
     */
    void signalAll();
}

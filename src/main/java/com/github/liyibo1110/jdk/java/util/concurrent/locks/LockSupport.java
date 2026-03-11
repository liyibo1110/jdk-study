package com.github.liyibo1110.jdk.java.util.concurrent.locks;

import jdk.internal.misc.Unsafe;

/**
 * 用于创建锁和其他同步类的基本线程阻塞原语。
 *
 * 该类为每个使用它的线程关联一个许可（类似于信号量类中的许可）。
 * 调用park方法时，若许可可用则立即返回并消耗该许可；否则可能阻塞。
 * 调用unpark方法可释放许可（若许可尚未被释放）。
 * （但与信号量不同，许可不可累积，最多仅存在一个。）可靠使用需借助易失性（或原子）变量控制停驻与释放时机。
 * 这些方法的调用顺序在易失性变量访问中得到维护，但在非易失性变量访问中则未必如此。
 *
 * park和unpark方法提供了高效的线程阻塞与解除阻塞机制，避免了已弃用的Thread.suspend和Thread.resume方法在此场景下的缺陷：
 * 当一个线程调用park时，若另一线程尝试unpark，由于许可机制的存在，两者间的竞争状态仍能保持系统活性。
 * 此外，若调用线程被中断，park会立即返回，同时支持超时版本。
 * park方法也可能在任何其他时间“无故”返回，因此通常需在循环中调用，并在返回时重新检查条件。
 * 从这个意义上说，park作为“忙等”的优化方案，能减少空转时间，但必须与unpark配合使用才能发挥效用。
 *
 * 三种 park 形式均支持阻塞对象参数。
 * 该对象在线程阻塞期间被记录，以便监控和诊断工具识别阻塞原因（此类工具可通过getBlocker(Thread)方法访问阻塞对象）。
 * 强烈建议使用带参数形式而非原始无参数形式。锁实现中作为阻塞对象的常规参数应为this。
 *
 * 以上比较官方，LockSupport工具类实际解决的就是一个很古老的问题：线程如何安全地挂起和唤醒，
 * 早期是通过Thread的suspend和resume方法来实现的，但是有问题（Thread里面写过了）
 * 核心概念只有一个：每个线程都有一个permit（许可），只有0和1两种状态，不会累积
 * park方法会查看permit是否为1，是则消费permit并立即返回，permit为0则阻塞线程。
 * unpark(thread)方法会把permit改成1，多次调用结果还是1，同时唤醒传入的thread线程
 *
 * 这个结构的重点在于：它解决了丢失唤醒问题，例如有2个线程t1和t2：t1.park; t2.unpark(t1);
 * 对应传统wait/notify方法，如果notify先发生，wait后发生，那wait就永远阻塞了，
 * 而unpark会先设置permit=1，其他线程park时发现permit是1，则不会阻塞。
 *
 * 另外这个类代码量并不大，原因在于复杂的代码全在Unsafe里面了，LockSupport主要就做3件事：
 * 1、提供API。
 * 2、记录blocker。
 * 3、委托调用Unsafe。
 *
 * 最后特别要注意的是：通过park阻塞的线程，可能无理由被唤醒，所以在实际用park的地方，基本都是在一个不停检查condition的while循环里调用park。
 * @author liyibo
 * @date 2026-03-10 14:29
 */
public class LockSupport {

    private LockSupport() {}

    private static void setBlocker(Thread t, Object arg) {
        U.putReferenceOpaque(t, PARKBLOCKER, arg);
    }

    public static void setCurrentBlocker(Object blocker) {
        U.putReferenceOpaque(Thread.currentThread(), PARKBLOCKER, blocker);
    }

    public static void unpark(Thread thread) {
        if(thread != null)
            U.unpark(thread);   // 只是委托Unsafe的unpark就完事了（permit恢复1）
    }

    public static void park(Object blocker) {
        Thread t = Thread.currentThread();
        setBlocker(t, blocker); // 记录线程为什么阻塞，即被哪个对象阻塞
        U.park(false, 0L);  // 也是委托Unsafe的park，无限等
        setBlocker(t, null);
    }

    public static void parkNanos(Object blocker, long nanos) {
        if(nanos > 0) {
            Thread t = Thread.currentThread();
            setBlocker(t, blocker);
            U.park(false, nanos);   // Unsafe.park的参数1代表：绝对时间（true）、相对时间（false）
            setBlocker(t, null);
        }
    }

    public static void parkUntil(Object blocker, long deadline) {
        Thread t = Thread.currentThread();
        setBlocker(t, blocker);
        U.park(true, deadline); // Unsafe.park的参数1代表：绝对时间（true）、相对时间（false）
        setBlocker(t, null);
    }

    public static Object getBlocker(Thread t) {
        if(t == null)
            throw new NullPointerException();
        return U.getReferenceOpaque(t, PARKBLOCKER);
    }

    public static void park() {
        U.park(false, 0L);
    }

    public static void parkNanos(long nanos) {
        if(nanos > 0)
            U.park(false, nanos);
    }

    public static void parkUntil(long deadline) {
        U.park(true, deadline);
    }

    static final long getThreadId(Thread thread) {
        return U.getLong(thread, TID);
    }

    private static final Unsafe U = Unsafe.getUnsafe();

    private static final long PARKBLOCKER = U.objectFieldOffset(Thread.class, "parkBlocker");
    private static final long TID = U.objectFieldOffset(Thread.class, "tid");
}

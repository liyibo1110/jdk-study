package com.github.liyibo1110.jdk.java.lang;

import jdk.internal.misc.TerminatingThreadLocal;
import jdk.internal.reflect.Reflection;
import sun.nio.ch.Interruptible;
import sun.security.util.SecurityConstants;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Thread的5大职责：
 * 1、标识线程的身份：id、name
 * 2、线程执行逻辑体：Runnable target
 * 3、线程状态管理
 * 4、线程控制：start / interrupt / join / sleep
 * 5、ThreadLocal支持
 * 相当于是一个线程对象的控制器
 *
 * Thread的4个核心设计：
 * 1、start与run分离：start()负责创建线程，run()负责执行代码。
 * 2、interrupt模型：协作式取消。
 * 3、join实现
 * 4、ThreadLocal设计：Thread -> ThreadLocalMap
 * @author liyibo
 * @date 2026-03-09 13:54
 */
public class Thread {
    private static native void registerNatives();

    static {
        registerNatives();
    }

    /** 线程名称 */
    private volatile String name;

    /** 线程优先级，现在已经没什么用了 */
    private int priority;

    /** 是否为daemon线程 */
    private boolean daemon = false;

    /** 是否被interrupt，在Java17后改为由JVM来管理 */
    private volatile boolean interrupted;

    private boolean stillborn = false;

    /** 对应JVM内部线程对象的指针（即JavaThread对象的地址，最终指向OS thread） */
    private volatile long eetop;

    /** 要运行的任务 */
    private Runnable target;

    /**
     * 早期产物，现在没什么大用，在没有线程池的背景下，设计用来分组管理线程，主要解决三个问题：
     * 1、批量操作线程：例如对一个group进行interrupt或stop，可以同时影响多个线程。
     * 2、枚举线程：例如列出组内线程进行统计，用于监控线程。
     * 3、安全控制：早期Applet环境中，SecurityManager会限制一个线程组不能操作另一个线程组，可以实现沙箱安全模型。
     * ThreadGroup被淘汰的原因：
     * 1、设计过于简单，功能不够：例如现代线程池、ForkJoinPool里面的功能，ThreadGroup都没有。
     * 2、API比较危险：例如stop、suspend、resume，会导致死锁、资源不一致、线程状态破坏等问题。
     * 3、线程数量并不等于实际任务数量：ThreadGroup假设一个线程 = 一个任务，但现代线程池模型是：线程池 -> 线程复用 -> 任务调度
     *
     * 现在的替代方案：
     * 1、Executor/ThreadPool：ExecutorService、ThreadPoolExecutor、ForkJoinPool（负责线程生命周期、线程复用、任务调度）
     * 2、ThreadFactory：用来控制线程创建行为。
     * 3、UncaughtExceptionHandler：ThreadGroup曾经负责处理线程未能捕获的异常，现在可以用Thread.setUncaughtExceptionHandler方法
     * 4、structured concurrency（Java21）
     *
     * JVM内部仍然会依赖ThreadGroup：
     * 系统线程、main线程、GC线程、信号处理线程，依然会放在不同的ThreadGroup
     */
    private ThreadGroup group;

    /** 用于ClassLoader */
    private ClassLoader contextClassLoader;

    /** 用于SecurityManager */
    private AccessControlContext inheritedAccessControlContext;

    private static int threadInitNumber;

    private static synchronized int nextThreadNum() {
        return threadInitNumber++;
    }

    /** ThreadLocal的数据 */
    ThreadLocal.ThreadLocalMap threadLocals = null;

    ThreadLocal.ThreadLocalMap inheritableThreadLocals = null;

    private final long stackSize;

    /** 线程ID，由JVM分配的唯一ID */
    private final long tid;

    private static long threadSeqNumber;

    private static synchronized long nextThreadID() {
        return ++threadSeqNumber;
    }

    /**
     * 0：NEW
     */
    private volatile int threadStatus;

    volatile Object parkBlocker;

    /**
     * 当线程阻塞时，要阻塞在这里可中断的阻塞对象，
     * 即当线程被调用了interrupt，就会调用blocker.interrupt(thread)方法，
     * 让blocker内部对阻塞自己做唤醒/关闭/取消等功能
     *
     * Interruptible时JDK的一个内部接口，里面最重要的方法时interrupt(thread)
     * 含义是：如果传来的线程阻塞在我这个，我负责怎么把它给唤醒。
     * 因为Thread自己并不知道socket应该怎么关、selector怎么wakeup以及file lock怎么取消。
     * 因此这个的设计变成了：Thread只负责通知（即调用interrupt方法），具体Blocker组件自己负责解除阻塞。
     */
    private volatile Interruptible blocker;

    /**
     * 专门保护blocker字段的锁，涉及了：
     * 1、设置blocker
     * 2、清空blocker
     * 3、interrupt()读取blocker
     * 4、避免竞态条件
     * 即设置blocker和对blocker执行interrupt回调之间的原子协作关系，光是一个volatile是不够的
     */
    private final Object blockerLock = new Object();

    /**
     * 将Interruptible回调对象设置给当前线程的blocker
     * 注意在阻塞结束后，还需要调用blockedOn(null)
     *
     * Thread的interrupt()方法并不是强杀线程，而是设置中断标志，以及对某些阻塞操作进行协作式唤醒。
     * 因为如果线程卡在了内核级阻塞中，并不能自动让底层I/O返回，所以JDK需要一套机制：
     * 1、阻塞前先告诉线程：我现在要阻塞在谁身上。
     * 2、当其他线程调用interrupt()中断这个线程时，线程可以自动回调这个blocker。
     * 3、由blocker执行真正的解除阻塞动作（比如关闭fd、唤醒selector、标记channel closed以及抛出ClosedByInterruptException）。
     */
    static void blockedOn(Interruptible b) {
        Thread me = Thread.currentThread();
        synchronized(me.blockerLock) {
            me.blocker = b;
        }
    }

    public static final int MIN_PRIORITY = 1;
    public static final int NORM_PRIORITY = 5;
    public static final int MAX_PRIORITY = 10;

    /**
     * 返回当前正在执行的线程对象引用。
     */
    public static native Thread currentThread();

    /**
     * 向调度器发出提示，表明当前线程愿意放弃其对处理器的当前使用权。调度器可自由忽略此提示。
     * 让步是一种启发式尝试，旨在改善本会过度占用CPU的线程间的相对进度。其使用应结合详细的性能分析和基准测试，以确保实际达到预期效果。
     * 此方法极少适用。在调试或测试场景中可能有所助益，有助于复现因竞争条件引发的缺陷。在设计并发控制结构时（如java.util.concurrent.locks包中的实现），该方法亦可能发挥作用。
     *
     * 总之就是已经没啥用了
     */
    public static native void yield();

    /**
     *使当前执行的线程休眠（暂时停止执行）指定毫秒数，具体时长取决于系统计时器和调度程序的精度与准确性。该线程不会失去对任何监视器的所有权。
     */
    public static native void sleep(long millis) throws InterruptedException;

    public static void sleep(long millis, int nanos) throws InterruptedException {
        if(millis < 0)
            throw new IllegalArgumentException("timeout value is negative");

        if(nanos < 0 || nanos > 999999)
            throw new IllegalArgumentException("nanosecond timeout value out of range");

        if(nanos > 0 && millis < Long.MAX_VALUE)
            millis++;

        sleep(millis);
    }

    /**
     * 表示调用方暂时无法继续执行，需等待其他活动执行一项或多项操作后才能继续。
     * 通过在自旋等待循环结构的每次迭代中调用此方法，调用线程向运行时环境表明其处于忙等待状态。
     * 运行时环境可采取措施优化自旋等待循环结构的调用性能。
     */
    public static void onSpinWait() {}

    @SuppressWarnings("removal")
    private Thread(ThreadGroup g, Runnable target, String name,
                   long stackSize, AccessControlContext acc,
                   boolean inheritThreadLocals) {
        if(name == null)
            throw new NullPointerException("name cannot be null");
        this.name = name;

        Thread parent = currentThread();
        SecurityManager security = System.getSecurityManager();

        if(g == null) {
            if(security != null)
                g = security.getThreadGroup();
            if(g == null)
                g = parent.getThreadGroup();
        }
        g.checkAccess();

        if (security != null) {
            if(isCCLOverridden(getClass()))
                security.checkPermission(SecurityConstants.SUBCLASS_IMPLEMENTATION_PERMISSION);
        }
        g.addUnstarted();

        this.group = g;
        this.daemon = parent.isDaemon();
        this.priority = parent.getPriority();
        if (security == null || isCCLOverridden(parent.getClass()))
            this.contextClassLoader = parent.getContextClassLoader();
        else
            this.contextClassLoader = parent.contextClassLoader;
        this.inheritedAccessControlContext = acc != null ? acc : AccessController.getContext();
        this.target = target;
        setPriority(priority);
        if(inheritThreadLocals && parent.inheritableThreadLocals != null)
            this.inheritableThreadLocals = ThreadLocal.createInheritedMap(parent.inheritableThreadLocals);
        this.stackSize = stackSize;
        this.tid = nextThreadID();
    }

    @Override
    protected java.lang.Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public Thread() {
        this(null, null, "Thread-" + nextThreadNum(), 0);
    }

    public Thread(Runnable target) {
        this(null, target, "Thread-" + nextThreadNum(), 0);
    }

    Thread(Runnable target, @SuppressWarnings("removal") AccessControlContext acc) {
        this(null, target, "Thread-" + nextThreadNum(), 0, acc, false);
    }

    public Thread(ThreadGroup group, Runnable target) {
        this(group, target, "Thread-" + nextThreadNum(), 0);
    }

    public Thread(String name) {
        this(null, null, name, 0);
    }

    public Thread(ThreadGroup group, String name) {
        this(group, null, name, 0);
    }

    public Thread(Runnable target, String name) {
        this(null, target, name, 0);
    }

    public Thread(ThreadGroup group, Runnable target, String name) {
        this(group, target, name, 0);
    }

    public Thread(ThreadGroup group, Runnable target, String name, long stackSize) {
        this(group, target, name, stackSize, null, true);
    }

    public Thread(ThreadGroup group, Runnable target, String name, long stackSize, boolean inheritThreadLocals) {
        this(group, target, name, stackSize, null, inheritThreadLocals);
    }

    /**
     * 导致该线程开始执行；Java虚拟机调用该线程的run方法。
     * 结果是两个线程并发运行：当前线程（从start方法调用中返回）和另一个线程（执行其run方法）。
     * 不允许对同一个线程进行多次启动。特别是，线程一旦完成执行，就不能被重新启动。
     */
    public synchronized void start() {
        if(threadStatus != 0)
            throw new IllegalThreadStateException();
        group.add(this);

        boolean started = false;
        try {
            start0();
            started = true;
        } finally {
            try {
                if(!started)
                    group.threadStartFailed(this);
            } catch (java.lang.Throwable ignore) {
                // nothing to do
            }
        }
    }

    /**
     * 和OS的线程模型打交道去了
     */
    private native void start0();

    /**
     * 如果该线程是通过单独的Runnable构建的，则调用该Runnable的run方法；否则，此方法不执行任何操作并返回。
     */
    @Override
    public void run() {
        if(target != null)
            target.run();
    }

    /**
     * 该方法由系统调用，为线程在实际退出前提供清理机会。
     */
    private void exit() {
        if(threadLocals != null && TerminatingThreadLocal.REGISTRY.isPresent())
            TerminatingThreadLocal.threadTerminated();

        if(group != null) {
            group.threadTerminated(this);
            group = null;
        }
        /* Aggressively null out all reference fields: see bug 4006245 */
        target = null;
        /* Speed the release of some of these resources */
        threadLocals = null;
        inheritableThreadLocals = null;
        inheritedAccessControlContext = null;
        blocker = null;
        uncaughtExceptionHandler = null;
    }

    /**
     * 强制线程停止执行。
     * 如果安装了安全管理器，则会调用其checkAccess方法，并将此作为参数。这可能会导致（在当前线程中）抛出SecurityException异常。
     *
     * 若此线程与当前线程不同（即当前线程试图停止自身以外的线程），则额外调用安全管理器的checkPermission方法（带RuntimePermission(“stopThread”)参数）。
     * 同样可能导致抛出SecurityException（在当前线程中）。
     *
     * 由该线程表示的线程将被迫异常终止当前操作，并抛出新创建的ThreadDeath对象作为异常。
     * 允许终止尚未启动的线程。若该线程最终被启动，则会立即终止。
     *
     * 应用程序通常不应尝试捕获ThreadDeath异常，除非必须执行特殊清理操作（注意抛出ThreadDeath会在线程正式终止前触发try语句的finally子句）。
     * 若catch子句捕获了ThreadDeath对象，必须重新抛出该对象以确保线程真正终止。
     *
     * 当顶级错误处理器响应未捕获异常时，若该异常实例为 ThreadDeath，则不会打印消息或向应用程序发出通知。
     *
     * @deprecated
     * 此方法本质上存在安全隐患。使用Thread.stop停止线程会导致其解锁所有已锁定的监视器（这是未检查的ThreadDeath异常向上传播栈的自然结果）。
     * 若这些监视器先前保护的对象处于不一致状态，受损对象将对其他线程可见，可能引发任意行为。
     * 多数情况下，应使用简单修改变量来指示目标线程停止运行的代码替代stop方法。目标线程应定期检查该变量，若变量指示其停止运行，则应有序地从run方法中返回。
     * 若目标线程需长时间等待（例如在条件变量上），应使用interrupt方法中断等待。更多信息请参阅《为何Thread.stop、Thread.suspend和Thread.resume已被弃用？》。
     *
     * 总之很久以前就不让再用这个方法了。
     */
    @Deprecated(since="1.2")
    public final void stop() {
        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if(security != null) {
            checkAccess();
            if(this != java.lang.Thread.currentThread())
                security.checkPermission(SecurityConstants.STOP_THREAD_PERMISSION);
        }
        // A zero status value corresponds to "NEW", it can't change to
        // not-NEW because we hold the lock.
        if(threadStatus != 0)
            resume(); // Wake up thread if it was suspended; no-op otherwise

        // The VM can handle all thread states
        stop0(new ThreadDeath());
    }

    /**
     * 中断此线程，除非当前线程正在中断自身（这始终被允许），否则将调用该线程的checkAccess方法，这可能导致抛出SecurityException异常。
     * 若该线程在调用Object类的wait()、wait(long)或wait(long, int)方法，
     * 或本类的join()、join(long)、join(long, int)、sleep(long)或sleep(long, int)方法时被阻塞，
     * 则其中断状态将被清除并抛出InterruptedException异常。
     *
     * 若该线程在InterruptibleChannel的I/O操作中被阻塞，则通道将被关闭，线程中断状态将被设置，并抛出java.nio.channels.ClosedByInterruptException异常。
     *
     * 若线程在java.nio.channels.Selector中被阻塞，则会设置该线程的中断状态，并立即从选择操作中返回（可能返回非零值），效果如同调用了选择器的唤醒方法。
     *
     * 若上述条件均不成立，则会设置该线程的中断状态。中断非活动线程可能不会产生任何效果。
     * 这个方法是Thread的重点学习目标
     */
    public void interrupt() {
        // 如果是其他线程调用的interrupt方法
        if(this != Thread.currentThread()) {
            checkAccess();
            // 如果绑定了blocker，则除了设置中断标记，还要回调blocker的interrupt，让其实现恢复阻塞的功能
            synchronized(blockerLock) {
                Interruptible b = blocker;
                if(b != null) {
                  interrupted = true;
                  interrupt0();
                  b.interrupt(this);
                  return;
                }
            }
        }
        interrupted = true;
        interrupt0();
    }

    /**
     * 检测当前线程是否处于中断状态。该方法会清除线程的中断状态。
     * 换言之，若连续两次调用此方法，第二次调用将返回false（除非在第一次调用清除中断状态后、第二次调用检测状态前，当前线程再次被中断）。
     */
    public static boolean interrupted() {
        Thread t = currentThread();
        boolean interrupted = t.interrupted;
        // 注意调用了interrupted，会自动恢复interrupted的值
        if(interrupted) {
            t.interrupted = false;
            clearInterruptEvent();
        }
        return interrupted;
    }

    /**
     * 注意和上面的interrupted不要搞混，这个方法只是返回interrupted值而已
     */
    public boolean isInterrupted() {
        return interrupted;
    }

    /**
     * 检测此线程是否存活。若线程已被启动且尚未终止，则视为存活。
     */
    public final boolean isAlive() {
        return eetop != 0;
    }

    /**
     * 暂停此线程，和stop方法一样，不能再使用，因为容易引发死锁。
     */
    @Deprecated(since="1.2", forRemoval=true)
    public final void suspend() {
        checkAccess();
        suspend0();
    }

    /**
     * 恢复此线程，和suspend方法一样，不能再使用。
     */
    @Deprecated(since="1.2", forRemoval=true)
    public final void resume() {
        checkAccess();
        resume0();
    }

    public final void setPriority(int newPriority) {
        ThreadGroup g;
        checkAccess();
        if(newPriority > MAX_PRIORITY || newPriority < MIN_PRIORITY)
            throw new IllegalArgumentException();
        if((g = getThreadGroup()) != null) {
            if (newPriority > g.getMaxPriority())
                newPriority = g.getMaxPriority();
            setPriority0(priority = newPriority);
        }
    }

    public final int getPriority() {
        return priority;
    }

    public final synchronized void setName(String name) {
        checkAccess();
        if(name == null)
            throw new NullPointerException("name cannot be null");
        this.name = name;
        if(threadStatus != 0)
            setNativeName(name);
    }

    public final String getName() {
        return name;
    }

    public final ThreadGroup getThreadGroup() {
        return group;
    }

    /**
     * 返回当前线程所在线程组及其子组中活跃线程数量的估计值。该方法会递归遍历当前线程组中的所有子组。
     * 由于线程数量在遍历内部数据结构时可能动态变化，且可能受某些系统线程存在的影响，因此返回值仅为估计值。本方法主要用于调试和监控目的。
     */
    public static int activeCount() {
        return currentThread().getThreadGroup().activeCount();
    }

    public static int enumerate(java.lang.Thread tarray[]) {
        return currentThread().getThreadGroup().enumerate(tarray);
    }

    @Deprecated(since="1.2", forRemoval=true)
    public int countStackFrames() {
        throw new UnsupportedOperationException();
    }

    /**
     * 最多等待线程终止的毫秒数。超时值为 0 表示无限等待。
     * 此实现通过循环调用this.wait方法，条件为this.isAlive。
     * 当线程终止时会调用this.notifyAll方法。建议应用程序不要在Thread实例上使用wait、notify或notifyAll方法。
     */
    public final synchronized void join(final long millis) throws java.lang.InterruptedException {
        if(millis > 0) {
            if(isAlive()) {
                final long startTime = System.nanoTime();
                long delay = millis;
                do {
                    wait(delay);
                } while(isAlive() && (delay = millis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)) > 0);
            }
        }else if(millis == 0) {
            while(isAlive())
                wait(0);
        }else {
            throw new IllegalArgumentException("timeout value is negative");
        }
    }

    public final synchronized void join(long millis, int nanos) throws java.lang.InterruptedException {
        if(millis < 0)
            throw new IllegalArgumentException("timeout value is negative");
        if(nanos < 0 || nanos > 999999)
            throw new IllegalArgumentException("nanosecond timeout value out of range");
        if(nanos > 0 && millis < Long.MAX_VALUE)
            millis++;
        join(millis);
    }
    public final void join() throws java.lang.InterruptedException {
        join(0);
    }

    /**
     * 将当前线程的堆栈跟踪打印到标准错误流。此方法仅用于调试。
     */
    public static void dumpStack() {
        new Exception("Stack trace").printStackTrace();
    }

    /**
     * 将此线程标记为守护线程或用户线程。当仅剩守护线程在运行时，Java虚拟机将退出。
     * 必须在线程启动前调用此方法
     */
    public final void setDaemon(boolean on) {
        checkAccess();
        if(isAlive())
            throw new IllegalThreadStateException();
        daemon = on;
    }

    public final boolean isDaemon() {
        return daemon;
    }

    /**
     * 判断当前运行的线程是否具有修改此线程的权限。
     * 若存在安全管理器，则调用其checkAccess方法，并将此线程作为参数传递。这可能导致抛出SecurityException异常。
     *
     * @deprecated
     * 此方法仅在与安全管理器配合使用时才有效，而安全管理器已被弃用，将在未来版本中移除。
     * 因此，此方法同样已被弃用，也将被移除。目前尚无替代安全管理器或此方法的方案。
     */
    @Deprecated(since="17", forRemoval=true)
    public final void checkAccess() {
        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if(security != null)
            security.checkAccess(this);
    }

    public String toString() {
        ThreadGroup group = getThreadGroup();
        if(group != null)
            return "Thread[" + getName() + "," + getPriority() + "," + group.getName() + "]";
        else
            return "Thread[" + getName() + "," + getPriority() + "," + "" + "]";
    }

    /**
     * 返回此线程的上下文类加载器。该上下文类加载器由线程创建者提供，供本线程中运行的代码加载类和资源时使用。
     * 若未设置，则默认采用父线程的类加载器上下文。原始线程的上下文类加载器通常设置为加载应用程序所用的类加载器。
     */
    public ClassLoader getContextClassLoader() {
        if(contextClassLoader == null)
            return null;
        @SuppressWarnings("removal")
        SecurityManager sm = System.getSecurityManager();
        if(sm != null)
            ClassLoader.checkClassLoaderPermission(contextClassLoader, Reflection.getCallerClass());
        return contextClassLoader;
    }

    /**
     * 设置此线程的上下文类加载器。
     * 上下文类加载器可在创建线程时设置，允许线程创建者通过getContextClassLoader方法为线程中运行的代码提供适当的类加载器，用于加载类和资源。
     *
     * 若存在安全管理器，则会调用其checkPermission方法，并传递RuntimePermission(“setContextClassLoader”)权限以验证设置上下文类加载器的许可权限。
     */
    public void setContextClassLoader(ClassLoader cl) {
        @SuppressWarnings("removal")
        SecurityManager sm = System.getSecurityManager();
        if(sm != null)
            sm.checkPermission(new RuntimePermission("setContextClassLoader"));
        contextClassLoader = cl;
    }

    /**
     * 当且仅当当前线程持有指定对象的监视器锁时返回true。
     * 此方法旨在允许程序断言当前线程已持有指定锁：
     * assert Thread.holdsLock(obj);
     */
    public static native boolean holdsLock(Object obj);

    private static final StackTraceElement[] EMPTY_STACK_TRACE = new StackTraceElement[0];

    /**
     * 返回一个堆栈跟踪元素数组，表示该线程的堆栈转储。
     * 若该线程尚未启动、已启动但尚未被系统调度运行，或已终止，则此方法将返回零长度数组。
     * 若返回数组长度不为零，则数组的首个元素代表堆栈顶部，即序列中最近的方法调用。数组的最后一个元素代表栈底，即序列中最久远的方法调用。
     *
     * 若存在安全管理器且当前线程非活动线程，则会调用安全管理器的checkPermission方法，并使用RuntimePermission(“getStackTrace”)权限检查获取堆栈跟踪的许可。
     *
     * 某些虚拟机在特定情况下可能省略堆栈跟踪中的一个或多个堆栈帧。极端情况下，若虚拟机不具备该线程的堆栈跟踪信息，则允许该方法返回零长度数组。
     */
    public StackTraceElement[] getStackTrace() {
        if (this != Thread.currentThread()) {
            // check for getStackTrace permission
            @SuppressWarnings("removal")
            SecurityManager security = System.getSecurityManager();
            if(security != null)
                security.checkPermission(SecurityConstants.GET_STACK_TRACE_PERMISSION);

            // optimization so we do not call into the vm for threads that
            // have not yet started or have terminated
            if(!isAlive())
                return EMPTY_STACK_TRACE;
            StackTraceElement[][] stackTraceArray = dumpThreads(new Thread[] {this});
            StackTraceElement[] stackTrace = stackTraceArray[0];
            // a thread that was alive during the previous isAlive call may have
            // since terminated, therefore not having a stacktrace.
            if(stackTrace == null)
                stackTrace = EMPTY_STACK_TRACE;
            return stackTrace;
        } else {
            return (new Exception()).getStackTrace();
        }
    }

    public static Map<Thread, StackTraceElement[]> getAllStackTraces() {
        // check for getStackTrace permission
        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(SecurityConstants.GET_STACK_TRACE_PERMISSION);
            security.checkPermission(SecurityConstants.MODIFY_THREADGROUP_PERMISSION);
        }

        // Get a snapshot of the list of all threads
        Thread[] threads = getThreads();
        StackTraceElement[][] traces = dumpThreads(threads);
        Map<Thread, StackTraceElement[]> m = new HashMap<>(threads.length);
        for(int i = 0; i < threads.length; i++) {
            StackTraceElement[] stackTrace = traces[i];
            if(stackTrace != null)
                m.put(threads[i], stackTrace);
            // else terminated so we don't put it in the map
        }
        return m;
    }

    private static class Caches {
        static final ConcurrentMap<Thread.WeakClassKey,Boolean> subclassAudits = new ConcurrentHashMap<>();
        static final ReferenceQueue<Class<?>> subclassAuditsQueue = new ReferenceQueue<>();
    }

    private static boolean isCCLOverridden(Class<?> cl) {
        if (cl == java.lang.Thread.class)
            return false;

        processQueue(Thread.Caches.subclassAuditsQueue, Thread.Caches.subclassAudits);
        Thread.WeakClassKey key = new Thread.WeakClassKey(cl, Thread.Caches.subclassAuditsQueue);
        Boolean result = Thread.Caches.subclassAudits.get(key);
        if(result == null) {
            result = Boolean.valueOf(auditSubclass(cl));
            Thread.Caches.subclassAudits.putIfAbsent(key, result);
        }

        return result.booleanValue();
    }

    private static boolean auditSubclass(final Class<?> subcl) {
        @SuppressWarnings("removal")
        Boolean result = AccessController.doPrivileged(
                new PrivilegedAction<>() {
                    public Boolean run() {
                        for (Class<?> cl = subcl;
                             cl != java.lang.Thread.class;
                             cl = cl.getSuperclass())
                        {
                            try {
                                cl.getDeclaredMethod("getContextClassLoader", new Class<?>[0]);
                                return Boolean.TRUE;
                            } catch (NoSuchMethodException ex) {
                            }
                            try {
                                Class<?>[] params = {ClassLoader.class};
                                cl.getDeclaredMethod("setContextClassLoader", params);
                                return Boolean.TRUE;
                            } catch (NoSuchMethodException ex) {
                            }
                        }
                        return Boolean.FALSE;
                    }
                }
        );
        return result.booleanValue();
    }

    private static native java.lang.StackTraceElement[][] dumpThreads(java.lang.Thread[] threads);
    private static native java.lang.Thread[] getThreads();

    public long getId() {
        return tid;
    }

    /**
     * 线程状态
     */
    public enum State {
        /**
         * 尚未启动的线程的线程状态
         */
        NEW,

        /**
         * 可运行线程的线程状态。处于可运行状态的线程正在Java虚拟机中执行，但可能正在等待操作系统提供的其他资源（如处理器）。
         */
        RUNNABLE,

        /**
         * 线程处于阻塞状态，正在等待监视器锁。
         * 处于阻塞状态的线程正在等待监视器锁，以便进入同步块/方法，或在调用Object.wait后重新进入同步块/方法。
         */
        BLOCKED,

        /**
         * 等待线程的线程状态。线程因调用以下方法之一而处于等待状态：
         * 1、Object.wait（无超时设置）
         * 2、Thread.join（无超时设置）
         * 3、LockSupport.park
         * 处于等待状态的线程正在等待其他线程执行特定操作。
         * 例如：调用对象Object.wait()的线程，正在等待其他线程对该对象调用Object.notify()或Object.notifyAll()；调用Thread.join()的线程，则在等待指定线程终止。
         */
        WAITING,

        /**
         * 具有指定等待时间的等待线程的状态。当线程调用以下任一方法并指定正等待时间时，将进入定时等待状态：
         * Thread.sleep
         * 带超时的Object.wait
         * 带超时的Thread.join
         * LockSupport.parkNanos
         * LockSupport.parkUntil
         */
        TIMED_WAITING,

        /**
         * 已终止线程的线程状态。该线程已完成执行。
         */
        TERMINATED;
    }

    public java.lang.Thread.State getState() {
        // get current thread state
        return jdk.internal.misc.VM.toThreadState(threadStatus);
    }

    // Added in JSR-166

    /**
     * 当线程因未捕获异常而突然终止时调用的处理器接口。
     * 当线程因未捕获异常即将终止时，Java虚拟机将通过getUncaughtExceptionHandler方法查询该线程的UncaughtExceptionHandler，
     * 并调用处理器的uncaughtException方法，同时将线程和异常作为参数传递。
     * 若线程未显式设置UncaughtExceptionHandler，则其ThreadGroup对象将充当 UncaughtExceptionHandler。
     * 若ThreadGroup对象对异常处理无特殊要求，可将调用转发至默认未捕获异常处理程序。
     */
    @FunctionalInterface
    public interface UncaughtExceptionHandler {
        /**
         * 当给定线程因给定未捕获异常终止时调用的方法。
         * 此方法抛出的任何异常都将被Java虚拟机忽略。
         */
        void uncaughtException(Thread t, Throwable e);
    }

    private volatile Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    private static volatile Thread.UncaughtExceptionHandler defaultUncaughtExceptionHandler;

    /**
     * 设置当线程因未捕获异常而突然终止且未为该线程定义其他处理程序时调用的默认处理程序。
     * 未捕获异常的处理由以下顺序控制：
     * 1、首先由线程自身控制。
     * 2、其次由线程的线程组对象控制。
     * 3、最后由默认未捕获异常处理程序控制。
     * 若线程未显式设置未捕获异常处理程序，且该线程的线程组（包括父线程组）未重写其uncaughtException方法，则将调用默认处理程序的uncaughtException方法。
     * 通过设置默认未捕获异常处理程序，应用程序可改变未捕获异常的处理方式（例如将日志记录到特定设备或文件），从而覆盖系统默认行为。
     * 需注意：默认未捕获异常处理程序通常不应委托给线程的线程组对象，否则可能引发无限递归。
     */
    public static void setDefaultUncaughtExceptionHandler(Thread.UncaughtExceptionHandler eh) {
        @SuppressWarnings("removal")
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(new RuntimePermission("setDefaultUncaughtExceptionHandler"));
        defaultUncaughtExceptionHandler = eh;
    }

    public static Thread.UncaughtExceptionHandler getDefaultUncaughtExceptionHandler(){
        return defaultUncaughtExceptionHandler;
    }

    public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return uncaughtExceptionHandler != null ? uncaughtExceptionHandler : group;
    }

    public void setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler eh) {
        checkAccess();
        uncaughtExceptionHandler = eh;
    }

    private void dispatchUncaughtException(Throwable e) {
        getUncaughtExceptionHandler().uncaughtException(this, e);
    }

    static void processQueue(ReferenceQueue<Class<?>> queue, ConcurrentMap<? extends WeakReference<Class<?>>, ?> map) {
        Reference<? extends Class<?>> ref;
        while((ref = queue.poll()) != null)
            map.remove(ref);
    }

    static class WeakClassKey extends WeakReference<Class<?>> {
        /**
         * saved value of the referent's identity hash code, to maintain
         * a consistent hash code after the referent has been cleared
         */
        private final int hash;

        /**
         * Create a new WeakClassKey to the given object, registered
         * with a queue.
         */
        WeakClassKey(Class<?> cl, ReferenceQueue<Class<?>> refQueue) {
            super(cl, refQueue);
            hash = System.identityHashCode(cl);
        }

        /**
         * Returns the identity hash code of the original referent.
         */
        @Override
        public int hashCode() {
            return hash;
        }

        /**
         * Returns true if the given object is this identical
         * WeakClassKey instance, or, if this object's referent has not
         * been cleared, if the given object is another WeakClassKey
         * instance with the identical non-null referent as this one.
         */
        @Override
        public boolean equals(java.lang.Object obj) {
            if (obj == this)
                return true;

            if (obj instanceof java.lang.Thread.WeakClassKey) {
                Class<?> referent = get();
                return (referent != null) &&
                        (((java.lang.Thread.WeakClassKey) obj).refersTo(referent));
            } else {
                return false;
            }
        }
    }

    /** The current seed for a ThreadLocalRandom */
    @jdk.internal.vm.annotation.Contended("tlr")
    long threadLocalRandomSeed;

    /** Probe hash value; nonzero if threadLocalRandomSeed initialized */
    @jdk.internal.vm.annotation.Contended("tlr")
    int threadLocalRandomProbe;

    /** Secondary seed isolated from public ThreadLocalRandom sequence */
    @jdk.internal.vm.annotation.Contended("tlr")
    int threadLocalRandomSecondarySeed;

    /* Some private helper methods */
    private native void setPriority0(int newPriority);
    private native void stop0(java.lang.Object o);
    private native void suspend0();
    private native void resume0();
    private native void interrupt0();
    private static native void clearInterruptEvent();
    private native void setNativeName(String name);
}

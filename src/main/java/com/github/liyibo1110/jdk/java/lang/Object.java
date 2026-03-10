package com.github.liyibo1110.jdk.java.lang;

/**
 * 类层次结构的根节点，每个类都将Object作为其父类，所有对象（包括数组类型）都实现了该类的方法
 * @author liyibo
 * @date 2026-03-09 11:51
 */
public class Object {

    public Object() {}

    /**
     * 返回此对象的运行时Class对象，其代表类中静态同步方法所锁定的对象。
     * 实际返回类型为Class<? extends |X|>，其中|X|是调用getClass方法的表达式静态类型的类型擦除结果，例如以下代码片段无需进行强制类型转换：
     * Number n = 0;
     * Class<? extends Number> c = n.getClass();
     */
    public final native Class<?> getClass();

    /**
     * 返回对象的hash值，此方法会hash表（例如HashMap）提供支持。
     * hashCode的一般契约为：
     * 1、在Java应用程序的执行过程中，当对同一个对象多次调用hashCode方法时，只要该对象用于equals比较的信息未被修改，该方法必须始终返回相同的整数值。
     * 但该整数值在不同应用程序执行之间不必保持一致。
     * 2、如果两个对象根据equals方法被判定为相等，则对这两个对象分别调用hashCode方法必须产生相同的整数结果。
     * 3、当两个对象根据equals方法判定为不相等时，调用这两个对象各自的hashCode方法不要求产生不同的整数结果。
     * 但程序员应意识到：为不相等的对象生成不同的整数结果可能提升hash表的性能。
     *
     */
    public native int hashCode();

    /**
     * 指示其他对象是否“等于”此对象。
     * equals方法在非空对象引用上实现等价关系：
     * 1、自反性：对于任何非空引用值x，x.equals(x)应当返回 true。
     * 2、对称性：对于任意非空引用值x和y，当且仅当y.equals(x)返回true时，x.equals(y)才应返回true。
     * 3、传递性：对于任意非空引用值x、y和 z，若x.equals(y)返回true且y.equals(z)返回true，则x.equals(z)应返回true。
     * 4、一致性：对于任意非空引用值x和y，多次调用x.equals(y)应当始终返回true或始终返回false，前提是用于对象比较的等值信息未被修改。
     * 5、对于任意非空引用值x，x.equals(null)应返回false。
     * 等价关系将操作对象划分为等价类；等价类中的所有成员彼此相等。等价类中的成员至少在某些情况下可相互替代。
     *
     * 通常在重写hashCode方法时，必须同时重写该方法，以保持hashCode方法的一般契约——即相等的对象必须具有相等的hash值。
     */
    public boolean equals(Object obj) {
        return (this == obj);
    }

    /**
     * 创建并返回此对象的副本,所谓“副本”的确切含义可能取决于对象的类。其基本意图是：
     * 对于任意对象x，表达式x.clone() != x应为真，且表达式x.clone().getClass() == x.getClass()应为真，
     * 但这些并非绝对要求。虽然通常情况下：x.clone().equals(x)会为真，但这并非绝对要求。
     *
     * 按惯例，返回的对象应通过调用super.clone获得。
     * 若某个类及其所有超类（除Object外）遵循此惯例，则x.clone().getClass() == x.getClass()将成立。
     *
     * 按惯例，该方法返回的对象应与被克隆对象相互独立。为实现这种独立性，可能需要在返回前修改super.clone返回对象的一个或多个字段。
     * 通常这意味着复制构成被克隆对象内部“深层结构”的所有可变对象，并将这些对象的引用替换为副本的引用。
     * 若类仅包含基本类型字段或不可变对象的引用，通常无需修改super.clone返回对象中的任何字段。
     */
    protected native Object clone() throws CloneNotSupportedException;

    /**
     * 通常toString方法会返回一个“文本化表示”该对象的字符串。
     * 该结果应为简洁且信息丰富的表示形式，便于人类阅读。
     * 建议所有子类都重写此方法。字符串输出结果在时间推移或不同JVM调用间未必保持稳定。
     */
    public String toString() {
        return getClass().getName() + "@" + Integer.toHexString(hashCode());
    }

    /**
     * 唤醒单个在线程监视器上等待的线程。若存在线程在此对象上等待，则随机选择其中一个进行唤醒。
     * 该选择过程具有任意性，具体由实现决定。线程通过调用等待方法之一来监视对象。
     *
     * 被唤醒的线程需待当前线程释放该对象锁定后方可继续执行。被唤醒的线程将以常规方式与其他可能正在争夺此对象同步的线程竞争；
     * 例如，被唤醒的线程在成为下一个锁定此对象的线程时，既不享有可靠的特权，也不处于劣势。
     *
     * 此方法仅应由拥有此对象监视器的线程调用。线程可通过以下三种方式之一成为对象监视器的所有者：
     * 1、通过执行该对象的synchronized实例的方法。
     * 2、通过执行以该对象为synchronized表达式的同步语句主体。
     * 3、对于Class类型的对象，通过执行该类的synchronized的static方法。
     * 每次只有1个线程可以拥有对象监视器。
     */
    public final native void notify();

    /**
     * 唤醒所有等待此对象监视器的线程。线程通过调用等待方法之一来等待对象的监视器。
     *
     * 被唤醒的线程将无法继续执行，直到当前线程释放对该对象的锁定。被唤醒的线程将与其他可能正在竞争此对象同步的线程以常规方式竞争；
     * 例如，被唤醒的线程在成为下一个锁定此对象的线程时，既不享有可靠的特权，也不处于劣势。
     *
     * 此方法仅应由该对象监视器的所有者线程调用。有关线程如何成为监视器所有者的说明，请参见notify方法。
     */
    public final native void notifyAll();

    /**
     * 使当前线程保持等待状态，直至被唤醒（通常通过notify或interrupt实现）。
     * 该方法的行为完全等同于调用wait(0L, 0)。具体细节请参阅wait(long, int)方法的规范说明。
     */
    public final void wait() throws InterruptedException {
        wait(0L);
    }

    /**
     * 使当前线程进入等待状态，直至被唤醒（通常通过notify或interrupt实现），或直至经过特定的实际时间。
     * 该方法的行为完全等同于调用wait(timeoutMillis, 0)。详情请参阅wait(long, int)方法的规范说明。
     */
    public final native void wait(long timeoutMillis) throws InterruptedException;

    /**
     * 使当前线程进入等待状态，直至被唤醒（通常通过notify或interrupt实现），或直至经过一定实际时间。
     * 当前线程必须持有该对象的监视器锁。有关线程如何成为监视器锁持有者的方式，请参见notify方法的说明。
     *
     * 该方法使当前线程（此处称为T）将自身加入该对象的等待集，并释放对该对象的所有同步请求。
     * 需注意：仅释放该对象的锁；当前线程可能同步的其他对象在等待期间仍保持锁定状态。
     * 随后线程T将被禁用（用于线程调度），处于休眠状态直至发生以下任一情况：
     * 1、某个其他线程调用了该对象的notify方法，而线程T恰好被随机选中作为被唤醒的线程。
     * 2、某个其他线程调用了该对象的notifyAll方法。
     * 3、某个其他线程中断了线程T。
     * 4、指定的实际时间（约略）已过去。该实际时间以纳秒为单位，由表达式1000000 * timeoutMillis + nanos计算得出。
     * 若timeoutMillis和nanos均为零，则不考虑实际时间，该线程将持续等待直至被其他原因唤醒。
     * 5、线程T被错误唤醒。（详见下文）
     *
     * 线程T随后将从该对象的等待队列中移除，并重新启用以参与线程调度。它将以常规方式与其他线程竞争对该对象的同步权限；
     * 一旦重新获得对象控制权，其所有对该对象的同步请求状态将恢复至原状——即调用wait方法时的状态。此时线程T将从wait方法的调用中返回。
     * 因此，从等待方法返回时，对象与线程T的同步状态完全恢复至调用等待方法时的状态。
     *
     * 线程可能在未被通知、未被中断且未超时的情况下被唤醒，此类现象称为虚假唤醒。
     * 虽然实际中罕见，但应用程序必须通过检测本应触发唤醒的条件来防范此类情况，若条件未满足则继续保持等待状态。参见下例。
     * 更多相关信息请参阅Brian Goetz等著《Java并发编程实践》(Addison-Wesley, 2006)第14.2节“条件队列”，或Joshua Bloch著《Effective Java》第二版(Addison-Wesley, 2008)中的第69条。
     *
     * 若当前线程在等待期间被任何线程中断，则抛出InterruptedException异常。
     * 该异常抛出时当前线程的中断状态将被清除。此异常仅在对象锁状态按前述方式恢复后才会抛出。
     *
     * @param  timeoutMillis 最大等待时长，单位是毫秒
     * @param  nanos   额外的等待时间，单位是微妙
     */
    public final void wait(long timeoutMillis, int nanos) throws InterruptedException {
        if(timeoutMillis < 0)
            throw new IllegalArgumentException("timeoutMillis value is negative");

        if(nanos < 0 || nanos > 999999)
            throw new IllegalArgumentException("nanosecond timeout value out of range");

        if(nanos > 0 && timeoutMillis < Long.MAX_VALUE)
            timeoutMillis++;
        wait(timeoutMillis);
    }

    /**
     *当垃圾回收器确定对象不再被任何引用时，会调用该对象的finalize方法。子类重写finalize方法用于释放系统资源或执行其他清理操作。
     *
     * finalize方法的一般契约是：当Java虚拟机判定该对象已无法被任何未终止的线程访问时（除非因其他待终结对象或类的终结操作所致），
     * 则会调用该方法。finalize方法可执行任意操作，包括使该对象重新对其他线程可用；但其通常目的是在对象被不可逆丢弃前执行清理操作。
     * 例如，表示输入/输出连接的对象的finalize方法，可能在对象被永久丢弃前执行显式I/O事务以断开连接。
     *
     * Object类的finalize方法不执行特殊操作，仅正常返回。Object的子类可重写此定义。
     *
     * Java编程语言不保证由哪个线程调用特定对象的finalize方法。但保证调用finalize的方法在调用时不会持有任何用户可见的同步锁。
     * 若finalize方法抛出未捕获的异常，该异常将被忽略，对象的终结过程随即终止。
     *
     * 当对象的finalize方法被调用后，Java虚拟机将不再执行任何操作，直至确认该对象已无法被任何未终止的线程访问（包括其他待终结对象或类的操作），此时方可丢弃该对象。
     *
     * Java虚拟机对任意对象的finalize方法最多仅调用一次。
     * 该方法抛出的异常将终止对象的终结过程，但异常本身将被忽略。
     *
     * @deprecated
     * finalize机制本身存在问题。终结操作可能引发性能问题、死锁和程序卡死。
     * 终结器中的错误会导致资源泄漏；若终结操作不再必要，则无法取消；不同对象终结方法的调用顺序也未作规定。
     * 此外，终结时机无法得到保证。可终结对象的终结方法可能在经过无限期延迟后才被调用，甚至可能永远不会被调用。
     * 持有非堆资源的类应提供显式释放资源的方法，并酌情实现AutoCloseable接口。
     * 当对象不可达时，java.lang.ref.Cleaner和java.lang.ref.PhantomReference提供了更灵活高效的资源释放方案。
     */
    @Deprecated(since="9")
    protected void finalize() throws Throwable {}
}

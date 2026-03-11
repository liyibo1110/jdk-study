package com.github.liyibo1110.jdk.java.util.concurrent.locks;

import jdk.internal.misc.Unsafe;
import org.w3c.dom.Node;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;

/**
 * 非常有名的AQS，概括作用就是：为“锁、信号量、门闩、条件变量”等同步器，提供统一的排队、阻塞、唤醒框架。
 * 也就是说AQS不直接代表某一种锁，而是一个同步器开发框架，以下类都建立在它之上：
 * 1、ReentrantLock
 * 2、Semaphore
 * 3、CountDownLatch
 * 4、ReentrantReadWriteLock
 * 5、FutureTask（不直接使用，但是思想相关）
 * 6、一些JUC同步器
 *
 * 它帮子类解决了什么问题：
 * 1、获取失败的线程怎么排队？
 * 2、排队后怎么挂起？
 * 3、谁来唤醒下一个线程？
 * 4、如果中断了，要怎么处理？
 * 5、如果超时了，要怎么处理？
 * 6、节点被取消了，要怎么处理？
 * 7、如果是条件等待，要怎么处理？
 * 把这些通用的：排队 + 阻塞 + 唤醒 + 取消等逻辑统一封装起来，子类只需要关注：
 * 1、tryAcquire
 * 2、tryRelease
 * 3、tryAcquireShared
 * 4、tryReleaseShared
 * 5、isHeldExclusiveLey
 *
 * 知识密度高，但是抽象主线其实比较清晰：
 * state（即要竞争的标的） -> 获取资源失败 -> 入队 -> park挂起 -> 前驱释放后unpark -> 再次竞争state，
 * 始终围绕：一个共享状态 + 一个等待队列展开的统一模型。
 * 运行逻辑大概是：
 * 1、线程尝试获取资源
 * 2、tryAcquire / tryAcquireShared
 * 3、获取失败
 * 4、加入同步队列
 * 5、LockSupport.park挂起
 * 6、等前驱释放资源
 * 7、unpark唤醒
 * 8、重新竞争获取资源
 *
 * AQS可以拆分成4大块来分别学习：
 * 1、同步状态 state
 * 2、等待队列 Queue / Node
 * 3、独占获取与释放 acquire / release
 * 4、条件队列 ConditionObject
 * @author liyibo
 * @date 2026-03-10 15:03
 */
public abstract class AbstractQueuedSynchronizer extends AbstractOwnableSynchronizer implements Serializable {
    private static final long serialVersionUID = 7373984972572414691L;

    protected AbstractQueuedSynchronizer() {}

    /** 必须是1 */
    static final int WAITING = 1;

    /** 必须是负值 */
    static final int CANCELLED = 0x80000000;

    /** 处于条件等待 */
    static final int COND = 2;

    abstract static class Node {
        volatile Node prev;
        volatile Node next;

        /** 表示这个Node对应哪个实际线程 */
        Thread waiter;

        /**
         * 表示节点的等待状态：取值包括：
         * CANCELLED：这个节点已经取消，不再参与竞争。
         * SIGNAL：我后面的节点需要我在被释放时去唤醒它。
         * CONDITION：这个节点当前在Condition条件队列里。
         * PROPAGATE：用于共享模式传播唤醒。
         * 0
         */
        volatile int status;

        /**
         * 替换prev
         */
        final boolean casPrev(Node c, Node v) {  // for cleanQueue
            return U.weakCompareAndSetReference(this, PREV, c, v);
        }

        /**
         * 替换next
         */
        final boolean casNext(Node c, Node v) {  // for cleanQueue
            return U.weakCompareAndSetReference(this, NEXT, c, v);
        }

        final int getAndUnsetStatus(int v) {     // for signalling
            return U.getAndBitwiseAndInt(this, STATUS, ~v);
        }

        final void setPrevRelaxed(Node p) {      // for off-queue assignment
            U.putReference(this, PREV, p);
        }

        final void setStatusRelaxed(int s) {     // for off-queue assignment
            U.putInt(this, STATUS, s);
        }

        /**
         * 内部status设置为0
         */
        final void clearStatus() {               // for reducing unneeded signals
            U.putIntOpaque(this, STATUS, 0);
        }

        private static final long STATUS = U.objectFieldOffset(java.util.concurrent.locks.AbstractQueuedSynchronizer.Node.class, "status");
        private static final long NEXT = U.objectFieldOffset(java.util.concurrent.locks.AbstractQueuedSynchronizer.Node.class, "next");
        private static final long PREV = U.objectFieldOffset(java.util.concurrent.locks.AbstractQueuedSynchronizer.Node.class, "prev");
    }

    /**
     * 独占模式的Node：一次只允许一个线程成功，相对比较简单
     */
    static final class ExclusiveNode extends Node {}

    /**
     * 共享模式的Node：可能允许多个线程连续传播成功，要更复杂一些，
     * 例如Semaphore、CountDownLatch等组件是基于共享模式的。
     */
    static final class SharedNode extends Node {}

    /**
     * 在队列里，其实有两种链，这个是第二种：
     * 1、正常的链通过：prev和next来查找。
     * 2、ConditionNode通过nextWaiter来查找。
     * 这个是AQS里最难的一部分，要先理解独占锁、同步列队以及节点状态迁移才可以学习这里。
     */
    static final class ConditionNode extends Node implements ForkJoinPool.ManagedBlocker {
        /** 条件队列专用 */
        ConditionNode nextWaiter;

        /**
         * 允许在ForkJoinPool中使用条件变量，而不会导致固定池耗尽的风险。
         * 此功能仅适用于无定时条件的等待操作，不适用于定时版本。
         */
        public final boolean isReleasable() {
            return status <= 1 || Thread.currentThread().isInterrupted();
        }

        public final boolean block() {
            while(!isReleasable())
                LockSupport.park();
            return true;
        }
    }

    /**
     * 等待队列的头元素，延迟初始化
     * 队列结构大概是：
     * head(dummy) <-> node1 <-> node2 <-> node3
     * 注意head是一个dummy节点 / 已成功获取后的前驱节点，真正等待的实际线程一般都在head.next往后。
     */
    private transient volatile Node head;

    /** 等待队列的尾元素，初始化后，仅可以通过casTail方法来修改 */
    private transient volatile Node tail;

    /**
     * 当前的资源状态，但其具体含义由子类来决定，例如：
     * 1、在ReentrantLock中：0表示未加锁；1表示加锁一次；2表示重入两次。
     * 2、在Semaphore中：state表示可用的许可数。
     * 3、在CountDownLatch中：state表示剩余计数。
     * AQS自己不解释state，state的业务语义由子类来定义。
     */
    private volatile int state;

    protected final int getState() {
        return state;
    }

    protected final void setState(int newState) {
        state = newState;
    }

    protected final boolean compareAndSetState(int expect, int update) {
        return U.compareAndSetInt(this, STATE, expect, update);
    }

    // Queuing utilities

    private boolean casTail(Node c, Node v) {
        return U.compareAndSetReference(this, TAIL, c, v);
    }

    /**
     * 尝试为头节点创建一个新的虚拟节点
     */
    private void tryInitializeHead() {
        Node h = new ExclusiveNode();
        if(U.compareAndSetReference(this, HEAD, null, h))
            tail = h;   // 如果队列是空的，则tail也指向一样的唯一节点
    }

    /**
     * 核心acquire方法，由所有对外的acquire方法来调用。
     * @param node 除非重新获取Condition，否则为null。
     * @param arg 来自acquire的arg参数
     * @param shared 是否为共享模式，或者独占模式。
     * @param interruptible 若终止操作，则在中断时返回负值结果。
     * @param timed true代表会等待一段时长。
     * @param time 如果timed为true，则要等待的值，单位是纳秒。
     * @return 获取成功则为正数、超时为0，被中断则返回负数。
     */
    final int acquire(Node node, int arg, boolean shared,
                      boolean interruptible, boolean timed, long time) {
        Thread current = Thread.currentThread();

        /**
         * 用于短暂自旋优化，当线程刚被唤醒时，先自旋几次再park，减少线程切换
         */
        byte spins = 0;
        byte postSpins = 0;

        /**
         * 记录线程在等待过程中是否被中断，因为有些API（acquireInterruptibly）要响应中断。
         */
        boolean interrupted = false;

        /**
         * 表示当前node是否已经是队列里第一个等待者了，
         * 即head.next == node本身
         */
        boolean first = false;

        /**
         * node的前驱节点
         */
        Node pred = null;

        /**
         * 重复执行：
         * 1、检查节点是否当前为首节点
         * 2、若为首节点，则确保队首稳定；否则确保前驱节点有效
         * 3、若节点为首节点或尚未入队，尝试获取
         * 4、否则若节点尚未创建，则创建该节点
         * 5、否则若尚未入队，尝试入队一次
         * 6、否则若从驻留状态唤醒，则重试（最多重试 postSpins 次）
         * 7、否则若未设置WAITING状态，则设置并重试
         * 8、否则进入park状态并清除WAITING状态，同时检查是否被取消
         */

        while(true) {
            /**
             * 这一大串用来判断自己是不是首节点。
             * 进来了就说明node不在队首，并且Node已经被创建同时前驱不为null，并且前驱不是head本身
             */
            if(!first && (pred = (node == null) ? null : node.prev) != null && !(first = (head == pred))) {
                /**
                 * 前驱节点已经被取消了（小于0表示CANCELLED状态，例如线程被中断或超时）
                 * 需要清理取消节点（调用cleanQueue方法）
                 */
                if(pred.status < 0) {
                    cleanQueue();
                    continue;
                }else if(pred.prev == null) {   // 前驱还未稳定，说明pred还没完全入队（并发暂态），就进行短暂自旋再重新循环
                    Thread.onSpinWait();
                    continue;
                }
            }

            /**
             * 判断能不能尝试获取锁
             * 1、first == true：说明当前节点是队首，可以抢锁。
             * 2、pred == null：说明pred还没有入队，也可以先抢锁，属于优化路径
             */
            if(first || pred == null) {
                boolean acquired;
                try {
                    // 尝试获取锁，不同的实现类会走不同的路径
                    if(shared)
                        acquired = (tryAcquireShared(arg) >= 0);
                    else
                        acquired = tryAcquire(arg);
                } catch (Throwable ex) {
                    cancelAcquire(node, interrupted, false);
                    throw ex;
                }
                if(acquired) {  // 如果抢到锁了
                    if(first) { // 如果自己是队首（因为pred为null也可以抢锁，不一定就是在队首，不在则不能执行里面的清理）
                        node.prev = null;
                        head = node;
                        pred.next = null;
                        node.waiter = null;
                        if(shared)  // 如果是共享锁，继续唤醒后继
                            signalNextIfShared(node);
                        if(interrupted) // 如果被中断过，要补上中断标记（因为如果park过，则可能会清除中断标记）
                            current.interrupt();
                        //注意node的next并没有清除，同时node就是head，结构相当于变成head -> next waiter
                    }
                    return 1;   // 正数代表获取锁成功
                }

                /**
                 * 注意这后面全是if/else语句，即每次只会进入一个分支，然后要么重新循环，要么break，执行最后的cancelAcquire
                 */
                if(node == null) {  // 线程第一次进来排队，就会先执行这里的逻辑，要先构造出Node
                    if(shared)
                        node = new SharedNode();
                    else
                        node = new ExclusiveNode();
                }else if(pred == null) {    // 这里判断Node已经在上一轮循环new出来了，但是还没有入队列，里面要尝试入队
                    node.waiter = current;
                    Node t = tail;
                    node.setPrevRelaxed(t); // 相当于node.prev = tail
                    if(t == null)   // 为true说明队列还没有初始化，要执行tryInitializeHead来建立一个起始空Node
                        tryInitializeHead();
                    else if(!casTail(t, node))  // casTail就是把tail指向node
                        node.setPrevRelaxed(null);
                    else
                        t.next = node;
                }else if(first && spins != 0) { // 如果自己是队首，但刚被唤醒，要先自旋几次，目的是减少park/unpark的频率
                    --spins;
                    Thread.onSpinWait();
                }else if(node.status == 0) {    // node还没有被标记为等待，则标记，允许前驱节点唤醒自己
                    node.status = WAITING;
                }else { // 最后要进行park了
                    long nanos;
                    spins = postSpins = (byte)((postSpins << 1) | 1);   // 重新生成自旋次数
                    if(!timed)
                        LockSupport.park(this); // 一直挂起直到被唤醒
                    else if((nanos = time - System.nanoTime()) > 0L)
                        LockSupport.parkNanos(this, nanos);
                    else
                        break;
                    // 这里是被唤醒后，要执行的逻辑
                    node.clearStatus(); // status由WAITING变回0
                    if((interrupted |= Thread.interrupted()) && interruptible)  // 检查线程是否被中断
                        break;
                }
            }
        }
        // 如果上面的逻辑，出现了：中断、超时、异常，就会跳出循环最后来到这里
        return cancelAcquire(node, interrupted, interruptible);
    }

    /**
     * 主要作用就是清理CANCELLED节点，从尾到头扫描队列，把取消状态的node从链表中摘掉，同时顺便修复一些还没完全连好的next指针。
     */
    private void cleanQueue() {
        while(true) {
            /**
             * q：当前正在检查的节点
             * p：q的前驱，也就是q.prev
             * s：q的后继（更准确地说，是上一次扫描的node）
             * n：p的next，用来检查前向链接是否一致
             * 结构为：p <-> q <-> s
             */
            for(Node q = tail, s = null, p, n;;) {
                if(q == null || (p = q.prev) == null)   // 队列空，或者q.prev为null（说明从后往前整个遍历完成了）
                    return;
                /**
                 * 检查三兄弟（p、q、s）是否状态正常：
                 * 1、s == null说明是第一次扫描，所以q应该是tail，如果后面的tail != q成立，说明tail被别的线程改了，状态不一致。
                 * 2、s != null说明不是第一次扫描，所以s.prev应该是q，如果不是则说明状态不一致，如果s.stats < 0成立，说明s被取消了，状态也不一致。
                 */
                if(s == null ? tail != q : (s.prev != q || s.status < 0))
                    break;
                if(q.status < 0) {  // q指向的node状态时CANCELLED，需要清理（即p - q - s变成p - s）
                    /**
                     * 又分两种情况：
                     * 1、s == null，说明q正好是tail，只需要把tail从q改成p就完事了。
                     * 2、s != null，说明q在中间，让后继s跳过q，直接指向p就完事了（中间多一步q和p的关系检查）。
                     */
                    if((s == null ? casTail(q, p) : s.casPrev(q, p)) && q.prev == p) {
                        // 调整q的前驱p的next
                        p.casNext(q, s);
                        /**
                         * 如果p就是head，这时head后面的有效后继可能就变了，有必要主动尝试唤醒新的下一个节点。
                         * 目的就是避免head后面因为取消节点导致唤醒链断掉。
                         */
                        if(p.prev == null)
                            signalNext(p);
                    }
                    break;  // 链表已经发生结构变化，直接从tail开始重扫一轮
                }
                /**
                 * 后面的逻辑是为了：帮助把前向next链补完整，并不会删除节点了
                 */
                if((n = p.next) != q) {
                    if(n != null && q.prev == p) {
                        p.casNext(n, q);
                        if(p.prev == null)
                            signalNext(p);
                    }
                    break;
                }
                // 向前移动一个node
                s = q;
                q = q.prev;
            }
        }
    }

    /**
     * 取消正在进行的获取锁尝试
     */
    private int cancelAcquire(Node node, boolean interrupted, boolean interruptible) {
        if(node != null) {
            node.waiter = null;
            node.status = CANCELLED;
            if(node.prev != null)
                cleanQueue();
        }
        if(interrupted) {
            if (interruptible)  // 是否应该返回中断，而非恢复中断标记
                return CANCELLED;
            else
                Thread.currentThread().interrupt(); // 恢复中断标记
        }
        return 0;
    }

    // Main exported methods

    /**
     * 尝试以独占模式获取。该方法应查询对象状态是否允许以独占模式获取，若允许则执行获取操作。
     * 该方法总是由执行获取操作的线程调用。若该方法报告失败，则获取方法可将该线程加入队列（若尚未入队），直至收到其他线程释放信号。
     * 此机制可用于实现Lock.tryLock()方法。
     * 默认实现会抛出UnsupportedOperationException。
     * @param arg 获取参数。该值始终是传递给获取方法的参数，或是进入条件等待时的保存值。该值在其他情况下不作解释，可代表任意内容。
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 尝试将状态设置为反映独占模式下的释放操作。此方法总是由执行释放操作的线程调用。
     * 默认实现会抛出UnsupportedOperationException。
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 尝试以共享模式获取。该方法应查询对象状态是否允许以共享模式获取，若允许则执行获取操作。
     * 此方法总是由执行获取操作的线程调用。若该方法报告失败，且该线程尚未被排队，则获取方法可将其加入队列，直至收到其他线程释放信号。
     * 默认实现会抛出UnsupportedOperationException。
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 尝试将状态设置为共享模式下的释放状态。此方法总是由执行释放操作的线程调用。
     * 默认实现会抛出UnsupportedOperationException。
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 若当前（调用）线程独占持有同步锁，则返回 true。
     * 每次调用AbstractQueuedSynchronizer.ConditionObject方法时都会调用此方法。
     * 默认实现会抛出 UnsupportedOperationException。
     * 此方法仅在AbstractQueuedSynchronizer.ConditionObject方法内部调用，因此若未使用条件锁则无需定义。
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * 以独占模式获取，忽略中断。
     * 通过至少调用一次tryAcquire实现，成功时返回。否则线程将被加入队列，可能反复阻塞和解除阻塞，持续调用tryAcquire直至成功。
     * 此方法可用于实现Lock.lock方法。
     *
     * PS：这也是学习AQS的入口方法
     * @param arg
     */
    public final void acquire(int arg) {
        if(!tryAcquire(arg))
            acquire(null, arg, false, false, false, 0L);
    }

    // Queue inspection methods

    /**
     * 队列中是否有正常排队的线程。
     */
    public final boolean hasQueuedThreads() {
        for(Node p = tail, h = head; p != h && p != null; p = p.prev)
            if(p.status >= 0)
                return true;
        return false;
    }

    /**
     * 查询是否曾有线程争用过此同步器；即是否曾有获取方法被阻塞过。
     * 在此实现中，该操作以常数时间返回。
     */
    public final boolean hasContended() {
        return head != null;    // head应该是dummy，只要有就说明有线程在里面排队过
    }

    /**
     * 获取排在第一位的正常排队线程
     */
    public final Thread getFirstQueuedThread() {
        Thread first = null;
        Thread w;
        Node h, s;
        if((h = head) != null && ((s = h.next) == null || (first = s.waiter) == null || s.prev == null)) {
            // 一直从后往前遍历，直到找到最靠头且不为null的Node
            for(Node p = tail, q; p != null && (q = p.prev) != null; p = q) {
                if((w = p.waiter) != null)
                    first = w;
            }
        }
        return first;
    }

    /**
     * 指定线程是否在队列中
     */
    public final boolean isQueued(Thread thread) {
        if(thread == null)
            throw new NullPointerException();
        for(Node p = tail; p != null; p = p.prev) {
            if(p.waiter == thread)
                return true;
        }
        return false;
    }

    /**
     * 若存在首个队列线程，且该线程正以独占模式等待，则返回true。
     * 若此方法返回true，且当前线程正尝试以共享模式获取锁（即此方法由tryAcquireShared调用），则可确保当前线程并非首个队列线程。
     * 仅在ReentrantReadWriteLock中作为启发式策略使用。
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null && (s = h.next) != null && !(s instanceof SharedNode) && s.waiter != null;
    }

    /**
     * 返回队列中，在当前线程前面，是否还有别的线程在排队（例如FairSync公平锁会用到这个，返回true则要进来老实排队）
     */
    public final boolean hasQueuedPredecessors() {
        Thread first = null;
        Node h, s;
        /**
         * 正常来说判断前面有没有，只判断head.next就行了，但是这里的代码比较保守，
         * 后面的3个是否为null的检查，是在确认：head.next这条快速路径能不能可靠使用，
         * 1、(s = h.next) == null，如果为true表示：head存在，但head.next却没有，表示暂时还没有后继节点或者有线程正在入队中，
         * 所以不可靠，要走getFirstQueuedThread方法。
         * 2、(first = s.waiter) == null，如果为true表示：head.next也有，但是里面的waiter还是null，所以不可靠。
         * 3、s.prev == null，如果为true表示：h.next指向了s，但是s的前驱prev还没连好，所以不可靠。
         */
        if((h = head) != null && ((s = h.next) == null || (first = s.waiter) == null || s.prev == null))
            first = getFirstQueuedThread();
        return first != null && first != Thread.currentThread();    // 前面还有排队的线程，同时不是我自己
    }

    // Instrumentation methods for conditions

    /**
     * 返回队列中的排队线程数
     */
    public final int getQueueLength() {
        int n = 0;
        for(Node p = tail; p != null; p = p.prev) {
            if (p.waiter != null)
                ++n;
        }
        return n;
    }

    /**
     * 返回队列中的排队线程
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<>();
        for(Node p = tail; p != null; p = p.prev) {
            Thread t = p.waiter;
            if(t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * 返回队列中的排队线程（只要独占模式的）
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<>();
        for(Node p = tail; p != null; p = p.prev) {
            if(!(p instanceof SharedNode)) {
                Thread t = p.waiter;
                if(t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * 返回队列中的排队线程（只要共享模式的）
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<>();
        for(Node p = tail; p != null; p = p.prev) {
            if(p instanceof SharedNode) {
                Thread t = p.waiter;
                if(t != null)
                    list.add(t);
            }
        }
        return list;
    }

    @Override
    public String toString() {
        return super.toString()
                + "[State = " + getState() + ", "
                + (hasQueuedThreads() ? "non" : "") + "empty queue]";
    }

    /**
     * 作为锁实现基础的AQS的Condition实现。
     * 本类的文档描述了其工作机制，而非从锁和条件使用者的角度阐述的行为规范。
     * 导出版本通常需附带文档说明条件语义，这些语义依赖于关联的抽象队列同步器的语义。
     *
     * 该类具有序列化特性，但所有字段均为瞬态字段，因此反序列化的条件对象不会包含等待者。
     */
    public class ConditionObject implements Condition, Serializable {
        private static final long serialVersionUID = 1173984872572414699L;

        /** 条件队列的头节点 */
        private transient ConditionNode firstWaiter;

        /** 条件队列的尾节点 */
        private transient ConditionNode lastWaiter;

        public ConditionObject() {}
    }

    // Unsafe
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long STATE = U.objectFieldOffset(java.util.concurrent.locks.AbstractQueuedSynchronizer.class, "state");
    private static final long HEAD = U.objectFieldOffset(java.util.concurrent.locks.AbstractQueuedSynchronizer.class, "head");
    private static final long TAIL = U.objectFieldOffset(java.util.concurrent.locks.AbstractQueuedSynchronizer.class, "tail");

    static {
        Class<?> ensureLoaded = LockSupport.class;
    }
}

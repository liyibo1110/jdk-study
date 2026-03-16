package com.github.liyibo1110.jdk.java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * CountDownLatch和CyclicBarrier的进阶产物，作用可以概括为：
 * 1、可动态注册参与者。
 * 2、多阶段同步屏障。
 * 3、不基于AQS（CAS + 自旋 + Treiber stack）。
 *
 * CountDownLatch的限制：
 * 1、计数器只能减少。
 * 2、只能一次性使用。
 *
 * CyclicBarrier的限制：
 * 1、虽然可以循环使用，但是参与的线程数量是固定的。
 *
 * Phaser的功能：
 * 1、参与线程可以动态注册和取消。
 * 2、可以有多个阶段（phase）
 * 即phase 0: 线程1到达、线程2到达、线程3到达 -> 所有线程进入phase1，在phase1里面继续同步直到进入phase2
 * @author liyibo
 * @date 2026-03-15 17:12
 */
public class Phaser {

    /**
     * 结构如下：
     * termination + 32bit phase + 16bit parties + 16bit unarrived
     * 1、phase：当前阶段编号，每次barrier完成就加1.
     * 2、parties：注册的参与者数量，即线程数。
     * 3、unarrived：还没达到barrier的线程数量。例如共3个线程，前2个已经arrive，那么unarrived就是1
     * 4、termination：是否已终止。
     */
    private volatile long state;

    private static final int  MAX_PARTIES     = 0xffff;
    private static final int  MAX_PHASE       = Integer.MAX_VALUE;
    private static final int  PARTIES_SHIFT   = 16;
    private static final int  PHASE_SHIFT     = 32;
    private static final int  UNARRIVED_MASK  = 0xffff;      // to mask ints
    private static final long PARTIES_MASK    = 0xffff0000L; // to mask longs
    private static final long COUNTS_MASK     = 0xffffffffL;
    private static final long TERMINATION_BIT = 1L << 63;

    // some special values
    private static final int  ONE_ARRIVAL     = 1;
    private static final int  ONE_PARTY       = 1 << PARTIES_SHIFT;
    private static final int  ONE_DEREGISTER  = ONE_ARRIVAL|ONE_PARTY;
    private static final int  EMPTY           = 1;

    // The following unpacking methods are usually manually inlined

    private static int unarrivedOf(long s) {
        int counts = (int)s;
        return (counts == EMPTY) ? 0 : (counts & UNARRIVED_MASK);
    }

    private static int partiesOf(long s) {
        return (int)s >>> PARTIES_SHIFT;
    }

    private static int phaseOf(long s) {
        return (int)(s >>> PHASE_SHIFT);
    }

    private static int arrivedOf(long s) {
        int counts = (int)s;
        return (counts == EMPTY) ? 0 :
                (counts >>> PARTIES_SHIFT) - (counts & UNARRIVED_MASK);
    }

    /**
     * 当前phaser的父phaser，可以为null。
     */
    private final Phaser parent;

    /**
     * 当前phaser的根phaser。
     */
    private final Phaser root;

    /**
     * Phaser组件的优化点，本来一个队列，却搞成了奇偶两个队列，核心原因是：避免O(n)扫描等待队列，
     * 分成了2个队列，使得同一个队列只会存储相同奇偶phase的等待线程。
     * 目的是当推进之后，新进来的等待线程，会被单独放置特定队列中，总之就是可以减少扫描的节点数。
     */
    private final AtomicReference<QNode> evenQ;
    private final AtomicReference<QNode> oddQ;

    private String badArrive(long s) {
        return "Attempted arrival of unregistered party for " + stateToString(s);
    }

    private String badRegister(long s) {
        return "Attempt to register more than " + MAX_PARTIES + " parties for " + stateToString(s);
    }

    /**
     * 参与者到达当前phase，并在必要时触发phase推进，方法本质是：
     * 把当前phaser的unarrived减去adjust对应的数量，如果当前调用者刚好是最后一个未到达者，就负责推进phase，或者把到达信息向parent传播。
     *
     * 注意这个参数adjust其实有两种类型的值：
     * 1、ONE_ARRIVAL：含义是unarrived -= 1，然后parties不变，意思是：我完成了，但我还要继续参与后续的phase。
     * 2、ONE_DEREGISTER：含义是unarrived -=1，然后parties -= 1，意思是：我完成了，而且我从此退出，不再参与后续phase。
     *
     * 整体流程：
     * 1、读取并校准当前状态。
     * 2、检查当前调用是否合法。
     * 3、CAS扣减计数。
     * 4、如果当前调用者是最后一个到达者，则要负责：
     * 对root：推进到下一个phase，同时唤醒等待者。
     * 对sub-phaser：把我这个child节点到齐了这件事情，传播给parent。
     */
    private int doArrive(int adjust) {
        final Phaser root = this.root;  // 后面会频繁用到
        while(true) {
            long s = (root == this) ? state : reconcileState(); // root直接读state，sub要调用reconcileState来获取
            int phase = (int)(s >>> PHASE_SHIFT);   // 取phase值
            if(phase < 0)  // 为负说明phase已经终止
                return phase;
            int counts = (int)s;
            /**
             * EMPTY不是普通的parties=0，unarrived=0的常规运行态，
             * 它是一个特殊的编码态，表示当前phaser没有注册party。
             */
            int unarrived = (counts == EMPTY) ? 0 : (counts & UNARRIVED_MASK);
            /**
             * unarrived <= 0说明：
             * 1、当前phase已经没人需要再到达了，即本轮已经结束了，你却又来arrive，是非法的状态。
             * 2、当前的phaser是空的，没有被注册，你来调用arrive，是非法的状态。
             */
            if(unarrived <= 0)
                throw new IllegalStateException(badArrive(s));
            if(STATE.compareAndSet(this, s, s -= adjust)) { // 注意这个s值已经修改成减少过之后的版本了
                if(unarrived == 1) {    // 说明当前线程是最后一个到达者，要负责推进或传播
                    long n = s & PARTIES_MASK;  // 下一阶段还剩多少注册party的基础值
                    int nextUnarrived = (int)n >>> PARTIES_SHIFT;   // 下一个phase开始时，应该有多少个未到达者
                    /**
                     * 当前对象是root，自己负责推进phase，直接完成：
                     * 1、是否终止。
                     * 2、是否转EMPTY。
                     * 3、是否进入下一个phase。
                     * 4、唤醒等待者。
                     */
                    if(root == this) {
                        /**
                         * 一个核心钩子，有默认实现：
                         * 1、如果下一阶段已经没有party了，则终止phaser。
                         * 2、子类可以重写这个方法，自定义何时停止。
                         * 所以这个方法的意思是：当前phase收尾后，我还要不要继续活着，如果方法返回true，就设置终止位。
                         */
                        if(onAdvance(phase, nextUnarrived))
                            n |= TERMINATION_BIT;
                        /**
                         * 如果没有终止，但下阶段没有party了，则置EMPTY，和上一步相似，但语义不同，这里表示：
                         * phaser不一定算终止，但是当前已经没有注册party了，所以下一状态可以进入EMPTY。
                         * 可以理解成：没有参与者了，先变成空状态，而不是一定强制终止。
                         */
                        else if(nextUnarrived == 0)
                            n |= EMPTY;
                        /**
                         * 这里是最正常的情况，要重置下一个phase的unarrived，
                         * 如果当前phase收尾后仍有3个parties，则下一个阶段刚开始应该是parties=3，unarrived=3，
                         * 所以这里把nextUnarrived写回去。
                         */
                        else
                            n |= nextUnarrived;
                        int nextPhase = (phase + 1) & MAX_PHASE;
                        n |= (long)nextPhase << PHASE_SHIFT;
                        STATE.compareAndSet(this, s, n);
                        /**
                         * 重要方法调用，表示当前phase已经结束，把等待这个phase完成的线程都给唤醒。
                         */
                        releaseWaiters(phase);
                    }else if(nextUnarrived == 0) {
                        /**
                         * 当前对象不是root，不能自己推进全局，只能向parent报到，总原则：
                         * sub-phaser自己内部凑齐，只代表这个子节点凑齐了，它不能单独决定全局phase推进，必须把结果向parent传播。
                         */
                        phase = parent.doArrive(ONE_DEREGISTER);
                        STATE.compareAndSet(this, s, s | EMPTY);
                    }else {
                        phase = parent.doArrive(ONE_ARRIVAL);
                    }
                }
                return phase;
            }
        }
    }

    /**
     * 把registrations个新参与者注册进当前phaser，并在必要时把parent也联动注册，要保证两件事：
     * 1、parties += registrations
     * 2、unarrived += registrations
     * 注意如果这是一个子phaser，并且这是它第一次从空变成非空，那必须让它的parent也要注册1个party，
     * 因为子phaser整体在parent看来，相当于一个参与者，
     * 所以子phaser一旦第一次启用，parent也要直到它下面多了一个活跃子节点，即parent.doRegister(1)的存在意义。
     *
     * 方法最终返回：注册完成时观察到的phase
     */
    private int doRegister(int registrations) {
        /**
         * 一般根本看不懂这里：
         * 1、registrations << PARTIES_SHIFT等同于：parties += registrations。
         * 2、| registrations等同于：unarrived += registrations
         */
        long adjust = ((long)registrations << PARTIES_SHIFT) | registrations;
        final Phaser parent = this.parent;
        int phase;
        while(true) {
            /**
             * 大概是5条路径：
             * 1、已终止：则直接退出。
             * 2、普通注册（不是第一次注册）：直接cas增加parties/unarrived。
             * 3、root的第一次注册：直接把EMPTY状态初始化成真正可用状态。
             * 4、sub-phaser的第一次注册：先给parent注册1个，再初始化自己。
             * 5、碰到phase正在推进中：不能直接注册，要等推进完成再重试。
             */

            /**
             * 如果当前是root，则直接取state。
             * 如果当前是子phaser，不能直接信自己的state，因为子phaser的phase需要和root/parent保持一致。
             *
             * reconcileState()作用大致就是：把当前phaser的本地状态和root的phase做一次对齐，即：
             * 1、root的state是权威状态。
             * 2、sub-phaser的state可能需要先校准后才能用。
             **/
            long s = (parent == null) ? state : reconcileState();
            /** 从state中拆除以下3个状态值 */
            int counts = (int) s;
            int parties = counts >>> PARTIES_SHIFT;
            int unarrived = counts & UNARRIVED_MASK;
            if (registrations > MAX_PARTIES - parties)   // 保护逻辑，因为parties只有16Bit，所以有最大值限制
                throw new IllegalStateException(badRegister(s));
            phase = (int) (s >>> PHASE_SHIFT);   // 取phase值
            if (phase < 0)   // phase值小于0，按照语义说明phase已停止，跳出循环直接返回这个负值即可
                break;
            /**
             * 当前phaser不是未初始化状态，所以这次不是第一次注册，也就是说这个phaser已经活着了：
             * 1、要么已经有parties。
             * 2、要么已经建立过phase状态。
             * 这是最常见的普通注册路径。
             */
            if (counts != EMPTY) {
                /**
                 * 如果是root则不需要额外处理。
                 * 如果是sub-phaser，则要再调用一次reconcileState()，要求结果仍然等于刚才读到的s，
                 * 这是为了防止你刚才读完状态后，root phase又推进了，导致你基于旧状态来注册。
                 * 也就是说，要保证s现在仍然是有效基线，可以认为这是轻量级一致性确认。
                 */
                if(parent == null || reconcileState() == s) {
                    /**
                     * 非常重要的功能点，当unarrived为0时，要等：
                     * 因为这表示当前phase的所有party都已经到齐了，phase正处于：即将advance或正在advance的边界状态。
                     * 现在不能乱加state值，所以这里要：
                     * 1、如果当前phase已经收齐了，就不要在这一轮里插队了。
                     * 2、先等phase真正推进完成，再在新phase里注册。
                     * 同时注意这里是用root调用的internalAwaitAdvance，而不是this
                     */
                    if(unarrived == 0)
                        root.internalAwaitAdvance(phase, null);
                    else if(STATE.compareAndSet(this, s, s + adjust))   // 正常的CAS注册
                        break;
                }
            }else if(parent == null) {
                /**
                 * 说明当前phaser还是EMPTY，并且它是root，也就是root phaser第一次注册。
                 * 注意EMPTY并不是parties = 0 / unarrived = 0这么简单。
                 * 它表示这个phaser目前还是一种未正式启用的特殊编码状态。
                 * 第一册注册时，不能简单做s + adjust，而是要构造一个完整的新状态。
                 */
                long next = ((long)phase << PHASE_SHIFT) | adjust;
                if(STATE.compareAndSet(this, s, next))
                    break;
            }else {
                /**
                 * 最难的一部分，进入条件为：
                 * 1、当前phaser是子phaser。
                 * 2、并且现在是它的第一次注册。
                 * 也就是一个原本空的sub-phaser，第一次变成活跃状态，这时必须做：父子联动注册事务。
                 *
                 * 另外这个synchronized，也不是直接CAS能搞定的，它涉及了两步：
                 * 1、parent.doRegister(1)
                 * 2、将自己从EMPTY初始化成非EMPTY。
                 * 这是一个跨对象的符合操作，必须避免多个线程同时把“第一次注册”给做乱。
                 * 如果不用同步，可能出现：
                 * 1、两个线程都发现state == EMPTY。
                 * 2、两个线程都去给parent注册1.
                 * 3、最后parent多注册了一次。
                 */
                synchronized(this) {
                    if(state == s) {    // 进入synchronized前，s是在锁外读到的，拿到锁后必须再次确认当前state是否还是那个s
                        /**
                         * 先给parent注册1个party，返回的phase是给sub-phaser用的，要用这个phase来初始化自己。
                         */
                        phase = parent.doRegister(1);
                        if(phase < 0)
                            break;
                        while (!STATE.weakCompareAndSet(this, s, ((long)phase << PHASE_SHIFT) | adjust)) {
                            s = state;
                            phase = (int)(root.state >>> PHASE_SHIFT);
                            // assert (int)s == EMPTY;
                        }
                        break;
                    }
                }
            }
        }
        return phase;
    }

    private long reconcileState() {
        final Phaser root = this.root;
        long s = state;
        if (root != this) {
            int phase;
            int p;
            // CAS to root phase with current parties, tripping unarrived
            while ((phase = (int)(root.state >>> PHASE_SHIFT)) !=
                    (int)(s >>> PHASE_SHIFT) &&
                    !STATE.weakCompareAndSet
                            (this, s,
                                    s = (((long)phase << PHASE_SHIFT) |
                                            ((phase < 0) ? (s & COUNTS_MASK) :
                                                    (((p = (int)s >>> PARTIES_SHIFT) == 0) ? EMPTY :
                                                            ((s & PARTIES_MASK) | p))))))
                s = state;
        }
        return s;
    }

    public Phaser() {
        this(null, 0);
    }

    public Phaser(int parties) {
        this(null, parties);
    }

    public Phaser(Phaser parent) {
        this(parent, 0);
    }

    public Phaser(Phaser parent, int parties) {
        if(parties >>> PARTIES_SHIFT != 0)
            throw new IllegalArgumentException("Illegal number of parties");
        int phase = 0;
        this.parent = parent;
        if(parent != null) {
            final Phaser root = parent.root;
            this.root = root;
            this.evenQ = root.evenQ;
            this.oddQ = root.oddQ;
            if(parties != 0)
                phase = parent.doRegister(1);
        }else {
            this.root = this;
            this.evenQ = new AtomicReference<>();
            this.oddQ = new AtomicReference<>();
        }
        this.state = (parties == 0)
                ? (long)EMPTY
                : ((long)phase << PHASE_SHIFT) | ((long)parties << PARTIES_SHIFT) | ((long)parties);
    }

    public int register() {
        return doRegister(1);
    }

    public int bulkRegister(int parties) {
        if(parties < 0)
            throw new IllegalArgumentException();
        if(parties == 0)
            return getPhase();
        return doRegister(parties);
    }

    public int arrive() {
        return doArrive(ONE_ARRIVAL);
    }

    public int arriveAndDeregister() {
        return doArrive(ONE_DEREGISTER);
    }

    public int arriveAndAwaitAdvance() {
        // Specialization of doArrive+awaitAdvance eliminating some reads/paths
        final Phaser root = this.root;
        while(true) {
            long s = (root == this) ? state : reconcileState();
            int phase = (int)(s >>> PHASE_SHIFT);
            if (phase < 0)
                return phase;
            int counts = (int)s;
            int unarrived = (counts == EMPTY) ? 0 : (counts & UNARRIVED_MASK);
            if (unarrived <= 0)
                throw new IllegalStateException(badArrive(s));
            if (STATE.compareAndSet(this, s, s -= ONE_ARRIVAL)) {
                if (unarrived > 1)
                    return root.internalAwaitAdvance(phase, null);
                if (root != this)
                    return parent.arriveAndAwaitAdvance();
                long n = s & PARTIES_MASK;  // base of next state
                int nextUnarrived = (int)n >>> PARTIES_SHIFT;
                if (onAdvance(phase, nextUnarrived))
                    n |= TERMINATION_BIT;
                else if (nextUnarrived == 0)
                    n |= EMPTY;
                else
                    n |= nextUnarrived;
                int nextPhase = (phase + 1) & MAX_PHASE;
                n |= (long)nextPhase << PHASE_SHIFT;
                if (!STATE.compareAndSet(this, s, n))
                    return (int)(state >>> PHASE_SHIFT); // terminated
                releaseWaiters(phase);
                return nextPhase;
            }
        }
    }

    public int awaitAdvance(int phase) {
        final Phaser root = this.root;
        long s = (root == this) ? state : reconcileState();
        int p = (int)(s >>> PHASE_SHIFT);
        if(phase < 0)
            return phase;
        if(p == phase)
            return root.internalAwaitAdvance(phase, null);
        return p;
    }

    public int awaitAdvanceInterruptibly(int phase) throws InterruptedException {
        final Phaser root = this.root;
        long s = (root == this) ? state : reconcileState();
        int p = (int)(s >>> PHASE_SHIFT);
        if (phase < 0)
            return phase;
        if (p == phase) {
            QNode node = new QNode(this, phase, true, false, 0L);
            p = root.internalAwaitAdvance(phase, node);
            if (node.wasInterrupted)
                throw new InterruptedException();
        }
        return p;
    }

    public int awaitAdvanceInterruptibly(int phase, long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        final Phaser root = this.root;
        long s = (root == this) ? state : reconcileState();
        int p = (int)(s >>> PHASE_SHIFT);
        if(phase < 0)
            return phase;
        if(p == phase) {
            QNode node = new QNode(this, phase, true, true, nanos);
            p = root.internalAwaitAdvance(phase, node);
            if(node.wasInterrupted)
                throw new InterruptedException();
            else if (p == phase)
                throw new TimeoutException();
        }
        return p;
    }

    public void forceTermination() {
        // Only need to change root state
        final Phaser root = this.root;
        long s;
        while((s = root.state) >= 0) {
            if(STATE.compareAndSet(root, s, s | TERMINATION_BIT)) {
                // signal all threads
                releaseWaiters(0); // Waiters on evenQ
                releaseWaiters(1); // Waiters on oddQ
                return;
            }
        }
    }

    public final int getPhase() {
        return (int)(root.state >>> PHASE_SHIFT);
    }

    public int getRegisteredParties() {
        return partiesOf(state);
    }

    public int getArrivedParties() {
        return arrivedOf(reconcileState());
    }

    public int getUnarrivedParties() {
        return unarrivedOf(reconcileState());
    }

    public Phaser getParent() {
        return parent;
    }

    public Phaser getRoot() {
        return root;
    }

    public boolean isTerminated() {
        return root.state < 0L;
    }

    /**
     * 具体注释参见doArrive方法
     * @param phase
     * @param registeredParties
     * @return
     */
    protected boolean onAdvance(int phase, int registeredParties) {
        return registeredParties == 0;
    }

    public String toString() {
        return stateToString(reconcileState());
    }

    private String stateToString(long s) {
        return super.toString() +
                "[phase = " + phaseOf(s) +
                " parties = " + partiesOf(s) +
                " arrived = " + arrivedOf(s) + "]";
    }

    // Waiting mechanics

    private void releaseWaiters(int phase) {
        QNode q;    // 当前遍历的node
        Thread t;
        AtomicReference<QNode> head = (phase & 1) == 0 ? evenQ : oddQ;
        while((q = head.get()) != null && q.phase != (int)(root.state >>> PHASE_SHIFT)) {
            if(head.compareAndSet(q, q.next) && (t = q.thread) != null) {
                q.thread = null;
                LockSupport.unpark(t);
            }
        }
    }

    private int abortWait(int phase) {
        AtomicReference<QNode> head = (phase & 1) == 0 ? evenQ : oddQ;
        while(true) {
            Thread t;
            QNode q = head.get();
            int p = (int)(root.state >>> PHASE_SHIFT);
            if(q == null || ((t = q.thread) != null && q.phase == p))
                return p;
            if(head.compareAndSet(q, q.next) && t != null) {
                q.thread = null;
                LockSupport.unpark(t);
            }
        }
    }

    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    static final int SPINS_PER_ARRIVAL = (NCPU < 2) ? 1 : 1 << 8;

    /**
     * 等待root phaser从指定的phase推进到下一个phase。
     * 如果短时间内推进不了，就把当前线程包装成QNode挂到对应等待栈上，再阻塞等待被唤醒。
     * 可以理解成awaitAdvance的真正底层实现：
     * 1、先自旋等一会儿。
     * 2、再入队。
     * 3、再阻塞。
     * 4、phase变了就返回
     * 这个方法只在root上会被执行
     */
    private int internalAwaitAdvance(int phase, QNode node) {
        // assert root == this;
        releaseWaiters(phase-1);          // ensure old queue clean
        boolean queued = false;           // true when node is enqueued
        int lastUnarrived = 0;            // to increase spins upon change
        int spins = SPINS_PER_ARRIVAL;
        long s;
        int p;
        while((p = (int)((s = state) >>> PHASE_SHIFT)) == phase) {
            if(node == null) {           // spinning in noninterruptible mode
                int unarrived = (int)s & UNARRIVED_MASK;
                if(unarrived != lastUnarrived && (lastUnarrived = unarrived) < NCPU)
                    spins += SPINS_PER_ARRIVAL;
                boolean interrupted = Thread.interrupted();
                if(interrupted || --spins < 0) { // need node to record intr
                    node = new QNode(this, phase, false, false, 0L);
                    node.wasInterrupted = interrupted;
                }else {
                    Thread.onSpinWait();
                }

            }else if (node.isReleasable()) { // done or aborted
                    break;
            }else if(!queued) {           // push onto queue
                AtomicReference<QNode> head = (phase & 1) == 0 ? evenQ : oddQ;
                QNode q = node.next = head.get();
                if ((q == null || q.phase == phase) &&
                        (int)(state >>> PHASE_SHIFT) == phase) // avoid stale enq
                    queued = head.compareAndSet(q, node);
            }
            else {
                try {
                    ForkJoinPool.managedBlock(node);
                } catch (InterruptedException cantHappen) {
                    node.wasInterrupted = true;
                }
            }
        }

        if(node != null) {
            if(node.thread != null)
                node.thread = null;       // avoid need for unpark()
            if(node.wasInterrupted && !node.interruptible)
                Thread.currentThread().interrupt();
            if(p == phase && (p = (int)(state >>> PHASE_SHIFT)) == phase)
                return abortWait(phase); // possibly clean up on abort
        }
        releaseWaiters(phase);
        return p;
    }

    static final class QNode implements ForkJoinPool.ManagedBlocker {
        final Phaser phaser;
        final int phase;
        final boolean interruptible;
        final boolean timed;
        boolean wasInterrupted;
        long nanos;
        final long deadline;
        volatile Thread thread;
        QNode next;

        QNode(Phaser phaser, int phase, boolean interruptible, boolean timed, long nanos) {
            this.phaser = phaser;
            this.phase = phase;
            this.interruptible = interruptible;
            this.nanos = nanos;
            this.timed = timed;
            this.deadline = timed ? System.nanoTime() + nanos : 0L;
            thread = Thread.currentThread();
        }

        public boolean isReleasable() {
            if(thread == null)
                return true;
            if(phaser.getPhase() != phase) {
                thread = null;
                return true;
            }
            if(Thread.interrupted())
                wasInterrupted = true;
            if(wasInterrupted && interruptible) {
                thread = null;
                return true;
            }
            if(timed && (nanos <= 0L || (nanos = deadline - System.nanoTime()) <= 0L)) {
                thread = null;
                return true;
            }
            return false;
        }

        public boolean block() {
            while(!isReleasable()) {
                if(timed)
                    LockSupport.parkNanos(this, nanos);
                else
                    LockSupport.park(this);
            }
            return true;
        }
    }

    // VarHandle mechanics
    private static final VarHandle STATE;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(Phaser.class, "state", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }

        // Reduce the risk of rare disastrous classloading in first call to
        // LockSupport.park: https://bugs.openjdk.java.net/browse/JDK-8074773
        Class<?> ensureLoaded = LockSupport.class;
    }
}

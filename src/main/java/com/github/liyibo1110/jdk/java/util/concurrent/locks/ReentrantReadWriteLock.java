package com.github.liyibo1110.jdk.java.util.concurrent.locks;

import java.io.Serializable;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * 官方不翻译了，太长了
 *
 * ReentrantLock只有一种独占锁的模式，而ReentrantReadWriteLock则有两种锁语义：
 * 写锁（WriteLock）
 * 1、写锁是独占锁。
 * 2、和ReentrantLock基本一样。
 * 3、同一时间只能由一个线程持有。
 *
 * 读锁（ReadLock）
 * 1、读锁是共享锁。
 * 2、可以让多个线程同时持有。
 * 3、前提是没有线程持有写锁。

 * state同时保存读锁和写锁的状态：即用一个int的高16位保存读锁数量，低16位保存写锁的重入次数。
 *
 * 读写锁模式存在的原因：很多场景都是读多于写，如果用ReentrantLock则读之间也会互斥，影响了读性能。
 *
 * 新的问题：
 * 1、写锁支持可重入，和ReentrantLock一样没什么问题，但是增加了读锁的可重入，并且读锁本身也可能是多个，所以必须记录每个线程的读锁次数：
 * 比如线程A：read * 3; 线程B read * 2； 如果线程A unlock，不分别记录的话，就不知道要减哪个线程的read数，
 * 因为在代码了会有ThreadLocalHoldCounter的类，用来记录每个线程的读锁次数。
 *
 * 锁降级（没有锁升级）：非常重要的特性，即支持写锁 -> 读锁，例如以下代码：
 * writeLock.lock();
 * // 在这里修改了一些数据
 * readLock.lock(); // 锁降级
 * writeLock.unlock();
 * 这时该线程会继续持有读锁，上面释放的是写锁，作用是：写完数据后可以继续安全读取，也是缓存刷新场景后的典型模式。
 *
 * 锁公平：要注意读锁在非公平模式下有很多优化，严格公平条件下，读锁性能会很差。
 * 注意这里的知识点很微妙，加入线程A和B获取的读锁，又来了线程C获取写锁（获取不到要排队），这时如果来了线程D要获取读锁，这时是获取不到的，
 * 因为公平实现的readerShouldBlock()方法里，调用的是方法是readerShouldBlock()，这个方法实现不会去判断前面的是写还是读，有就要返回false。
 * 而非公平实现的readerShouldBlock()方法里，调用的是方法是apparentlyFirstQueuedIsExclusive()，只有当队列第一个是写线程，才会阻塞新的读。
 * @author liyibo
 * @date 2026-03-13 12:38
 */
public class ReentrantReadWriteLock implements ReadWriteLock, Serializable {
    private static final long serialVersionUID = -6992448646407690164L;
    private final ReentrantReadWriteLock.ReadLock readerLock;
    private final ReentrantReadWriteLock.WriteLock writerLock;
    final Sync sync;

    public ReentrantReadWriteLock() {
        this(false);
    }

    public ReentrantReadWriteLock(boolean fair) {
        sync = fair ? new ReentrantReadWriteLock.FairSync() : new ReentrantReadWriteLock.NonfairSync();
        readerLock = new ReentrantReadWriteLock.ReadLock(this);
        writerLock = new ReentrantReadWriteLock.WriteLock(this);
    }

    public ReentrantReadWriteLock.WriteLock writeLock() {
        return writerLock;
    }

    public ReentrantReadWriteLock.ReadLock readLock()  {
        return readerLock;
    }

    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 6317671515068378041L;

        static final int SHARED_SHIFT   = 16;
        static final int SHARED_UNIT    = (1 << SHARED_SHIFT);
        static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;
        static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;

        /**
         * 获取读锁的数量
         */
        static int sharedCount(int c) {
            return c >>> SHARED_SHIFT;
        }

        /**
         * 获取写锁的重入次数
         */
        static int exclusiveCount(int c) {
            return c & EXCLUSIVE_MASK;
        }

        /**
         * 用来保存线程拥有的读锁次数的容器
         */
        static final class HoldCounter {
            int count;
            final long tid = LockSupport.getThreadId(Thread.currentThread());
        }

        static final class ThreadLocalHoldCounter extends ThreadLocal<HoldCounter> {
            public HoldCounter initialValue() {
                return new Sync.HoldCounter();
            }
        }

        /** 用来保存线程拥有的读锁次数的ThreadLocal实现 */
        private transient ThreadLocalHoldCounter readHolds;

        /** 属于优化作用的字段，相当于ThreadLocalHoldCounter的缓存，避免频繁查ThreadLocal里面。 */
        private transient HoldCounter cachedHoldCounter;

        /** 属于优化作用的字段，专门保存了第一个读线程，避免ThreadLocal的开销。 */
        private transient Thread firstReader;
        private transient int firstReaderHoldCount;

        Sync() {
            readHolds = new ThreadLocalHoldCounter();
            setState(getState());
        }

        /**
         * 具体子类要实现的判断方法，即是否要阻塞读请求。
         */
        abstract boolean readerShouldBlock();

        /**
         * 具体子类要实现的判断方法，即是否要阻塞写请求。
         */
        abstract boolean writerShouldBlock();

        /**
         * 只有获取写锁才会调用这个方法。
         */
        protected final boolean tryRelease(int releases) {
            if(!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int nextc = getState() - releases;
            boolean free = exclusiveCount(nextc) == 0;  // 写锁数量是否为0，为0说明没有线程持有独占锁了
            if(free)
                setExclusiveOwnerThread(null);
            setState(nextc);
            return free;    // true代表没有写锁了，可以继续唤醒AQS queue的后面线程了
        }

        /**
         * 只有获取写锁才会调用这个方法。
         */
        protected final boolean tryAcquire(int acquires) {
            /**
             * 1、若读取计数不为零或写入计数不为零，且所有者属于不同线程，则失败。
             * 2、若计数将导致饱和，则失败。（此情况仅当计数已非零时发生。）
             * 3、否则，若当前线程属于可重入获取或队列策略允许，则有权获取锁。若成功，更新状态并设置所有者。
             */
            Thread current = Thread.currentThread();
            int c = getState();
            int w = exclusiveCount(c);
            if(c != 0) {    // 有锁（但可能是读也可能是写）
                if(w == 0 || current != getExclusiveOwnerThread())  // 只有读锁，或者不是自己，直接失败，因为读写锁不能共存
                    return false;
                // 下面是没有读锁，只有写锁，并且写锁也是自己的，说明可以直接重入了
                if(w + exclusiveCount(acquires) > MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                setState(c + acquires); // 写锁重入
                return true;
            }
            // 说明没有锁，可以尝试抢锁（writerShouldBlock是公平锁特有的判断，非公平模式这个方法会直接返回false）
            if(writerShouldBlock() || !compareAndSetState(c, c + acquires))
                return false;
            // 抢到写锁了
            setExclusiveOwnerThread(current);
            return true;
        }

        private static IllegalMonitorStateException unmatchedUnlockException() {
            return new IllegalMonitorStateException("attempt to unlock read lock, not locked by current thread");
        }

        /**
         * 只有获取读锁才会调用这个方法，返回true说明当前线程已经释放全部的读锁了。
         */
        protected final boolean tryReleaseShared(int unused) {
            Thread current = Thread.currentThread();
            if(firstReader == current) {    // 当前线程是第一个读锁持有者
                // assert firstReaderHoldCount > 0;
                if(firstReaderHoldCount == 1)
                    firstReader = null;
                else
                    firstReaderHoldCount--;
            }else { // 当前线程不是第一个读锁持有者，要去ThreadLocal里面减少count
                HoldCounter rh = cachedHoldCounter;
                if(rh == null || rh.tid != LockSupport.getThreadId(current))
                    rh = readHolds.get();
                int count = rh.count;
                if(count <= 1) {
                    readHolds.remove();
                    if(count < 0)
                        throw unmatchedUnlockException();
                }
                --rh.count;
            }
            // 上面只是减少first或者ThreadLocal的count，下面要减少state的读锁值
            while(true) {
                int c = getState();
                int nextc = c - SHARED_UNIT;
                if(compareAndSetState(c, nextc))
                    return nextc == 0;
            }
        }

        /**
         * 只有获取读锁才会调用这个方法。
         * 结果 >= 0表示成功，结果 < 0表示失败，会进入AQS队列
         */
        protected final int tryAcquireShared(int unused) {
            /**
             * 1、若写锁被其他线程持有，则失败。
             * 2、否则，当前线程有权获取锁写状态，故需判断是否因队列策略而应阻塞，若不阻塞，则尝试通过CAS操作状态并更新计数来授予锁。
             * 注意此步骤不检查可重入获取，该检查推迟至完整版本以避免在更常见的非可重入场景中检查持有计数。
             * 3、若步骤2失败（因线程明显无权限、CAS失败或计数饱和），则转入带完整重试循环的版本。
             */
            Thread current = Thread.currentThread();
            int c = getState();
            // 检查是否有写锁，并且不是自己持有（自己持有说明要进行锁降级，不算失败），满足则直接方法失败，也不进入排队
            if(exclusiveCount(c) != 0 && getExclusiveOwnerThread() != current)
                return -1;
            int r = sharedCount(c);
            // 快速尝试获取读锁（直接尝试CAS）
            if(!readerShouldBlock() && r < MAX_COUNT && compareAndSetState(c, c + SHARED_UNIT)) {
                // 进来说明获取到了读锁
                if(r == 0) {    // AQS里面还没有读锁，当前线程是第一个读锁持有者，写入优化字段
                    firstReader = current;
                    firstReaderHoldCount = 1;
                }else if(firstReader == current) {  // 第一个读锁持有者之前就是自己，直接增加读锁缓存次数
                    firstReaderHoldCount++;
                }else { // 自己不是第一个读锁持有者，需要记录到cachedHoldCounter和readHolds中
                    HoldCounter rh = cachedHoldCounter;
                    if(rh == null || rh.tid != LockSupport.getThreadId(current))    // cache里如果没有就从readHolds里加载
                        cachedHoldCounter = rh = readHolds.get();
                    else if(rh.count == 0)  // count为0说明这个线程是第一次进来，还没有被ThreadLocal记录
                        readHolds.set(rh);
                    rh.count++; // 最后都要增加count
                }
                return 1;   // 成功获取到了读锁
            }
            // 到这里说明没拿到读锁（因为公平模式），会进入慢路经
            return fullTryAcquireShared(current);
        }

        /**
         * 方法流程，一直循环下面的流程：
         * 1、再次检查写锁。
         * 2、再次检查公平策略。
         * 3、检查重入。
         * 4、CAS读锁。
         */
        final int fullTryAcquireShared(Thread current) {
            HoldCounter rh = null;
            while(true) {
                int c = getState();
                if(exclusiveCount(c) != 0) {   // 还是一样的写锁判断，有而且不是自己就要失败返回
                    if(getExclusiveOwnerThread() != current)
                        return -1;
                }else if(readerShouldBlock()) { // 公平模式下，要求读线程阻塞
                    if(firstReader == current) {    // 说明是读锁重入，则允许
                        // assert firstReaderHoldCount > 0;
                    }else { // 不是读锁重入，则检查ThreadLocal计数
                        /**
                         * 全是为了优化而生的复杂逻辑
                         */
                        if(rh == null) {    // 局部变量rh还没有准备好
                            rh = cachedHoldCounter; // 先尝试用缓存加载（最近缓存的那个HoldCounter能不能直接拿来用）
                            /** 重要判断：检查这个cache是不是当前线程的 */
                            if(rh == null || rh.tid != LockSupport.getThreadId(current)) {
                                // 进来了，说明当前线程不在cache里，要从ThreadLocal里面取
                                rh = readHolds.get();   // 注意当ThreadLocal里面没有时，这个get会自动调用initialValue创建一个新的再返回
                                if(rh.count == 0)   // 判断当前线程没有持有过读锁，就要把上一步新增的HoldCounter给删除
                                    readHolds.remove();
                            }
                        }
                        if(rh.count == 0)   // 折腾一圈最终在这里判断：当前线程到底是不是重入（即之前就持有读锁了，0代表不是重入，返回去排队）
                            return -1;
                    }
                }
                if(sharedCount(c) == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                // 获取到了读锁
                if(compareAndSetState(c, c + SHARED_UNIT)) {
                    if(sharedCount(c) == 0) {   // 如果自己是第一个读锁持有者
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    }else if(firstReader == current) {  // 自己重入了
                        firstReaderHoldCount++;
                    }else {
                        if(rh == null)
                            rh = cachedHoldCounter;
                        if(rh == null || rh.tid != LockSupport.getThreadId(current))
                            rh = readHolds.get();
                        /**
                         * 最复杂的在这里，因为到这里的前提是：
                         * 1、rh就是cachedHoldCounter。
                         * 2、rh就是属于当前线程。
                         * 仍然要做一步ThreadLocal的set操作，对应场景：某线程之前把读锁都释放了，tryReleaseShared里执行过readHolds.remove()，
                         * 于是ThreadLocal里已经没有这个线程的HoldCounter了，但cachedHoldCounter还有引用，并且count为0。
                         * 这时如果当前线程又重来获取读锁，它可以继续复用这个HoldCounter对象，但是要重新把它挂回ThreadLocal。
                         * set方法就是为了：对象复用 + ThreadLocal重新关联的优化。
                         */
                        else if(rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                        cachedHoldCounter = rh;
                    }
                    return 1;
                }
            }
        }

        final boolean tryWriteLock() {
            Thread current = Thread.currentThread();
            int c = getState();
            if(c != 0) {    // 有写锁或读锁
                int w = exclusiveCount(c);  // 写锁数量
                if(w == 0 || current != getExclusiveOwnerThread())  // 只有读锁，并且持有者不是自己（即不能降级），返回失败
                    return false;
                if(w == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
            }
            // 到这里说明没有锁，直接尝试抢锁
            if(!compareAndSetState(c, c + 1))
                return false;
            // 到这里说明抢到写锁了
            setExclusiveOwnerThread(current);
            return true;
        }

        final boolean tryReadLock() {
            Thread current = Thread.currentThread();
            while(true) {
                int c = getState();
                if(exclusiveCount(c) != 0 && getExclusiveOwnerThread() != current)  // 有写锁并且不是自己，返回失败
                    return false;
                int r = sharedCount(c);
                if(r == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                if(compareAndSetState(c, c + SHARED_UNIT)) {
                    if(r == 0) {    // 还没有其他线程持有读锁
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    }else if (firstReader == current) { // 自己是第一个，而且重入了
                        firstReaderHoldCount++;
                    }else {
                        HoldCounter rh = cachedHoldCounter;
                        if(rh == null || rh.tid != LockSupport.getThreadId(current))
                            cachedHoldCounter = rh = readHolds.get();
                        else if(rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                    }
                    return true;
                }
            }
        }

        protected final boolean isHeldExclusively() {
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        // Methods relayed to outer class

        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        final Thread getOwner() {
            // Must read state before owner to ensure memory consistency
            return ((exclusiveCount(getState()) == 0) ? null : getExclusiveOwnerThread());
        }

        final int getReadLockCount() {
            return sharedCount(getState());
        }

        final boolean isWriteLocked() {
            return exclusiveCount(getState()) != 0;
        }

        final int getWriteHoldCount() {
            return isHeldExclusively() ? exclusiveCount(getState()) : 0;
        }

        /**
         * 获取当前线程的读锁数。
         */
        final int getReadHoldCount() {
            if(getReadLockCount() == 0)
                return 0;

            Thread current = Thread.currentThread();
            if(firstReader == current)
                return firstReaderHoldCount;

            HoldCounter rh = cachedHoldCounter;
            if(rh != null && rh.tid == LockSupport.getThreadId(current))
                return rh.count;

            int count = readHolds.get().count;
            if(count == 0)
                readHolds.remove();
            return count;
        }

        private void readObject(java.io.ObjectInputStream s)
                throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            readHolds = new Sync.ThreadLocalHoldCounter();
            setState(0); // reset to unlocked state
        }

        final int getCount() {
            return getState();
        }
    }

    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -8159625535654395037L;

        final boolean writerShouldBlock() {
            return false;
        }

        final boolean readerShouldBlock() {
            return apparentlyFirstQueuedIsExclusive();
        }
    }

    static final class FairSync extends Sync {
        private static final long serialVersionUID = -2274990926593161451L;

        final boolean writerShouldBlock() {
            return hasQueuedPredecessors();
        }

        final boolean readerShouldBlock() {
            return hasQueuedPredecessors();
        }
    }

    public static class ReadLock implements Lock, Serializable {
        private static final long serialVersionUID = -5992448646407690164L;
        private final Sync sync;

        protected ReadLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        public void lock() {
            sync.acquireShared(1);
        }

        public void lockInterruptibly() throws InterruptedException {
            sync.acquireSharedInterruptibly(1);
        }

        public boolean tryLock() {
            return sync.tryReadLock();
        }

        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
        }

        public void unlock() {
            sync.releaseShared(1);
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }

        public String toString() {
            int r = sync.getReadLockCount();
            return super.toString() + "[Read locks = " + r + "]";
        }
    }

    public static class WriteLock implements Lock, Serializable {
        private static final long serialVersionUID = -4992448646407690164L;
        private final Sync sync;

        protected WriteLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        public void lock() {
            sync.acquire(1);
        }

        public void lockInterruptibly() throws InterruptedException {
            sync.acquireInterruptibly(1);
        }

        public boolean tryLock() {
            return sync.tryWriteLock();
        }

        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            return sync.tryAcquireNanos(1, unit.toNanos(timeout));
        }

        public void unlock() {
            sync.release(1);
        }

        public Condition newCondition() {
            return sync.newCondition();
        }

        public String toString() {
            Thread o = sync.getOwner();
            return super.toString() + ((o == null)
                    ? "[Unlocked]"
                    : "[Locked by thread " + o.getName() + "]");
        }

        public boolean isHeldByCurrentThread() {
            return sync.isHeldExclusively();
        }

        public int getHoldCount() {
            return sync.getWriteHoldCount();
        }
    }

    // Instrumentation and status

    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    protected Thread getOwner() {
        return sync.getOwner();
    }

    public int getReadLockCount() {
        return sync.getReadLockCount();
    }

    public boolean isWriteLocked() {
        return sync.isWriteLocked();
    }

    public boolean isWriteLockedByCurrentThread() {
        return sync.isHeldExclusively();
    }

    public int getWriteHoldCount() {
        return sync.getWriteHoldCount();
    }

    public int getReadHoldCount() {
        return sync.getReadHoldCount();
    }

    protected Collection<Thread> getQueuedWriterThreads() {
        return sync.getExclusiveQueuedThreads();
    }

    protected Collection<Thread> getQueuedReaderThreads() {
        return sync.getSharedQueuedThreads();
    }

    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    public boolean hasWaiters(Condition condition) {
        if(condition == null)
            throw new NullPointerException();
        if(!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    public int getWaitQueueLength(Condition condition) {
        if(condition == null)
            throw new NullPointerException();
        if(!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if(condition == null)
            throw new NullPointerException();
        if(!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    public String toString() {
        int c = sync.getCount();
        int w = Sync.exclusiveCount(c);
        int r = Sync.sharedCount(c);
        return super.toString() + "[Write locks = " + w + ", Read locks = " + r + "]";
    }
}

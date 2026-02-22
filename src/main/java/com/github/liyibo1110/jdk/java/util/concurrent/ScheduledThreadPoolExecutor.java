package com.github.liyibo1110.jdk.java.util.concurrent;

import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一种可额外安排任务在指定延迟后运行，或周期性执行的executor。
 * 当需要多个工作线程，或需要executor的额外灵活性或功能时，本类优于java.util.Timer。
 *
 * 延迟任务在启动后不会提前执行，但启用后何时开始执行不作实时保证，在完全相同执行时间点安排的任务，将按提交顺序（先入先出）启动。
 * 若提交任务在运行前被取消，则执行将被抑制，默认情况下，此类取消任务不会在延迟时间结束前自动从工作队列移除。
 * 虽然这便于进一步检查和监控，但也可能导致取消任务无限期保留，为避免此问题，请使用setRemoveOnCancelPolicy策略，使任务在取消使立即从工作队列中移除。
 *
 * 通过scheduledAtFixedRate或scheduleWithFixedDelay调度的周期性任务，其连续执行不会重叠，虽然不同执行可能由不同线程来处理，但先前执行的效果会发生在后续执行之前。
 *
 * 虽然本类继承自ThreadPoolExecutor，但部分继承的调优方法对其无效，尤其当其作为固定大小的线程池运行时（用core和无界队列），调整maximum毫无意义。
 * 此外将core设为0或使用allowCoreThreadTimeOut通常不是个好主意，这可能导致任务具备运行资格时，线程池内已无可用线程处理。
 *
 * 与ThreadPoolExecutor相同，若未另行指定，本类默认使用Executors.defaultThreadFactory作为线程工厂，并采用ThreadPoolExecutor.AbortPolicy作为默认拒绝执行处理器。
 * @author liyibo
 * @date 2026-02-22 14:31
 */
public class ScheduledThreadPoolExecutor extends ThreadPoolExecutor implements ScheduledExecutorService {

    /**
     * shutdown()后是否继续执行周期任务。
     */
    private volatile boolean continueExistingPeriodicTasksAfterShutdown;

    /**
     * shutdown()后是否执行已经在队列里的延迟任务。
     */
    private volatile boolean executeExistingDelayedTasksAfterShutdown = true;

    /**
     * cancel()时是否立即从队列删除任务，如果不删除，任务会留在队列，直到delay到期才会被清理，可能导致内部滞留。
     */
    volatile boolean removeOnCancel;

    /**
     * 解决相同触发时间的FIFO的顺序问题（再compareTo会用这个，保证先提交的先执行）。
     */
    private static final AtomicLong sequencer = new AtomicLong();

    /**
     * 专用的FutureTask实现
     */
    private class ScheduledFutureTask<V> extends FutureTask<V> implements java.util.concurrent.RunnableScheduledFuture<V> {

        /** 用于处理时间相同的排序 */
        private final long sequenceNumber;

        /** 下一次触发执行的时间，是个绝对时间戳 */
        private volatile long time;

        /**
         * 0：一次性任务
         * >0：固定频率
         * <0：固定间隔
         */
        private final long period;

        /** 由reExecutePeriodic重新入队的实际任务 */
        RunnableScheduledFuture<V> outerTask = this;

        /**
         * 用于优化从堆中删除任务（复杂度变为O(logN)，否则复杂度时O(n)）
         */
        int heapIndex;

        ScheduledFutureTask(Runnable r, V result, long triggerTime, long sequenceNumber) {
            super(r, result);
            this.time = triggerTime;
            this.period = 0;
            this.sequenceNumber = sequenceNumber;
        }

        ScheduledFutureTask(Runnable r, V result, long triggerTime, long period, long sequenceNumber) {
            super(r, result);
            this.time = triggerTime;
            this.period = period;
            this.sequenceNumber = sequenceNumber;
        }

        ScheduledFutureTask(Callable<V> callable, long triggerTime, long sequenceNumber) {
            super(callable);
            this.time = triggerTime;
            this.period = 0;
            this.sequenceNumber = sequenceNumber;
        }

        public long getDelay(TimeUnit unit) {
            return unit.convert(time - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if(other == this)
                return 0;
            if(other instanceof ScheduledFutureTask) {
                ScheduledFutureTask<?> x = (ScheduledFutureTask<?>)other;
                long diff = time - x.time;
                if (diff < 0)
                    return -1;
                else if (diff > 0)
                    return 1;
                else if (sequenceNumber < x.sequenceNumber) // 2个task的time全一致，则看sequenceNumber（谁先入谁先出）
                    return -1;
                else
                    return 1;
            }
            long diff = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS);
            return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
        }

        @Override
        public boolean isPeriodic() {
            return period != 0;
        }

        /**
         * 设置周期性任务的下次运行时间点
         */
        private void setNextRunTime() {
            long p = period;
            if(p > 0)
                time += p;  // 直接给time加时间
            else
                time = triggerTime(-p); // 需要在新方法里计算下一次的时间
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if(cancelled && removeOnCancel && heapIndex >= 0)
                remove(this);
            return cancelled;
        }

        @Override
        public void run() {
            if(!canRunInCurrentRunState(this))
                cancel(false);
            else if(!isPeriodic())  // 一次性任务
                super.run();
            else if(super.runAndReset()) {  // 周期性任务（先执行 -> 重置状态 -> 再放回队列）
                setNextRunTime();
                reExecutePeriodic(outerTask);
            }
        }
    }

    /**
     * 如果当前运行状态和SHUTDOWN后的相关参数允许执行任务，则返回true
     */
    boolean canRunInCurrentRunState(RunnableScheduledFuture<?> task) {
        if(!isShutdown())
            return true;
        if(isStopped())
            return false;
        return task.isPeriodic()
                ? continueExistingPeriodicTasksAfterShutdown
                : (executeExistingDelayedTasksAfterShutdown || task.getDelay(TimeUnit.NANOSECONDS) <= 0);
    }

    /**
     * 延迟或周期性任务的主要执行方式。
     * 若线程池已关闭，则拒绝该任务，否则将任务加入队列，并在必要时启动线程执行（不能预先启动线程运行任务，因为该任务可能还不能执行）。
     * 若在添加任务使线程池关闭，则根据状态参数和关闭后执行参数的要求，取消并移除该任务。
     */
    private void delayedExecute(RunnableScheduledFuture<?> task) {
        if(this.isShutdown())
            this.reject(task);
        else {
            super.getQueue().add(task);
            if(!this.canRunInCurrentRunState(task) && this.remove(task))
                task.cancel(false);
            else
                this.ensurePrestart();
        }
    }

    /**
     * 除非当前运行状态不允许，否则重新入队特定的周期性任务。
     * 原理与delayedExecute相同，但会删除任务而非拒绝执行。
     */
    void reExecutePeriodic(RunnableScheduledFuture<?> task) {
        if(this.canRunInCurrentRunState(task)) {
            super.getQueue().add(task);
            if(this.canRunInCurrentRunState(task) || !this.remove(task)) {
                this.ensurePrestart();
                return;
            }
        }
        task.cancel(false);
    }

    /**
     * 取消并清除所有因SHUTDOWN而不应运行的任务队列，此方法会在父类的shutdown中被调用。
     */
    @Override
    void onShutdown() {
        BlockingQueue<Runnable> q = super.getQueue();
        boolean keepDelayed = getExecuteExistingDelayedTasksAfterShutdownPolicy();
        boolean keepPeriodic = getContinueExistingPeriodicTasksAfterShutdownPolicy();
        for(Object e : q.toArray()) {
            if(e instanceof RunnableScheduledFuture) {
                RunnableScheduledFuture<?> t = (RunnableScheduledFuture<?>)e;
                if((t.isPeriodic() ? !keepPeriodic : (!keepDelayed && t.getDelay(TimeUnit.NANOSECONDS) > 0)) || t.isCancelled()) {
                    if(q.remove(t))
                        t.cancel(false);
                }
            }
        }
    }

    /**
     * 修改或替换task，此方法可用于覆盖用于管理内部任务的具体类，默认实现仅返回给定的任务
     */
    protected <V> RunnableScheduledFuture<V> decorateTask(Runnable runnable, RunnableScheduledFuture<V> task) {
        return task;
    }

    protected <V> RunnableScheduledFuture<V> decorateTask(Callable<V> callable, RunnableScheduledFuture<V> task) {
        return task;
    }

    private static final long DEFAULT_KEEPALIVE_MILLIS = 10L;

    public ScheduledThreadPoolExecutor(int corePoolSize) {
        super(corePoolSize, Integer.MAX_VALUE, DEFAULT_KEEPALIVE_MILLIS, TimeUnit.MILLISECONDS, new DelayedWorkQueue());
    }

    public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, Integer.MAX_VALUE, DEFAULT_KEEPALIVE_MILLIS, TimeUnit.MILLISECONDS, new DelayedWorkQueue(), threadFactory);
    }

    public ScheduledThreadPoolExecutor(int corePoolSize, RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, DEFAULT_KEEPALIVE_MILLIS, TimeUnit.MILLISECONDS, new DelayedWorkQueue(), handler);
    }

    public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, DEFAULT_KEEPALIVE_MILLIS, TimeUnit.MILLISECONDS, new DelayedWorkQueue(), threadFactory, handler);
    }

    /**
     * 返回延迟操作基于纳秒的触发时间
     */
    private long triggerTime(long delay, TimeUnit unit) {
        return triggerTime(unit.toNanos((delay < 0) ? 0 : delay));
    }

    long triggerTime(long delay) {
        return System.nanoTime() + ((delay < (Long.MAX_VALUE >> 1)) ? delay : overflowFree(delay));
    }

    private long overflowFree(long delay) {
        Delayed head = (Delayed) super.getQueue().peek();
        if (head != null) {
            long headDelay = head.getDelay(TimeUnit.NANOSECONDS);
            if (headDelay < 0 && (delay - headDelay < 0))
                delay = Long.MAX_VALUE + headDelay;
        }
        return delay;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        if(command == null || unit == null)
            throw new NullPointerException();
        RunnableScheduledFuture<Void> t = decorateTask(command,
                new ScheduledFutureTask<>(command, null, triggerTime(delay, unit), sequencer.getAndIncrement()));
        this.delayedExecute(t);
        return t;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if(callable == null || unit == null)
            throw new NullPointerException();
        RunnableScheduledFuture<V> t = decorateTask(callable,
                new ScheduledFutureTask<V>(callable, triggerTime(delay, unit), sequencer.getAndIncrement()));
        this.delayedExecute(t);
        return t;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        if(command == null || unit == null)
            throw new NullPointerException();
        if(period <= 0L)
            throw new IllegalArgumentException();
        ScheduledFutureTask<Void> sft =
                new ScheduledFutureTask<>(command, null, triggerTime(initialDelay, unit), unit.toNanos(period), sequencer.getAndIncrement());
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);
        sft.outerTask = t;
        this.delayedExecute(t);
        return t;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        if(command == null || unit == null)
            throw new NullPointerException();
        if(delay <= 0L)
            throw new IllegalArgumentException();
        ScheduledFutureTask<Void> sft =
                new ScheduledFutureTask<Void>(command, null, triggerTime(initialDelay, unit), -unit.toNanos(delay), sequencer.getAndIncrement());
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);
        sft.outerTask = t;
        this.delayedExecute(t);
        return t;
    }

    @Override
    public void execute(Runnable command) {
        this.schedule(command, 0, TimeUnit.NANOSECONDS);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return schedule(task, 0, TimeUnit.NANOSECONDS);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return schedule(Executors.callable(task, result), 0, TimeUnit.NANOSECONDS);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return schedule(task, 0, TimeUnit.NANOSECONDS);
    }

    public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
        continueExistingPeriodicTasksAfterShutdown = value;
        if(!value && this.isShutdown())
            this.onShutdown();
    }

    public boolean getContinueExistingPeriodicTasksAfterShutdownPolicy() {
        return continueExistingPeriodicTasksAfterShutdown;
    }

    public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
        executeExistingDelayedTasksAfterShutdown = value;
        if(!value && this.isShutdown())
            this.onShutdown();
    }

    public boolean getExecuteExistingDelayedTasksAfterShutdownPolicy() {
        return executeExistingDelayedTasksAfterShutdown;
    }

    public void setRemoveOnCancelPolicy(boolean value) {
        removeOnCancel = value;
    }

    public boolean getRemoveOnCancelPolicy() {
        return removeOnCancel;
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return super.shutdownNow();
    }

    public BlockingQueue<Runnable> getQueue() {
        return super.getQueue();
    }

    /**
     * ScheduledThreadPoolExecutor的核心数据结构：基于数组的小顶堆（min-heap）
     * 排序规则：delay值最小的任务，永远在堆顶（queue[0]），谁最早到期，谁在0号位。
     * 写入时：插入到数组末尾，然后执行siftUp（向上调整，时间复杂度为O(logN)）
     *
     */
    static class DelayedWorkQueue extends AbstractQueue<Runnable> implements BlockingQueue<Runnable> {
        private static final int INITIAL_CAPACITY = 16;

        /**
         * 基于数组的堆，按照getDelay的结果排序，最小delay值在堆顶。
         */
        private RunnableScheduledFuture<?>[] queue = new RunnableScheduledFuture<?>[INITIAL_CAPACITY];
        private final ReentrantLock lock = new ReentrantLock();
        private int size;

        private Thread leader;
        private final Condition available = lock.newCondition();

        private static void setIndex(RunnableScheduledFuture<?> f, int index) {
            if(f instanceof ScheduledFutureTask)
                ((ScheduledFutureTask)f).heapIndex = index;
        }

        /**
         * 将key放入合适的位置
         */
        private void siftUp(int k, RunnableScheduledFuture<?> key) {
            while(k > 0) {  // 不是最顶部，就尝试继续
                int parent = (k - 1) >>> 1; // 关键算法，找出k位置的父节点下标
                RunnableScheduledFuture<?> e = queue[parent];   // 取出父节点
                if (key.compareTo(e) >= 0)  // 如果父节点delay值更小，则退出while，结束向上
                    break;
                // 到这里说明key需要上浮
                queue[k] = e;   // 父节点移到下层
                setIndex(e, k);
                k = parent;
            }
            queue[k] = key; // 将自己写入到最终的位置
            setIndex(key, k);
        }

        private void siftDown(int k, RunnableScheduledFuture<?> key) {
            int half = size >>> 1;
            while(k < half) {
                int child = (k << 1) + 1;
                RunnableScheduledFuture<?> c = queue[child];
                int right = child + 1;
                if (right < size && c.compareTo(queue[right]) > 0)
                    c = queue[child = right];
                if (key.compareTo(c) <= 0)
                    break;
                queue[k] = c;
                setIndex(c, k);
                k = child;
            }
            queue[k] = key;
            setIndex(key, k);
        }

        /**
         * 动态扩容
         */
        private void grow() {
            int oldCapacity = queue.length;
            int newCapacity = oldCapacity + (oldCapacity >> 1);   // 容量增长50%
            if(newCapacity < 0) // 太大了，溢出了
                newCapacity = Integer.MAX_VALUE;
            queue = Arrays.copyOf(queue, newCapacity);
        }

        /**
         * 返回指定元素的下标，没找到则返回-1
         */
        private int indexOf(Object x) {
            if(x != null) {
                if(x instanceof ScheduledFutureTask) {
                    int i = ((ScheduledFutureTask)x).heapIndex;
                    if(i >= 0 && i < size && queue[i] == x)
                        return i;
                }else { // 不是ScheduledFutureTask，就正常一个一个遍历找
                    for(int i = 0; i < size; i++) {
                        if(x.equals(queue[i]))
                            return i;
                    }
                }
            }
            return -1;
        }

        @Override
        public boolean contains(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return indexOf(x) != -1;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean remove(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = indexOf(x);
                if(i < 0)
                    return false;
                setIndex(queue[i], -1);
                int s = --size;
                RunnableScheduledFuture<?> replacement = queue[s];
                queue[s] = null;
                if(s != i) {
                    this.siftDown(i, replacement);
                    if(queue[i] == replacement)
                        this.siftUp(i, replacement);
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int size() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return size;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean isEmpty() {
            return size() == 0;
        }

        @Override
        public int remainingCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public RunnableScheduledFuture<?> peek() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return queue[0];
            } finally {
                lock.unlock();
            }
        }

        public boolean offer(Runnable x) {
            if (x == null)
                throw new NullPointerException();
            RunnableScheduledFuture<?> e = (RunnableScheduledFuture<?>)x;
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = size;
                if(i >= queue.length)
                    this.grow();
                size = i + 1; // 容量加1
                if(i == 0) {    // 空数组
                    queue[0] = e;
                    setIndex(e, 0);
                }else { // 先放入数组末尾，再向上调整到合适位置
                    this.siftUp(i, e);
                }
                if(queue[0] == e) {
                    leader = null;  // 清除leader，允许worker们重新争抢
                    available.signal();
                }
            } finally {
                lock.unlock();
            }
            return true;
        }

        @Override
        public void put(Runnable e) {
            this.offer(e);
        }

        @Override
        public boolean add(Runnable e) {
            return this.offer(e);
        }

        @Override
        public boolean offer(Runnable e, long timeout, TimeUnit unit) {
            return this.offer(e);
        }

        private RunnableScheduledFuture<?> finishPoll(RunnableScheduledFuture<?> f) {
            int s = --size;
            RunnableScheduledFuture<?> x = queue[s];    // 取出最后一个元素
            queue[s] = null;    // 清空最后一个元素
            if(s != 0)
                this.siftDown(0, x);    // 将最后一个元素先放入顶部，再向下调整
            setIndex(f, -1);    // heapIndex改成-1，代表不在数组里了
            return f;
        }

        public RunnableScheduledFuture<?> poll() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first = queue[0];
                return (first == null || first.getDelay(TimeUnit.NANOSECONDS) > 0) ? null : finishPoll(first);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public RunnableScheduledFuture<?> take() throws InterruptedException {
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {
                for(;;) {
                    RunnableScheduledFuture<?> first = queue[0];
                    if(first == null)
                        available.await();
                    else {
                        long delay = first.getDelay(TimeUnit.NANOSECONDS);
                        if(delay <= 0L) // 到期了要执行了
                            return finishPoll(first);
                        first = null;   // 还没到执行时间
                        if(leader != null)  // 已经有leader（即不是自己的其它worker）再按精确时间阻塞了，自己索性就无限阻塞好了（避免浪费并发资源）
                            available.await();
                        else {  // 否则自己当作新的leader，进行按精确时间阻塞
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                available.awaitNanos(delay);
                            } finally {
                                if(leader == thisThread)    // 解除阻塞后再取消leader，重新争抢
                                    leader = null;
                            }
                        }
                    }
                }
            } finally {
                if(leader == null && queue[0] != null)  // 通知所有worker不用无限等待了
                    available.signal();
                lock.unlock();
            }
        }

        @Override
        public RunnableScheduledFuture<?> poll(long timeout, TimeUnit unit) throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {
                for(;;) {
                    RunnableScheduledFuture<?> first = queue[0];
                    if(first == null) {
                        if(nanos <= 0L)
                            return null;
                        else
                            nanos = available.awaitNanos(nanos);
                    }else {
                        long delay = first.getDelay(TimeUnit.NANOSECONDS);
                        if(delay <= 0L)
                            return this.finishPoll(first);
                        if(nanos <= 0L)
                            return null;
                        first = null;
                        if(nanos < delay || leader != null)
                            nanos = available.awaitNanos(nanos);
                        else {
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                long timeLeft = available.awaitNanos(delay);
                                nanos -= delay - timeLeft;
                            } finally {
                                if(leader == thisThread)
                                    leader = null;
                            }
                        }
                    }
                }
            } finally {
                if(leader == null && queue[0] != null)
                    available.signal();
                lock.unlock();
            }
        }

        @Override
        public void clear() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                for (int i = 0; i < size; i++) {
                    RunnableScheduledFuture<?> t = queue[i];
                    if (t != null) {
                        queue[i] = null;
                        this.setIndex(t, -1);
                    }
                }
                size = 0;
            } finally {
                lock.unlock();
            }
        }

        public int drainTo(Collection<? super Runnable> c) {
            return drainTo(c, Integer.MAX_VALUE);
        }

        @Override
        public int drainTo(Collection<? super Runnable> c, int maxElements) {
            Objects.requireNonNull(c);
            if (c == this)
                throw new IllegalArgumentException();
            if(maxElements <= 0)
                return 0;
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int n = 0;
                for(RunnableScheduledFuture<?> first;
                    n < maxElements && (first = queue[0]) != null && first.getDelay(TimeUnit.NANOSECONDS) <= 0;) {
                    c.add(first);
                    finishPoll(first);
                    ++n;
                }
                return n;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Object[] toArray() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return Arrays.copyOf(queue, size, Object[].class);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public <T> T[] toArray(T[] a) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                if(a.length < size)
                    return (T[]) Arrays.copyOf(queue, size, a.getClass());
                System.arraycopy(queue, 0, a, 0, size);
                if(a.length > size)
                    a[size] = null;
                return a;
            } finally {
                lock.unlock();
            }
        }

        private class Itr implements Iterator<Runnable> {
            final RunnableScheduledFuture<?>[] array;

            /** 下一个要返回的元素的下标，初始值为0 */
            int cursor;

            /** 最后一次返回的元素的下标，如果没有则返回-1（用来remove） */
            int lastRet = -1;

            Itr(RunnableScheduledFuture<?>[] array) {
                this.array = array;
            }

            @Override
            public boolean hasNext() {
                return cursor < array.length;
            }

            @Override
            public Runnable next() {
                if(cursor >= array.length)
                    throw new NoSuchElementException();
                return array[lastRet = cursor++];
            }

            @Override
            public void remove() {
                if(lastRet < 0)
                    throw new IllegalStateException();
                DelayedWorkQueue.this.remove(array[lastRet]);   // 直接删除原始队列里面的元素
                lastRet = -1;   // 恢复，下一次调用next才可能会有正常值
            }
        }
    }
}

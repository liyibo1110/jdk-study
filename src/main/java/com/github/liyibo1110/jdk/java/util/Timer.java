package com.github.liyibo1110.jdk.java.util;

import java.lang.ref.Cleaner;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TimerTask的任务调度器，重点在于它是个单线程模型，所有TimerTask都会在这个线程里面串行跑，功能和可靠性都比较弱，因此学习价值大于实用价值，
 * 现实中一般都使用ScheduledThreadPoolExecutor之类来实现延迟执行或周期性执行某线程任务，而不会用这个Timer了。
 * @author liyibo
 * @date 2026-04-01 14:23
 */
public class Timer {
    private final TaskQueue queue = new TaskQueue();

    /** 负责从queue中获取、放入，以及执行里面task的线程，注意是个单线程，因此理论上大部分时间应该都在run方法里面跑。 */
    private final TimerThread thread = new TimerThread(queue);

    /**
     * 该Timer对象会被注册到一个Cleaner中，作为该Timer对象的清理处理程序。
     * 当Timer对象不再被任何活动引用，且定时器队列中没有任务时，这将导致执行线程优雅退出。
     */
    private static class ThreadReaper implements Runnable {
        private final TaskQueue queue;
        private final TimerThread thread;

        ThreadReaper(TaskQueue queue, TimerThread thread) {
            this.queue = queue;
            this.thread = thread;
        }

        public void run() {
            synchronized (queue) {
                thread.newTasksMayBeScheduled = false;  // 没有新任务要执行了
                queue.notify();
            }
        }
    }

    private final Cleaner.Cleanable cleanup;

    /** 用来生成thread名称 */
    private static final AtomicInteger nextSerialNumber = new AtomicInteger();

    private static int serialNumber() {
        return nextSerialNumber.getAndIncrement();
    }

    public Timer() {
        this("Timer-" + serialNumber());
    }

    public Timer(boolean isDaemon) {
        this("Timer-" + serialNumber(), isDaemon);
    }

    public Timer(String name) {
        this(name, false);
    }

    public Timer(String name, boolean isDaemon) {
        ThreadReaper readper = new ThreadReaper(queue, thread);
        // CleanerFactory是JDK内部才能用到的组件，用来监控Timer是否已经没有了外部引用，就会触发ThreadReaper的run方法
        this.cleanup = CleanerFactory.cleaner().register(this, threadReaper);
        thread.setName(name);
        thread.setDaemon(isDaemon);
        thread.start(); // Timer构造时就直接启动worker了
    }

    /**
     * 一次性延迟任务
     */
    public void schedule(TimerTask task, long delay) {
        if (delay < 0)
            throw new IllegalArgumentException("Negative delay.");
        sched(task, System.currentTimeMillis() + delay, 0);
    }

    /**
     * 一次性延迟任务
     */
    public void schedule(TimerTask task, Date time) {
        sched(task, time.getTime(), 0);
    }

    /**
     * fixed-delay类型的周期任务
     */
    public void schedule(TimerTask task, long delay, long period) {
        if (delay < 0)
            throw new IllegalArgumentException("Negative delay.");
        if (period <= 0)
            throw new IllegalArgumentException("Non-positive period.");
        sched(task, System.currentTimeMillis() + delay, -period);
    }

    /**
     * fixed-delay类型的周期任务
     */
    public void schedule(TimerTask task, Date firstTime, long period) {
        if (period <= 0)
            throw new IllegalArgumentException("Non-positive period.");
        sched(task, firstTime.getTime(), -period);
    }

    /**
     * fixed-rate类型的周期任务
     */
    public void scheduleAtFixedRate(TimerTask task, long delay, long period) {
        if (delay < 0)
            throw new IllegalArgumentException("Negative delay.");
        if (period <= 0)
            throw new IllegalArgumentException("Non-positive period.");
        sched(task, System.currentTimeMillis() + delay, period);
    }

    /**
     * fixed-rate类型的周期任务
     */
    public void scheduleAtFixedRate(TimerTask task, Date firstTime, long period) {
        if (period <= 0)
            throw new IllegalArgumentException("Non-positive period.");
        sched(task, firstTime.getTime(), period);
    }

    /**
     * 将指定的task安排在指定时间执行，单位为毫秒。
     * 如果period为正数，则任务被安排为循环执行。
     * 如果period为零，则任务被安排为仅执行一次。
     * 时间格式应采用Date.getTime()的格式。
     * 此方法会检查定时器状态、任务状态和初始执行时间，但不会检查间隔时间。
     */
    private void sched(TimerTask task, long time, long period) {
        if (time < 0)
            throw new IllegalArgumentException("Illegal execution time.");
        // 防止period过大导致溢出
        if (Math.abs(period) > (Long.MAX_VALUE >> 1))
            period >>= 1;

        synchronized(queue) {
            if (!thread.newTasksMayBeScheduled)
                throw new IllegalStateException("Timer already cancelled.");
            synchronized(task.lock) {
                // Task必须是初始化状态才能进来
                if (task.state != TimerTask.VIRGIN)
                    throw new IllegalStateException("Task already scheduled or cancelled");
                task.nextExecutionTime = time;
                task.period = period;
                task.state = TimerTask.SCHEDULED;
            }

            queue.add(task);    // 入队并找到合适的位置
            if(queue.getMin() == task)  // 入队后，如果自己是队头，则立即唤醒queue干活
                queue.notify();
        }
    }

    public void cancel() {
        synchronized(queue) {
            queue.clear();
            cleanup.clean();
        }
    }

    /**
     * 从该Timer的queue中移除所有已取消的任务。调用此方法不会影响定时器的行为，但会从队列中清除对已取消任务的引用。如果这些任务没有外部引用，它们将符合垃圾回收的条件。
     * 大多数程序无需调用此方法。它专为那些需要取消大量任务的罕见应用程序而设计。
     * 调用此方法是以时间换取空间：该方法的运行时间可能与n + c log n成正比，其中n是队列中的任务数，c是已取消任务的数量。
     * 请注意，允许从该定时器调度的一个任务内部调用此方法。
     */
    public int purge() {
        int result = 0;
        synchronized(queue) {
            for (int i = queue.size(); i > 0; i--) {    // 从尾部遍历
                if(queue.get(i).state == TimerTask.CANCELLED) {
                    queue.quickRemove(i);
                    result++;
                }
            }
            if(result != 0) // 只要清理了，则重新构造优先级队列树
                queue.heapify();
        }
        return result;
    }
}

/**
 * Timer用来干活的单线程。
 */
class TimerThread extends Thread {
    /**
     *
     */
    boolean newTasksMayBeScheduled = true;

    private TaskQueue queue;

    TimerThread(TaskQueue queue) {
        this.queue = queue;
    }

    public void run() {
        try {
            mainLoop();
        } finally {
            // 当Timer不再有任何任务可以执行了，会进入这里清理
            synchronized(queue) {
                newTasksMayBeScheduled = false;
                queue.clear();
            }
        }
    }

    private void mainLoop() {
        while (true) {
            try {
                TimerTask task; // 当前的task
                boolean taskFired;  // 当前task是否应该被触发了
                synchronized(queue) {
                    // 如果只是队列为空，newTasksMayBeScheduled还是true，说明Timer还在运行中，只是没有任务了，这时就直接挂起worker线程
                    while(queue.isEmpty() && newTasksMayBeScheduled)
                        queue.wait();
                    // 队列为空，newTasksMayBeScheduled变成了false，说明Timer已经不再调度任务了，worker线程直接退出
                    if(queue.isEmpty())
                        break;

                    long currentTime;
                    long executionTime;
                    task = queue.getMin();  // 获取下一个task（离现在时间最近的），注意getMin不会从队列中移除task
                    synchronized(task.lock) {
                        // 已被取消则丢掉后继续
                        if(task.state == TimerTask.CANCELLED) {
                            queue.removeMin();
                            continue;
                        }
                        currentTime = System.currentTimeMillis();
                        executionTime = task.nextExecutionTime;
                        if(taskFired = (executionTime <= currentTime)) {
                            // 进来了说明task需要被触发了，首先看是否为一次性任务
                            if(task.period == 0) {
                                queue.removeMin();
                                task.state = TimerTask.EXECUTED;    // 先标记成EXECUTED
                            }else { // 如果是周期性任务，则计算下一次的运行时间，重新放回队列
                                queue.rescheduleMin(task.period < 0
                                        ? currentTime - task.period       // fixed-delay，因为period是负值，所以要减（其实也是加）
                                        : executionTime + task.period);   // fixed-rate
                            }
                        }
                    }
                    if(!taskFired)  // 如果头元素还没到时间，则直接挂起这一段时间，避免一直循环判断
                        queue.wait(executionTime - currentTime);
                }
                if(taskFired)
                    task.run();
            } catch (InterruptedException e) {
                // nothing to do
            }
        }
    }
}

/**
 * 该类表示一个定时器任务队列：这是一个由TimerTask对象组成的优先级队列，按nextExecutionTime排序。
 * 每个Timer对象都拥有这样一个队列，并与所属的TimerThread共享。
 * 该类内部使用堆内存，这使得add、removeMin和rescheduleMin操作的性能为log(n)，而getMin操作的性能为常数时间。
 */
class TaskQueue {
    /**
     * 优先队列以平衡二叉堆的形式表示：queue[n]的两个子节点分别是queue[2*n]和queue[2*n+1]。
     * 优先队列按nextExecutionTime字段排序：nextExecutionTime值最小的TimerTask位于queue[1]中（假设队列不为空）。
     * 对于堆中的每个节点n及其每个后代节点d，n.nextExecutionTime <= d.nextExecutionTime
     */
    private TimerTask[] queue = new TimerTask[128];

    private int size = 0;

    int size() {
        return size;
    }

    void add(TimerTask task) {
        if (size + 1 == queue.length)   // 扩容一倍
            queue = Arrays.copyOf(queue, 2*queue.length);
        queue[++size] = task;
        fixUp(size);
    }

    /**
     * 返回优先队列的头任务（即nextExecutionTime值最小的任务）。
     */
    TimerTask getMin() {
        return queue[1];
    }

    TimerTask get(int i) {
        return queue[i];
    }

    /**
     * 移除头元素
     */
    void removeMin() {
        queue[1] = queue[size];
        queue[size--] = null;
        fixDown(1);
    }

    /**
     * 从队列中移除第i个元素，且不考虑是否保持堆的不变性。
     * 请注意，队列的索引从1开始，因此1 <= i <= size。
     */
    void quickRemove(int i) {
        assert i <= size;
        queue[i] = queue[size];
        queue[size--] = null;
    }

    /**
     * 将主任务关联的nextExecutionTime设置为指定值，并相应地调整优先级队列。
     */
    void rescheduleMin(long newTime) {
        queue[1].nextExecutionTime = newTime;
        fixDown(1);
    }

    boolean isEmpty() {
        return size==0;
    }

    void clear() {
        for(int i = 1; i <= size; i++)
            queue[i] = null;
        size = 0;
    }

    /**
     * 将指定位置的task向上移到合适的优先级位置（触发时间更早）。
     */
    private void fixUp(int k) {
        while (k > 1) {
            int j = k >> 1;
            if (queue[j].nextExecutionTime <= queue[k].nextExecutionTime)
                break;
            TimerTask tmp = queue[j];  queue[j] = queue[k]; queue[k] = tmp;
            k = j;
        }
    }

    /**
     * 将指定位置的task向下移到合适的优先级位置（触发时间更晚）。
     */
    private void fixDown(int k) {
        int j;
        while ((j = k << 1) <= size && j > 0) {
            if (j < size && queue[j].nextExecutionTime > queue[j+1].nextExecutionTime)
                j++; // j indexes smallest kid
            if (queue[k].nextExecutionTime <= queue[j].nextExecutionTime)
                break;
            TimerTask tmp = queue[j];  queue[j] = queue[k]; queue[k] = tmp;
            k = j;
        }
    }

    /**
     * 将整个数组元素逐一进行优先级排序。
     */
    void heapify() {
        for (int i = size/2; i >= 1; i--)
            fixDown(i);
    }
}

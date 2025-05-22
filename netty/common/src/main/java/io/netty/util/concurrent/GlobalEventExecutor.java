/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import org.jetbrains.annotations.Async.Schedule;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单线程全局单例 {@link EventExecutor}。
 * <p>
 * <b>设计意图：</b>
 * <ul>
 *   <li>为全局任务（如定时任务、全局回调等）提供统一的事件执行器。</li>
 *   <li>采用单线程模型，保证任务串行执行，避免并发冲突。</li>
 *   <li>自动管理线程生命周期，无任务时自动停止，节省资源。</li>
 *   <li>不适合大量任务并发调度，建议仅用于全局轻量级任务。</li>
 * </ul>
 * <b>性能优化：</b>
 * <ul>
 *   <li>任务队列采用高效的 LinkedBlockingQueue，支持高并发任务提交。</li>
 *   <li>线程按需启动与回收，避免长期空转浪费。</li>
 *   <li>定时任务与普通任务统一调度，减少线程切换和上下文切换开销。</li>
 * </ul>
 */
public final class GlobalEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(GlobalEventExecutor.class);

    private static final long SCHEDULE_QUIET_PERIOD_INTERVAL;

    static {
        int quietPeriod = SystemPropertyUtil.getInt("io.netty.globalEventExecutor.quietPeriodSeconds", 1);
        if (quietPeriod <= 0) {
            quietPeriod = 1;
        }
        logger.debug("-Dio.netty.globalEventExecutor.quietPeriodSeconds: {}", quietPeriod);

        SCHEDULE_QUIET_PERIOD_INTERVAL = TimeUnit.SECONDS.toNanos(quietPeriod);
    }

    /**
     * 全局唯一实例，单例模式。
     */
    public static final GlobalEventExecutor INSTANCE = new GlobalEventExecutor();

    /**
     * 任务队列，支持高并发任务提交。
     * <b>性能优化：</b> LinkedBlockingQueue 线程安全，适合多线程环境。
     */
    final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<Runnable>();

    /**
     * 用于保持线程存活的空任务，防止线程过早退出。
     */
    final ScheduledFutureTask<Void> quietPeriodTask = new ScheduledFutureTask<Void>(
            this, Executors.<Void>callable(new Runnable() {
        @Override
        public void run() {
            // NOOP
        }
    }, null),
            // 注意：此处调用 getCurrentTimeNanos() 之所以安全，是因为 GlobalEventExecutor 是 final 类，
            // 不会被子类重写该方法，避免了初始化时多态带来的不确定性。
            // 若为非 final 类，子类可能重写 getCurrentTimeNanos()，导致初始化时行为不可控。
            deadlineNanos(getCurrentTimeNanos(), SCHEDULE_QUIET_PERIOD_INTERVAL),
            -SCHEDULE_QUIET_PERIOD_INTERVAL
    );

    // 线程工厂，确保线程组不固定，避免线程泄漏
    final ThreadFactory threadFactory;
    private final TaskRunner taskRunner = new TaskRunner();
    private final AtomicBoolean started = new AtomicBoolean();
    volatile Thread thread;

    private final Future<?> terminationFuture;

    private GlobalEventExecutor() {
        scheduledTaskQueue().add(quietPeriodTask);
        threadFactory = ThreadExecutorMap.apply(new DefaultThreadFactory(
                DefaultThreadFactory.toPoolName(getClass()), false, Thread.NORM_PRIORITY, null), this);

        UnsupportedOperationException terminationFailure = new UnsupportedOperationException();
        ThrowableUtil.unknownStackTrace(terminationFailure, GlobalEventExecutor.class, "terminationFuture");
        terminationFuture = new FailedFuture<Object>(this, terminationFailure);
    }

    /**
     * 从任务队列中获取下一个 {@link Runnable}，若无任务则阻塞等待。
     * <br>
     * <b>性能优化：</b> 优先处理定时任务，避免饥饿；无定时任务时高效阻塞等待，节省 CPU。
     *
     * @return 若线程被中断或唤醒，返回 null
     */
    Runnable takeTask() {
        BlockingQueue<Runnable> taskQueue = this.taskQueue;
        for (;;) {
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            if (scheduledTask == null) {
                Runnable task = null;
                try {
                    task = taskQueue.take();
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                if (delayNanos > 0) {
                    try {
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        // Waken up.
                        return null;
                    }
                }
                if (task == null) {
                    // 性能优化：及时将到期的定时任务转移到主队列，避免定时任务饿死
                    fetchFromScheduledTaskQueue();
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    /**
     * 将到期的定时任务批量转移到主任务队列，提升调度效率。
     */
    private void fetchFromScheduledTaskQueue() {
        long nanoTime = getCurrentTimeNanos();
        Runnable scheduledTask = pollScheduledTask(nanoTime);
        while (scheduledTask != null) {
            taskQueue.add(scheduledTask);
            scheduledTask = pollScheduledTask(nanoTime);
        }
    }

    /**
     * 返回当前待处理任务数。
     */
    public int pendingTasks() {
        return taskQueue.size();
    }

    /**
     * 添加任务到队列，若已关闭则抛出异常。
     */
    private void addTask(Runnable task) {
        taskQueue.add(ObjectUtil.checkNotNull(task, "task"));
    }

    @Override
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isShuttingDown() {
        return false;
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return false;
    }

    /**
     * 阻塞等待全局线程无任务后自动退出。
     * <br>
     * <b>典型场景：</b> 应用关闭后确保线程已终止，防止资源泄漏。
     *
     * @return 仅当线程已终止时返回 true
     */
    public boolean awaitInactivity(long timeout, TimeUnit unit) throws InterruptedException {
        ObjectUtil.checkNotNull(unit, "unit");

        final Thread thread = this.thread;
        if (thread == null) {
            throw new IllegalStateException("thread was not started");
        }
        thread.join(unit.toMillis(timeout));
        return !thread.isAlive();
    }

    @Override
    public void execute(Runnable task) {
        execute0(task);
    }

    /**
     * 提交任务并按需启动线程。
     * <br>
     * <b>性能优化：</b> 仅在有任务时启动线程，避免空转浪费。
     */
    private void execute0(@Schedule Runnable task) {
        addTask(ObjectUtil.checkNotNull(task, "task"));
        if (!inEventLoop()) {
            startThread();
        }
    }

    /**
     * 启动全局线程，采用原子操作保证线程安全。
     * <br>
     * <b>性能优化：</b> 线程仅在首次有任务时启动，后续复用，避免频繁创建销毁。
     */
    private void startThread() {
        if (started.compareAndSet(false, true)) {
            final Thread callingThread = Thread.currentThread();
            ClassLoader parentCCL = AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                @Override
                public ClassLoader run() {
                    return callingThread.getContextClassLoader();
                }
            });
            // 避免线程组泄漏，防止内存泄漏
            setContextClassLoader(callingThread, null);
            try {
                final Thread t = threadFactory.newThread(taskRunner);
                // 防止 classloader 泄漏，见相关 issue
                setContextClassLoader(t, null);

                // 先设置 thread，再启动，保证 inEventLoop() 正确
                thread = t;
                t.start();
            } finally {
                setContextClassLoader(callingThread, parentCCL);
            }
        }
    }

    private static void setContextClassLoader(final Thread t, final ClassLoader cl) {
        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            @Override
            public Void run() {
                t.setContextClassLoader(cl);
                return null;
            }
        });
    }

    /**
     * 全局任务执行主循环，串行处理所有任务。
     * <br>
     * <b>性能优化：</b> 线程空闲时自动退出，减少资源占用；新任务到来时自动重启。
     */
    final class TaskRunner implements Runnable {
        @Override
        public void run() {
            for (;;) {
                Runnable task = takeTask();
                if (task != null) {
                    try {
                        runTask(task);
                    } catch (Throwable t) {
                        logger.warn("Unexpected exception from the global event executor: ", t);
                    }

                    if (task != quietPeriodTask) {
                        continue;
                    }
                }

                Queue<ScheduledFutureTask<?>> scheduledTaskQueue = GlobalEventExecutor.this.scheduledTaskQueue;
                // 若主队列和定时队列均为空（仅剩空任务），则安全退出线程，节省资源
                if (taskQueue.isEmpty() && (scheduledTaskQueue == null || scheduledTaskQueue.size() == 1)) {
                    // 标记线程已停止，保证只有一个线程在运行
                    boolean stopped = started.compareAndSet(true, false);
                    assert stopped;

                    // 检查是否有新任务到来，若有则重启线程，保证任务不丢失
                    if (taskQueue.isEmpty()) {
                        // A) 无新任务，安全退出
                        // B) 新线程已启动，安全退出
                        break;
                    }

                    // 有新任务到来，尝试重启线程，保证任务及时处理
                    if (!started.compareAndSet(false, true)) {
                        // 新线程已启动，当前线程退出
                        break;
                    }

                    // 当前线程更快，继续处理新任务
                }
            }
        }
    }
}

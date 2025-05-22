/*
 * Copyright 2016 The Netty Project
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
package io.netty.channel.kqueue;

import io.netty.channel.Channel;
import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.IoHandlerContext;
import io.netty.channel.IoHandle;
import io.netty.channel.IoHandler;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.IoOps;
import io.netty.channel.IoRegistration;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SelectStrategyFactory;
import io.netty.channel.unix.FileDescriptor;
import io.netty.util.IntSupplier;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.ThreadAwareExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static java.lang.Math.min;

/**
 * {@link IoHandler} 的一个实现，它在底层使用 kqueue。<b>仅适用于 BSD 系统 (例如 macOS, FreeBSD)！</b>
 * 这个类是 Netty KQueue 传输的核心，负责处理所有基于 KQueue 的 I/O 操作。
 * KQueue 是 BSD 系统上的一种高效的 I/O 事件通知机制，类似于 Linux 上的 Epoll。
 *
 * <p><b>核心组件与职责：</b></p>
 * <ul>
 *     <li><b>KQueue 文件描述符 ({@code kqueueFd}):</b> 代表一个 KQueue 实例，用于注册和监听文件描述符上的 I/O 事件 (称为 knote)。</li>
 *     <li><b>用户事件唤醒 ({@code KQUEUE_WAKE_UP_IDENT}):</b> KQueue 使用用户定义的事件 (EVFILT_USER) 来实现线程间唤醒。
 *         当其他线程需要唤醒阻塞在 {@code kevent} 上的事件循环线程时，会触发此用户事件。</li>
 *     <li><b>事件列表 ({@code eventList}) 和变更列表 ({@code changeList}):</b>
 *         {@code changeList} 用于向 KQueue 注册或修改监听的事件 (kevent)。
 *         {@code eventList} 用于从 {@code kevent} 系统调用接收就绪的事件。</li>
 *     <li><b>注册表 ({@code registrations}):</b> 一个映射，将文件描述符的标识符 (ident) 映射到其对应的 {@link DefaultKqueueIoRegistration}，
 *         后者封装了与特定 {@link KQueueIoHandle} (通常是 Channel) 相关的注册信息。</li>
 *     <li><b>事件处理：</b> 在事件循环中调用 {@code kevent} 等待 I/O 事件，然后处理返回的就绪事件，并将它们分发给相应的 {@link KQueueIoHandle}。</li>
 *     <li><b>唤醒机制 ({@code wakeup()}):</b> 允许其他线程安全地唤醒事件循环。</li>
 * </ul>
 *
 * <p><b>与 Epoll/NIO 的比较：</b></p>
 * <ul>
 *     <li><b>KQueue vs Epoll:</b> KQueue 和 Epoll 都是为解决传统 select/poll 模型的性能瓶颈而设计的高性能 I/O 多路复用机制。
 *         它们在 API 设计和具体实现上有所不同，但目标相似。KQueue 主要用于 BSD 派生系统，而 Epoll 用于 Linux。</li>
 *     <li><b>KQueue vs NIO Selector:</b> 与 Java NIO Selector 相比，KQueue 通常能提供更高的性能和更低的延迟，尤其是在处理大量并发连接时。</li>
 * </ul>
 *
 * <p><b>线程模型：</b></p>
 * 每个 {@link KQueueIoHandler} 通常由一个专用的 {@link ThreadAwareExecutor} (通常是 {@link KQueueEventLoop}) 驱动，
 * 所有 KQueue 操作和事件处理都在这个单线程中执行。
 *
 * @see Native (KQueue JNI 方法)
 * @see KQueueIoHandle
 * @see KQueueEventLoop
 * @see FileDescriptor
 * @see KQueueEventArray
 */
public final class KQueueIoHandler implements IoHandler {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(KQueueIoHandler.class);
    /**
     * 用于原子更新 {@link #wakenUp} 状态的 {@link AtomicIntegerFieldUpdater}。
     * {@code wakenUp} 标志用于控制唤醒逻辑：0 表示未唤醒，1 表示已请求唤醒。
     */
    private static final AtomicIntegerFieldUpdater<KQueueIoHandler> WAKEN_UP_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(KQueueIoHandler.class, "wakenUp");
    /**
     * 用于唤醒 {@code kevent} 调用的用户事件的标识符。
     * 当其他线程需要唤醒事件循环时，会向 KQueue 触发一个以此为 ident 的 EVFILT_USER 事件。
     */
    private static final int KQUEUE_WAKE_UP_IDENT = 0;
    // `kqueue()` 在将一个大数字（例如 Integer.MAX_VALUE）指定为超时时可能会返回 EINVAL。
    // 24 小时应该是一个足够大的值。
    // 参考: https://man.freebsd.org/cgi/man.cgi?query=kevent&apropos=0&sektion=0&manpath=FreeBSD+6.1-RELEASE&format=html#end
    /**
     * {@code kevent} 系统调用允许的最大超时时间 (秒)。
     * 设置为 24 小时减 1 秒，以避免某些系统上对于非常大的超时值可能出现的问题 (如返回 EINVAL)。
     */
    private static final int KQUEUE_MAX_TIMEOUT_SECONDS = 86399; // 24 hours - 1 second

    static {
        // 确保 JNI 在此类加载时已初始化！
        // 我们在此类中使用 unix-common 方法，这些方法由 JNI 方法支持。
        KQueue.ensureAvailability();
    }

    /**
     * 是否允许 {@link #eventList} 和 {@link #changeList} 数组在需要时自动增长。
     */
    private final boolean allowGrowing;
    /**
     * KQueue 实例的文件描述符。
     */
    private final FileDescriptor kqueueFd;
    /**
     * 用于向 KQueue 提交事件变更 (注册/修改监听) 的列表。
     */
    private final KQueueEventArray changeList;
    /**
     * 用于从 KQueue 接收就绪事件的列表。
     */
    private final KQueueEventArray eventList;
    /**
     * {@code kevent} 的选择策略。
     */
    private final SelectStrategy selectStrategy;
    /**
     * 辅助类，用于处理与 JNI 相关的数组操作，主要用作 DefaultKqueueIoRegistration 的 attachment。
     */
    private final NativeArrays nativeArrays;
    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return kqueueWaitNow(); // 用于 SelectStrategy 计算
        }
    };
    /**
     * 驱动此 {@link KQueueIoHandler} 的执行器，通常是 {@link KQueueEventLoop}。
     */
    private final ThreadAwareExecutor executor;
    /**
     * 存储文件描述符标识符 (ident) 到其对应 {@link DefaultKqueueIoRegistration} 的映射。
     */
    private final IntObjectMap<DefaultKqueueIoRegistration> registrations = new IntObjectHashMap<>(4096);
    /**
     * 当前注册的 Channel 数量。
     */
    private int numChannels;

    /**
     * 唤醒状态标志。0 表示未请求唤醒，1 表示已请求唤醒。
     * 使用 {@link AtomicIntegerFieldUpdater} 进行原子更新。
     */
    private volatile int wakenUp;

    /**
     * 返回一个新的 {@link IoHandlerFactory}，用于创建 {@link KQueueIoHandler} 实例。
     * 使用默认的 {@code maxEvents} (0，表示允许事件数组动态增长) 和默认的 {@link SelectStrategyFactory}。
     *
     * @return {@link IoHandlerFactory} 实例。
     */
    public static IoHandlerFactory newFactory() {
        return newFactory(0, DefaultSelectStrategyFactory.INSTANCE);
    }

    /**
     * 返回一个新的 {@link IoHandlerFactory}，用于创建 {@link KQueueIoHandler} 实例。
     *
     * @param maxEvents             {@code kevent} 一次可以返回的最大事件数。如果为0，则事件数组的大小可以动态增长。
     * @param selectStrategyFactory 用于创建 {@link SelectStrategy} 的工厂。
     * @return {@link IoHandlerFactory} 实例。
     */
    public static IoHandlerFactory newFactory(final int maxEvents,
                                              final SelectStrategyFactory selectStrategyFactory) {
        ObjectUtil.checkPositiveOrZero(maxEvents, "maxEvents");
        ObjectUtil.checkNotNull(selectStrategyFactory, "selectStrategyFactory");
        return executor -> new KQueueIoHandler(executor, maxEvents, selectStrategyFactory.newSelectStrategy());
    }

    private KQueueIoHandler(ThreadAwareExecutor executor, int maxEvents, SelectStrategy strategy) {
        this.executor = ObjectUtil.checkNotNull(executor, "executor");
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "strategy");
        this.kqueueFd = Native.newKQueue();
        if (maxEvents == 0) {
            allowGrowing = true;
            maxEvents = 4096;
        } else {
            allowGrowing = false;
        }
        this.changeList = new KQueueEventArray(maxEvents);
        this.eventList = new KQueueEventArray(maxEvents);
        nativeArrays = new NativeArrays();
        int result = Native.keventAddUserEvent(kqueueFd.intValue(), KQUEUE_WAKE_UP_IDENT);
        if (result < 0) {
            destroy();
            throw new IllegalStateException("kevent failed to add user event with errno: " + (-result));
        }
    }

    @Override
    public void wakeup() {
        if (!executor.isExecutorThread(Thread.currentThread())
                && WAKEN_UP_UPDATER.compareAndSet(this, 0, 1)) {
            wakeup0();
        }
    }

    /**
     * 触发 KQueue 用户事件以唤醒阻塞在 {@code kevent} 上的线程。
     */
    private void wakeup0() {
        Native.keventTriggerUserEvent(kqueueFd.intValue(), KQUEUE_WAKE_UP_IDENT);
        // Note that the result may return an error (e.g. errno = EBADF after the event loop has been shutdown).
        // So it is not very practical to assert the return value is always >= 0.
    }

    /**
     * 执行 {@code kevent} 等待操作，处理定时和唤醒逻辑。
     *
     * @param context     {@link IoHandlerContext}，提供定时信息。
     * @param oldWakeup   在调用此方法之前 {@code wakenUp} 标志的值。
     * @return {@code kevent} 返回的就绪事件数量。
     * @throws IOException 如果 {@code kevent} 调用失败。
     */
    private int kqueueWait(IoHandlerContext context, boolean oldWakeup) throws IOException {
        if (oldWakeup && !context.canBlock()) {
            return kqueueWaitNow();
        }

        long totalDelay = context.delayNanos(System.nanoTime());
        int delaySeconds = (int) min(totalDelay / 1000000000L, KQUEUE_MAX_TIMEOUT_SECONDS);
        int delayNanos = (int) (totalDelay % 1000000000L);
        return kqueueWait(delaySeconds, delayNanos);
    }

    private int kqueueWaitNow() throws IOException {
        return kqueueWait(0, 0);
    }

    private int kqueueWait(int timeoutSec, int timeoutNs) throws IOException {
        int numEvents = Native.keventWait(kqueueFd.intValue(), changeList, eventList, timeoutSec, timeoutNs);
        changeList.clear();
        return numEvents;
    }

    private void processReady(int ready) {
        for (int i = 0; i < ready; ++i) {
            final short filter = eventList.filter(i);
            final short flags = eventList.flags(i);
            final int ident = eventList.ident(i);
            if (filter == Native.EVFILT_USER || (flags & Native.EV_ERROR) != 0) {
                assert filter != Native.EVFILT_USER ||
                        (filter == Native.EVFILT_USER && ident == KQUEUE_WAKE_UP_IDENT);
                continue;
            }

            DefaultKqueueIoRegistration registration = registrations.get(ident);
            if (registration == null) {
                logger.warn("events[{}]=[{}, {}] had no registration!", i, ident, filter);
                continue;
            }
            registration.handle(ident, filter, flags, eventList.fflags(i), eventList.data(i));
        }
    }

    @Override
    public int run(IoHandlerContext context) {
        int handled = 0;
        try {
            int strategy = selectStrategy.calculateStrategy(selectNowSupplier, !context.canBlock());
            switch (strategy) {
                case SelectStrategy.CONTINUE:
                    return 0;

                case SelectStrategy.BUSY_WAIT:
                    strategy = kqueueWait(context, WAKEN_UP_UPDATER.getAndSet(this, 0) == 1);

                    if (wakenUp == 1) {
                        wakeup0();
                    }
                    // fall-through
                default:
            }

            if (strategy > 0) {
                handled = strategy;
                processReady(strategy);
            }

            if (allowGrowing && strategy == eventList.capacity()) {
                eventList.realloc(false);
            }
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            handleLoopException(t);
        }
        return handled;
    }

    int numRegisteredChannels() {
        return numChannels;
    }

    List<Channel> registeredChannelsList() {
        IntObjectMap<DefaultKqueueIoRegistration> ch = registrations;
        if (ch.isEmpty()) {
            return Collections.emptyList();
        }

        List<Channel> channels = new ArrayList<>(ch.size());

        for (DefaultKqueueIoRegistration registration : ch.values()) {
            if (registration.handle instanceof AbstractKQueueChannel.AbstractKQueueUnsafe) {
                channels.add(((AbstractKQueueChannel.AbstractKQueueUnsafe) registration.handle).channel());
            }
        }
        return Collections.unmodifiableList(channels);
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    @Override
    public void prepareToDestroy() {
        try {
            kqueueWaitNow();
        } catch (IOException e) {
            // ignore on close
        }

        DefaultKqueueIoRegistration[] copy = registrations.values().toArray(new DefaultKqueueIoRegistration[0]);

        for (DefaultKqueueIoRegistration reg: copy) {
            reg.close();
        }
    }

    @Override
    public void destroy() {
        try {
            try {
                kqueueFd.close();
            } catch (IOException e) {
                logger.warn("Failed to close the kqueue fd.", e);
            }
        } finally {
            nativeArrays.free();
            changeList.free();
            eventList.free();
        }
    }

    @Override
    public IoRegistration register(IoHandle handle) {
        final KQueueIoHandle kqueueHandle = cast(handle);
        if (kqueueHandle.ident() == KQUEUE_WAKE_UP_IDENT) {
            throw new IllegalArgumentException("ident " + KQUEUE_WAKE_UP_IDENT + " is reserved for internal usage");
        }

        DefaultKqueueIoRegistration registration = new DefaultKqueueIoRegistration(
                executor, kqueueHandle);
        DefaultKqueueIoRegistration old = registrations.put(kqueueHandle.ident(), registration);
        if (old != null) {
            registrations.put(kqueueHandle.ident(), old);
            throw new IllegalStateException("registration for the KQueueIoHandle.ident() already exists");
        }

        if (kqueueHandle instanceof AbstractKQueueChannel.AbstractKQueueUnsafe) {
            numChannels++;
        }
        return registration;
    }

    private static KQueueIoHandle cast(IoHandle handle) {
        if (handle instanceof KQueueIoHandle) {
            return (KQueueIoHandle) handle;
        }
        throw new IllegalArgumentException("IoHandle of type " + StringUtil.simpleClassName(handle) + " not supported");
    }

    private static KQueueIoOps cast(IoOps ops) {
        if (ops instanceof KQueueIoOps) {
            return (KQueueIoOps) ops;
        }
        throw new IllegalArgumentException("IoOps of type " + StringUtil.simpleClassName(ops) + " not supported");
    }

    @Override
    public boolean isCompatible(Class<? extends IoHandle> handleType) {
        return KQueueIoHandle.class.isAssignableFrom(handleType);
    }

    private final class DefaultKqueueIoRegistration implements IoRegistration {
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final KQueueIoEvent event = new KQueueIoEvent();

        final KQueueIoHandle handle;

        private final ThreadAwareExecutor executor;

        DefaultKqueueIoRegistration(ThreadAwareExecutor executor, KQueueIoHandle handle) {
            this.executor = executor;
            this.handle = handle;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T attachment() {
            return (T) nativeArrays;
        }

        @Override
        public long submit(IoOps ops) {
            KQueueIoOps kQueueIoOps = cast(ops);
            if (!isValid()) {
                return -1;
            }
            short filter = kQueueIoOps.filter();
            short flags = kQueueIoOps.flags();
            int fflags = kQueueIoOps.fflags();
            if (executor.isExecutorThread(Thread.currentThread())) {
                evSet(filter, flags, fflags);
            } else {
                executor.execute(() -> evSet(filter, flags, fflags));
            }
            return 0;
        }

        void handle(int ident, short filter, short flags, int fflags, long data) {
            event.update(ident, filter, flags, fflags, data);
            handle.handle(this, event);
        }

        private void evSet(short filter, short flags, int fflags) {
            changeList.evSet(handle.ident(), filter, flags, fflags);
        }

        @Override
        public boolean isValid() {
            return !canceled.get();
        }

        @Override
        public boolean cancel() {
            if (!canceled.compareAndSet(false, true)) {
                return false;
            }
            if (executor.isExecutorThread(Thread.currentThread())) {
                cancel0();
            } else {
                executor.execute(this::cancel0);
            }
            return true;
        }

        private void cancel0() {
            int ident = handle.ident();
            DefaultKqueueIoRegistration old = registrations.remove(ident);
            if (old != null) {
                if (old != this) {
                    registrations.put(ident, old);
                } else if (old.handle instanceof AbstractKQueueChannel.AbstractKQueueUnsafe) {
                    numChannels--;
                }
            }
        }

        void close() {
            cancel();
            try {
                handle.close();
            } catch (Exception e) {
                logger.debug("Exception during closing " + handle, e);
            }
        }
    }
}

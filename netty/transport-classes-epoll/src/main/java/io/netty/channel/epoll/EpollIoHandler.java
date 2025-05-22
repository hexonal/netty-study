/*
 * Copyright 2014 The Netty Project
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
package io.netty.channel.epoll;

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
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.Math.min;
import static java.lang.System.nanoTime;

/**
 * {@link IoHandler} 的一个实现，它在底层使用 epoll。<b>仅适用于 Linux 系统！</b>
 * 这个类是 Netty Epoll 传输的核心，负责处理所有基于 Epoll 的 I/O 操作，提供了比 NIO 更高的性能和更低延迟的事件处理机制。
 *
 * <p><b>核心组件与职责：</b></p>
 * <ul>
 *     <li><b>Epoll 文件描述符 ({@code epollFd}):</b> 代表一个 Epoll 实例，用于注册和监听文件描述符上的 I/O 事件。</li>
 *     <li><b>Event 文件描述符 ({@code eventFd}):</b> 用于线程间唤醒。当其他线程需要唤醒阻塞在 {@code epoll_wait} 上的事件循环线程时，
 *         会向此 {@code eventFd} 写入数据，从而触发 Epoll 事件。</li>
 *     <li><b>Timer 文件描述符 ({@code timerFd}):</b> 用于处理定时任务。通过设置 {@code timerFd} 的超时时间，可以使 {@code epoll_wait}
 *         在指定时间后返回，从而执行计划任务。</li>
 *     <li><b>事件数组 ({@code events}):</b> 用于存储从 {@code epoll_wait} 返回的就绪事件。</li>
 *     <li><b>注册表 ({@code registrations}):</b> 一个映射，将文件描述符 (fd) 映射到其对应的 {@link DefaultEpollIoRegistration}，
 *         后者封装了与特定 {@link EpollIoHandle} (通常是 Channel) 相关的注册信息。</li>
 *     <li><b>事件处理：</b> 在事件循环中调用 {@code epoll_wait} (或其变体) 等待 I/O 事件，然后处理返回的就绪事件，并将它们分发给相应的 {@link EpollIoHandle}。</li>
 *     <li><b>唤醒机制 ({@code wakeup()}):</b> 允许其他线程安全地唤醒事件循环。</li>
 * </ul>
 *
 * <p><b>与 NIO 的比较：</b></p>
 * Epoll 是 Linux 特有的 I/O 事件通知机制，与标准的 Java NIO Selector相比，通常具有以下优势：
 * <ul>
 *     <li><b>性能更高：</b> 特别是在处理大量并发连接时，Epoll 的性能通常优于 NIO Selector。</li>
 *     <li><b>边缘触发 (ET) 支持：</b> Epoll 支持边缘触发模式 (EPOLLET)，这可以减少事件通知的次数，但需要应用程序正确处理所有可用数据。Netty 的 Epoll 实现通常与边缘触发结合使用。</li>
 *     <li><b>更细粒度的控制：</b> Epoll 提供了更丰富的 API 来管理文件描述符和事件。</li>
 * </ul>
 *
 * <p><b>线程模型：</b></p>
 * 与 {@link io.netty.channel.nio.NioIoHandler} 类似，每个 {@link EpollIoHandler} 通常由一个专用的 {@link ThreadAwareExecutor}
 * (通常是 {@link EpollEventLoop}) 驱动，所有 Epoll 操作和事件处理都在这个单线程中执行。
 *
 * @see Native (Linux JNI 方法)
 * @see EpollIoHandle
 * @see EpollEventLoop
 * @see FileDescriptor
 */
public class EpollIoHandler implements IoHandler {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(EpollIoHandler.class);
    /**
     * epoll_wait 调用的最小超时阈值（毫秒）。
     * 如果计算出的超时时间小于此阈值，Netty 可能会选择不同的等待策略或直接使用一个较短的固定超时。
     * 通过系统属性 {@code io.netty.channel.epoll.epollWaitThreshold} 控制，默认为 10ms。
     */
    private static final long EPOLL_WAIT_MILLIS_THRESHOLD =
            SystemPropertyUtil.getLong("io.netty.channel.epoll.epollWaitThreshold", 10);

    static {
        // 确保 JNI 在此类加载时已初始化！
        // 我们在此类中使用 unix-common 方法，这些方法由 JNI 方法支持。
        Epoll.ensureAvailability();
    }

    // 选择一个先前任务不可能使用过的数字。
    // 用于跟踪上一个 epoll_wait 的截止时间，以优化 timerFd 的重置。
    private long prevDeadlineNanos = nanoTime() - 1;
    /**
     * Epoll 实例的文件描述符。所有 Channel 的文件描述符都会注册到此 epollFd。
     */
    private FileDescriptor epollFd;
    /**
     * 用于线程间唤醒的 eventfd 文件描述符。
     * 当其他线程需要唤醒事件循环时，会向此 fd 写入一个字节。
     */
    private FileDescriptor eventFd;
    /**
     * 用于处理定时任务的 timerfd 文件描述符。
     * Netty 使用它来实现高效的定时器，而不是依赖于 Java 的 {@link Object#wait(long)} 或 {@link java.util.concurrent.locks.LockSupport#parkNanos(long)}。
     */
    private FileDescriptor timerFd;
    /**
     * 存储文件描述符 (fd) 到其对应 {@link DefaultEpollIoRegistration} 的映射。
     * 用于在 epoll 事件发生时快速找到关联的 Channel/Handle。
     */
    private final IntObjectMap<DefaultEpollIoRegistration> registrations = new IntObjectHashMap<>(4096);
    /**
     * 是否允许 {@link #events} 数组在需要时自动增长。
     * 如果构造函数中的 {@code maxEvents} 为 0，则此值为 true。
     */
    private final boolean allowGrowing;
    /**
     * 用于从 {@code epoll_wait} 接收就绪事件的数组。
     * 其大小可以固定或根据 {@link #allowGrowing} 动态调整。
     */
    private final EpollEventArray events;
    /**
     * 辅助类，用于处理与 JNI 相关的数组操作，这里主要用作 DefaultEpollIoRegistration 的 attachment。
     * 实际上，EpollIoHandler 本身似乎并不直接使用 NativeArrays 来传递数据，
     * 可能是为了与某些 Channel 实现的特定需求兼容，或者是一个遗留的设计。
     * 对于 DefaultEpollIoRegistration，attachment() 方法返回此 NativeArrays 实例。
     */
    private final NativeArrays nativeArrays;

    /**
     * {@code epoll_wait} 的选择策略。
     */
    private final SelectStrategy selectStrategy;
    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            // 直接调用 epollWaitNow()，用于 SelectStrategy 计算选择策略
            return epollWaitNow();
        }
    };
    /**
     * 驱动此 {@link EpollIoHandler} 的执行器，通常是 {@link EpollEventLoop}。
     */
    private final ThreadAwareExecutor executor;

    /**
     * 表示事件循环当前处于唤醒状态的特殊值。
     */
    private static final long AWAKE = -1L;
    /**
     * 表示事件循环当前没有计划的唤醒任务，正在等待的特殊值。
     */
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos 的状态:
    //    AWAKE (即 -1L):         当事件循环 (EL) 处于唤醒状态时 (例如，有任务待处理，或者刚刚被 wakeup())。
    //    NONE (即 Long.MAX_VALUE): 当事件循环正在等待且没有计划的唤醒 (没有定时任务)。
    //    其他值 T:              当事件循环正在等待且计划在时间 T 唤醒 (有定时任务)。
    // 使用 AtomicLong 来确保线程安全地更新此状态。
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);
    /**
     * 标志是否有挂起的唤醒请求。
     * 当 {@link #wakeup()} 被调用时，如果事件循环不在其自己的线程中，此标志可能被设置。
     * {@code run()} 方法会检查此标志以处理唤醒逻辑。
     */
    private boolean pendingWakeup;

    /**
     * 当前注册的 Channel 数量。
     * 仅在注册的 handle 是 AbstractEpollChannel.AbstractEpollUnsafe 实例时递增。
     */
    private int numChannels;

    // 参考: https://man7.org/linux/man-pages/man2/timerfd_create.2.html
    // timerfd_settime 所能接受的最大纳秒值 (struct timespec 的 tv_nsec 字段)。
    private static final long MAX_SCHEDULED_TIMERFD_NS = 999999999;

    /**
     * 返回一个新的 {@link IoHandlerFactory}，用于创建 {@link EpollIoHandler} 实例。
     * 使用默认的 {@code maxEvents} (0，表示允许事件数组动态增长) 和默认的 {@link SelectStrategyFactory}。
     *
     * @return {@link IoHandlerFactory} 实例。
     */
    public static IoHandlerFactory newFactory() {
        return newFactory(0, DefaultSelectStrategyFactory.INSTANCE);
    }

    /**
     * 返回一个新的 {@link IoHandlerFactory}，用于创建 {@link EpollIoHandler} 实例。
     *
     * @param maxEvents             {@code epoll_wait} 一次可以返回的最大事件数。如果为0，则事件数组 {@link #events} 的大小可以动态增长。
     * @param selectStrategyFactory 用于创建 {@link SelectStrategy} 的工厂。
     * @return {@link IoHandlerFactory} 实例。
     */
    public static IoHandlerFactory newFactory(final int maxEvents,
                                              final SelectStrategyFactory selectStrategyFactory) {
        ObjectUtil.checkPositiveOrZero(maxEvents, "maxEvents");
        ObjectUtil.checkNotNull(selectStrategyFactory, "selectStrategyFactory");
        return executor -> new EpollIoHandler(executor, maxEvents, selectStrategyFactory.newSelectStrategy());
    }

    // 包级私有，用于测试
    EpollIoHandler(ThreadAwareExecutor executor, int maxEvents, SelectStrategy strategy) {
        this.executor = ObjectUtil.checkNotNull(executor, "executor");
        selectStrategy = ObjectUtil.checkNotNull(strategy, "strategy");
        if (maxEvents == 0) { // 如果 maxEvents 为0，则允许事件数组动态增长
            allowGrowing = true;
            events = new EpollEventArray(4096); // 初始大小为 4096
        } else {
            allowGrowing = false;
            events = new EpollEventArray(maxEvents); // 固定大小
        }
        nativeArrays = new NativeArrays();
        openFileDescriptors(); // 创建 epollFd, eventFd, timerFd
    }

    private static EpollIoHandle cast(IoHandle handle) {
        if (handle instanceof EpollIoHandle) {
            return (EpollIoHandle) handle;
        }
        throw new IllegalArgumentException("IoHandle of type " + StringUtil.simpleClassName(handle) + " not supported");
    }

    private static EpollIoOps cast(IoOps ops) {
        if (ops instanceof EpollIoOps) {
            return (EpollIoOps) ops;
        }
        throw new IllegalArgumentException("IoOps of type " + StringUtil.simpleClassName(ops) + " not supported");
    }

    /**
     * 打开 Epoll、Event 和 Timer 文件描述符。
     * 此方法旨在供进程检查点/恢复 (checkpoint/restore) 集成使用，例如 OpenJDK CRaC。
     * CRaC 允许在 JVM 运行时创建快照并在稍后恢复，这需要重新打开在快照创建时打开的文件描述符。
     */
    @UnstableApi
    public void openFileDescriptors() {
        boolean success = false;
        FileDescriptor epollFd = null;
        FileDescriptor eventFd = null;
        FileDescriptor timerFd = null;
        try {
            this.epollFd = epollFd = Native.newEpollCreate(); // 创建 epoll 实例
            this.eventFd = eventFd = Native.newEventFd();     // 创建 eventfd 实例 (用于唤醒)
            try {
                // 将 eventFd 添加到 epoll 实例中，监听 EPOLLIN 事件 (可读) 和 EPOLLET (边缘触发)
                // EPOLLET 很重要，因为我们只想在每次唤醒时收到一次通知，并且不调用 eventfd_read(...) 来消耗数据。
                Native.epollCtlAdd(epollFd.intValue(), eventFd.intValue(), Native.EPOLLIN | Native.EPOLLET);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to add eventFd filedescriptor to epoll", e);
            }
            this.timerFd = timerFd = Native.newTimerFd();     // 创建 timerfd 实例 (用于定时任务)
            try {
                // 将 timerFd 添加到 epoll 实例中，监听 EPOLLIN 事件 (可读) 和 EPOLLET (边缘触发)
                // EPOLLET 很重要，因为我们只想在每次定时器触发时收到一次通知，并且不调用 read(...) 来消耗数据。
                Native.epollCtlAdd(epollFd.intValue(), timerFd.intValue(), Native.EPOLLIN | Native.EPOLLET);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to add timerFd filedescriptor to epoll", e);
            }
            success = true;
        } finally {
            if (!success) { // 如果在打开过程中任何一步失败，则关闭已创建的文件描述符
                closeFileDescriptor(epollFd);
                closeFileDescriptor(eventFd);
                closeFileDescriptor(timerFd);
            }
        }
    }

    /**
     * 安全地关闭给定的文件描述符，忽略任何关闭期间可能发生的异常。
     * @param fd 要关闭的文件描述符。
     */
    private static void closeFileDescriptor(FileDescriptor fd) {
        if (fd != null) {
            try {
                fd.close();
            } catch (Exception e) {
                // 忽略关闭期间的异常
            }
        }
    }

    @Override
    public void wakeup() {
        // 如果调用者不是当前事件循环线程，并且 nextWakeupNanos 成功从非 AWAKE 原子地设置为 AWAKE
        // (这意味着事件循环之前可能处于休眠或等待定时器状态)
        if (!executor.isExecutorThread(Thread.currentThread()) && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            // 向 eventFd 写入一个 64 位整数 (1L)，这将唤醒 epoll_wait(...) 调用。
            Native.eventFdWrite(eventFd.intValue(), 1L);
        }
    }

    @Override
    public void prepareToDestroy() {
        // 使用中间集合以防止 ConcurrentModificationException。
        // 在 `close()` 方法中，channel 会从 `registrations` 映射中删除。
        DefaultEpollIoRegistration[] copy = registrations.values().toArray(new DefaultEpollIoRegistration[0]);

        for (DefaultEpollIoRegistration reg: copy) {
            reg.close(); // 关闭每个注册的 Channel/Handle
        }
    }

    @Override
    public void destroy() {
        try {
            closeFileDescriptors(); // 关闭 epollFd, eventFd, timerFd
        } finally {
            // 释放本地内存
            nativeArrays.free();
            events.free();
        }
    }

    /**
     * {@link IoRegistration} 的 Epoll 实现。
     * 每个 {@link EpollIoHandle} 注册到 {@link EpollIoHandler} 时，都会创建一个此类的实例。
     * 它封装了与特定 {@link EpollIoHandle} 相关的注册状态和操作。
     */
    private final class DefaultEpollIoRegistration implements IoRegistration {
        private final ThreadAwareExecutor executor; // 关联的执行器
        private final AtomicBoolean canceled = new AtomicBoolean(); // 标志此注册是否已取消
        final EpollIoHandle handle; // 关联的 EpollIoHandle

        DefaultEpollIoRegistration(ThreadAwareExecutor executor, EpollIoHandle handle) {
            this.executor = executor;
            this.handle = handle;
        }

        /**
         * 返回附加到此注册的对象。对于 Epoll，通常是 {@link NativeArrays} 实例，
         * 但实际上它在 Epoll 场景中似乎没有被 {@link EpollIoHandler} 直接用于数据传递。
         * @param <T> 附件的类型。
         * @return {@link NativeArrays} 实例。
         */
        @SuppressWarnings("unchecked")
        @Override
        public <T> T attachment() {
            return (T) nativeArrays;
        }

        /**
         * 提交 (更新) 此注册感兴趣的 I/O 操作。
         *
         * @param ops 新的感兴趣的 I/O 操作 (类型为 {@link EpollIoOps})。
         * @return {@link EpollIoOps#value} (整数形式的 epoll 事件掩码)，如果注册无效则返回 -1。
         * @throws UncheckedIOException 如果 {@code epoll_ctl_mod} 调用失败。
         */
        @Override
        public long submit(IoOps ops) {
            EpollIoOps epollIoOps = cast(ops);
            try {
                if (!isValid()) { // 如果注册已取消，则不执行任何操作
                    return -1;
                }
                // 修改 epoll 实例中 handle.fd() 的监听事件
                Native.epollCtlMod(epollFd.intValue(), handle.fd().intValue(), epollIoOps.value);
                return epollIoOps.value;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * 检查此注册是否仍然有效。
         * @return 如果未取消，则为 {@code true}；否则为 {@code false}。
         */
        @Override
        public boolean isValid() {
            return !canceled.get();
        }

        /**
         * 取消此注册。
         * 如果成功取消 (之前未取消)，则将从 epoll 实例中移除此 handle 的 fd。
         *
         * @return 如果成功取消，则为 {@code true}；如果已经取消，则为 {@code false}。
         */
        @Override
        public boolean cancel() {
            if (!canceled.compareAndSet(false, true)) { // 原子地设置 canceled 标志
                return false; // 已经取消
            }
            // 确保在事件循环线程中执行实际的取消操作
            if (executor.isExecutorThread(Thread.currentThread())) {
                cancel0();
            } else {
                executor.execute(this::cancel0);
            }
            return true;
        }

        /**
         * 实际执行取消操作的内部方法。
         * 从 {@link EpollIoHandler#registrations} 映射中移除此注册，
         * 并从 epoll 实例中删除文件描述符的监听。
         */
        private void cancel0() {
            int fd = handle.fd().intValue();
            DefaultEpollIoRegistration old = registrations.remove(fd); // 从映射中移除
            if (old != null) {
                if (old != this) {
                    // 如果 FD 被重用，映射可能已经被替换。将存储的 Channel 放回。
                    // 这种情况理论上不应该频繁发生，除非有 FD 重用的竞态条件。
                    registrations.put(fd, old);
                    return;
                } else if (old.handle instanceof AbstractEpollChannel.AbstractEpollUnsafe) {
                    // 如果是 Channel 的 Unsafe，递减 numChannels 计数
                    numChannels--;
                }
                if (handle.fd().isOpen()) { // 仅当文件描述符仍然打开时才从 epoll 中删除
                    try {
                        // 从 epoll 实例中删除 fd。
                        // 这只需要在 fd 仍然打开时进行，因为如果 fd 关闭，它会自动从 epoll 中移除。
                        Native.epollCtlDel(epollFd.intValue(), fd);
                    } catch (IOException e) {
                        // 通常可以忽略此异常。如果 fd 已经被关闭并从 epoll 中移除，epoll_ctl_del 会失败。
                        // 或者，如果尝试删除一个从未注册的 fd (理论上不应该发生)。
                        logger.debug("Unable to remove fd {} from epoll {}", fd, epollFd.intValue());
                    }
                }
            }
        }

        /**
         * 关闭此注册，包括取消注册并尝试关闭关联的 {@link EpollIoHandle}。
         */
        void close() {
            try {
                cancel(); // 首先取消注册
            } catch (Exception e) {
                logger.debug("Exception during canceling " + this, e);
            }
            try {
                handle.close(); // 然后关闭 EpollIoHandle
            } catch (Exception e) {
                logger.debug("Exception during closing " + handle, e);
            }
        }

        /**
         * 处理已就绪的 I/O 事件。
         * 此方法由 {@link EpollIoHandler#processReady(EpollEventArray, int)} 调用。
         *
         * @param ev 从 epoll_wait 返回的事件掩码。
         */
        void handle(long ev) {
            // 将就绪事件委托给 EpollIoHandle 进行处理
            handle.handle(this, EpollIoOps.eventOf((int) ev));
        }
    }

    @Override
    public IoRegistration register(IoHandle handle)
            throws Exception {
        final EpollIoHandle epollHandle = cast(handle);
        DefaultEpollIoRegistration registration = new DefaultEpollIoRegistration(executor, epollHandle);
        int fd = epollHandle.fd().intValue();
        // 将新的 fd 添加到 epoll 实例，初始时通常只关注 EPOLLERR (错误事件)。
        // 实际的读写事件监听会在后续的 submit() 调用中通过 epoll_ctl_mod 设置。
        // Netty 通常在注册后立即或稍后通过 ChannelPipeline 修改 interestOps。
        Native.epollCtlAdd(epollFd.intValue(), fd, EpollIoOps.EPOLLERR.value);
        DefaultEpollIoRegistration old = registrations.put(fd, registration);

        // 我们期望映射中要么没有相同 FD 的注册，要么旧注册的 FD 已经关闭。
        // 这是一个断言，用于捕捉 FD 被意外重用且旧注册未正确清理的情况。
        assert old == null || !old.isValid();

        // 如果 handle 是 Channel 的 Unsafe 实现，则增加 numChannels 计数。
        // 这用于跟踪实际的 Channel 数量，而不是所有类型的 EpollIoHandle。
        if (epollHandle instanceof AbstractEpollChannel.AbstractEpollUnsafe) {
            numChannels++;
        }
        return registration;
    }

    @Override
    public boolean isCompatible(Class<? extends IoHandle> handleType) {
        return EpollIoHandle.class.isAssignableFrom(handleType);
    }

    /**
     * 返回当前注册的 {@link Channel} 数量。
     * @return Channel 数量。
     */
    int numRegisteredChannels() {
        return numChannels;
    }

    /**
     * 返回所有已注册 {@link Channel} 的列表。
     * @return 不可修改的 Channel 列表。
     */
    List<Channel> registeredChannelsList() {
        IntObjectMap<DefaultEpollIoRegistration> ch = registrations;
        if (ch.isEmpty()) {
            return Collections.emptyList();
        }

        List<Channel> channels = new ArrayList<>(ch.size());
        for (DefaultEpollIoRegistration registration : ch.values()) {
            if (registration.handle instanceof AbstractEpollChannel.AbstractEpollUnsafe) {
                channels.add(((AbstractEpollChannel.AbstractEpollUnsafe) registration.handle).channel());
            }
        }
        return Collections.unmodifiableList(channels);
    }

    /**
     * 执行 {@code epoll_wait} 操作，并根据需要设置/解除 {@code timerFd}。
     *
     * @param context       {@link IoHandlerContext}，提供定时信息。
     * @param deadlineNanos epoll_wait 的截止时间（纳秒）。如果为 {@link #NONE}，则表示不使用 timerFd (无限等待或由 eventFd 唤醒)。
     * @return {@code epoll_wait} 的原始返回值，包含了就绪事件数量和 timerFd 是否被使用的信息。
     * @throws IOException 如果 {@code epoll_wait} 或 {@code timerfd_settime} 调用失败。
     */
    private long epollWait(IoHandlerContext context, long deadlineNanos) throws IOException {
        if (deadlineNanos == NONE) { // 如果没有计划的截止时间
            // 调用 epollWait，将 timerFd 的超时设置为最大值 (实际上是解除 timerFd)
            // timeoutSeconds 设置为 Integer.MAX_VALUE, timeoutNanos 设置为 0
            return Native.epollWait(epollFd, events, timerFd,
                    Integer.MAX_VALUE, 0, EPOLL_WAIT_MILLIS_THRESHOLD); // 解除定时器
        }
        // 计算距离截止时间还有多久
        long totalDelay = context.delayNanos(System.nanoTime());
        // 将总延迟转换为秒和纳秒，用于设置 timerFd
        int delaySeconds = (int) min(totalDelay / 1000000000L, Integer.MAX_VALUE);
        int delayNanos = (int) min(totalDelay - delaySeconds * 1000000000L, MAX_SCHEDULED_TIMERFD_NS);
        // 调用 epollWait，同时设置 timerFd
        return Native.epollWait(epollFd, events, timerFd, delaySeconds, delayNanos, EPOLL_WAIT_MILLIS_THRESHOLD);
    }

    /**
     * 执行 {@code epoll_wait} 操作，但不改变 {@code timerFd} 的当前状态。
     * 这用于当没有新的定时任务，并且当前的 {@code timerFd} 设置仍然有效时。
     *
     * @return {@code epoll_wait} 返回的就绪事件数量。
     * @throws IOException 如果 {@code epoll_wait} 调用失败。
     */
    private int epollWaitNoTimerChange() throws IOException {
        // 调用 epollWait，参数 useTimerNotChanged 设置为 false，表示不尝试修改 timerFd
        return Native.epollWait(epollFd, events, false);
    }

    /**
     * 执行一次非阻塞的 {@code epoll_wait} 操作 (等同于超时为0)。
     *
     * @return {@code epoll_wait} 返回的就绪事件数量。
     * @throws IOException 如果 {@code epoll_wait} 调用失败。
     */
    private int epollWaitNow() throws IOException {
        // 调用 epollWait，参数 isImmediate 设置为 true，表示立即返回 (非阻塞)
        return Native.epollWait(epollFd, events, true);
    }

    /**
     * 执行一次忙等待的 {@code epoll_wait} 操作。
     * 这通常意味着 epoll_wait 的超时参数会非常小，或者使用特殊标志进行轮询。
     * Netty 的 {@link Native#epollBusyWait} 内部可能使用非常短的超时来模拟忙等待。
     *
     * @return {@code epoll_wait} 返回的就绪事件数量。
     * @throws IOException 如果 {@code epoll_wait} 调用失败。
     */
    private int epollBusyWait() throws IOException {
        return Native.epollBusyWait(epollFd, events);
    }

    /**
     * 执行一次带有固定超时 (1秒) 的 {@code epoll_wait} 操作。
     * 这通常用作一种安全措施，例如在等待 {@code eventFd} 写入时，防止无限期阻塞。
     *
     * @return {@code epoll_wait} 返回的就绪事件数量。
     * @throws IOException 如果 {@code epoll_wait} 调用失败。
     */
    private int epollWaitTimeboxed() throws IOException {
        // 使用 1 秒的"保障性"超时进行等待
        return Native.epollWait(epollFd, events, 1000); // 1000ms 超时
    }

    @Override
    public int run(IoHandlerContext context) {
        int handled = 0; // 记录处理的事件数量
        try {
            // 1. 计算选择策略 (CONTINUE, BUSY_WAIT, SELECT)
            int strategy = selectStrategy.calculateStrategy(selectNowSupplier, !context.canBlock());
            switch (strategy) {
                case SelectStrategy.CONTINUE:
                    // 如果策略是 CONTINUE，表示当前不进行 select 操作，直接返回。
                    return 0;

                case SelectStrategy.BUSY_WAIT:
                    // 如果策略是 BUSY_WAIT，执行 epollBusyWait()。
                    strategy = epollBusyWait();
                    break; // BUSY_WAIT 后也会处理事件 (如果 strategy > 0)

                case SelectStrategy.SELECT:
                    // 如果策略是 SELECT，执行阻塞或带超时的 epoll_wait。
                    if (pendingWakeup) {
                        // 如果有挂起的唤醒请求 (通常是 eventFd 被写入)，
                        // 我们期望 epoll_wait 立即返回。使用带固定超时的 epollWaitTimeboxed()，
                        // 以防万一错过了 eventfd 写入事件 (例如，由于异常的系统调用失败)。
                        strategy = epollWaitTimeboxed(); // 使用1秒超时等待
                        if (strategy != 0) { // 如果有事件 (包括 eventFd 事件)，则跳出 SELECT 处理
                            break;
                        }
                        // 如果超时后仍然没有事件，则假设我们错过了 eventfd 写入。
                        logger.warn("Missed eventfd write (not seen after > 1 second)");
                        pendingWakeup = false; // 重置挂起唤醒标志
                        if (!context.canBlock()) { // 如果当前不能阻塞 (有其他任务)，则直接跳出
                            break;
                        }
                        // 否则，继续执行正常的 SELECT 逻辑 (fall-through)
                    }

                    // 获取当前的截止时间 (用于定时任务)
                    long curDeadlineNanos = context.deadlineNanos();
                    if (curDeadlineNanos == -1L) { // -1L 表示没有计划任务
                        curDeadlineNanos = NONE; // 使用 NONE (Long.MAX_VALUE)
                    }
                    // 设置 nextWakeupNanos，用于 wakeup() 方法判断是否需要实际写入 eventFd
                    nextWakeupNanos.set(curDeadlineNanos);
                    try {
                        if (context.canBlock()) { // 只有在可以阻塞时才执行可能阻塞的 epoll_wait
                            if (curDeadlineNanos == prevDeadlineNanos) {
                                // 如果当前的截止时间与上一次相同，说明 timerFd 不需要重新设置。
                                // 调用 epollWaitNoTimerChange()，它不会尝试修改 timerFd。
                                strategy = epollWaitNoTimerChange();
                            } else {
                                // 如果截止时间不同，说明 timerFd 需要重新武装 (arm) 或解除武装 (disarm)。
                                // 调用 epollWait()，它会根据 curDeadlineNanos 设置 timerFd。
                                long result = epollWait(context, curDeadlineNanos);
                                // epollWait 的返回值打包了实际的就绪事件数和 timerFd 是否被使用。
                                // 需要使用 Native 方法解包。
                                strategy = Native.epollReady(result); // 获取就绪事件数
                                // 更新 prevDeadlineNanos。如果 timerFd 被使用了，则设为 curDeadlineNanos，否则设为 NONE。
                                prevDeadlineNanos = Native.epollTimerWasUsed(result) ? curDeadlineNanos : NONE;
                            }
                        }
                        // 如果 context.canBlock() 为 false，则不会执行阻塞的 epoll_wait，
                        // strategy 会保持 calculateStrategy 的结果 (可能是 SELECT，但由于不能阻塞，实际上会像 epollWaitNow)。
                        // 或者，如果之前是 BUSY_WAIT，则 strategy 已经是 epollBusyWait 的结果。
                        // 这里缺少一个在 !context.canBlock() 情况下的 epollWaitNow() 调用，但 selectStrategy 应该已经处理了。
                    } finally {
                        // 确保在 epoll_wait 之后，如果 nextWakeupNanos 是 AWAKE (例如被 wakeup() 修改了)，
                        // 或者成功地从某个值原子地设置为 AWAKE，则设置 pendingWakeup = true。
                        // 这是为了确保如果 wakeup() 发生在 epoll_wait 期间，我们能正确处理。
                        // 先尝试 get() 是为了避免在已经被唤醒的情况下进行昂贵的 CAS 操作。
                        if (nextWakeupNanos.get() == AWAKE || nextWakeupNanos.getAndSet(AWAKE) == AWAKE) {
                            pendingWakeup = true;
                        }
                    }
                    // fallthrough 到 default 处理事件
                default:
                    // 如果 strategy > 0 (有事件就绪)
            }
            if (strategy > 0) {
                handled = strategy; // 记录处理的事件数
                // 处理就绪的事件。如果处理过程中遇到了 timerFd 事件，processReady 返回 true。
                if (processReady(events, strategy)) {
                    // 如果 timerFd 触发了，将 prevDeadlineNanos 设置为 NONE，
                    // 这样下一次循环就会强制重新评估和设置 timerFd (如果需要)。
                    prevDeadlineNanos = NONE;
                }
            }
            // 如果允许事件数组增长，并且本次返回的事件数达到了数组容量，则增加数组大小。
            if (allowGrowing && strategy == events.length()) {
                events.increase();
            }
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            handleLoopException(t);
        }
        return handled;
    }

    /**
     * 处理事件循环中的异常。仅用于测试！
     * @param t 异常。
     */
    void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // 防止可能的连续立即故障导致过度的 CPU 消耗。
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // 忽略。
        }
    }

    // 处理就绪的 epoll 事件。
    // 返回 true 如果遇到了 timerFd 事件。
    private boolean processReady(EpollEventArray events, int ready) {
        boolean timerFired = false; // 标志是否处理了 timerFd 事件
        for (int i = 0; i < ready; i ++) { // 遍历所有就绪的事件
            final int fd = events.fd(i); // 获取文件描述符
            if (fd == eventFd.intValue()) { // 如果是 eventFd 的事件
                pendingWakeup = false; // 重置挂起唤醒标志，因为我们已经处理了唤醒事件
            } else if (fd == timerFd.intValue()) { // 如果是 timerFd 的事件
                timerFired = true; // 标记 timerFd 已触发
            } else { // 其他文件描述符的事件 (通常是 Channel)
                final long ev = events.events(i); // 获取 epoll 事件掩码

                DefaultEpollIoRegistration registration = registrations.get(fd); // 根据 fd 查找注册信息
                if (registration != null) {
                    // 如果找到注册信息，则调用其 handle 方法处理事件
                    registration.handle(ev);
                } else {
                    // 如果没有找到注册信息 (例如，Channel 可能已经关闭并在处理此事件之前被移除)，
                    // 则尝试从 epoll 实例中删除此 fd，以清理悬空的监听。
                    try {
                        Native.epollCtlDel(epollFd.intValue(), fd);
                    } catch (IOException ignore) {
                        // 忽略删除失败。这可能发生，但无需担心，因为我们只是尝试清理。
                        // 如果 fd 在此之前已被删除或关闭，此调用可能会失败。
                    }
                }
            }
        }
        return timerFired;
    }

    /**
     * 关闭 {@code epollFd}、{@code eventFd} 和 {@code timerFd}。
     * 此方法旨在供进程检查点/恢复 (checkpoint/restore) 集成使用，例如 OpenJDK CRaC。
     * 调用者有责任确保在关闭这些 FD 时没有并发使用，例如通过阻塞执行器。
     */
    @UnstableApi
    public void closeFileDescriptors() {
        // 在关闭 eventFd 之前，确保所有正在进行的唤醒写入已完成。
        while (pendingWakeup) {
            try {
                int count = epollWaitTimeboxed(); // 使用带超时的 epoll_wait
                if (count == 0) {
                    // 如果超时，则假设我们期望的写入不会到来
                    break;
                }
                for (int i = 0; i < count; i++) {
                    if (events.fd(i) == eventFd.intValue()) { // 如果是 eventFd 事件
                        pendingWakeup = false; // 清除挂起唤醒标志
                        break;
                    }
                }
            } catch (IOException ignore) {
                // 忽略 IO 异常
            }
        }
        try {
            eventFd.close();
        } catch (IOException e) {
            logger.warn("Failed to close the event fd.", e);
        }
        try {
            timerFd.close();
        } catch (IOException e) {
            logger.warn("Failed to close the timer fd.", e);
        }

        try {
            epollFd.close();
        } catch (IOException e) {
            logger.warn("Failed to close the epoll fd.", e);
        }
    }
}

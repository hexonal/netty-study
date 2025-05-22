/*
 * Copyright 2024 The Netty Project
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
package io.netty.channel.uring;

import io.netty.buffer.Unpooled;
import io.netty.channel.IoHandlerContext;
import io.netty.channel.IoHandle;
import io.netty.channel.IoHandler;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.IoOps;
import io.netty.channel.IoRegistration;
import io.netty.channel.unix.Buffer;
import io.netty.channel.unix.Errors;
import io.netty.channel.unix.FileDescriptor;
import io.netty.channel.unix.IovArray;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.ThreadAwareExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

/**
 * {@link IoHandler} 的一个实现，它基于 Linux 特有的 {@code io_uring} API。
 * {@code io_uring} 是一种现代的异步 I/O 接口，旨在提供比 epoll 更高的性能和更灵活的功能。
 * 它通过共享内存环形缓冲区 (ring buffer) 在用户空间和内核空间之间传递 I/O 请求和完成事件，从而减少了系统调用开销。
 *
 * <p><b>核心组件与职责：</b></p>
 * <ul>
 *     <li><b>RingBuffer ({@link RingBuffer}):</b> 管理 io_uring 的核心数据结构，包括提交队列 (SQ - Submission Queue)
 *         和完成队列 (CQ - Completion Queue)。SQ 用于向内核提交 I/O 请求 (SQE - Submission Queue Entry)，
 *         CQ 用于从内核接收 I/O 完成事件 (CQE - Completion Queue Event)。</li>
 *     <li><b>EventFD ({@code eventfd}):</b> 一个事件文件描述符，用于线程间的唤醒机制。当其他线程需要唤醒阻塞在等待 I/O 完成
 *         的 io_uring 事件循环线程时，会向此 eventfd 写入数据，触发一个 CQE。</li>
 *     <li><b>Timeout Memory ({@code timeoutMemory}):</b> 一块直接内存，用于存储超时操作 (如 {@code IORING_OP_TIMEOUT} 或
 *         {@code IORING_OP_LINK_TIMEOUT}) 所需的 {@code __kernel_timespec} 结构。</li>
 *     <li><b>IovArray ({@link IovArray}):</b> 用于 scatter/gather I/O 操作 (如 readv/writev) 的 iovec 数组。</li>
 *     <li><b>CompletionBuffer ({@link CompletionBuffer}):</b> 一个内部缓冲区，用于暂存从 CQ 中获取的 CQE，以便进行批量处理。</li>
 *     <li><b>注册表 ({@code registrations}):</b> 将一个唯一的注册 ID 映射到 {@link DefaultIoUringIoRegistration}，
 *         后者封装了与特定 {@link IoUringIoHandle} (通常是 Channel) 相关的注册信息和状态。</li>
 *     <li><b>IoUringBufferRing ({@link IoUringBufferRing}):</b> (可选) 用于 io_uring 的一种高级特性，允许内核直接使用用户空间提供的
 *         一组预分配的缓冲区进行 I/O 操作 (IORING_OP_PROVIDE_BUFFERS, IORING_OP_READ_FIXED, IORING_OP_WRITE_FIXED)，
 *         进一步减少数据拷贝和提高效率。</li>
 *     <li><b>事件处理循环 ({@code run} 方法):</b>
 *         <ol>
 *             <li>如果 CQ 为空且可以阻塞，则提交一个 eventfd 读操作，并可能提交一个超时操作，然后调用
 *                {@code io_uring_enter} (通过 {@link SubmissionQueue#submitAndWait()} 或
 *                {@link #submitAndWaitWithTimeout(SubmissionQueue, boolean, long)}) 等待 I/O 完成或超时。</li>
 *             <li>否则，提交当前 SQ 中的所有 SQE。</li>
 *             <li>从 CQ 中提取所有可用的 CQE 到 {@link #completionBuffer}。</li>
 *             <li>处理 {@link #completionBuffer} 中的 CQE，并将结果分发给相应的 {@link IoUringIoHandle}。</li>
 *             <li>重复提交和处理，直到没有更多工作可做。</li>
 *         </ol>
 *     </li>
 * </ul>
 *
 * <p><b>与 Epoll/KQueue 的区别：</b></p>
 * <ul>
 *     <li><b>真正的异步：</b> io_uring 被设计为真正的异步接口，许多操作在提交后可以不依赖于用户线程的进一步干预直到完成，
 *         而 epoll/kqueue 仍然需要用户线程在事件就绪后执行实际的 I/O 操作 (如 read/write)。</li>
 *     <li><b>减少系统调用：</b> 通过批处理 SQE 和 CQE，以及共享内存环形缓冲区，io_uring 可以显著减少系统调用的次数。</li>
 *     <li><b>更丰富的功能：</b> io_uring 支持更广泛的异步操作，不仅仅是网络 I/O，还包括文件 I/O、定时器、信号等。</li>
 *     <li><b>缓冲区提供：</b> io_uring 允许应用程序向内核提供一组缓冲区，内核可以直接在这些缓冲区上进行 I/O，避免了数据在内核和用户空间之间的多次拷贝。</li>
 * </ul>
 *
 * <p><b>线程模型：</b></p>
 * 每个 {@link IoUringIoHandler} 通常由一个专用的 {@link ThreadAwareExecutor} (通常是 {@link IoUringEventLoop}) 驱动，
 * 所有 io_uring 操作和事件处理都在这个单线程中执行。
 *
 * @see RingBuffer
 * @see SubmissionQueue
 * @see CompletionQueue
 * @see Native (io_uring JNI 方法)
 * @see IoUringIoHandle
 * @see IoUringEventLoop
 * @see IoUringBufferRing
 */
public final class IoUringIoHandler implements IoHandler {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(IoUringIoHandler.class);

    /**
     * io_uring 的核心环形缓冲区，包含提交队列 (SQ) 和完成队列 (CQ)。
     */
    private final RingBuffer ringBuffer;
    /**
     * 存储已注册的 {@link IoUringBufferRing} 实例的映射。
     * 键是缓冲区组 ID (bgid)，值是 {@link IoUringBufferRing} 实例。
     * 用于支持 io_uring 的 provide buffers 特性。
     */
    private final IntObjectMap<IoUringBufferRing> registeredIoUringBufferRing;
    /**
     * 存储注册 ID 到其对应 {@link DefaultIoUringIoRegistration} 的映射。
     * 每个 {@link IoUringIoHandle} 在注册时会获得一个唯一的 ID。
     */
    private final IntObjectMap<DefaultIoUringIoRegistration> registrations;
    // InetAddress / Inet6Address 的最大字节数
    /**
     * 用于临时存储 IPv4 地址字节的数组。
     */
    private final byte[] inet4AddressArray = new byte[SockaddrIn.IPV4_ADDRESS_LENGTH];
    /**
     * 用于临时存储 IPv6 地址字节的数组。
     */
    private final byte[] inet6AddressArray = new byte[SockaddrIn.IPV6_ADDRESS_LENGTH];

    /**
     * 原子布尔值，用于控制 eventfd 的异步通知。
     * 如果为 true，表示一个唤醒事件已经被触发或即将被触发。
     */
    private final AtomicBoolean eventfdAsyncNotify = new AtomicBoolean();
    /**
     * 事件文件描述符，用于线程间唤醒。
     */
    private final FileDescriptor eventfd;
    /**
     * 用于从 eventfd 读取数据的直接缓冲区。
     */
    private final ByteBuffer eventfdReadBuf;
    /**
     * {@link #eventfdReadBuf} 的内存地址。
     */
    private final long eventfdReadBufAddress;
    /**
     * 用于存储 io_uring 超时操作 (如 IORING_OP_TIMEOUT) 所需的 {@code __kernel_timespec} 结构的直接内存。
     */
    private final ByteBuffer timeoutMemory;
    /**
     * {@link #timeoutMemory} 的内存地址。
     */
    private final long timeoutMemoryAddress;
    /**
     * 用于 scatter/gather I/O 操作 (如 readv/writev) 的 iovec 数组。
     */
    private final IovArray iovArray;
    /**
     * 记录提交给 io_uring 的 eventfd 读操作的用户数据 (udata)。
     * 如果不为0，表示当前有一个挂起的 eventfd 读操作。
     */
    private long eventfdReadSubmitted;
    /**
     * 标志 eventfd 是否正在关闭过程中。
     */
    private boolean eventFdClosing;
    /**
     * 标志此 {@link IoUringIoHandler} 是否正在关闭过程中。
     * 使用 volatile 以确保多线程可见性。
     */
    private volatile boolean shuttingDown;
    /**
     * 标志 io_uring 环是否已完全关闭并释放资源。
     */
    private boolean closeCompleted;
    /**
     * 用于生成唯一的注册 ID。
     */
    private int nextRegistrationId = Integer.MIN_VALUE;

    // 这两个 ID 在内部使用，因此不能用于 nextRegistrationId()。
    /**
     * eventfd 读操作在 io_uring 完成事件中使用的特殊 ID。
     */
    private static final int EVENTFD_ID = Integer.MAX_VALUE;
    /**
     * io_uring 内部操作 (如 NOP, TIMEOUT) 在完成事件中使用的特殊 ID。
     */
    private static final int RINGFD_ID = EVENTFD_ID - 1;
    /**
     * 表示无效的注册 ID 或操作用户数据。
     */
    private static final int INVALID_ID = 0;

    /**
     * 内核中 {@code __kernel_timespec} 结构的大小 (字节)。
     */
    private static final int KERNEL_TIMESPEC_SIZE = 16; //__kernel_timespec

    /**
     * {@code __kernel_timespec} 结构中 {@code tv_sec} (秒) 字段的偏移量。
     */
    private static final int KERNEL_TIMESPEC_TV_SEC_FIELD = 0;
    /**
     * {@code __kernel_timespec} 结构中 {@code tv_nsec} (纳秒) 字段的偏移量。
     */
    private static final int KERNEL_TIMESPEC_TV_NSEC_FIELD = 8;

    /**
     * 用于暂存从完成队列 (CQ) 中获取的完成事件 (CQE) 的缓冲区。
     * 目的是进行批量处理，而不是逐个处理 CQE。
     */
    private final CompletionBuffer completionBuffer;
    /**
     * 驱动此 {@link IoUringIoHandler} 的执行器，通常是 {@link IoUringEventLoop}。
     */
    private final ThreadAwareExecutor executor;

    IoUringIoHandler(ThreadAwareExecutor executor, IoUringIoHandlerConfig config) {
        // 确保在 IovArray 中尝试使用本地方法之前已加载所有本地位
        IoUring.ensureAvailability();
        this.executor = requireNonNull(executor, "executor");
        requireNonNull(config, "config");
        int setupFlags = Native.setupFlags(); // 获取特定于平台的 io_uring 设置标志

        // 默认的 CQ 大小始终是 ringSize 的两倍。
        // 仅当用户实际指定 CQ 环大小时才有意义。
        int cqSize = 2 * config.getRingSize();
        if (config.needSetupCqeSize()) { // 如果用户配置了自定义 CQ 大小
            if (!IoUring.isSetupCqeSizeSupported()) { // 检查内核是否支持 IORING_SETUP_CQSIZE
                throw new UnsupportedOperationException("IORING_SETUP_CQSIZE is not supported");
            }
            setupFlags |= Native.IORING_SETUP_CQSIZE; // 添加标志以使用自定义 CQ 大小
            cqSize = config.checkCqSize(config.getCqSize()); // 校验并获取用户配置的 CQ 大小
        }
        // 创建 io_uring 环形缓冲区 (包括 SQ 和 CQ)
        this.ringBuffer = Native.createRingBuffer(config.getRingSize(), cqSize, setupFlags);
        // 如果内核支持并且用户配置了 I/O 轮询工作线程的最大数量
        if (IoUring.isRegisterIowqMaxWorkersSupported() && config.needRegisterIowqMaxWorker()) {
            int maxBoundedWorker = Math.max(config.getMaxBoundedWorker(), 0);
            int maxUnboundedWorker = Math.max(config.getMaxUnboundedWorker(), 0);
            // 注册 I/O 轮询工作线程的最大数量
            int result = Native.ioUringRegisterIoWqMaxWorkers(ringBuffer.fd(), maxBoundedWorker, maxUnboundedWorker);
            if (result < 0) { // 如果注册失败
                // 在抛出异常前关闭 ringBuffer 以确保释放所有内存。
                ringBuffer.close();
                throw new UncheckedIOException(Errors.newIOException("io_uring_register", result));
            }
        }

        registeredIoUringBufferRing = new IntObjectHashMap<>();
        Collection<IoUringBufferRingConfig> bufferRingConfigs = config.getInternBufferRingConfigs();
        // 如果配置了内部缓冲区环 (用于 provide buffers 特性)
        if (bufferRingConfigs != null && !bufferRingConfigs.isEmpty()) {
            if (!IoUring.isRegisterBufferRingSupported()) { // 检查内核是否支持 IORING_REGISTER_PBUF_RING
                // 在抛出异常前关闭 ringBuffer。
                ringBuffer.close();
                throw new UnsupportedOperationException("IORING_REGISTER_PBUF_RING is not supported");
            }
            for (IoUringBufferRingConfig bufferRingConfig : bufferRingConfigs) {
                try {
                    // 为每个配置创建一个新的 IoUringBufferRing
                    IoUringBufferRing ring = newBufferRing(ringBuffer.fd(), bufferRingConfig);
                    registeredIoUringBufferRing.put(bufferRingConfig.bufferGroupId(), ring);
                } catch (Errors.NativeIoException e) {
                    // 如果创建任何一个 bufferRing 失败，则关闭所有已创建的 bufferRing 和 ringBuffer。
                    for (IoUringBufferRing bufferRing : registeredIoUringBufferRing.values()) {
                        bufferRing.close();
                    }
                    ringBuffer.close();
                    throw new UncheckedIOException(e);
                }
            }
        }

        registrations = new IntObjectHashMap<>();
        eventfd = Native.newBlockingEventFd(); // 创建用于唤醒的 eventfd
        // 为 eventfd 读取分配直接缓冲区
        eventfdReadBuf = Buffer.allocateDirectWithNativeOrder(Long.BYTES);
        eventfdReadBufAddress = Buffer.memoryAddress(eventfdReadBuf);
        // 为超时操作分配直接内存 (__kernel_timespec)
        this.timeoutMemory = Buffer.allocateDirectWithNativeOrder(KERNEL_TIMESPEC_SIZE);
        this.timeoutMemoryAddress = Buffer.memoryAddress(timeoutMemory);
        // 我们在批量处理 CQE 之前，最多缓冲 2 * CompletionQueue.ringCapacity 个完成事件。
        // 由于我们从不提交 udata 为 0L 的操作，因此将其用作标记。
        // 初始化 CompletionBuffer，用于暂存和处理 CQE。
        completionBuffer = new CompletionBuffer(ringBuffer.ioUringCompletionQueue().ringCapacity * 2, 0);

        // 初始化 IovArray，用于 scatter/gather 操作。
        iovArray = new IovArray(Unpooled.wrappedBuffer(
                Buffer.allocateDirectWithNativeOrder(IoUring.NUM_ELEMENTS_IOVEC * IovArray.IOV_SIZE))
                .setIndex(0, 0));
    }

    @Override
    public void initialize() {
        ringBuffer.enable(); // 启用 io_uring 环 (如果需要，例如带有 IORING_SETUP_R_DISABLED 标志创建时)
        // 立即填充所有已注册的缓冲区环。
        for (IoUringBufferRing bufferRing : registeredIoUringBufferRing.values()) {
            bufferRing.initialize();
        }
    }

    @Override
    public int run(IoHandlerContext context) {
        if (closeCompleted) { // 如果环已关闭，则不执行任何操作
            return 0;
        }
        int processedPerRun = 0; // 记录本轮处理的 CQE 数量
        SubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();
        CompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();

        // 如果完成队列 (CQ) 为空，并且事件循环允许阻塞
        if (!completionQueue.hasCompletions() && context.canBlock()) {
            if (eventfdReadSubmitted == 0) { // 如果当前没有挂起的 eventfd 读操作
                submitEventFdRead(); // 提交一个新的 eventfd 读操作，以便可以被唤醒
            }
            // 计算超时时间：如果 context.deadlineNanos() 为 -1 (无计划任务)，则超时为 -1 (无限等待)；
            // 否则，计算距离下一个计划任务的延迟时间。
            long timeoutNanos = context.deadlineNanos() == -1 ? -1 : context.delayNanos(System.nanoTime());
            // 提交 SQ 中的所有请求，并等待 CQE 或超时。
            // linkTimeout = false 表示超时操作是独立的，不与 SQ 中的最后一个操作链接。
            submitAndWaitWithTimeout(submissionQueue, false, timeoutNanos);
        } else {
            // 如果 CQ 不为空或不允许阻塞，则只提交 SQ 中的请求，不等待。
            submitAndClear(submissionQueue);
        }

        for (;;) { // 循环处理，直到没有更多工作
            // 从 CQ 中提取所有可用的 CQE 到 completionBuffer，并处理它们。
            // this::handle 是处理单个 CQE 的回调方法。
            // 我们可能会在 completionArray 中处理内容时调用 submitAndRunNow()，需要将处理的完成事件数加到 processedPerRun。
            int processed = drainAndProcessAll(completionQueue, this::handle);
            processedPerRun += processed;

            // 再次尝试提交 SQ 中的请求。
            // 如果无法提交任何内容 (SQ 为空或已满)，并且 completionBuffer 中也没有剩余的 CQE，则跳出循环。
            if (submitAndClear(submissionQueue) == 0 && processed == 0) {
                break;
            }
        }

        return processedPerRun;
    }

    /**
     * 提交当前 {@link SubmissionQueue} 中的所有挂起的 SQE，并立即处理由指定 {@code udata} 标识的完成事件 (如果已完成)。
     * 此方法用于需要同步等待特定操作完成的场景。
     *
     * @param udata 要立即处理其完成事件的操作的用户数据。
     */
    void submitAndRunNow(long udata) {
        if (closeCompleted) {
            return;
        }
        SubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();
        CompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();
        if (submitAndClear(submissionQueue) > 0) { // 如果成功提交了任何 SQE
            completionBuffer.drain(completionQueue); // 从 CQ 中提取所有 CQE 到 completionBuffer
            // 立即处理 completionBuffer 中与指定 udata 匹配的那个 CQE
            completionBuffer.processOneNow(this::handle, udata);
        }
    }

    /**
     * 提交 {@link SubmissionQueue} 中的所有 SQE，并清除 {@link #iovArray} 以便重用。
     * @param submissionQueue 提交队列。
     * @return 成功提交的 SQE 数量。
     */
    private int submitAndClear(SubmissionQueue submissionQueue) {
        int submitted = submissionQueue.submit(); // 提交 SQ 中的所有 SQE

        // 清除 iovArray，因为在提交后，其中的内容被认为是"稳定的"，可以重用 iovArray。
        // 参考: https://man7.org/linux/man-pages/man3/io_uring_prep_sendmsg.3.html
        iovArray.clear();
        return submitted;
    }

    /**
     * 创建并注册一个新的 {@link IoUringBufferRing} (用于 io_uring 的 provide buffers 特性)。
     *
     * @param ringFd           io_uring 实例的文件描述符。
     * @param bufferRingConfig {@link IoUringBufferRing} 的配置。
     * @return 创建并初始化的 {@link IoUringBufferRing}。
     * @throws Errors.NativeIoException 如果注册 buffer ring 失败。
     */
    private static IoUringBufferRing newBufferRing(int ringFd, IoUringBufferRingConfig bufferRingConfig)
            throws Errors.NativeIoException {
        short bufferRingSize = bufferRingConfig.bufferRingSize(); // 缓冲区环的大小 (条目数)
        short bufferGroupId = bufferRingConfig.bufferGroupId();   // 缓冲区组 ID
        // 如果配置为增量式，则设置 IOU_PBUF_RING_INC 标志
        int flags = bufferRingConfig.isIncremental() ? Native.IOU_PBUF_RING_INC : 0;
        // 调用 JNI 方法注册缓冲区环
        long ioUringBufRingAddr = Native.ioUringRegisterBufRing(ringFd, bufferRingSize, bufferGroupId, flags);
        if (ioUringBufRingAddr < 0) { // 如果注册失败
            throw Errors.newIOException("ioUringRegisterBufRing", (int) ioUringBufRingAddr);
        }
        // 创建 IoUringBufferRing 实例，包装注册后获得的内存地址
        return new IoUringBufferRing(ringFd,
                Buffer.wrapMemoryAddressWithNativeOrder(ioUringBufRingAddr, Native.ioUringBufRingSize(bufferRingSize)),
                bufferRingSize, bufferRingConfig.batchSize(), bufferRingConfig.maxUnreleasedBuffers(),
                bufferGroupId, bufferRingConfig.isIncremental(), bufferRingConfig.allocator()
        );
    }

    /**
     * 根据缓冲区组 ID (bgid) 查找已注册的 {@link IoUringBufferRing}。
     *
     * @param bgId 缓冲区组 ID。
     * @return 对应的 {@link IoUringBufferRing}。
     * @throws IllegalArgumentException 如果找不到具有指定 bgId 的 buffer ring。
     */
    IoUringBufferRing findBufferRing(short bgId) {
        IoUringBufferRing cached = registeredIoUringBufferRing.get(bgId);
        if (cached != null) {
            return cached;
        }
        throw new IllegalArgumentException(
                String.format("Cant find bgId:%d, please register it in ioUringIoHandler", bgId)
        );
    }

    /**
     * 从完成队列 (CQ) 中提取所有可用的完成事件 (CQE) 到 {@link #completionBuffer}，并处理它们。
     * 会持续执行此操作，直到 CQ 中没有更多 CQE 且 {@link #completionBuffer} 也被处理完毕。
     *
     * @param completionQueue 完成队列。
     * @param callback        处理单个 CQE 的回调函数。
     * @return 本次调用总共处理的 CQE 数量。
     */
    private int drainAndProcessAll(CompletionQueue completionQueue, CompletionCallback callback) {
        int processed = 0;
        for (;;) {
            // 从 CQ 中提取 CQE 到 completionBuffer。如果 drainedAll 为 true，表示 CQ 已空。
            boolean drainedAll = completionBuffer.drain(completionQueue);
            // 处理 completionBuffer 中当前所有的 CQE。
            processed += completionBuffer.processNow(callback);
            if (drainedAll) { // 如果 CQ 和 completionBuffer 都已处理完毕，则退出循环。
                break;
            }
        }
        return processed;
    }

    /**
     * 处理在事件循环中发生的未捕获异常。
     * 记录警告日志，并使当前线程休眠一小段时间，以防止因连续快速失败而导致 CPU 过度消耗。
     *
     * @param throwable 捕获到的异常。
     */
    private static void handleLoopException(Throwable throwable) {
        logger.warn("Unexpected exception in the IO event loop.", throwable);

        // 防止可能的连续立即失败导致过度的 CPU 消耗。
        try {
            Thread.sleep(100); // 休眠100毫秒
        } catch (InterruptedException ignore) {
            // 忽略中断异常。
        }
    }

    /**
     * 处理单个完成事件 (CQE)。
     * 此方法作为回调传递给 {@link CompletionBuffer#processNow(CompletionCallback)} 等方法。
     *
     * @param res    CQE 的结果字段 (通常是 I/O 操作的返回值，如字节数或错误码)。
     * @param flags  CQE 的标志字段。
     * @param udata  与此 CQE 关联的用户数据 (通常由 {@link UserData}编码)。
     * @return 总是返回 {@code true}，表示回调已处理此 CQE。
     */
    private boolean handle(int res, int flags, long udata) {
        try {
            // 从用户数据 (udata) 中解码出注册 ID, 操作码 (op), 和附加数据 (data)。
            int id = UserData.decodeId(udata);
            byte op = UserData.decodeOp(udata);
            short data = UserData.decodeData(udata);

            if (logger.isTraceEnabled()) {
                logger.trace("completed(ring {}): {}(id={}, res={})",
                        ringBuffer.fd(), Native.opToStr(op), data, res);
            }
            if (id == EVENTFD_ID) { // 如果是 eventfd 读操作的完成事件
                handleEventFdRead();
                return true;
            }
            if (id == RINGFD_ID) { // 如果是 io_uring 内部操作 (如 NOP, TIMEOUT) 的完成事件
                // 直接返回，通常这些操作不需要特定的处理逻辑，它们的存在本身就是一种信号。
                return true;
            }
            // 根据 ID 查找对应的注册信息
            DefaultIoUringIoRegistration registration = registrations.get(id);
            if (registration == null) { // 如果找不到注册信息 (例如，Channel 可能已关闭)
                logger.debug("ignoring {} completion for unknown registration (id={}, res={})",
                        Native.opToStr(op), id, res);
                return true;
            }
            // 调用注册信息的 handle 方法处理此 CQE
            registration.handle(res, flags, op, data);
            return true;
        } catch (Error e) {
            throw e;
        } catch (Throwable throwable) {
            handleLoopException(throwable);
            return true;
        }
    }

    /**
     * 处理 eventfd 读操作的完成事件。
     * 重置 {@link #eventfdReadSubmitted} 和 {@link #eventfdAsyncNotify} 状态，
     * 并如果 eventfd 没有正在关闭，则重新提交一个新的 eventfd 读操作以监听下一次唤醒。
     */
    private void handleEventFdRead() {
        eventfdReadSubmitted = 0; // 表示之前的 eventfd 读操作已完成
        if (!eventFdClosing) { // 如果 eventfd 没有正在关闭
            eventfdAsyncNotify.set(false); // 重置异步通知标志
            submitEventFdRead(); // 重新提交 eventfd 读操作
        }
    }

    /**
     * 向 io_uring 提交一个读取 eventfd 的操作。
     * 这个操作用于在事件循环阻塞时，可以被其他线程通过写入 eventfd 来唤醒。
     */
    private void submitEventFdRead() {
        SubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();
        // 为 eventfd 读操作编码用户数据，使用特殊的 EVENTFD_ID
        long udata = UserData.encode(EVENTFD_ID, Native.IORING_OP_READ, (short) 0);

        // 向提交队列 (SQ) 添加一个读取 eventfd 的 SQE (Submission Queue Entry)。
        // 当 eventfd 被写入时，此读操作会完成，生成一个 CQE，从而唤醒事件循环。
        eventfdReadSubmitted = submissionQueue.addEventFdRead(
                eventfd.intValue(), eventfdReadBufAddress, 0, 8, udata);
    }

    /**
     * 提交当前 {@link SubmissionQueue} 中的所有挂起的 SQE，并等待指定的超时时间。
     * 如果 {@code timeoutNanoSeconds} 不为 -1，则会向 SQ 中添加一个超时操作。
     *
     * @param submissionQueue    提交队列。
     * @param linkTimeout        如果为 true，则超时操作将与 SQ 中的上一个操作链接 (IORING_OP_LINK_TIMEOUT)；
     *                           否则，超时操作是独立的 (IORING_OP_TIMEOUT)。
     * @param timeoutNanoSeconds 等待的超时时间 (纳秒)。如果为 -1，则无限等待 (除非被其他事件唤醒)。
     * @return 成功提交的 SQE 数量。
     */
    private int submitAndWaitWithTimeout(SubmissionQueue submissionQueue,
                                         boolean linkTimeout, long timeoutNanoSeconds) {
        if (timeoutNanoSeconds != -1) { // 如果指定了超时时间
            // 为超时操作编码用户数据，使用特殊的 RINGFD_ID
            long udata = UserData.encode(RINGFD_ID,
                    linkTimeout ? Native.IORING_OP_LINK_TIMEOUT : Native.IORING_OP_TIMEOUT, (short) 0);
            // 我们对所有 add*Timeout 操作使用相同的 timespec 指针。这只有在之后立即调用 submit 才有效。
            // 这确保了提交的超时被认为是"稳定的"，因此可以重用。
            long seconds, nanoSeconds;
            if (timeoutNanoSeconds == 0) { // 如果超时为0 (立即超时)
                seconds = 0;
                nanoSeconds = 0;
            } else {
                seconds = (int) min(timeoutNanoSeconds / 1000000000L, Integer.MAX_VALUE);
                nanoSeconds = (int) max(timeoutNanoSeconds - seconds * 1000000000L, 0);
            }

            // 将超时值写入到为超时操作准备的直接内存中 (__kernel_timespec 结构)
            timeoutMemory.putLong(KERNEL_TIMESPEC_TV_SEC_FIELD, seconds);
            timeoutMemory.putLong(KERNEL_TIMESPEC_TV_NSEC_FIELD, nanoSeconds);

            if (linkTimeout) { // 如果是链接超时
                submissionQueue.addLinkTimeout(timeoutMemoryAddress, udata);
            } else { // 独立超时
                submissionQueue.addTimeout(timeoutMemoryAddress, udata);
            }
        }
        // 提交 SQ 中的所有 SQE，并等待至少一个 CQE 出现 (或超时)。
        int submitted = submissionQueue.submitAndWait();
        // 清除 iovArray 以便重用。
        iovArray.clear();
        return submitted;
    }

    @Override
    public void prepareToDestroy() {
        shuttingDown = true; // 标记正在关闭
        CompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();
        SubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();

        // 创建注册列表的副本，以避免在迭代时修改原始列表 (因为 close() 可能会移除注册)
        List<DefaultIoUringIoRegistration> copy = new ArrayList<>(registrations.values());

        for (DefaultIoUringIoRegistration registration: copy) {
            registration.close(); // 关闭每个注册的 Handle (进而取消注册)
        }

        // 向 eventfd 写入数据，以确保如果之前提交了 eventfd 读操作，我们会看到其完成事件。
        Native.eventFdWrite(eventfd.intValue(), 1L);

        // 确保在销毁所有内容之前，所有先前提交的 IO 操作都已完成。
        // 提交一个带有 IOSQE_IO_DRAIN 标志的 NOP 操作，它会等待所有先前提交的操作完成后才生成 CQE。
        long udata = UserData.encode(RINGFD_ID, Native.IORING_OP_NOP, (short) 0);
        submissionQueue.addNop((byte) Native.IOSQE_IO_DRAIN, udata);

        // 提交所有挂起的 SQE 并等待它们完成。
        submissionQueue.submitAndWait();
        while (completionQueue.hasCompletions()) { // 处理所有完成的事件
            completionQueue.process(this::handle);

            if (submissionQueue.count() > 0) { // 如果在处理过程中有新的 SQE 被添加，则再次提交
                submissionQueue.submit();
            }
        }
    }

    @Override
    public void destroy() {
        SubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();
        CompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();
        drainEventFd(); // 确保 eventfd 被正确处理和关闭
        if (submissionQueue.remaining() < 2) {
            // 我们需要提交2个链接的操作。由于它们是链接的，我们不能允许一个 submit 调用将它们分开。
            // 如果队列中没有足够的空间 (< 2)，我们现在就提交以腾出更多空间。
            submissionQueue.submit();
        }
        // 尝试首先从队列中排出所有IO...
        long udata = UserData.encode(RINGFD_ID, Native.IORING_OP_NOP, (short) 0);
        // 我们还需要指定 Native.IOSQE_LINK 标志才能使其工作，否则它不会与超时正确链接。
        // 参考:
        // - https://man7.org/linux/man-pages/man2/io_uring_enter.2.html
        // - https://git.kernel.dk/cgit/liburing/commit/?h=link-timeout&id=bc1bd5e97e2c758d6fd975bd35843b9b2c770c5a
        // 提交一个带有 IOSQE_IO_DRAIN 和 IOSQE_LINK 标志的 NOP 操作，并链接一个超时操作。
        submissionQueue.addNop((byte) (Native.IOSQE_IO_DRAIN | Native.IOSQE_LINK), udata);
        // ... 但在此操作上只等待 200 毫秒。
        submitAndWaitWithTimeout(submissionQueue, true, TimeUnit.MILLISECONDS.toNanos(200));
        completionQueue.process(this::handle); // 处理可能已完成的事件

        // 关闭所有已注册的 IoUringBufferRing
        for (IoUringBufferRing ioUringBufferRing : registeredIoUringBufferRing.values()) {
            ioUringBufferRing.close();
        }
        completeRingClose(); // 完成 io_uring 环的关闭和资源释放
    }

    // 我们需要防止出现唤醒事件提交到一个已经被释放（并可能被操作系统重新分配）的文件描述符的竞争条件。
    // 由于提交的事件受 `eventfdAsyncNotify` 标志的控制，我们可以关闭这个门，但可能需要读取任何已经（或将要）写入的未完成事件。
    /**
     * 排空 eventfd 中的所有挂起事件，并确保在关闭前不再有新的事件被写入。
     * 这是为了处理在关闭 eventfd 时可能存在的竞争条件。
     */
    private void drainEventFd() {
        CompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();
        SubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();
        assert !eventFdClosing; // 断言 eventfd 尚未开始关闭
        eventFdClosing = true; // 标记 eventfd 正在关闭
        // 原子地设置 eventfdAsyncNotify 为 true，并获取其旧值。
        // 这可以阻止新的 wakeup() 调用触发 eventfd 写入。
        boolean eventPending = eventfdAsyncNotify.getAndSet(true);
        if (eventPending) {
            // 如果 eventPending 为 true，表示之前有其他线程已经或即将写入 eventfd，我们需要等待并处理这个事件。
            // 确保我们确实在监听对 eventfd 的写入。
            while (eventfdReadSubmitted == 0) { // 如果没有挂起的 eventfd 读操作
                submitEventFdRead(); // 提交一个新的读操作
                submissionQueue.submit(); // 立即提交，因为我们需要它来捕获挂起的写入
            }
            // 定义一个回调来排空 eventfd 的挂起唤醒事件。
            class DrainFdEventCallback implements CompletionCallback {
                boolean eventFdDrained = false; // 标志是否已处理了预期的 eventfd 完成事件

                @Override
                public boolean handle(int res, int flags, long udata) {
                    if (UserData.decodeId(udata) == EVENTFD_ID) { // 如果是 eventfd 的完成事件
                        eventFdDrained = true;
                    }
                    return IoUringIoHandler.this.handle(res, flags, udata); // 调用外部类的 handle 方法
                }
            }
            final DrainFdEventCallback handler = new DrainFdEventCallback();
            // 持续从 CQ 中提取并处理事件，直到预期的 eventfd 事件被处理。
            drainAndProcessAll(completionQueue, handler);
            completionQueue.process(handler); // 再处理一次，以防有 CQE 在 drainAndProcessAll 后到达
            while (!handler.eventFdDrained) { // 如果预期的 eventfd 事件尚未处理
                submissionQueue.submitAndWait(); // 阻塞等待更多事件
                drainAndProcessAll(completionQueue, handler); // 再次处理
            }
        }
        // 我们已经消耗了所有挂起的 eventfd 读事件，并且 `eventfdAsyncNotify` 应该永远不会转换回 false，
        // 因此我们不应该再有任何事件被写入。
        // 所以，如果我们有一个挂起的读事件，我们可以取消它。
        if (eventfdReadSubmitted != 0) { // 如果仍然有一个挂起的 eventfd 读操作
            // 提交一个异步取消操作来取消它。
            long udata = UserData.encode(EVENTFD_ID, Native.IORING_OP_ASYNC_CANCEL, (short) 0);
            submissionQueue.addCancel(eventfdReadSubmitted, udata);
            eventfdReadSubmitted = 0; // 重置标志
            submissionQueue.submit(); // 提交取消操作
        }
    }

    /**
     * 完成 io_uring 环的关闭过程，释放所有相关资源。
     * 确保只执行一次。
     */
    private void completeRingClose() {
        if (closeCompleted) {
            // 已经完成。
            return;
        }
        closeCompleted = true;
        ringBuffer.close(); // 关闭并释放在 RingBuffer 中分配的本地内存
        try {
            eventfd.close(); // 关闭 eventfd 文件描述符
        } catch (IOException e) {
            logger.warn("Failed to close eventfd", e);
        }
        Buffer.free(eventfdReadBuf); // 释放为 eventfd 读取分配的直接缓冲区
        Buffer.free(timeoutMemory);  // 释放为超时操作分配的直接内存
        iovArray.release();          // 释放 IovArray 使用的内存
    }

    @Override
    public IoRegistration register(IoHandle handle) throws Exception {
        IoUringIoHandle ioHandle = cast(handle); // 确保是 IoUringIoHandle 类型
        if (shuttingDown) { // 如果 Handler 正在关闭，则不允许新的注册
            throw new IllegalStateException("IoUringIoHandler is shutting down");
        }
        DefaultIoUringIoRegistration registration = new DefaultIoUringIoRegistration(executor, ioHandle);
        for (;;) { // 循环直到成功分配一个唯一的 ID
            int id = nextRegistrationId(); // 获取下一个可用的注册 ID
            DefaultIoUringIoRegistration old = registrations.put(id, registration);
            if (old != null) { // 如果此 ID 已被占用 (理论上不太可能，除非 ID 空间非常小或存在 bug)
                assert old.handle != registration.handle; // 断言不是同一个 handle
                registrations.put(id, old); // 将旧的注册放回，然后重试获取新 ID
            } else {
                registration.setId(id); // 设置注册 ID
                break; // 成功分配 ID，退出循环
            }
        }

        return registration;
    }

    /**
     * 生成下一个可用的注册 ID。
     * 跳过内部保留的 ID (RINGFD_ID, EVENTFD_ID, INVALID_ID)。
     * @return 一个唯一的注册 ID。
     */
    private int nextRegistrationId() {
        int id;
        do {
            id = nextRegistrationId++;
        } while (id == RINGFD_ID || id == EVENTFD_ID || id == INVALID_ID);
        return id;
    }

    /**
     * {@link IoRegistration} 的 io_uring 实现。
     * 封装了与特定 {@link IoUringIoHandle} 相关的注册状态、操作提交和完成事件处理逻辑。
     */
    private final class DefaultIoUringIoRegistration implements IoRegistration {
        private final AtomicBoolean canceled = new AtomicBoolean(); // 标志此注册是否已取消
        private final ThreadAwareExecutor executor; // 关联的执行器
        // 可重用的 IoUringIoEvent 对象，用于向 handle 传递完成事件信息，避免重复创建对象。
        private final IoUringIoEvent event = new IoUringIoEvent(0, 0, (byte) 0, (short) 0);
        final IoUringIoHandle handle; // 关联的 IoUringIoHandle

        private boolean removeLater; // 标志是否需要在所有未完成的操作完成后移除此注册
        private int outstandingCompletions; // 当前为此注册提交但尚未完成的操作数量
        private int id; // 此注册的唯一 ID

        DefaultIoUringIoRegistration(ThreadAwareExecutor executor, IoUringIoHandle handle) {
            this.executor = executor;
            this.handle = handle;
        }

        /**
         * 设置此注册的唯一 ID。
         * @param id 唯一 ID。
         */
        void setId(int id) {
            this.id = id;
        }

        /**
         * 提交一个 I/O 操作到 io_uring。
         * 将 {@link IoUringIoOps} 转换为 io_uring 的 SQE (Submission Queue Entry) 并添加到提交队列。
         *
         * @param ops 要提交的 I/O 操作，必须是 {@link IoUringIoOps} 类型。
         * @return 操作的用户数据 (udata)，用于在完成事件中识别此操作。如果注册无效，则返回 {@link #INVALID_ID}。
         * @throws IllegalArgumentException 如果 ops 不是 {@link IoUringIoOps} 类型，或者包含不支持的标志 (如 IOSQE_CQE_SKIP_SUCCESS)。
         */
        @Override
        public long submit(IoOps ops) {
            IoUringIoOps ioOps = (IoUringIoOps) ops;
            if (!isValid()) { // 如果注册已取消
                return INVALID_ID;
            }
            // 由于我们期望每个提交至少有一个完成事件 (用于 outstandingCompletions 计数)，
            // 所以不支持 IOSQE_CQE_SKIP_SUCCESS 标志 (它只在失败时产生完成事件)。
            if ((ioOps.flags() & Native.IOSQE_CQE_SKIP_SUCCESS) != 0) {
                throw new IllegalArgumentException("IOSQE_CQE_SKIP_SUCCESS not supported");
            }
            // 为此操作编码用户数据，包含注册 ID、操作码和附加数据。
            long udata = UserData.encode(id, ioOps.opcode(), ioOps.data());
            // 确保在事件循环线程中提交 SQE
            if (executor.isExecutorThread(Thread.currentThread())) {
                submit0(ioOps, udata);
            } else {
                executor.execute(() -> submit0(ioOps, udata));
            }
            return udata;
        }

        /**
         * 实际将 SQE 添加到 io_uring 提交队列的内部方法。
         * @param ioOps 描述 I/O 操作的 {@link IoUringIoOps}。
         * @param udata 与操作关联的用户数据。
         */
        private void submit0(IoUringIoOps ioOps, long udata) {
            ringBuffer.ioUringSubmissionQueue().enqueueSqe(ioOps.opcode(), ioOps.flags(), ioOps.ioPrio(),
                    ioOps.fd(), ioOps.union1(), ioOps.union2(), ioOps.len(), ioOps.union3(), udata,
                    ioOps.union4(), ioOps.personality(), ioOps.union5(), ioOps.union6()
            );
            outstandingCompletions++; // 增加未完成操作的计数
        }

        /**
         * 返回附加到此注册的对象，即 {@link IoUringIoHandler} 自身。
         * 这允许 {@link IoUringIoHandle} 在需要时访问 Handler 的方法或状态 (例如获取 {@link #iovArray()})。
         * @param <T> 附件的类型。
         * @return {@link IoUringIoHandler} 实例。
         */
        @SuppressWarnings("unchecked")
        @Override
        public <T> T attachment() {
            return (T) IoUringIoHandler.this;
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
         * 如果成功取消 (之前未取消)，则会尝试从 {@link IoUringIoHandler#registrations} 映射中移除此注册。
         * 如果尚有未完成的操作，则会将移除操作推迟到所有操作完成后执行。
         *
         * @return 如果成功取消，则为 {@code true}；如果已经取消，则为 {@code false}。
         */
        @Override
        public boolean cancel() {
            if (!canceled.compareAndSet(false, true)) { // 原子地设置 canceled 标志
                // 已经取消。
                return false;
            }
            // 确保在事件循环线程中执行实际的取消/移除操作
            if (executor.isExecutorThread(Thread.currentThread())) {
                tryRemove();
            } else {
                executor.execute(this::tryRemove);
            }
            return true;
        }

        /**
         * 尝试移除此注册。
         * 如果没有未完成的操作，则立即移除；否则，标记为稍后移除。
         */
        private void tryRemove() {
            if (outstandingCompletions > 0) {
                // 尚有未完成的操作，我们将在这些操作完成后移除 id <-> registration 映射。
                removeLater = true;
                return;
            }
            remove(); // 没有未完成的操作，立即移除
        }

        /**
         * 从 {@link IoUringIoHandler#registrations} 映射中移除此注册。
         */
        private void remove() {
            DefaultIoUringIoRegistration old = registrations.remove(id);
            assert old == this; // 断言移除的是自身
        }

        /**
         * 关闭此注册关联的 {@link IoUringIoHandle}。
         * 通常在 {@link IoUringIoHandler#prepareToDestroy()} 时调用。
         * 关闭 handle 通常也会触发其自身的取消逻辑。
         */
        void close() {
            // 关闭 handle 也会取消注册。
            // 重要：我们不能手动取消，因为 close() 可能需要向环提交一些工作。
            assert executor.isExecutorThread(Thread.currentThread());
            try {
                handle.close();
            } catch (Exception e) {
                logger.debug("Exception during closing " + handle, e);
            }
        }

        /**
         * 处理与此注册相关的 io_uring 完成事件 (CQE)。
         * 此方法由 {@link IoUringIoHandler#handle(int, int, long)} 调用。
         *
         * @param res    CQE 的结果字段。
         * @param flags  CQE 的标志字段。
         * @param op     原始提交的操作码。
         * @param data   原始提交的附加数据。
         */
        void handle(int res, int flags, byte op, short data) {
            event.update(res, flags, op, data); // 更新可重用的 event 对象
            handle.handle(this, event); // 将事件委托给 IoUringIoHandle 处理

            // 仅当 CQE 的 IORING_CQE_F_MORE 标志未设置时才递减 outstandingCompletions，
            // 因为如果设置了此标志，表示我们知道初始请求还会有更多的完成事件。
            if ((flags & Native.IORING_CQE_F_MORE) == 0 && --outstandingCompletions == 0 && removeLater) {
                // 没有更多未完成的操作，并且之前已标记为稍后移除，现在执行移除。
                removeLater = false;
                remove();
            }
        }
    }

    private static IoUringIoHandle cast(IoHandle handle) {
        if (handle instanceof IoUringIoHandle) {
            return (IoUringIoHandle) handle;
        }
        throw new IllegalArgumentException("IoHandle of type " + StringUtil.simpleClassName(handle) + " not supported");
    }

    @Override
    public void wakeup() {
        // 如果调用者不是当前事件循环线程，并且 eventfdAsyncNotify 成功从 false 原子地设置为 true
        if (!executor.isExecutorThread(Thread.currentThread()) &&
                !eventfdAsyncNotify.getAndSet(true)) {
            // 向 eventfd 写入数据，这将触发一个 eventfd 读完成事件，从而唤醒事件循环。
            Native.eventFdWrite(eventfd.intValue(), 1L);
        }
    }

    @Override
    public boolean isCompatible(Class<? extends IoHandle> handleType) {
        return IoUringIoHandle.class.isAssignableFrom(handleType);
    }

    /**
     * 返回用于 scatter/gather I/O 操作的 {@link IovArray}。
     * 如果 {@link #iovArray} 已满，会先提交当前 SQ 中的操作以释放它。
     * @return 可用的 {@link IovArray}。
     */
    IovArray iovArray() {
        if (iovArray.isFull()) {
            // 提交 SQ 以便我们可以重用 iovArray。
            submitAndClear(ringBuffer.ioUringSubmissionQueue());
        }
        assert iovArray.count() == 0; // 断言 iovArray 当前为空
        return iovArray;
    }

    /**
     * 可用于临时存储 IPv4 地址编码的 {@code byte[]}。
     * @return IPv4 地址字节数组。
     */
    byte[] inet4AddressArray() {
        return inet4AddressArray;
    }

    /**
     * 可用于临时存储 IPv6 地址编码的 {@code byte[]}。
     * @return IPv6 地址字节数组。
     */
    byte[] inet6AddressArray() {
        return inet6AddressArray;
    }

    /**
     * 创建一个新的 {@link IoHandlerFactory}，用于创建 {@link IoUringIoHandler} 实例。
     * 使用默认的 {@link IoUringIoHandlerConfig}。
     *
     * @return {@link IoHandlerFactory} 实例。
     */
    public static IoHandlerFactory newFactory() {
        return newFactory(new IoUringIoHandlerConfig());
    }

    /**
     * 创建一个新的 {@link IoHandlerFactory}，用于创建 {@link IoUringIoHandler} 实例。
     * 每个 {@link IoUringIoHandler} 将使用大小为 {@code ringSize} 的环。
     *
     * @param  ringSize     环的大小。
     * @return              {@link IoHandlerFactory} 实例。
     */
    public static IoHandlerFactory newFactory(int ringSize) {
        IoUringIoHandlerConfig configuration = new IoUringIoHandlerConfig();
        configuration.setRingSize(ringSize);
        return eventLoop -> new IoUringIoHandler(eventLoop, configuration);
    }

    /**
     * 创建一个新的 {@link IoHandlerFactory}，用于创建 {@link IoUringIoHandler} 实例。
     * 每个 {@link IoUringIoHandler} 将使用相同的选项。
     * @param config io_uring 配置。
     * @return {@link IoHandlerFactory} 实例。
     */
    public static IoHandlerFactory newFactory(IoUringIoHandlerConfig config) {
        IoUring.ensureAvailability(); // 确保 io_uring 可用
        ObjectUtil.checkNotNull(config, "config");
        return eventLoop -> new IoUringIoHandler(eventLoop, config);
    }
}

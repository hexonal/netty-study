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
package io.netty.channel.local;

import io.netty.channel.IoHandlerContext;
import io.netty.channel.IoHandle;
import io.netty.channel.IoHandler;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.IoOps;
import io.netty.channel.IoRegistration;
import io.netty.util.concurrent.ThreadAwareExecutor;
import io.netty.util.internal.StringUtil;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * {@link IoHandler} 的实现，用于处理 {@link LocalChannel} 的 I/O 事件。
 * {@link LocalChannel} 是一种特殊的 Channel，用于在同一个 JVM 内部进行通信，不涉及实际的网络套接字或文件描述符。
 * 因此，它的 I/O 模型与基于网络的 Channel (如 NioSocketChannel, EpollSocketChannel) 有显著不同。
 *
 * <p><b>核心职责：</b></p>
 * <ul>
 *     <li>管理注册到此 Handler 的 {@link LocalIoHandle} 实例的集合。</li>
 *     <li><b>事件循环 ({@code run} 方法):</b> {@link LocalIoHandler} 的事件循环不依赖于外部选择器 (如 NIO Selector 或 Epoll)。
 *         相反，它主要通过 {@link LockSupport#parkNanos(Object, long)} 来实现阻塞和等待。
 *         当没有任务执行时，事件循环线程会阻塞，直到被 {@link #wakeup()} 方法唤醒或达到超时。</li>
 *     <li><b>唤醒机制 ({@code wakeup} 方法):</b> 允许其他线程通过 {@link LockSupport#unpark(Thread)} 来唤醒正在阻塞的事件循环线程。
 *         这通常在有新的任务提交到事件循环时发生。</li>
 *     <li><b>注册与注销 ({@code register}, {@code LocalIoRegistration#cancel}):</b> 处理 {@link LocalIoHandle} 的注册和注销请求。
 *         由于不涉及底层操作系统资源，这些操作相对简单，主要是管理内部的 {@code registeredChannels} 集合。</li>
 *     <li><b>资源清理 ({@code prepareToDestroy}, {@code destroy}):</b> 在关闭时，会关闭所有已注册的 {@link LocalIoHandle}。</li>
 * </ul>
 *
 * <p><b>与其他 {@link IoHandler} 的区别：</b></p>
 * <ul>
 *     <li><b>无选择器：</b> 不使用 NIO Selector, Epoll, KQueue 等基于文件描述符的 I/O 多路复用机制。</li>
 *     <li><b>阻塞/唤醒：</b> 主要依赖 {@link LockSupport} 进行线程的阻塞和唤醒。</li>
 *     <li><b>I/O 操作：</b> {@link LocalChannel} 的数据读写通常是直接的方法调用和内存操作，不涉及网络传输。
 *         因此，{@link LocalIoRegistration#submit(IoOps)} 方法会抛出 {@link UnsupportedOperationException}，
 *         因为 {@link LocalChannel} 不通过传统的 I/O 操作集 (read, write, connect, accept) 进行交互。</li>
 * </ul>
 *
 * <p><b>使用场景：</b></p>
 * {@link LocalChannel} 和 {@link LocalIoHandler} 主要用于测试场景，或者在需要高性能 JVM 内进程间通信 (IPC) 的特定情况下。
 *
 * @see LocalChannel
 * @see LocalIoHandle
 * @see LockSupport
 */
public final class LocalIoHandler implements IoHandler {
    /**
     * 存储所有已注册到此 Handler 的 {@link LocalIoHandle} 实例。
     * 使用 {@link HashSet} 进行存储，初始容量为 64。
     */
    private final Set<LocalIoHandle> registeredChannels = new HashSet<LocalIoHandle>(64);
    /**
     * 驱动此 {@link LocalIoHandler} 的执行器，通常是 {@link io.netty.channel.SingleThreadEventLoop} 的一个实例，
     * 例如 {@link io.netty.channel.DefaultEventLoop}。
     */
    private final ThreadAwareExecutor executor;
    /**
     * 当前执行此 {@link LocalIoHandler} 的 {@code run} 方法的线程。
     * 此字段是易失的 (volatile)，以确保多线程环境下的可见性。
     * 它在 {@code run} 方法首次执行时被设置，并由 {@code wakeup} 方法用于 unpark 正确的线程。
     */
    private volatile Thread executionThread;

    private LocalIoHandler(ThreadAwareExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * 返回一个新的 {@link IoHandlerFactory}，用于创建 {@link LocalIoHandler} 实例。
     *
     * @return {@link IoHandlerFactory} 实例。
     */
    public static IoHandlerFactory newFactory() {
        return LocalIoHandler::new;
    }

    /**
     * 将 {@link IoHandle} 转换为 {@link LocalIoHandle}。
     * @param handle 要转换的 {@link IoHandle}。
     * @return 转换后的 {@link LocalIoHandle}。
     * @throws IllegalArgumentException 如果 handle 不是 {@link LocalIoHandle} 类型。
     */
    private static LocalIoHandle cast(IoHandle handle) {
        if (handle instanceof LocalIoHandle) {
            return (LocalIoHandle) handle;
        }
        throw new IllegalArgumentException("IoHandle of type " + StringUtil.simpleClassName(handle) + " not supported");
    }

    /**
     * {@link IoHandler} 的核心运行方法，由事件循环线程重复调用。
     * 对于 {@link LocalIoHandler}，它不执行传统的 I/O 选择操作。
     * 如果当前没有任务且允许阻塞 ({@code context.canBlock()} 为 true)，
     * 它会使用 {@link LockSupport#parkNanos(Object, long)} 使当前线程阻塞，
     * 直到被 {@link #wakeup()} 唤醒或达到指定的超时时间。
     *
     * @param context {@link IoHandlerContext}，提供定时信息和判断是否可以阻塞的能力。
     * @return 通常返回 0，因为 {@link LocalIoHandler} 不直接处理像网络 I/O 那样的事件计数。
     */
    @Override
    public int run(IoHandlerContext context) {
        if (executionThread == null) {
            executionThread = Thread.currentThread();
        }
        if (context.canBlock()) {
            LockSupport.parkNanos(this, context.delayNanos(System.nanoTime()));
        }
        return 0;
    }

    /**
     * 唤醒可能因调用 {@link #run(IoHandlerContext)} 而阻塞的事件循环线程。
     * 如果调用此方法的线程不是事件循环线程本身，并且 {@code executionThread} 已被设置，
     * 则调用 {@link LockSupport#unpark(Thread)} 来唤醒事件循环线程。
     */
    @Override
    public void wakeup() {
        if (!executor.isExecutorThread(Thread.currentThread())) {
            Thread thread = executionThread;
            if (thread != null) {
                LockSupport.unpark(thread);
            }
        }
    }

    /**
     * 准备销毁此 {@link LocalIoHandler}。
     * 在实际销毁之前调用，用于执行清理操作。
     * 它会遍历所有已注册的 {@link LocalIoHandle} 并立即关闭它们，然后清空注册集合。
     */
    @Override
    public void prepareToDestroy() {
        for (LocalIoHandle handle : registeredChannels) {
            handle.closeNow();
        }
        registeredChannels.clear();
    }

    /**
     * 销毁此 {@link LocalIoHandler}。
     * 对于 {@link LocalIoHandler}，此方法目前为空，因为主要的清理工作在 {@link #prepareToDestroy()} 中完成，
     * 并且 {@link LocalIoHandler} 本身通常不持有需要显式释放的底层系统资源 (如文件描述符)。
     */
    @Override
    public void destroy() {
    }

    /**
     * 将给定的 {@link LocalIoHandle} 注册到此 {@link LocalIoHandler}。
     * 如果注册成功，会创建一个 {@link LocalIoRegistration} 并将其返回。
     *
     * @param handle 要注册的 {@link IoHandle}，必须是 {@link LocalIoHandle} 类型。
     * @return 代表此注册的 {@link IoRegistration}。
     * @throws IllegalStateException 如果尝试注册一个已经注册的 {@link LocalIoHandle} (理论上不应发生，因为 add 会处理)。
     * @throws IllegalArgumentException 如果 handle 不是 {@link LocalIoHandle} 类型。
     */
    @Override
    public IoRegistration register(IoHandle handle) {
        LocalIoHandle localHandle = cast(handle);
        if (registeredChannels.add(localHandle)) {
            LocalIoRegistration registration = new LocalIoRegistration(executor, localHandle);
            localHandle.registerNow();
            return registration;
        }
        throw new IllegalStateException("LocalIoHandle already registered: " + localHandle);
    }

    /**
     * 检查给定的 {@link IoHandle} 类型是否与此 {@link LocalIoHandler} 兼容。
     * 对于 {@link LocalIoHandler}，它只兼容 {@link LocalIoHandle} 及其子类型。
     *
     * @param handleType {@link IoHandle} 的类型。
     * @return 如果兼容则为 {@code true}，否则为 {@code false}。
     */
    @Override
    public boolean isCompatible(Class<? extends IoHandle> handleType) {
        return LocalIoHandle.class.isAssignableFrom(handleType);
    }

    /**
     * {@link IoRegistration} 的 Local 实现。
     * 用于跟踪 {@link LocalIoHandle} 在 {@link LocalIoHandler} 中的注册状态。
     */
    private final class LocalIoRegistration implements IoRegistration {
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final ThreadAwareExecutor executor;
        private final LocalIoHandle handle;

        LocalIoRegistration(ThreadAwareExecutor executor, LocalIoHandle handle) {
            this.executor = executor;
            this.handle = handle;
        }

        /**
         * {@link LocalIoRegistration} 没有特定的附件。
         * @param <T> 附件的类型。
         * @return 总是 {@code null}。
         */
        @Override
        public <T> T attachment() {
            return null;
        }

        /**
         * {@link LocalChannel} 不使用传统的 I/O 操作集 (read, write, connect, accept) 进行交互，
         * 因此不支持通过 {@code submit(IoOps)} 来更改感兴趣的操作。
         *
         * @param ops 要提交的 I/O 操作。
         * @return 不适用。
         * @throws UnsupportedOperationException 总是抛出此异常。
         */
        @Override
        public long submit(IoOps ops) {
            throw new UnsupportedOperationException();
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
         * 如果成功取消 (之前未取消)，则将从 {@link LocalIoHandler#registeredChannels} 集合中移除关联的 {@link LocalIoHandle}，
         * 并通知 {@link LocalIoHandle} 它已被注销。
         *
         * @return 如果成功取消，则为 {@code true}；如果已经取消，则为 {@code false}。
         */
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

        /**
         * 实际执行取消操作的内部方法。
         * 从 {@code registeredChannels} 移除 handle，并调用 {@code handle.deregisterNow()}。
         */
        private void cancel0() {
            if (registeredChannels.remove(handle)) {
                handle.deregisterNow();
            }
        }
    }
}

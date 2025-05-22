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
package io.netty.channel;

import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.MultithreadEventExecutorGroup;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * {@link EventLoopGroup} 实现的抽象基类，用于同时使用多个线程处理其任务。
 * 这个类扩展了 {@link MultithreadEventExecutorGroup} 并实现了 {@link EventLoopGroup} 接口，
 * 为创建多线程的事件循环组提供了一个通用的框架。
 *
 * <p><b>核心功能：</b></p>
 * <ul>
 *     <li>管理一组 {@link EventLoop} 实例。</li>
 *     <li>提供了选择下一个 {@link EventLoop} 的机制 (通过 {@link #next()} 方法)。</li>
 *     <li>负责 {@link Channel} 的注册 (通过 {@link #register(Channel)} 系列方法)。</li>
 *     <li>定义了创建子 {@link EventLoop} 的抽象方法 {@link #newChild(Executor, Object...)}，具体实现由子类完成。</li>
 * </ul>
 *
 * <p><b>线程数默认值：</b></p>
 * 默认的事件循环线程数 ({@code DEFAULT_EVENT_LOOP_THREADS}) 由系统属性 {@code io.netty.eventLoopThreads} 控制，
 * 如果未设置，则默认为 {@code NettyRuntime.availableProcessors() * 2} (可用CPU核心数的两倍)，但至少为1。
 * 这个默认值旨在为大多数应用程序提供良好的开箱即用性能。
 *
 * <p><b>线程工厂：</b></p>
 * 默认情况下，它使用 {@link DefaultThreadFactory} 来创建线程，并将线程优先级设置为 {@link Thread#MAX_PRIORITY}。
 * 这是因为 I/O 线程通常需要较高的优先级以确保及时响应。
 *
 * @see MultithreadEventExecutorGroup
 * @see EventLoopGroup
 * @see EventLoop
 */
public abstract class MultithreadEventLoopGroup extends MultithreadEventExecutorGroup implements EventLoopGroup {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(MultithreadEventLoopGroup.class);

    /**
     * 默认的事件循环线程数。
     * 计算方式：首先尝试从系统属性 "io.netty.eventLoopThreads" 获取。
     * 如果未设置，则默认为 {@code NettyRuntime.availableProcessors() * 2} (可用CPU核心数的两倍)。
     * 最小值为 1。
     * 这个值用于在构造函数中未明确指定线程数 (nThreads 为 0) 时，决定创建多少个 EventLoop 实例。
     */
    private static final int DEFAULT_EVENT_LOOP_THREADS;

    static {
        DEFAULT_EVENT_LOOP_THREADS = Math.max(1, SystemPropertyUtil.getInt(
                "io.netty.eventLoopThreads", NettyRuntime.availableProcessors() * 2));

        if (logger.isDebugEnabled()) {
            // 记录最终确定的默认 EventLoop 线程数，便于调试和配置检查。
            logger.debug("-Dio.netty.eventLoopThreads: {}", DEFAULT_EVENT_LOOP_THREADS);
        }
    }

    /**
     * 构造函数。
     * @param nThreads 如果为0，则使用 {@link #DEFAULT_EVENT_LOOP_THREADS} 作为线程数；否则使用指定的 {@code nThreads}。
     * @param executor 用于执行任务的 {@link Executor}。如果为 {@code null}，则会创建新的线程。
     * @param args 传递给 {@link #newChild(Executor, Object...)} 方法的参数。
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, Executor, Object...)
     */
    protected MultithreadEventLoopGroup(int nThreads, Executor executor, Object... args) {
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, executor, args);
    }

    /**
     * 构造函数。
     * @param nThreads 如果为0，则使用 {@link #DEFAULT_EVENT_LOOP_THREADS} 作为线程数；否则使用指定的 {@code nThreads}。
     * @param threadFactory 用于创建线程的 {@link ThreadFactory}。如果为 {@code null}，则使用 {@link #newDefaultThreadFactory()} 创建默认工厂。
     * @param args 传递给 {@link #newChild(Executor, Object...)} 方法的参数。
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, ThreadFactory, Object...)
     */
    protected MultithreadEventLoopGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, threadFactory, args);
    }

    /**
     * 构造函数。
     * @param nThreads 如果为0，则使用 {@link #DEFAULT_EVENT_LOOP_THREADS} 作为线程数；否则使用指定的 {@code nThreads}。
     * @param executor 用于执行任务的 {@link Executor}。如果为 {@code null}，则会创建新的线程。
     * @param chooserFactory 用于选择 {@link EventExecutor} 的工厂。
     * @param args 传递给 {@link #newChild(Executor, Object...)} 方法的参数。
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, Executor, EventExecutorChooserFactory, Object...)
     */
    protected MultithreadEventLoopGroup(int nThreads, Executor executor, EventExecutorChooserFactory chooserFactory,
                                     Object... args) {
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, executor, chooserFactory, args);
    }

    /**
     * 创建一个默认的 {@link ThreadFactory}。
     * 返回的 {@link DefaultThreadFactory} 会将创建的线程的优先级设置为 {@link Thread#MAX_PRIORITY}。
     * I/O 线程通常需要较高的优先级以确保低延迟和高吞吐量。
     *
     * @return 一个新的 {@link DefaultThreadFactory} 实例。
     */
    @Override
    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass(), Thread.MAX_PRIORITY);
    }

    /**
     * 从管理的 {@link EventLoop} 池中选择并返回下一个 {@link EventLoop}。
     * 选择策略由父类 {@link MultithreadEventExecutorGroup} 中配置的 {@link EventExecutorChooserFactory} 决定，
     * 默认为轮询选择。
     *
     * @return 下一个 {@link EventLoop}。
     */
    @Override
    public EventLoop next() {
        return (EventLoop) super.next();
    }

    /**
     * 创建一个新的子 {@link EventLoop} 实例。
     * 这个方法由具体的子类实现，以定义如何创建特定类型的 {@link EventLoop}。
     * 例如，NIO 实现会创建基于 Selector 的 EventLoop，Epoll 实现会创建基于 Epoll 的 EventLoop。
     *
     * @param executor 用于驱动 {@link EventLoop} 的 {@link Executor}。
     * @param args     创建 {@link EventLoop} 时可能需要的额外参数。
     * @return 一个新的 {@link EventLoop} 实例。
     * @throws Exception 如果创建过程中发生错误。
     */
    @Override
    protected abstract EventLoop newChild(Executor executor, Object... args) throws Exception;

    /**
     * 将给定的 {@link Channel} 注册到从此 {@link EventLoopGroup} 中选择的下一个 {@link EventLoop}。
     * 返回的 {@link ChannelFuture} 可用于获取注册操作的结果。
     *
     * @param channel 要注册的 {@link Channel}。
     * @return 代表注册操作的 {@link ChannelFuture}。
     */
    @Override
    public ChannelFuture register(Channel channel) {
        return next().register(channel);
    }

    /**
     * 将给定的 {@link ChannelPromise} 中包含的 {@link Channel} 注册到从此 {@link EventLoopGroup} 中选择的下一个 {@link EventLoop}。
     * {@link ChannelPromise} 用于在注册完成后通知结果。
     *
     * @param promise 包含要注册的 {@link Channel} 并用于通知结果的 {@link ChannelPromise}。
     * @return 传入的 {@link ChannelPromise}。
     */
    @Override
    public ChannelFuture register(ChannelPromise promise) {
        return next().register(promise);
    }

    /**
     * @deprecated Use {@link #register(ChannelPromise)} instead.
     * 将给定的 {@link Channel} 注册到从此 {@link EventLoopGroup} 中选择的下一个 {@link EventLoop}，并使用给定的 {@link ChannelPromise} 来通知结果。
     *
     * @param channel 要注册的 {@link Channel}。
     * @param promise 用于通知注册操作结果的 {@link ChannelPromise}。
     * @return 传入的 {@link ChannelPromise}。
     */
    @Deprecated
    @Override
    public ChannelFuture register(Channel channel, ChannelPromise promise) {
        return next().register(channel, promise);
    }

}

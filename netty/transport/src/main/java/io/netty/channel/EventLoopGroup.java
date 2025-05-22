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

import io.netty.util.concurrent.EventExecutorGroup;

/**
 * 特殊的 {@link EventExecutorGroup}，它允许注册 {@link Channel}，
 * 这些 Channel 将在其管理的 {@link EventLoop} 中被处理，用于后续的事件选择和处理。
 *
 * <p><b>{@link EventLoopGroup} 的核心角色与职责：</b></p>
 * <ul>
 *     <li><b>Reactor 池：</b> 在 Reactor 模式中，{@link EventLoopGroup} 可以被视为一个 Reactor 池，它管理着一个或多个 {@link EventLoop} 实例。</li>
 *     <li><b>{@link EventLoop} 的管理者：</b> 它负责创建和管理其包含的 {@link EventLoop} 的生命周期。</li>
 *     <li><b>{@link Channel} 注册：</b> 提供了将 {@link Channel} 注册到其管理的某个 {@link EventLoop} 的能力。
 *         一旦注册，该 {@link EventLoop} 将负责处理该 {@link Channel} 上的所有 I/O 事件。</li>
 *     <li><b>{@link EventLoop} 选择：</b> 通过 {@link #next()} 方法，可以从组中选择一个 {@link EventLoop} 来执行任务或注册 Channel。
 *         选择策略通常是轮询 (round-robin) 或其他实现定义的策略。</li>
 *     <li><b>资源共享与隔离：</b> 多个 Channel 可以共享同一个 {@link EventLoopGroup}，甚至同一个 {@link EventLoop}，
 *         也可以为不同类型的 Channel 或服务使用不同的 {@link EventLoopGroup} 以实现资源隔离。</li>
 * </ul>
 *
 * <p><b>与 {@link EventExecutorGroup} 的关系：</b></p>
 * {@link EventLoopGroup} 继承自 {@link EventExecutorGroup}。{@link EventExecutorGroup} 是一个更通用的执行器组接口，
 * 用于执行提交的任务。{@link EventLoopGroup} 在此基础上增加了与 {@link Channel} I/O 事件处理相关的特定功能，
 * 即它管理的 {@link EventExecutor} 必须是 {@link EventLoop} 的实例，能够处理 I/O 事件。
 *
 * <p><b>在 Netty 中的典型应用：</b></p>
 * <ul>
 *     <li>在服务端 ({@link ServerBootstrap})，通常会配置两个 {@link EventLoopGroup}：
 *         <ul>
 *             <li>一个 "boss group" (父 EventLoopGroup)，负责接受新的客户端连接。</li>
 *             <li>一个 "worker group" (子 EventLoopGroup)，负责处理已接受连接的 I/O 读写操作。</li>
 *         </ul>
 *     </li>
 *     <li>在客户端 ({@link Bootstrap})，通常配置一个 {@link EventLoopGroup}，负责处理连接的建立以及后续的 I/O 读写操作。</li>
 * </ul>
 *
 * @see EventLoop
 * @see EventExecutorGroup
 * @see Channel
 * @see ServerBootstrap
 * @see Bootstrap
 */
public interface EventLoopGroup extends EventExecutorGroup {
    /**
     * 返回此 {@link EventLoopGroup} 中要使用的下一个 {@link EventLoop}。
     * 实现通常使用轮询 (round-robin) 策略来选择 {@link EventLoop}，以实现负载均衡。
     * 返回的 {@link EventLoop} 将用于执行 Channel 的 I/O 操作和处理相关的事件。
     *
     * @return 下一个 {@link EventLoop}。
     */
    @Override
    EventLoop next();

    /**
     * 将一个 {@link Channel} 注册到此 {@link EventLoopGroup} 中的某个 {@link EventLoop}。
     * 注册过程是异步的，返回的 {@link ChannelFuture} 将在注册操作完成时收到通知。
     * 一旦注册完成，该 {@link EventLoop} 将负责处理此 {@link Channel} 的所有 I/O 事件和生命周期。
     *
     * <p><b>注意：</b>通常用户不需要直接调用此方法，而是通过 {@link AbstractBootstrap#bind()} 或 {@link AbstractBootstrap#connect()} 方法间接完成注册。</p>
     *
     * @param channel 要注册的 {@link Channel}。
     * @return 一个 {@link ChannelFuture}，当注册完成时会收到通知。
     */
    ChannelFuture register(Channel channel);

    /**
     * 将一个 {@link Channel} (包含在 {@link ChannelPromise} 中) 注册到此 {@link EventLoopGroup} 中的某个 {@link EventLoop}。
     * 传入的 {@link ChannelPromise} 将在注册操作完成时收到通知，并且此方法也会返回该 {@link ChannelPromise} (作为 {@link ChannelFuture})。
     * 这种方式允许调用者提供一个 {@link ChannelPromise} 来跟踪注册操作的进度和结果。
     *
     * @param promise 一个 {@link ChannelPromise}，它包含了要注册的 {@link Channel}，并用于通知注册结果。
     * @return 传入的 {@link ChannelPromise} (作为 {@link ChannelFuture})。
     */
    ChannelFuture register(ChannelPromise promise);

    /**
     * 将一个 {@link Channel} 注册到此 {@link EventLoopGroup} 中的某个 {@link EventLoop}，并使用给定的 {@link ChannelPromise} 来通知结果。
     * 传入的 {@link ChannelPromise} 将在注册操作完成时收到通知，并且此方法也会返回该 {@link ChannelPromise} (作为 {@link ChannelFuture})。
     *
     * @deprecated 请改用 {@link #register(ChannelPromise)}。此方法在语义上与 {@link #register(ChannelPromise)} 重复，
     *             且后者通过只接受一个 {@link ChannelPromise} 参数来明确 Channel 是从 Promise 中获取的。
     * @param channel 要注册的 {@link Channel}。
     * @param promise 用于通知注册操作结果的 {@link ChannelPromise}。
     * @return 传入的 {@link ChannelPromise} (作为 {@link ChannelFuture})。
     */
    @Deprecated
    ChannelFuture register(Channel channel, ChannelPromise promise);
}

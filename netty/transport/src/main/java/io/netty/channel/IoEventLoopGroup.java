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
package io.netty.channel;

import io.netty.util.concurrent.Future;

/**
 * 用于 {@link IoEventLoop} 的 {@link EventLoopGroup}。
 * 这个接口继承自 {@link EventLoopGroup}，并专门针对 I/O 事件循环进行了扩展。
 * 它定义了与 {@link IoHandle} 和 {@link IoHandler} 相关的操作，这些是 Netty 新的 I/O 模型的一部分。
 *
 * <p><b>核心职责：</b></p>
 * <ul>
 *     <li>继承 {@link EventLoopGroup} 的所有功能，如管理事件循环、选择下一个事件循环。</li>
 *     <li>提供注册 {@link IoHandle} 的方法，用于将 I/O 句柄绑定到事件循环进行处理。</li>
 *     <li>提供检查 {@link IoHandle} 类型和 {@link IoHandler} 类型兼容性的方法。</li>
 * </ul>
 *
 * <p><b>与 {@link EventLoopGroup} 的关系：</b></p>
 * {@link IoEventLoopGroup} 是 {@link EventLoopGroup} 的一个特化版本，专注于 I/O 处理。这意味着一个
 * {@link IoEventLoopGroup} 管理的是 {@link IoEventLoop} 实例，这些实例能够处理特定类型的 I/O 操作。
 *
 * <p><b>新的 I/O 模型：</b></p>
 * <ul>
 *     <li>{@link IoHandle}: 代表一个可进行 I/O 操作的实体，例如一个文件描述符或一个套接字。它是对底层 I/O 资源的抽象。</li>
 *     <li>{@link IoHandler}: 负责处理与特定 {@link IoHandle} 相关的 I/O 事件和操作。</li>
 *     <li>{@link IoRegistration}: 代表 {@link IoHandle} 在 {@link IoEventLoop} 上的注册。</li>
 * </ul>
 * 这个新模型旨在提供更灵活和可扩展的 I/O 处理机制。
 *
 * @see EventLoopGroup
 * @see IoEventLoop
 * @see IoHandle
 * @see IoHandler
 * @see IoRegistration
 */
public interface IoEventLoopGroup extends EventLoopGroup {

    /**
     * 返回此 {@link IoEventLoopGroup} 中的下一个 {@link IoEventLoop}。
     * 选择逻辑通常是轮询或其他由实现定义的策略。
     *
     * @return 下一个 {@link IoEventLoop}。
     */
    @Override
    IoEventLoop next();

    /**
     * @deprecated 请改用 {@link #register(IoHandle)}。这个方法是为了向后兼容旧的基于 Channel 的 API。
     * 将给定的 {@link Channel} 注册到从此 {@link IoEventLoopGroup} 中选择的下一个 {@link IoEventLoop}。
     *
     * @param channel 要注册的 {@link Channel}。
     * @return 代表注册操作的 {@link ChannelFuture}。
     */
    @Deprecated
    @Override
    default ChannelFuture register(Channel channel) {
        return next().register(channel);
    }

    /**
     * @deprecated 请改用 {@link #register(IoHandle)}。这个方法是为了向后兼容旧的基于 Channel 的 API。
     * 将给定的 {@link ChannelPromise} 中包含的 {@link Channel} 注册到从此 {@link IoEventLoopGroup} 中选择的下一个 {@link IoEventLoop}。
     *
     * @param promise 包含要注册的 {@link Channel} 并用于通知结果的 {@link ChannelPromise}。
     * @return 传入的 {@link ChannelPromise}。
     */
    @Deprecated
    @Override
   default ChannelFuture register(ChannelPromise promise) {
        return next().register(promise);
    }

    /**
     * 将 {@link IoHandle} 注册到此 {@link IoEventLoopGroup} 中的下一个 {@link IoEventLoop} 以进行 I/O 处理。
     * 这是 Netty 新 I/O 模型的核心注册方法。
     *
     * @param handle 要注册的 {@link IoHandle}。
     * @return 一个 {@link Future}，当操作完成时会收到通知。此 Future 的结果是 {@link IoRegistration}，
     *         代表了 {@code handle} 在 {@link IoEventLoop} 上的成功注册。
     */
    default Future<IoRegistration> register(IoHandle handle) {
        return next().register(handle);
    }

    /**
     * 检查给定的 {@link IoHandle} 类型是否与此 {@link IoEventLoopGroup} 兼容，
     * 因此可以注册到其包含的 {@link IoEventLoop} 中。
     * 例如，一个基于 NIO 的 {@link IoEventLoopGroup} 可能只兼容特定类型的 {@link IoHandle} (如 representing selectable channels)。
     *
     * @param handleType {@link IoHandle} 的类型。
     * @return 如果兼容则返回 {@code true}，否则返回 {@code false}。
     */
    default boolean isCompatible(Class<? extends IoHandle> handleType) {
        return next().isCompatible(handleType);
    }

    /**
     * 检查给定的 {@link IoHandler} 类型是否由此 {@link IoEventLoopGroup} 使用。
     * 这可以用于确定一个事件循环组是否配置为处理特定类型的 I/O 逻辑。
     *
     * @param handlerType {@link IoHandler} 的类型。
     * @return 如果使用则返回 {@code true}，否则返回 {@code false}。
     */
    default boolean isIoType(Class<? extends IoHandler> handlerType) {
        return next().isIoType(handlerType);
    }
}

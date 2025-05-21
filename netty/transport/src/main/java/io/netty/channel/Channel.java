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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.AttributeMap;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * 表示一个网络套接字或具备 I/O 能力（如读、写、连接、绑定）的组件。
 * <p>
 * Channel 提供如下能力：
 * <ul>
 * <li>获取当前 Channel 的状态（如是否打开、是否已连接）；</li>
 * <li>获取 Channel 的配置参数（如接收缓冲区大小等）；</li>
 * <li>支持的 I/O 操作（如读、写、连接、绑定）；</li>
 * <li>获取与 Channel 关联的 {@link ChannelPipeline}，用于处理所有 I/O 事件和请求。</li>
 * </ul>
 *
 * <h3>所有 I/O 操作均为异步</h3>
 * <p>
 * Netty 中所有 I/O 操作均为异步，调用后立即返回，不保证操作已完成。通过 {@link ChannelFuture} 获取操作结果，
 * 可注册监听器实现回调通知。
 *
 * <h3>Channel 具备层级结构</h3>
 * <p>
 * Channel 可有父 Channel（如 SocketChannel 的 parent 为 ServerSocketChannel），
 * 具体层级结构取决于传输实现。
 *
 * <h3>可向下转型以访问特定传输操作</h3>
 * <p>
 * 某些传输实现提供特定操作，可通过向下转型访问（如 DatagramChannel 的组播操作）。
 *
 * <h3>资源释放</h3>
 * <p>
 * 使用完 Channel 后，务必调用 {@link #close()} 或 {@link #close(ChannelPromise)} 释放资源，
 * 以避免资源泄漏（如文件句柄等）。
 *
 * <h3>线程安全性</h3>
 * <ul>
 *   <li>Channel 的大部分方法为线程安全，底层通过事件循环（EventLoop）串行化 I/O 操作。</li>
 *   <li>但部分实现细节需参考具体子类文档。</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>
 * {@code
 * Channel channel = ...;
 * channel.writeAndFlush(msg).addListener(future -> {
 *     if (future.isSuccess()) {
 *         // 发送成功
 *     } else {
 *         // 发送失败，处理异常
 *     }
 * });
 * }
 * </pre>
 */
public interface Channel extends AttributeMap, ChannelOutboundInvoker, Comparable<Channel> {

    /**
     * 返回该 Channel 的全局唯一标识符。
     */
    ChannelId id();

    /**
     * 返回该 Channel 注册到的 {@link EventLoop}。
     * <p>
     * EventLoop 负责驱动 Channel 的所有 I/O 事件，保证线程安全。
     * </p>
     */
    EventLoop eventLoop();

    /**
     * 返回该 Channel 的父 Channel。
     * <p>
     * 若无父 Channel，则返回 null。常见于子 Channel（如 SocketChannel）被 ServerSocketChannel 接受时。
     * </p>
     */
    Channel parent();

    /**
     * 返回该 Channel 的配置信息。
     * <p>
     * 包含缓冲区大小、自动读等参数。
     * </p>
     */
    ChannelConfig config();

    /**
     * 判断 Channel 是否处于打开状态，后续可能变为激活。
     */
    boolean isOpen();

    /**
     * 判断 Channel 是否已注册到 EventLoop。
     */
    boolean isRegistered();

    /**
     * 判断 Channel 是否处于激活状态（如已连接）。
     */
    boolean isActive();

    /**
     * 返回 Channel 的元数据信息，描述 Channel 的特性。
     */
    ChannelMetadata metadata();

    /**
     * 返回 Channel 绑定的本地地址。
     * <p>
     * 可向下转型为 {@link InetSocketAddress} 获取详细信息。
     * 若未绑定则返回 null。
     * </p>
     */
    SocketAddress localAddress();

    /**
     * 返回 Channel 连接的远程地址。
     * <p>
     * 可向下转型为 {@link InetSocketAddress} 获取详细信息。
     * 若未连接则返回 null。
     * 对于可接收任意远程地址的 Channel（如 DatagramChannel），需通过 {@link DatagramPacket#recipient()} 获取来源。
     * </p>
     */
    SocketAddress remoteAddress();

    /**
     * 返回一个 {@link ChannelFuture}，在 Channel 关闭时通知。
     * <p>
     * 始终返回同一实例。
     * </p>
     */
    ChannelFuture closeFuture();

    /**
     * 判断 I/O 线程是否可立即执行写操作。
     * <p>
     * 若返回 false，写请求将被缓存，待可写时再处理。
     * 可通过 {@link WriteBufferWaterMark} 配置写缓冲区水位线。
     * </p>
     */
    default boolean isWritable() {
        ChannelOutboundBuffer buf = unsafe().outboundBuffer();
        return buf != null && buf.isWritable();
    }

    /**
     * 距离 Channel 变为不可写还可写入的字节数。
     * <p>
     * 若已不可写则为 0。可通过 {@link WriteBufferWaterMark} 配置。
     * </p>
     */
    default long bytesBeforeUnwritable() {
        ChannelOutboundBuffer buf = unsafe().outboundBuffer();
        // isWritable() 方法已假定无 outboundBuffer 时不可写，这里保持一致。
        return buf != null ? buf.bytesBeforeUnwritable() : 0;
    }

    /**
     * 距离 Channel 变为可写还需清空的字节数。
     * <p>
     * 若已可写则为 0。可通过 {@link WriteBufferWaterMark} 配置。
     * </p>
     */
    default long bytesBeforeWritable() {
        ChannelOutboundBuffer buf = unsafe().outboundBuffer();
        // isWritable() 方法已假定无 outboundBuffer 时不可写，这里保持一致。
        return buf != null ? buf.bytesBeforeWritable() : Long.MAX_VALUE;
    }

    /**
     * 返回仅供内部使用的 Unsafe 对象，提供底层操作能力。
     * <p>
     * <b>警告：仅限 Netty 内部调用，用户请勿直接使用！</b>
     * </p>
     */
    Unsafe unsafe();

    /**
     * 返回分配给该 Channel 的 {@link ChannelPipeline}。
     * <p>
     * Pipeline 负责事件传播与 handler 链管理，是 Netty 事件驱动模型的核心。
     * <br>
     * <b>关键节点说明：</b>
     * <ul>
     *   <li>所有 I/O 事件（如读、写、连接、异常）都会沿 pipeline 依次传递给各个 handler。</li>
     *   <li>用户可通过添加自定义 handler 实现业务逻辑、协议编解码等功能。</li>
     *   <li>典型调用链：I/O 事件 → pipeline → handler1 → handler2 ...</li>
     * </ul>
     * </p>
     */
    ChannelPipeline pipeline();

    /**
     * 返回分配给该 Channel 的 {@link ByteBufAllocator}，用于分配 ByteBuf。
     */
    default ByteBufAllocator alloc() {
        return config().getAllocator();
    }

    /**
     * 获取指定 {@link ChannelOption} 的值。
     */
    default <T> T getOption(ChannelOption<T> option) {
        return config().getOption(option);
    }

    /**
     * 设置指定 {@link ChannelOption} 的值。
     * <p>
     * 若需重写此方法，建议调用父类实现。
     * </p>
     * @return 设置成功返回 true
     */
    default <T> boolean setOption(ChannelOption<T> option, T value) {
        return config().setOption(option, value);
    }

    // 下面为常用 I/O 操作的默认实现，均委托给 pipeline，便于 handler 链统一处理

    @Override
    default Channel read() {
        pipeline().read();
        return this;
    }

    @Override
    default Channel flush() {
        pipeline().flush();
        return this;
    }

    /**
     * 异步写入并刷新消息到远端。
     * <p>
     * <b>关键节点说明：</b>
     * <ul>
     *   <li>消息首先经过 pipeline 的出站 handler 处理（如编码、加密等）。</li>
     *   <li>实际写操作由 EventLoop 线程异步完成，调用后立即返回 ChannelFuture。</li>
     *   <li>可通过 future.addListener() 注册回调，监听写操作完成、失败等事件。</li>
     *   <li>典型流程：writeAndFlush(msg) → pipeline 出站传播 → 出站 handler → 底层写缓冲区 → flush → 发送到网络</li>
     * </ul>
     * </p>
     */
    @Override
    default ChannelFuture writeAndFlush(Object msg) {
        return pipeline().writeAndFlush(msg);
    }

    /**
     * 异步写入并刷新消息到远端，并指定回调 Promise。
     * <p>
     * <b>关键节点说明：</b>
     * <ul>
     *   <li>与 writeAndFlush(Object msg) 类似，但可自定义回调 Promise，便于链式操作或特殊回调。</li>
     *   <li>典型场景：需要自定义异步回调或链式通知时使用。</li>
     * </ul>
     * </p>
     */
    @Override
    default ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return pipeline().writeAndFlush(msg, promise);
    }

    @Override
    default ChannelFuture write(Object msg, ChannelPromise promise) {
        return pipeline().write(msg, promise);
    }

    @Override
    default ChannelFuture write(Object msg) {
        return pipeline().write(msg);
    }

    @Override
    default ChannelFuture deregister(ChannelPromise promise) {
        return pipeline().deregister(promise);
    }

    /**
     * 异步关闭 Channel 并释放所有相关资源。
     * <p>
     * <b>关键节点说明：</b>
     * <ul>
     *   <li>关闭操作会触发 pipeline 的出站 close 事件，依次通知所有 handler 做资源清理。</li>
     *   <li>底层连接关闭后，相关资源（如文件句柄、缓冲区）会被释放。</li>
     *   <li>closeFuture() 可用于监听 Channel 关闭完成事件，适合做后续清理或重连。</li>
     *   <li>典型流程：close() → pipeline 出站传播 → handler 资源释放 → 关闭底层连接 → 通知 closeFuture</li>
     * </ul>
     * </p>
     */
    @Override
    default ChannelFuture close(ChannelPromise promise) {
        return pipeline().close(promise);
    }

    @Override
    default ChannelFuture disconnect(ChannelPromise promise) {
        return pipeline().disconnect(promise);
    }

    @Override
    default ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return pipeline().connect(remoteAddress, localAddress, promise);
    }

    @Override
    default ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline().connect(remoteAddress, promise);
    }

    @Override
    default ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return pipeline().bind(localAddress, promise);
    }

    @Override
    default ChannelFuture deregister() {
        return pipeline().deregister();
    }

    @Override
    default ChannelFuture close() {
        return pipeline().close();
    }

    @Override
    default ChannelFuture disconnect() {
        return pipeline().disconnect();
    }

    @Override
    default ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return pipeline().connect(remoteAddress, localAddress);
    }

    @Override
    default ChannelFuture connect(SocketAddress remoteAddress) {
        return pipeline().connect(remoteAddress);
    }

    @Override
    default ChannelFuture bind(SocketAddress localAddress) {
        return pipeline().bind(localAddress);
    }

    @Override
    default ChannelPromise newPromise() {
        return pipeline().newPromise();
    }

    @Override
    default ChannelProgressivePromise newProgressivePromise() {
        return pipeline().newProgressivePromise();
    }

    @Override
    default ChannelFuture newSucceededFuture() {
        return pipeline().newSucceededFuture();
    }

    @Override
    default ChannelFuture newFailedFuture(Throwable cause) {
        return pipeline().newFailedFuture(cause);
    }

    @Override
    default ChannelPromise voidPromise() {
        return pipeline().voidPromise();
    }

    /**
     * <em>Unsafe</em> 仅供 Netty 内部使用的底层操作接口。
     * <p>
     * <b>警告：用户代码请勿直接调用！</b>
     * <ul>
     *   <li>仅实现底层传输相关操作，必须在 I/O 线程中调用（除部分方法外）。</li>
     *   <li>典型场景：注册、绑定、连接、关闭、写入、读等底层操作。</li>
     *   <li>部分方法如 closeForcibly()、register()、deregister() 可在任意线程调用。</li>
     * </ul>
     * </p>
     */
    interface Unsafe {

        /**
         * 返回分配给该 Channel 的 {@link RecvByteBufAllocator.Handle}，用于接收数据时分配 ByteBuf。
         */
        RecvByteBufAllocator.Handle recvBufAllocHandle();

        /**
         * 返回绑定的本地地址，若无则为 null。
         */
        SocketAddress localAddress();

        /**
         * 返回绑定的远程地址，若无则为 null。
         */
        SocketAddress remoteAddress();

        /**
         * 注册 Channel 到指定 EventLoop，注册完成后通知 Promise。
         * <b>关键节点说明：</b> 该方法由 Netty 内部调用，确保 Channel 生命周期与事件循环绑定。
         */
        void register(EventLoop eventLoop, ChannelPromise promise);

        /**
         * 绑定本地地址，绑定完成后通知 Promise。
         * <b>关键节点说明：</b> 服务端 Channel 绑定端口监听的核心入口。
         */
        void bind(SocketAddress localAddress, ChannelPromise promise);

        /**
         * 连接到远程地址，连接完成后通知 Promise。
         * <b>关键节点说明：</b> 客户端 Channel 发起连接的底层实现。
         */
        void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise);

        /**
         * 断开连接，操作完成后通知 Promise。
         * <b>关键节点说明：</b> 关闭底层连接，释放相关资源。
         */
        void disconnect(ChannelPromise promise);

        /**
         * 关闭 Channel，操作完成后通知 Promise。
         * <b>关键节点说明：</b> 资源释放的最终入口，确保所有资源彻底回收。
         */
        void close(ChannelPromise promise);

        /**
         * 强制关闭 Channel，不触发任何事件。
         * <b>关键节点说明：</b> 适用于注册失败等异常场景下的紧急资源回收。
         */
        void closeForcibly();

        /**
         * 注销 Channel，操作完成后通知 Promise。
         * <b>关键节点说明：</b> Channel 生命周期结束时，从 EventLoop 注销，防止事件泄漏。
         */
        void deregister(ChannelPromise promise);

        /**
         * 安排一次读操作，填充第一个 ChannelInboundHandler 的入站缓冲区。
         * <b>关键节点说明：</b> 触发数据读取，驱动 pipeline 入站事件。
         */
        void beginRead();

        /**
         * 安排一次写操作。
         * <b>关键节点说明：</b> 消息写入后不会立即发送，需调用 flush() 刷新。
         */
        void write(Object msg, ChannelPromise promise);

        /**
         * 刷新所有已安排的写操作，真正发送数据。
         * <b>关键节点说明：</b> 触发底层网络发送，清空写缓冲区。
         */
        void flush();

        /**
         * 返回可复用的特殊 ChannelPromise，不会收到成功或失败通知。
         * <p>
         * 仅作为占位符使用，适用于不关心结果的场景。
         * </p>
         */
        ChannelPromise voidPromise();

        /**
         * 返回 Channel 的 {@link ChannelOutboundBuffer}，用于存储待发送的写请求。
         */
        ChannelOutboundBuffer outboundBuffer();
    }
}
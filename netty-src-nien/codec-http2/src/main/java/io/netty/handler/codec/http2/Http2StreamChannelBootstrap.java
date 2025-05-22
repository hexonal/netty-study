/*
 * Copyright 2017 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http2;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用于创建和引导 {@link Http2StreamChannel} 的辅助类。
 * 它允许为新的 HTTP/2 流通道配置选项 ({@link ChannelOption})、属性 ({@link AttributeKey}) 和处理器
 * ({@link ChannelHandler})。
 * <p>
 * 此引导程序与父 {@link Channel} (即 HTTP/2 连接的通道) 关联，并通过父通道的
 * {@link Http2MultiplexCodec} 或
 * {@link Http2MultiplexHandler} 来实际创建出站流。
 * <p>
 * <b>注意:</b> 此 API 目前标记为 {@link UnstableApi}，意味着它在未来的 Netty 版本中可能会发生变化。
 */
@UnstableApi
public final class Http2StreamChannelBootstrap {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Http2StreamChannelBootstrap.class);
    // 用于 ChannelOption 转换的空数组常量，避免重复创建。
    @SuppressWarnings("unchecked")
    private static final Map.Entry<ChannelOption<?>, Object>[] EMPTY_OPTION_ARRAY = new Map.Entry[0];
    // 用于 AttributeKey 转换的空数组常量，避免重复创建。
    @SuppressWarnings("unchecked")
    private static final Map.Entry<AttributeKey<?>, Object>[] EMPTY_ATTRIBUTE_ARRAY = new Map.Entry[0];

    // 存储为新流通道配置的 ChannelOption。使用 LinkedHashMap 保持选项插入顺序，因为某些选项可能依赖于顺序。
    // 对 options 的访问通过 synchronized 块进行同步。
    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<ChannelOption<?>, Object>();
    // 存储为新流通道配置的 AttributeKey。使用 ConcurrentHashMap 以支持潜在的并发访问（尽管配置阶段通常是单线程的）。
    private final Map<AttributeKey<?>, Object> attrs = new ConcurrentHashMap<AttributeKey<?>, Object>();
    // 父 Channel，即 HTTP/2 连接所在的 Channel。
    private final Channel channel;
    // 将应用于新创建的 Http2StreamChannel 的处理器。
    private volatile ChannelHandler handler;

    // 缓存 Http2MultiplexCodec/Handler 的 ChannelHandlerContext，以加速 open(...) 操作。
    // volatile 保证多线程可见性。
    private volatile ChannelHandlerContext multiplexCtx;

    /**
     * 创建一个新的 {@link Http2StreamChannelBootstrap} 实例。
     *
     * @param channel 父 {@link Channel}，新的 HTTP/2 流将通过此通道创建。不能为空。
     * @throws NullPointerException 如果 {@code channel} 为 {@code null}。
     */
    public Http2StreamChannelBootstrap(Channel channel) {
        this.channel = ObjectUtil.checkNotNull(channel, "channel");
    }

    /**
     * 为将要创建的 {@link Http2StreamChannel} 实例指定一个 {@link ChannelOption}。
     * 如果值为 {@code null}，则移除先前设置的 {@link ChannelOption}。
     *
     * @param option 要设置的 {@link ChannelOption}。不能为空。
     * @param value  选项的值。如果为 {@code null}，则移除该选项。
     * @param <T>    选项值的类型。
     * @return 此 {@link Http2StreamChannelBootstrap} 实例，以支持链式调用。
     * @throws NullPointerException 如果 {@code option} 为 {@code null}。
     */
    @SuppressWarnings("unchecked")
    public <T> Http2StreamChannelBootstrap option(ChannelOption<T> option, T value) {
        ObjectUtil.checkNotNull(option, "option");

        // 同步访问 options map，因为 LinkedHashMap 不是线程安全的。
        synchronized (options) {
            if (value == null) {
                options.remove(option);
            } else {
                options.put(option, value);
            }
        }
        return this;
    }

    /**
     * 为新创建的 {@link Http2StreamChannel} 指定一个初始属性。
     * 如果 {@code value} 为 {@code null}，则移除指定 {@code key} 的属性。
     *
     * @param key   要设置的属性的 {@link AttributeKey}。不能为空。
     * @param value 属性的值。如果为 {@code null}，则移除该属性。
     * @param <T>   属性值的类型。
     * @return 此 {@link Http2StreamChannelBootstrap} 实例，以支持链式调用。
     * @throws NullPointerException 如果 {@code key} 为 {@code null}。
     */
    @SuppressWarnings("unchecked")
    public <T> Http2StreamChannelBootstrap attr(AttributeKey<T> key, T value) {
        ObjectUtil.checkNotNull(key, "key");
        if (value == null) {
            attrs.remove(key);
        } else {
            attrs.put(key, value);
        }
        return this;
    }

    /**
     * 设置用于处理新创建的 {@link Http2StreamChannel} 请求的 {@link ChannelHandler}。
     *
     * @param handler 要设置的 {@link ChannelHandler}。不能为空。
     * @return 此 {@link Http2StreamChannelBootstrap} 实例，以支持链式调用。
     * @throws NullPointerException 如果 {@code handler} 为 {@code null}。
     */
    public Http2StreamChannelBootstrap handler(ChannelHandler handler) {
        this.handler = ObjectUtil.checkNotNull(handler, "handler");
        return this;
    }

    /**
     * 打开一个新的 {@link Http2StreamChannel} 以供使用。
     * 这是一个异步操作。
     *
     * @return 一个 {@link Future}，当通道成功打开或失败时，该 Future 将被通知。
     */
    public Future<Http2StreamChannel> open() {
        // 使用父 Channel 的 EventLoop 来创建 Promise。
        return open(channel.eventLoop().<Http2StreamChannel>newPromise());
    }

    /**
     * 打开一个新的 {@link Http2StreamChannel} 以供使用，并通过给定的 {@link Promise} 进行通知。
     * 这是一个异步操作。
     *
     * @param promise 用于接收操作结果的 {@link Promise}。
     * @return 传入的 {@link Promise} 本身，转换为了 {@link Future}。
     */
    @SuppressWarnings("deprecation") // open0 是废弃的，但这里是内部调用
    public Future<Http2StreamChannel> open(final Promise<Http2StreamChannel> promise) {
        try {
            // 查找父 Channel Pipeline 中的 Http2MultiplexCodec 或 Http2MultiplexHandler 的上下文。
            ChannelHandlerContext ctx = findCtx();
            EventExecutor executor = ctx.executor();
            // 确保操作在正确的 EventLoop 中执行。
            if (executor.inEventLoop()) {
                open0(ctx, promise);
            } else {
                final ChannelHandlerContext finalCtx = ctx;
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        open0(finalCtx, promise);
                    }
                });
            }
        } catch (Throwable cause) {
            promise.setFailure(cause);
        }
        return promise;
    }

    /**
     * 查找并返回父 {@link Channel} Pipeline 中 {@link Http2MultiplexCodec} 或
     * {@link Http2MultiplexHandler}
     * 的 {@link ChannelHandlerContext}。
     * 此上下文将用于创建新的出站流。
     * 如果找到上下文，则会缓存它以供后续快速访问。
     *
     * @return {@link Http2MultiplexCodec} 或 {@link Http2MultiplexHandler} 的
     *         {@link ChannelHandlerContext}。
     * @throws ClosedChannelException 如果父 {@link Channel} 已关闭。
     * @throws IllegalStateException  如果在活动的父 {@link Channel} 的 Pipeline 中找不到所需的
     *                                Codec/Handler。
     */
    private ChannelHandlerContext findCtx() throws ClosedChannelException {
        // 首先尝试使用缓存的上下文，如果无效则重新查找。
        ChannelHandlerContext ctx = this.multiplexCtx;
        if (ctx != null && !ctx.isRemoved()) {
            return ctx;
        }
        ChannelPipeline pipeline = channel.pipeline();
        // 优先查找 Http2MultiplexCodec
        ctx = pipeline.context(Http2MultiplexCodec.class);
        if (ctx == null) {
            // 如果没有找到 Codec，则查找 Http2MultiplexHandler
            ctx = pipeline.context(Http2MultiplexHandler.class);
        }
        if (ctx == null) {
            // 如果父 Channel 仍然活动但找不到必要的 Codec/Handler，则表示配置错误。
            if (channel.isActive()) {
                throw new IllegalStateException(StringUtil.simpleClassName(Http2MultiplexCodec.class) + " or "
                        + StringUtil.simpleClassName(Http2MultiplexHandler.class)
                        + " must be in the ChannelPipeline of Channel " + channel);
            } else {
                // 如果父 Channel 已关闭，则抛出 ClosedChannelException。
                throw new ClosedChannelException();
            }
        }
        // 缓存找到的上下文。
        this.multiplexCtx = ctx;
        return ctx;
    }

    /**
     * 实际打开并注册新的 {@link Http2StreamChannel} 的内部方法。
     * 此方法必须在 {@link ChannelHandlerContext} 的 {@link EventExecutor} 中执行。
     *
     * @param ctx     {@link Http2MultiplexCodec} 或 {@link Http2MultiplexHandler}
     *                的上下文。
     * @param promise 用于通知操作结果的 {@link Promise}。
     * @deprecated 此方法不应直接使用。请使用 {@link #open()} 或 {@link #open(Promise)}。
     */
    @Deprecated
    public void open0(ChannelHandlerContext ctx, final Promise<Http2StreamChannel> promise) {
        assert ctx.executor().inEventLoop(); // 确保在正确的 EventLoop 中执行

        // 设置 Promise 为不可取消，因为流的创建过程一旦开始，通常不应被外部取消。
        if (!promise.setUncancellable()) {
            return; // 如果 Promise 已经被完成或取消，则直接返回。
        }

        final Http2StreamChannel streamChannel;
        // 根据上下文中的处理器类型（Http2MultiplexCodec 或 Http2MultiplexHandler）创建出站流。
        if (ctx.handler() instanceof Http2MultiplexCodec) {
            streamChannel = ((Http2MultiplexCodec) ctx.handler()).newOutboundStream();
        } else {
            streamChannel = ((Http2MultiplexHandler) ctx.handler()).newOutboundStream();
        }

        try {
            // 初始化新创建的流通道 (应用 options, attrs, handler)。
            init(streamChannel);
        } catch (Exception e) {
            // 如果初始化失败，强制关闭流通道并使 Promise 失败。
            streamChannel.unsafe().closeForcibly();
            promise.setFailure(e);
            return;
        }

        // 将新创建的流通道注册到父通道的 EventLoop。
        // 这是一个异步操作。
        ChannelFuture future = ctx.channel().eventLoop().register(streamChannel);
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (future.isSuccess()) {
                    // 注册成功，Promise 成功。
                    promise.setSuccess(streamChannel);
                } else if (future.isCancelled()) {
                    // 注册被取消（理论上不应发生，因为已设为不可取消）。
                    promise.cancel(false);
                } else {
                    // 注册失败。
                    // 如果流通道已经注册，则正常关闭；否则强制关闭。
                    if (streamChannel.isRegistered()) {
                        streamChannel.close();
                    } else {
                        streamChannel.unsafe().closeForcibly();
                    }
                    promise.setFailure(future.cause());
                }
            }
        });
    }

    /**
     * 初始化新创建的 {@link Http2StreamChannel}。
     * 这包括添加配置的处理器、选项和属性。
     *
     * @param channel 要初始化的 {@link Http2StreamChannel}。
     */
    private void init(Channel channel) {
        ChannelPipeline p = channel.pipeline();
        ChannelHandler handler = this.handler; // 获取配置的处理器
        if (handler != null) {
            p.addLast(handler);
        }
        // 将配置的 ChannelOption 转换为数组形式。
        final Map.Entry<ChannelOption<?>, Object>[] optionArray;
        synchronized (options) { // options map 需要同步访问
            optionArray = options.entrySet().toArray(EMPTY_OPTION_ARRAY);
        }

        setChannelOptions(channel, optionArray); // 应用 ChannelOption
        setAttributes(channel, attrs.entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY)); // 应用 AttributeKey
    }

    /**
     * 将 {@link ChannelOption} 数组批量设置到给定的 {@link Channel}。
     *
     * @param channel 要设置选项的 {@link Channel}。
     * @param options 要设置的 {@link ChannelOption} 条目数组。
     */
    private static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options) {
        for (Map.Entry<ChannelOption<?>, Object> e : options) {
            setChannelOption(channel, e.getKey(), e.getValue());
        }
    }

    /**
     * 设置单个 {@link ChannelOption} 到给定的 {@link Channel}。
     * 如果选项未知或设置失败，会记录警告日志。
     *
     * @param channel 要设置选项的 {@link Channel}。
     * @param option  要设置的 {@link ChannelOption}。
     * @param value   选项的值。
     */
    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value) {
        try {
            @SuppressWarnings("unchecked")
            ChannelOption<Object> opt = (ChannelOption<Object>) option;
            if (!channel.config().setOption(opt, value)) {
                // 日志级别：WARN。当 ChannelOption 不被识别时记录。
                logger.warn("Unknown channel option '{}' for channel '{}'", option, channel);
            }
        } catch (Throwable t) {
            // 日志级别：WARN。当设置 ChannelOption 失败时记录，并包含异常堆栈。
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}'", option, value, channel, t);
        }
    }

    /**
     * 将 {@link AttributeKey} 数组批量设置到给定的 {@link Channel}。
     *
     * @param channel 要设置属性的 {@link Channel}。
     * @param attrs   要设置的 {@link AttributeKey} 条目数组。
     */
    private static void setAttributes(
            Channel channel, Map.Entry<AttributeKey<?>, Object>[] attrs) {
        for (Map.Entry<AttributeKey<?>, Object> e : attrs) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            channel.attr(key).set(e.getValue());
        }
    }
}

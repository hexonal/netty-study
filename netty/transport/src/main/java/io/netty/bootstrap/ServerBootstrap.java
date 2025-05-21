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
package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} 的子类，用于简化 {@link ServerChannel} 的引导和启动。
 * <p>
 * 该类是 Netty 服务端启动的核心入口，负责服务端 Channel 的初始化、参数配置、子 Channel 的处理器和事件循环分组的设置等。
 * 推荐从本类入手理解 Netty 服务端的主流程。
 * </p>
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    // 使用 Netty 内部日志工厂获取 logger，日志级别和用法需符合 Netty 规范
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    // 子 Channel 的配置选项，使用 LinkedHashMap 保证顺序，某些选项依赖顺序校验
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();
    // 子 Channel 的属性，使用线程安全的 ConcurrentHashMap
    private final Map<AttributeKey<?>, Object> childAttrs = new ConcurrentHashMap<AttributeKey<?>, Object>();
    // ServerBootstrap 的配置对象
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this);
    // 子事件循环组（用于处理已接入连接的 IO 事件）
    private volatile EventLoopGroup childGroup;
    // 子 Channel 的处理器
    private volatile ChannelHandler childHandler;

    /**
     * 默认构造方法
     */
    public ServerBootstrap() {
    }

    /**
     * 拷贝构造方法，用于 clone 操作
     */
    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        childAttrs.putAll(bootstrap.childAttrs);
    }

    /**
     * 设置 parent（接收连接）和 child（处理连接）共用的事件循环组。
     * 通常用于单线程模型或测试场景。
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * 分别设置 parent（接收连接）和 child（处理连接）的事件循环组。
     * parentGroup 负责监听端口和接收新连接，childGroup 负责处理已接入连接的 IO 事件。
     *
     * @param parentGroup 负责 accept 的事件循环组
     * @param childGroup  负责已接入连接 IO 的事件循环组
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        super.group(parentGroup);
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = ObjectUtil.checkNotNull(childGroup, "childGroup");
        return this;
    }

    /**
     * 设置子 Channel 的 ChannelOption 配置项。
     * 可通过传入 null 移除已设置的选项。
     *
     * 由于 childOptions 可能在多线程环境下被并发访问（如服务端启动和配置阶段），
     * 这里采用 synchronized 锁保证对 childOptions 的操作线程安全，
     * 防止并发修改导致的数据不一致或异常。
     *
     * @param childOption 子 Channel 的配置项
     * @param value       配置值
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        ObjectUtil.checkNotNull(childOption, "childOption");
        synchronized (childOptions) {
            if (value == null) {
                childOptions.remove(childOption);
            } else {
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    /**
     * 设置子 Channel 的 AttributeKey 属性。
     * 可通过传入 null 移除已设置的属性。
     *
     * @param childKey 子 Channel 的属性 key
     * @param value    属性值
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        ObjectUtil.checkNotNull(childKey, "childKey");
        if (value == null) {
            childAttrs.remove(childKey);
        } else {
            childAttrs.put(childKey, value);
        }
        return this;
    }

    /**
     * 设置子 Channel 的处理器（ChannelHandler）。
     * 该处理器会被添加到每个新接入的子 Channel 的 pipeline 中。
     *
     * @param childHandler 子 Channel 的处理器
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
        return this;
    }

    /**
     * 初始化主 Channel，设置选项、属性，并添加 ChannelInitializer。
     * 该方法由父类调用，主要负责服务端 Channel 的 pipeline 初始化。
     *
     * @param channel 服务端 Channel
     */
    @Override
    void init(Channel channel) {
        setChannelOptions(channel, newOptionsArray(), logger);
        setAttributes(channel, newAttributesArray());

        ChannelPipeline p = channel.pipeline();

        final EventLoopGroup currentChildGroup = childGroup;
        final ChannelHandler currentChildHandler = childHandler;
        final Entry<ChannelOption<?>, Object>[] currentChildOptions = newOptionsArray(childOptions);
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs = newAttributesArray(childAttrs);
        final Collection<ChannelInitializerExtension> extensions = getInitializerExtensions();

        // 添加 ChannelInitializer，负责后续子 Channel 的初始化
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) {
                final ChannelPipeline pipeline = ch.pipeline();
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    pipeline.addLast(handler);
                }

                // 在事件循环中异步添加 ServerBootstrapAcceptor 处理器
                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.addLast(new ServerBootstrapAcceptor(
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs,
                                extensions));
                    }
                });
            }
        });
        // 针对扩展点，允许对 ServerChannel 进行额外初始化
        if (!extensions.isEmpty() && channel instanceof ServerChannel) {
            ServerChannel serverChannel = (ServerChannel) channel;
            for (ChannelInitializerExtension extension : extensions) {
                try {
                    extension.postInitializeServerListenerChannel(serverChannel);
                } catch (Exception e) {
                    // warn 级别日志，记录扩展初始化异常，包含堆栈信息，便于排查
                    logger.warn("Exception thrown from postInitializeServerListenerChannel", e);
                }
            }
        }
    }

    /**
     * 校验 ServerBootstrap 配置的完整性。
     * 若 childHandler 未设置则抛出异常，若 childGroup 未设置则使用 parentGroup 并输出 warn 日志。
     */
    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            // warn 级别日志，提示 childGroup 未设置，自动使用 parentGroup
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    /**
     * 服务端接收新连接后，负责初始化子 Channel 的处理器、选项、属性，并注册到 childGroup。
     * 该处理器作为 ServerBootstrap 的核心内部类。
     */
    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

        private final EventLoopGroup childGroup;
        private final ChannelHandler childHandler;
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        private final Runnable enableAutoReadTask;
        private final Collection<ChannelInitializerExtension> extensions;

        /**
         * 构造方法，初始化各类参数和自动重启 autoRead 的任务。
         * 
         * @param channel      服务端 Channel
         * @param childGroup   子事件循环组
         * @param childHandler 子 Channel 处理器
         * @param childOptions 子 Channel 配置项
         * @param childAttrs   子 Channel 属性
         * @param extensions   扩展点集合
         */
        ServerBootstrapAcceptor(
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs,
                Collection<ChannelInitializerExtension> extensions) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;
            this.extensions = extensions;

            // 用于恢复 autoRead 的任务，避免因异常导致服务端长时间不接收新连接
            // 相关 issue: https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        /**
         * 处理新接入的子 Channel，设置 handler、选项、属性，并注册到 childGroup。
         * 若注册失败则强制关闭并输出 warn 日志。
         */
        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            final Channel child = (Channel) msg;

            // 为子 Channel 添加用户自定义的 handler
            child.pipeline().addLast(childHandler);

            // 设置子 Channel 的配置项和属性
            setChannelOptions(child, childOptions, logger);
            setAttributes(child, childAttrs);

            // 处理扩展点
            if (!extensions.isEmpty()) {
                for (ChannelInitializerExtension extension : extensions) {
                    try {
                        extension.postInitializeServerChildChannel(child);
                    } catch (Exception e) {
                        // warn 级别日志，记录扩展初始化异常
                        logger.warn("Exception thrown from postInitializeServerChildChannel", e);
                    }
                }
            }

            try {
                // 注册子 Channel 到 childGroup，异步监听注册结果
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        /**
         * 注册失败时强制关闭子 Channel，并输出 warn 日志，包含异常堆栈。
         * 
         * @param child 子 Channel
         * @param t     异常信息
         */
        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        /**
         * 处理 pipeline 中抛出的异常。
         * 若 autoRead 开启，则临时关闭 1 秒，防止异常风暴，随后自动恢复。
         * 异常继续向下传播，便于用户自定义处理。
         */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // warn 级别日志，记录异常并临时关闭 autoRead，1 秒后自动恢复
                // 相关 issue: https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // 异常继续传播，便于用户自定义处理
            ctx.fireExceptionCaught(cause);
        }
    }

    /**
     * clone 方法，深拷贝 ServerBootstrap 配置。
     */
    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * 获取子 Channel 的事件循环组（已废弃，推荐使用 config()）。
     * 
     * @deprecated 建议使用 {@link #config()} 替代
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    /**
     * 获取子 Channel 的处理器
     */
    final ChannelHandler childHandler() {
        return childHandler;
    }

    /**
     * 获取子 Channel 的配置项副本
     */
    final Map<ChannelOption<?>, Object> childOptions() {
        synchronized (childOptions) {
            return copiedMap(childOptions);
        }
    }

    /**
     * 获取子 Channel 的属性副本
     */
    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    /**
     * 获取 ServerBootstrap 的配置对象
     */
    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}

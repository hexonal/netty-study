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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NameResolver;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;

/**
 * 一个 {@link Bootstrap}，用于简化客户端 {@link Channel} 的引导过程。
 *
 * <p>当与无连接传输（如 UDP）结合使用时，{@link #bind()} 方法非常有用。
 * 对于常规的 TCP 连接，请使用提供的 {@link #connect()} 方法。</p>
 *
 * <p><b>核心作用：</b> 负责客户端 Channel 的创建、配置、注册、连接等全生命周期管理，支持异步、可扩展、链式调用。</p>
 *
 * <p><b>典型用法：</b>
 * <pre>
 *   Bootstrap b = new Bootstrap();
 *   b.group(...)
 *    .channel(...)
 *    .handler(...)
 *    .remoteAddress(...);
 *   ChannelFuture f = b.connect();
 * </pre>
 * </p>
 */
public class Bootstrap extends AbstractBootstrap<Bootstrap, Channel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Bootstrap.class);

    private final BootstrapConfig config = new BootstrapConfig(this);

    // 外部地址解析器，支持自定义 DNS/主机名解析
    private ExternalAddressResolver externalResolver;
    // 是否禁用地址解析
    private volatile boolean disableResolver;
    // 远程连接地址
    private volatile SocketAddress remoteAddress;

    public Bootstrap() { }

    private Bootstrap(Bootstrap bootstrap) {
        super(bootstrap);
        externalResolver = bootstrap.externalResolver;
        disableResolver = bootstrap.disableResolver;
        remoteAddress = bootstrap.remoteAddress;
    }

    /**
     * 设置 {@link NameResolver}，用于解析未解析的命名地址。
     *
     * <b>常见场景：</b> 需要自定义 DNS 解析、支持多种地址族、或特殊网络环境下。
     *
     * @param resolver 该 {@code Bootstrap} 的 {@link NameResolver}；可以为 {@code null}，此时将使用默认解析器
     *
     * @see io.netty.resolver.DefaultAddressResolverGroup
     */
    public Bootstrap resolver(AddressResolverGroup<?> resolver) {
        externalResolver = resolver == null ? null : new ExternalAddressResolver(resolver);
        disableResolver = false;
        return this;
    }

    /**
     * 禁用地址名称解析。可通过 {@link Bootstrap#resolver(AddressResolverGroup)} 重新启用。
     *
     * <b>典型场景：</b> 远程地址已是 IP，无需 DNS 解析，或自定义了连接逻辑。
     */
    public Bootstrap disableResolver() {
        externalResolver = null;
        disableResolver = true;
        return this;
    }

    /**
     * 在调用 {@link #connect()} 方法后要连接的 {@link SocketAddress}。
     *
     * <b>注意：</b> 必须设置，否则 connect() 会抛出 IllegalStateException。
     */
    public Bootstrap remoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
        return this;
    }

    /**
     * 通过主机名和端口设置远程地址，内部会创建未解析的 InetSocketAddress。
     */
    public Bootstrap remoteAddress(String inetHost, int inetPort) {
        remoteAddress = InetSocketAddress.createUnresolved(inetHost, inetPort);
        return this;
    }

    /**
     * 通过 IP 和端口设置远程地址。
     */
    public Bootstrap remoteAddress(InetAddress inetHost, int inetPort) {
        remoteAddress = new InetSocketAddress(inetHost, inetPort);
        return this;
    }

    /**
     * 连接到远程节点。
     *
     * <b>核心入口：</b> 完成参数校验、注册 Channel、异步解析地址、最终发起连接。
     *
     * @return ChannelFuture，异步通知连接结果
     * @throws IllegalStateException 未设置 remoteAddress
     */
    public ChannelFuture connect() {
        validate();
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            throw new IllegalStateException("remoteAddress not set");
        }

        return doResolveAndConnect(remoteAddress, config.localAddress());
    }

    /**
     * 通过主机名和端口连接远程节点。
     */
    public ChannelFuture connect(String inetHost, int inetPort) {
        return connect(InetSocketAddress.createUnresolved(inetHost, inetPort));
    }

    /**
     * 通过 IP 和端口连接远程节点。
     */
    public ChannelFuture connect(InetAddress inetHost, int inetPort) {
        return connect(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * 通过 SocketAddress 连接远程节点。
     *
     * <b>调用链说明：</b> connect() -> doResolveAndConnect() -> doResolveAndConnect0() -> doConnect()
     *
     * <b>异步机制：</b> 注册、解析、连接均为异步，结果通过 ChannelFuture 通知。
     */
    public ChannelFuture connect(SocketAddress remoteAddress) {
        ObjectUtil.checkNotNull(remoteAddress, "remoteAddress");
        validate();
        return doResolveAndConnect(remoteAddress, config.localAddress());
    }

    /**
     * 通过远程和本地地址连接。
     *
     * <b>高级用法：</b> 可指定本地绑定端口（如多网卡、多端口需求）。
     */
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        ObjectUtil.checkNotNull(remoteAddress, "remoteAddress");
        validate();
        return doResolveAndConnect(remoteAddress, localAddress);
    }

    /**
     * 内部核心流程：注册 Channel、异步解析远程地址、最终发起连接。
     *
     * <b>流程详解：</b>
     * 1. initAndRegister()：初始化并注册 Channel 到 EventLoop。
     * 2. 若注册已完成，直接进入 doResolveAndConnect0。
     * 3. 若注册未完成，添加监听器，注册完成后再进入 doResolveAndConnect0。
     *
     * <b>易错点：</b> 注册失败时直接返回失败的 ChannelFuture。
     */
    private ChannelFuture doResolveAndConnect(final SocketAddress remoteAddress, final SocketAddress localAddress) {
        final ChannelFuture regFuture = initAndRegister();
        final Channel channel = regFuture.channel();

        if (regFuture.isDone()) {
            if (!regFuture.isSuccess()) {
                return regFuture;
            }
            return doResolveAndConnect0(channel, remoteAddress, localAddress, channel.newPromise());
        } else {
            // 注册通常已经完成，但以防万一未完成。
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            regFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    // 直接获取异常并做 null 检查，减少 volatile 读取。
                    Throwable cause = future.cause();
                    if (cause != null) {
                        // EventLoop 注册失败，直接失败 promise，避免后续访问 Channel 的 EventLoop 时抛出 IllegalStateException。
                        promise.setFailure(cause);
                    } else {
                        // 注册成功，设置正确的执行器。
                        // 详见 https://github.com/netty/netty/issues/2586
                        promise.registered();
                        doResolveAndConnect0(channel, remoteAddress, localAddress, promise);
                    }
                }
            });
            return promise;
        }
    }

    /**
     * 地址解析与连接的核心实现。
     *
     * <b>详细流程：</b>
     * 1. 若禁用解析，直接连接。
     * 2. 获取 EventLoop 对应的 AddressResolver。
     * 3. 若地址已解析或不支持，直接连接。
     * 4. 否则异步解析，解析完成后再连接。
     *
     * <b>异常处理：</b> 解析失败会关闭 Channel 并设置失败。
     */
    private ChannelFuture doResolveAndConnect0(final Channel channel, SocketAddress remoteAddress,
                                               final SocketAddress localAddress, final ChannelPromise promise) {
        try {
            if (disableResolver) {
                doConnect(remoteAddress, localAddress, promise);
                return promise;
            }

            final EventLoop eventLoop = channel.eventLoop();
            AddressResolver<SocketAddress> resolver;
            try {
                resolver = ExternalAddressResolver.getOrDefault(externalResolver).getResolver(eventLoop);
            } catch (Throwable cause) {
                channel.close();
                return promise.setFailure(cause);
            }

            if (!resolver.isSupported(remoteAddress) || resolver.isResolved(remoteAddress)) {
                // 解析器不支持该地址或已解析，直接连接。
                doConnect(remoteAddress, localAddress, promise);
                return promise;
            }

            final Future<SocketAddress> resolveFuture = resolver.resolve(remoteAddress);

            if (resolveFuture.isDone()) {
                final Throwable resolveFailureCause = resolveFuture.cause();

                if (resolveFailureCause != null) {
                    // 立即解析失败
                    channel.close();
                    promise.setFailure(resolveFailureCause);
                } else {
                    // 立即解析成功（可能是缓存或阻塞查找）
                    doConnect(resolveFuture.getNow(), localAddress, promise);
                }
                return promise;
            }

            // 等待名称解析完成。
            resolveFuture.addListener(new FutureListener<SocketAddress>() {
                @Override
                public void operationComplete(Future<SocketAddress> future) throws Exception {
                    if (future.cause() != null) {
                        channel.close();
                        promise.setFailure(future.cause());
                    } else {
                        doConnect(future.getNow(), localAddress, promise);
                    }
                }
            });
        } catch (Throwable cause) {
            promise.tryFailure(cause);
        }
        return promise;
    }

    /**
     * 真正发起连接的地方。
     *
     * <b>异步执行：</b> 通过 EventLoop 执行，保证线程安全。
     * <b>本地地址可选：</b> 若 localAddress 为 null，则只指定远程地址。
     * <b>失败监听：</b> 添加 CLOSE_ON_FAILURE，连接失败自动关闭 Channel。
     */
    private static void doConnect(
            final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise connectPromise) {

        // 该方法在 channelRegistered() 触发前调用，给用户 handler 在 channelRegistered() 中设置 pipeline 的机会。
        final Channel channel = connectPromise.channel();
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (localAddress == null) {
                    channel.connect(remoteAddress, connectPromise);
                } else {
                    channel.connect(remoteAddress, localAddress, connectPromise);
                }
                connectPromise.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
        });
    }

    /**
     * 初始化 Channel，设置 handler、options、attributes 等。
     *
     * <b>扩展点：</b> 支持 ChannelInitializerExtension，可用于自定义初始化逻辑。
     */
    @Override
    void init(Channel channel) {
        ChannelPipeline p = channel.pipeline();
        p.addLast(config.handler());

        setChannelOptions(channel, newOptionsArray(), logger);
        setAttributes(channel, newAttributesArray());
        Collection<ChannelInitializerExtension> extensions = getInitializerExtensions();
        if (!extensions.isEmpty()) {
            for (ChannelInitializerExtension extension : extensions) {
                try {
                    extension.postInitializeClientChannel(channel);
                } catch (Exception e) {
                    logger.warn("Exception thrown from postInitializeClientChannel", e);
                }
            }
        }
    }

    /**
     * 校验配置项，确保 handler 已设置。
     *
     * <b>易错点：</b> 未设置 handler 会抛出 IllegalStateException。
     */
    @Override
    public Bootstrap validate() {
        super.validate();
        if (config.handler() == null) {
            throw new IllegalStateException("handler not set");
        }
        return this;
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public Bootstrap clone() {
        return new Bootstrap(this);
    }

    /**
     * 返回该 bootstrap 的深拷贝，除了使用指定的 {@link EventLoopGroup} 外，其他配置完全一致。
     * 该方法适用于创建多个具有相似设置的 {@link Channel}。
     *
     * <b>常见用法：</b> 批量创建连接时，复用配置但切换 group。
     */
    public Bootstrap clone(EventLoopGroup group) {
        Bootstrap bs = new Bootstrap(this);
        bs.group = group;
        return bs;
    }

    @Override
    public final BootstrapConfig config() {
        return config;
    }

    // 获取远程地址（内部使用）
    final SocketAddress remoteAddress() {
        return remoteAddress;
    }

    // 获取地址解析器（内部使用）
    final AddressResolverGroup<?> resolver() {
        if (disableResolver) {
            return null;
        }
        return ExternalAddressResolver.getOrDefault(externalResolver);
    }

    /*
       Holder 类用于避免在排除 netty-resolver 依赖时出现 NoClassDefFoundError
       （例如某些地址族不需要名称解析）
     */
    static final class ExternalAddressResolver {
        final AddressResolverGroup<SocketAddress> resolverGroup;

        @SuppressWarnings("unchecked")
        ExternalAddressResolver(AddressResolverGroup<?> resolverGroup) {
            this.resolverGroup = (AddressResolverGroup<SocketAddress>) resolverGroup;
        }

        @SuppressWarnings("unchecked")
        static AddressResolverGroup<SocketAddress> getOrDefault(ExternalAddressResolver externalResolver) {
            if (externalResolver == null) {
                AddressResolverGroup<?> defaultResolverGroup = DefaultAddressResolverGroup.INSTANCE;
                return (AddressResolverGroup<SocketAddress>) defaultResolverGroup;
            }
            return externalResolver.resolverGroup;
        }
    }
}

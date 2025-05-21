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
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AbstractBootstrap} 是 Netty 客户端/服务端引导的通用抽象模板。
 * <p>
 * <b>核心作用：</b>
 * <ul>
 *   <li>统一封装 Channel 创建、参数配置、注册、绑定等主流程</li>
 *   <li>支持链式调用，便于灵活配置</li>
 *   <li>为 Bootstrap、ServerBootstrap 提供基础能力</li>
 * </ul>
 *
 * <b>典型用法：</b>
 * <pre>
 *   Bootstrap b = new Bootstrap();
 *   b.group(...).channel(...).handler(...).option(...).attr(...);
 *   ChannelFuture f = b.connect(...);
 * </pre>
 *
 * <b>线程安全说明：</b>
 * - 配置阶段（调用 group/channel/option/attr 等）通常在单线程完成
 * - 注册、绑定、连接等操作为异步，线程安全
 *
 * <b>日志规范：</b>
 * - 日志均使用 InternalLogger，日志级别需明确（warn/error 用于异常，debug/info 用于流程追踪）
 * - 异常日志应包含堆栈，便于排查
 */
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {
    @SuppressWarnings("unchecked")
    private static final Map.Entry<ChannelOption<?>, Object>[] EMPTY_OPTION_ARRAY = new Map.Entry[0];
    @SuppressWarnings("unchecked")
    private static final Map.Entry<AttributeKey<?>, Object>[] EMPTY_ATTRIBUTE_ARRAY = new Map.Entry[0];

    // 事件循环组，负责所有 IO 事件的分发和处理
    // 生命周期：由用户传入并管理，通常与应用生命周期一致
    // 并发特性：只允许设置一次，后续只读
    volatile EventLoopGroup group;
    @SuppressWarnings("deprecation")
    // Channel 工厂，负责创建 Channel 实例
    // 支持自定义工厂或反射创建
    private volatile ChannelFactory<? extends C> channelFactory;
    // 本地绑定地址，服务端监听/客户端指定本地端口
    private volatile SocketAddress localAddress;

    // ChannelOption 配置，顺序敏感（部分 option 依赖其他 option）
    // 线程安全：配置阶段需同步，后续只读
    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<ChannelOption<?>, Object>();
    // Attribute 配置，线程安全
    private final Map<AttributeKey<?>, Object> attrs = new ConcurrentHashMap<AttributeKey<?>, Object>();
    // 业务处理器（如 ChannelInitializer），生命周期与 Bootstrap 一致
    private volatile ChannelHandler handler;
    // 扩展类加载器，支持 SPI 扩展
    private volatile ClassLoader extensionsClassLoader;

    /**
     * 构造方法，仅允许同包继承，防止外部扩展破坏主流程
     */
    AbstractBootstrap() {
        // 限制只能在同包下继承
    }

    /**
     * 拷贝构造，深拷贝所有配置（EventLoopGroup 浅拷贝）
     * 典型场景：批量创建多个配置一致的 Bootstrap 实例
     */
    AbstractBootstrap(AbstractBootstrap<B, C> bootstrap) {
        group = bootstrap.group;
        channelFactory = bootstrap.channelFactory;
        handler = bootstrap.handler;
        localAddress = bootstrap.localAddress;
        synchronized (bootstrap.options) {
            options.putAll(bootstrap.options);
        }
        attrs.putAll(bootstrap.attrs);
        extensionsClassLoader = bootstrap.extensionsClassLoader;
    }

    /**
     * 设置事件循环组，负责所有 Channel 的 IO 事件。
     * <b>注意：</b> 只能设置一次，否则抛出异常。
     * <b>典型场景：</b> 必须在 connect/bind 前设置，否则 validate() 抛异常
     */
    public B group(EventLoopGroup group) {
        ObjectUtil.checkNotNull(group, "group");
        // 检查是否已设置 group，防止重复配置
        if (this.group != null) {
            throw new IllegalStateException("group set already");
        }
        // 设置事件循环组
        this.group = group;
        return self();
    }

    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this;
    }

    /**
     * 设置 Channel 的实现类（如 NioSocketChannel、NioServerSocketChannel）。
     * <b>注意：</b> 若无无参构造器，需用 channelFactory 方式
     * <b>典型场景：</b> 配置网络类型（NIO/EPOLL/KQUEUE）
     */
    public B channel(Class<? extends C> channelClass) {
        // 通过反射工厂包装，支持无参构造的 Channel 实现
        return channelFactory(new ReflectiveChannelFactory<C>(
                ObjectUtil.checkNotNull(channelClass, "channelClass")
        ));
    }

    /**
     * @deprecated 建议使用 {@link #channelFactory(io.netty.channel.ChannelFactory)}
     * <b>典型场景：</b> 兼容老版本或特殊自定义工厂
     */
    @Deprecated
    public B channelFactory(ChannelFactory<? extends C> channelFactory) {
        ObjectUtil.checkNotNull(channelFactory, "channelFactory");
        // 检查是否已设置工厂，防止重复配置
        if (this.channelFactory != null) {
            throw new IllegalStateException("channelFactory set already");
        }
        // 设置自定义工厂
        this.channelFactory = channelFactory;
        return self();
    }

    /**
     * 设置 ChannelFactory，支持自定义 Channel 创建逻辑。
     * <b>典型场景：</b> 需要特殊构造参数或自定义 Channel 实现
     */
    @SuppressWarnings({ "unchecked", "deprecation" })
    public B channelFactory(io.netty.channel.ChannelFactory<? extends C> channelFactory) {
        return channelFactory((ChannelFactory<C>) channelFactory);
    }

    /**
     * 设置本地绑定地址。
     * <b>常用于服务端监听端口，客户端指定本地端口。</b>
     * <b>注意：</b> 若未设置，bind() 会抛异常
     */
    public B localAddress(SocketAddress localAddress) {
        this.localAddress = localAddress;
        return self();
    }

    /**
     * 通过端口设置本地地址。
     * <b>便捷方法，常用于服务端</b>
     */
    public B localAddress(int inetPort) {
        return localAddress(new InetSocketAddress(inetPort));
    }

    /**
     * 通过主机名和端口设置本地地址。
     * <b>便捷方法，支持多网卡场景</b>
     */
    public B localAddress(String inetHost, int inetPort) {
        return localAddress(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * 通过 IP 和端口设置本地地址。
     */
    public B localAddress(InetAddress inetHost, int inetPort) {
        return localAddress(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * 设置 ChannelOption，影响 Channel 的底层行为。
     * <b>如 TCP_NODELAY、SO_KEEPALIVE 等。</b>
     * <b>注意：</b> value 为 null 时移除该选项。
     * <b>线程安全：</b> 配置阶段需同步
     */
    public <T> B option(ChannelOption<T> option, T value) {
        ObjectUtil.checkNotNull(option, "option");
        synchronized (options) {
            // value 为 null 表示移除该 option
            if (value == null) {
                options.remove(option);
            } else {
                // 设置或覆盖 option
                options.put(option, value);
            }
        }
        return self();
    }

    /**
     * 设置 Channel 的初始 Attribute。
     * <b>可用于存储自定义数据。</b>
     * <b>注意：</b> value 为 null 时移除该属性。
     * <b>线程安全：</b> 支持并发
     */
    public <T> B attr(AttributeKey<T> key, T value) {
        ObjectUtil.checkNotNull(key, "key");
        // value 为 null 表示移除该 attribute
        if (value == null) {
            attrs.remove(key);
        } else {
            // 设置或覆盖 attribute
            attrs.put(key, value);
        }
        return self();
    }

    /**
     * 指定扩展类加载器，加载 ChannelInitializerExtension。
     * <b>高级扩展点，SPI 场景</b>
     */
    public B extensionsClassLoader(ClassLoader classLoader) {
        // 设置 SPI 扩展用的类加载器
        extensionsClassLoader = classLoader;
        return self();
    }

    /**
     * 校验所有参数，子类可重写但需调用 super。
     * <b>常见校验：group、channelFactory 必须设置。</b>
     * <b>典型场景：</b> connect/bind 前自动校验，防止遗漏配置
     */
    public B validate() {
        // 校验 group 是否已设置
        if (group == null) {
            throw new IllegalStateException("group not set");
        }
        // 校验 channelFactory 是否已设置
        if (channelFactory == null) {
            throw new IllegalStateException("channel or channelFactory not set");
        }
        return self();
    }

    /**
     * 返回该 bootstrap 的深拷贝，配置完全一致。
     * <b>注意：</b> EventLoopGroup 仅浅拷贝，多个 bootstrap 共享同一个 group。
     * <b>典型场景：</b> 批量创建连接、复用配置
     */
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public abstract B clone();

    /**
     * 创建并注册一个新的 Channel。
     * <b>异步操作，返回 ChannelFuture。</b>
     * <b>调用链：</b> register() -> initAndRegister()
     * <b>典型场景：</b> 需要手动注册 Channel 到 EventLoop
     * <b>重要说明：</b>
     * 1. 该方法不会自动绑定端口，仅完成 Channel 的初始化和注册。
     * 2. 注册过程为异步，返回的 ChannelFuture 可监听注册结果。
     * 3. 若注册失败，future.cause() 可获取异常原因。
     * 4. 适合自定义注册流程或特殊场景（如提前注册 Channel）。
     */
    public ChannelFuture register() {
        // 校验配置项，防止遗漏关键参数
        validate();
        // 初始化并注册 Channel，返回异步结果
        return initAndRegister();
    }

    /**
     * 创建并绑定一个新的 Channel。
     * <b>常用于服务端监听端口。</b>
     * <b>调用链：</b> bind() -> doBind() -> doBind0()
     * <b>注意：</b> localAddress 必须已设置
     * <b>重要说明：</b>
     * 1. 该方法会自动完成 Channel 的初始化、注册和端口绑定。
     * 2. 绑定过程为异步，返回的 ChannelFuture 可监听绑定结果。
     * 3. 若绑定失败，future.cause() 可获取异常原因。
     * 4. 适合标准服务端启动流程。
     */
    public ChannelFuture bind() {
        validate();
        SocketAddress localAddress = this.localAddress;
        // 必须设置本地地址，否则抛异常
        if (localAddress == null) {
            throw new IllegalStateException("localAddress not set");
        }
        // 执行绑定流程
        return doBind(localAddress);
    }

    /**
     * 通过端口创建并绑定 Channel。
     * <b>便捷方法</b>
     */
    public ChannelFuture bind(int inetPort) {
        return bind(new InetSocketAddress(inetPort));
    }

    /**
     * 通过主机名和端口创建并绑定 Channel。
     * <b>便捷方法</b>
     */
    public ChannelFuture bind(String inetHost, int inetPort) {
        return bind(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * 通过 IP 和端口创建并绑定 Channel。
     */
    public ChannelFuture bind(InetAddress inetHost, int inetPort) {
        return bind(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * 通过 SocketAddress 创建并绑定 Channel。
     * <b>核心流程：</b>
     * 1. initAndRegister() 初始化并注册 Channel
     * 2. 若注册已完成，直接 doBind0 绑定
     * 3. 若注册未完成，添加监听器，注册完成后再 doBind0
     * <b>异步机制：</b> 绑定结果通过 ChannelFuture 通知
     * <b>线程安全：</b> 注册与绑定均在 EventLoop 线程串行执行
     * <b>重要说明：</b>
     * - 该方法是服务端启动的主流程入口，建议重点跟踪。
     * - 绑定失败时，future.cause() 可获取详细异常。
     * - 若注册未完成，采用 PendingRegistrationPromise 保证回调线程安全。
     */
    public ChannelFuture bind(SocketAddress localAddress) {
        validate();
        // 检查参数非空
        return doBind(ObjectUtil.checkNotNull(localAddress, "localAddress"));
    }

    /**
     * 绑定核心实现，处理注册与绑定的异步衔接。
     * <b>易错点：</b> 注册失败直接返回失败的 ChannelFuture。
     * <b>扩展点：</b> 可自定义注册/绑定流程
     * <b>详细流程：</b>
     * 1. 调用 initAndRegister() 初始化并注册 Channel
     * 2. 若注册失败，直接返回失败的 future
     * 3. 若注册已完成，直接绑定端口
     * 4. 若注册未完成，添加监听器，注册完成后再绑定
     * <b>异步机制：</b> 注册和绑定均为异步，结果通过 ChannelFuture 通知
     * <b>线程安全：</b> 所有回调均在 EventLoop 线程串行执行，保证线程安全
     */
    private ChannelFuture doBind(final SocketAddress localAddress) {
        // 1. 初始化并注册 Channel
        final ChannelFuture regFuture = initAndRegister();
        final Channel channel = regFuture.channel();
        // 2. 注册失败，直接返回失败的 future
        if (regFuture.cause() != null) {
            return regFuture;
        }
        // 3. 注册已完成，直接绑定
        if (regFuture.isDone()) {
            ChannelPromise promise = channel.newPromise();
            doBind0(regFuture, channel, localAddress, promise);
            return promise;
        } else {
            // 4. 注册未完成，添加监听器，注册完成后再绑定
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            regFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    Throwable cause = future.cause();
                    if (cause != null) {
                        // 注册失败，promise 失败
                        promise.setFailure(cause);
                    } else {
                        // 注册成功，切换到 EventLoop
                        promise.registered();
                        doBind0(regFuture, channel, localAddress, promise);
                    }
                }
            });
            return promise;
        }
    }

    /**
     * 初始化并注册 Channel 到 EventLoop。
     * <b>详细流程：</b>
     * 1. 通过 channelFactory 创建 Channel
     * 2. 调用 init(channel) 进行自定义初始化
     * 3. 注册到 EventLoopGroup
     * <b>异常处理：</b> 创建或初始化失败时，强制关闭 Channel 并返回失败的 Promise
     * <b>扩展点：</b> 子类可重写 init() 实现自定义初始化
     * <b>重要说明：</b>
     * - 该方法是 Channel 生命周期的起点，建议重点跟踪。
     * - 注册失败时，future.cause() 可获取详细异常。
     * - 若注册未完成，后续流程通过监听器串联。
     */
    final ChannelFuture initAndRegister() {
        Channel channel = null;
        try {
            // 1. 通过工厂创建 Channel 实例
            channel = channelFactory.newChannel();
            // 2. 调用子类实现的初始化方法
            init(channel);
        } catch (Throwable t) {
            if (channel != null) {
                // 创建或初始化失败，强制关闭 Channel
                channel.unsafe().closeForcibly();
                // 返回失败的 Promise，使用全局执行器
                return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
            }
            // Channel 创建失败，返回失败的 Promise
            return new DefaultChannelPromise(new FailedChannel(), GlobalEventExecutor.INSTANCE).setFailure(t);
        }
        // 3. 注册 Channel 到 EventLoopGroup
        final ChannelFuture regFuture = config().group().register(channel);
        // 4. 注册失败，关闭 Channel
        if (regFuture.cause() != null) {
            if (channel.isRegistered()) {
                channel.close();
            } else {
                channel.unsafe().closeForcibly();
            }
        }
        // 5. 返回注册结果 future
        return regFuture;
    }

    /**
     * 子类实现，初始化 Channel（如设置 handler、option、attribute 等）
     * <b>扩展点：</b> 支持自定义 pipeline、handler 链
     */
    abstract void init(Channel channel) throws Exception;

    /**
     * 获取 ChannelInitializerExtension 扩展点。
     * <b>高级扩展机制，SPI 场景</b>
     */
    Collection<ChannelInitializerExtension> getInitializerExtensions() {
        ClassLoader loader = extensionsClassLoader;
        if (loader == null) {
            loader = getClass().getClassLoader();
        }
        return ChannelInitializerExtensions.getExtensions().extensions(loader);
    }

    /**
     * 真正执行绑定操作，保证在 EventLoop 线程中。
     * <b>扩展点：</b> 用户可在 channelRegistered() 中设置 pipeline。
     * <b>线程安全：</b> 绑定操作在 EventLoop 内串行执行
     */
    private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) {
        // 保证在 EventLoop 线程中执行绑定
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (regFuture.isSuccess()) {
                    // 注册成功，执行绑定，添加失败自动关闭监听器
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else {
                    // 注册失败，promise 失败
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }

    /**
     * 设置业务处理器（如 ChannelInitializer）。
     * <b>典型场景：</b> 配置 pipeline 处理链
     */
    public B handler(ChannelHandler handler) {
        this.handler = ObjectUtil.checkNotNull(handler, "handler");
        return self();
    }

    /**
     * 获取已配置的 EventLoopGroup。
     * @deprecated 建议用 config() 获取
     */
    @Deprecated
    public final EventLoopGroup group() {
        return group;
    }

    /**
     * 获取当前 bootstrap 的配置对象。
     * <b>便于调试和扩展</b>
     */
    public abstract AbstractBootstrapConfig<B, C> config();

    // 生成 ChannelOption 数组，供后续设置
    final Map.Entry<ChannelOption<?>, Object>[] newOptionsArray() {
        return newOptionsArray(options);
    }

    static Map.Entry<ChannelOption<?>, Object>[] newOptionsArray(Map<ChannelOption<?>, Object> options) {
        synchronized (options) {
            return new LinkedHashMap<ChannelOption<?>, Object>(options).entrySet().toArray(EMPTY_OPTION_ARRAY);
        }
    }

    // 生成 Attribute 数组，供后续设置
    final Map.Entry<AttributeKey<?>, Object>[] newAttributesArray() {
        return newAttributesArray(attrs0());
    }

    static Map.Entry<AttributeKey<?>, Object>[] newAttributesArray(Map<AttributeKey<?>, Object> attributes) {
        return attributes.entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY);
    }

    // 获取原始 ChannelOption map
    final Map<ChannelOption<?>, Object> options0() {
        return options;
    }

    // 获取原始 Attribute map
    final Map<AttributeKey<?>, Object> attrs0() {
        return attrs;
    }

    // 获取本地绑定地址
    final SocketAddress localAddress() {
        return localAddress;
    }

    @SuppressWarnings("deprecation")
    // 获取 ChannelFactory
    final ChannelFactory<? extends C> channelFactory() {
        return channelFactory;
    }

    // 获取 handler
    final ChannelHandler handler() {
        return handler;
    }

    // 获取 ChannelOption 的只读副本
    final Map<ChannelOption<?>, Object> options() {
        synchronized (options) {
            return copiedMap(options);
        }
    }

    // 获取 Attribute 的只读副本
    final Map<AttributeKey<?>, Object> attrs() {
        return copiedMap(attrs);
    }

    // 生成只读 map，防止外部修改
    static <K, V> Map<K, V> copiedMap(Map<K, V> map) {
        if (map.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<K, V>(map));
    }

    // 批量设置 Attribute，供 Channel 初始化时调用
    static void setAttributes(Channel channel, Map.Entry<AttributeKey<?>, Object>[] attrs) {
        for (Map.Entry<AttributeKey<?>, Object> e: attrs) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            channel.attr(key).set(e.getValue());
        }
    }

    // 批量设置 ChannelOption，供 Channel 初始化时调用
    // 日志级别：warn（未知 option）、error（设置失败）
    static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options, InternalLogger logger) {
        for (Map.Entry<ChannelOption<?>, Object> e: options) {
            // 逐个设置 ChannelOption，带异常处理
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }

    // 设置单个 ChannelOption，带异常处理
    // 日志级别：warn
    @SuppressWarnings("unchecked")
    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value, InternalLogger logger) {
        try {
            // 设置 option，若返回 false 说明该 option 未被识别
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) {
                logger.warn("Unknown channel option '{}' for channel '{}'", option, channel);
            }
        } catch (Throwable t) {
            // 设置失败，记录异常堆栈
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}'", option, value, channel, t);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append('(').append(config()).append(')');
        return buf.toString();
    }

    /**
     * 注册未完成时的 Promise 实现，确保回调在正确的线程执行。
     * <b>注册成功后，切换到 EventLoop；失败则用全局执行器。</b>
     * <b>典型场景：</b> 注册异步未完成时，保证后续回调线程安全
     */
    static final class PendingRegistrationPromise extends DefaultChannelPromise {
        private volatile boolean registered;
        PendingRegistrationPromise(Channel channel) {
            super(channel);
        }
        void registered() {
            registered = true;
        }
        @Override
        protected EventExecutor executor() {
            // 注册成功，使用 EventLoop；否则用全局执行器
            if (registered) {
                return super.executor();
            }
            return GlobalEventExecutor.INSTANCE;
        }
    }
}

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
import io.netty.util.internal.logging.InternalLoggerFactory;

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
 * {@link AbstractBootstrap} 是一个抽象基类，为 {@link Bootstrap} (客户端) 和
 * {@link ServerBootstrap} (服务端)
 * 提供了通用的引导和配置机制。它封装了创建和配置 {@link Channel} 的大部分共享逻辑。
 * <p>
 * <b>核心职责:</b>
 * <ul>
 * <li>管理 {@link EventLoopGroup} (事件循环组)。</li>
 * <li>配置 {@link Channel} 的类型 (例如 NIO, Epoll, KQueue)。</li>
 * <li>设置 {@link ChannelOption} (通道选项) 和 {@link AttributeKey} (通道属性)。</li>
 * <li>指定 {@link ChannelHandler} (通道处理器，通常是一个
 * {@link io.netty.channel.ChannelInitializer})。</li>
 * <li>提供注册 {@link Channel} 到 {@link EventLoop} 以及绑定/连接操作的方法。</li>
 * <li>支持通过 {@link #clone()} 方法复制引导器配置。</li>
 * </ul>
 *
 * <b>设计模式与原则:</b>
 * <ul>
 * <li>采用构建者模式 (Builder Pattern)，通过链式调用进行配置 (e.g.,
 * {@code group(...).channel(...).option(...).handler(...)}).</li>
 * <li>模板方法模式 (Template Method Pattern): 定义了引导过程的骨架 (如
 * {@code initAndRegister()}, {@code doBind()}),
 * 并将一些具体步骤 (如 {@code init(Channel)}) 延迟到子类实现。</li>
 * <li>配置的惰性验证: 配置项的有效性通常在执行如 {@code bind()} 或 {@code connect()} 等操作前通过
 * {@link #validate()} 方法进行检查。</li>
 * </ul>
 *
 * <b>线程安全注意事项:</b>
 * <ul>
 * <li>引导器的配置方法 (如 {@code group()}, {@code channel()}, {@code option()},
 * {@code handler()} 等) 通常不是线程安全的，
 * 建议在单个线程中完成引导器的配置。</li>
 * <li>一旦配置完成，诸如 {@code bind()}, {@code connect()}, {@code register()}
 * 等操作是线程安全的，它们会将实际工作提交到
 * {@link EventLoop} 中执行。返回的 {@link ChannelFuture} 可用于异步获取操作结果。</li>
 * </ul>
 *
 * <b>日志规范:</b>
 * <ul>
 * <li>所有内部日志记录都应使用 {@link InternalLogger}，通过
 * {@link InternalLoggerFactory#getInstance(Class)} 获取。</li>
 * <li>日志级别应明确：{@code warn} 和 {@code error} 用于指示问题或错误情况，而 {@code debug} 和
 * {@code info}
 * (在 Netty 中较少直接使用 {@code info}，更多倾向于 {@code debug}) 用于记录流程追踪和诊断信息。</li>
 * <li>记录异常时，应包含完整的异常堆栈信息，以便于问题排查。
 * 例如: {@code logger.warn("Failed to set channel option '{}' ...", option,
 * value, channel, throwable);}</li>
 * <li>日志内容应简洁明了，避免泄露敏感信息。</li>
 * </ul>
 *
 * @param <B> 引导器自身的类型 (例如 {@code Bootstrap} 或
 *            {@code ServerBootstrap})，用于支持链式调用的返回类型。
 * @param <C> 此引导器将创建和操作的 {@link Channel} 的类型 (例如
 *            {@link io.netty.channel.socket.SocketChannel} 或
 *            {@link io.netty.channel.socket.ServerSocketChannel})。
 */
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {

    // 获取日志记录器实例，用于记录引导过程中的信息和警告。
    // 根据Netty日志规范，logger应为static final，并通过InternalLoggerFactory获取。
    // 注意：在 Netty 源码中，logger 通常定义在具体子类中，但此处为了遵循规则5的"日志应使用 InternalLogger"而添加。
    // 考虑到 AbstractBootstrap 自身也可能需要记录一些通用警告/错误，在此处声明一个 logger 是合理的。
    // 如果子类有更具体的日志需求，它们可以定义自己的 logger。
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractBootstrap.class);

    // 用于将 options 集合转换为空数组的共享常量实例，避免重复创建。
    @SuppressWarnings("unchecked")
    static final Map.Entry<ChannelOption<?>, Object>[] EMPTY_OPTION_ARRAY = new Map.Entry[0];
    // 用于将 attrs 集合转换为空数组的共享常量实例，避免重复创建。
    @SuppressWarnings("unchecked")
    static final Map.Entry<AttributeKey<?>, Object>[] EMPTY_ATTRIBUTE_ARRAY = new Map.Entry[0];

    // volatile确保多线程环境下的可见性。此 group 负责处理 Channel 的 I/O 事件以及执行任务。
    // 一旦设置后，不应再更改。
    volatile EventLoopGroup group;

    // Channel 工厂，用于创建 Channel 实例。
    // 使用 volatile 保证多线程读取时的可见性。
    // @SuppressWarnings("deprecation") 是因为 io.netty.channel.ChannelFactory 接口自身被标记为
    // @Deprecated，
    // 鼓励使用 ReflectiveChannelFactory 或直接传入 Class 对象。
    @SuppressWarnings("deprecation")
    private volatile ChannelFactory<? extends C> channelFactory;

    // Channel 绑定的本地地址。对于服务端，这是监听的地址；对于客户端，这是可选的本地发出连接的地址。
    // 使用 volatile 保证可见性。
    private volatile SocketAddress localAddress;

    // 用于存储配置的 ChannelOption。使用 LinkedHashMap 来保持选项插入的顺序，
    // 因为某些选项的设置可能依赖于其他选项或其顺序。
    // 对此 map 的修改操作 (option() 方法) 是同步的。
    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<ChannelOption<?>, Object>();

    // 用于存储配置的 Channel 属性 (Attribute)。使用 ConcurrentHashMap 保证线程安全，
    // 因为属性的设置 (attr() 方法) 可能在不同线程中发生（尽管不推荐）。
    private final Map<AttributeKey<?>, Object> attrs = new ConcurrentHashMap<AttributeKey<?>, Object>();

    // Channel 的处理器，通常是一个 ChannelInitializer，负责在 Channel 注册后初始化其 ChannelPipeline。
    // 使用 volatile 保证可见性。
    private volatile ChannelHandler handler;

    // 用于加载 ChannelInitializerExtension 的类加载器，支持 SPI (Service Provider Interface)
    // 扩展机制。
    // 使用 volatile 保证可见性。
    private volatile ClassLoader extensionsClassLoader;

    /**
     * 默认构造函数。限制为包内可见，以防止在包外部直接继承此类。
     * 子类 (Bootstrap, ServerBootstrap) 应该在同一个包内。
     */
    AbstractBootstrap() {
        // Disallow extending from a different package.
    }

    /**
     * 拷贝构造函数，用于创建一个新的 {@link AbstractBootstrap} 实例，该实例具有与传入的 {@code bootstrap}
     * 相同的配置。
     * 这对于需要创建多个具有相似配置的引导器实例非常有用。
     * <p>
     * <b>拷贝行为:</b>
     * <ul>
     * <li>{@link #group}, {@link #channelFactory}, {@link #handler},
     * {@link #localAddress}, {@link #extensionsClassLoader} 字段是浅拷贝 (引用复制)。</li>
     * <li>{@link #options} map 中的内容会被深拷贝到一个新的 {@link LinkedHashMap} 中 (同步访问原始
     * options map)。</li>
     * <li>{@link #attrs} map 中的内容会被深拷贝到一个新的 {@link ConcurrentHashMap} 中。</li>
     * </ul>
     * 因此，修改克隆实例的 options 或 attrs 不会影响原始实例，反之亦然。
     * 但是，对于 group、handler 等引用类型的字段，克隆实例和原始实例将共享相同的对象引用。
     *
     * @param bootstrap 要从中拷贝配置的源 {@link AbstractBootstrap} 实例。
     */
    AbstractBootstrap(AbstractBootstrap<B, C> bootstrap) {
        this.group = bootstrap.group;
        this.channelFactory = bootstrap.channelFactory;
        this.handler = bootstrap.handler;
        this.localAddress = bootstrap.localAddress;
        // options 的拷贝需要在同步块中进行，以确保线程安全
        synchronized (bootstrap.options) {
            this.options.putAll(bootstrap.options);
        }
        // attrs 是 ConcurrentHashMap，其 putAll 操作本身是线程安全的
        this.attrs.putAll(bootstrap.attrs);
        this.extensionsClassLoader = bootstrap.extensionsClassLoader;
    }

    /**
     * 设置此引导器及其创建的 {@link Channel} 将使用的 {@link EventLoopGroup}。
     * {@link EventLoopGroup} 负责处理所有已注册 {@link Channel} 的 I/O 事件和任务执行。
     * <p>
     * <b>重要:</b> 此方法只能调用一次。如果 {@code group} 已经被设置，再次调用将抛出
     * {@link IllegalStateException}。
     * 这是为了防止在引导过程中意外更改核心的事件处理机制。
     * <p>
     * 必须在执行 {@link #bind(SocketAddress)} 或
     * {@link #connect(SocketAddress, SocketAddress)} (对于 {@link Bootstrap})
     * 等操作之前设置 {@link EventLoopGroup}，否则 {@link #validate()} 方法会抛出异常。
     *
     * @param group 要使用的 {@link EventLoopGroup}。不能为 {@code null}。
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     * @throws NullPointerException  如果 {@code group} 为 {@code null}。
     * @throws IllegalStateException 如果 {@code group} 已经被设置过。
     */
    public B group(EventLoopGroup group) {
        ObjectUtil.checkNotNull(group, "group");
        if (this.group != null) {
            // 防止重复设置 EventLoopGroup，这通常表示配置错误。
            throw new IllegalStateException("group set already");
        }
        this.group = group;
        return self();
    }

    /**
     * 返回引导器实例自身，并强制转换为类型 {@code B}。
     * 这是一个内部辅助方法，用于在链式调用中返回正确的子类类型。
     *
     * @return 引导器实例自身。
     */
    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this;
    }

    /**
     * 设置将用于创建 {@link Channel} 实例的 {@link Class} 类型。
     * 这个类必须有一个无参构造函数。
     * <p>
     * 例如，对于 NIO TCP 客户端，可以传入 {@code NioSocketChannel.class}；
     * 对于 NIO TCP 服务端，可以传入 {@code NioServerSocketChannel.class}。
     * <p>
     * 此方法内部会使用 {@link ReflectiveChannelFactory} 来包装传入的 {@code channelClass}。
     * 如果需要更复杂的 {@link Channel} 创建逻辑（例如，需要带参数的构造函数），
     * 应改用 {@link #channelFactory(io.netty.channel.ChannelFactory)} 方法。
     *
     * @param channelClass 要实例化的 {@link Channel} 的 {@link Class} 对象。不能为
     *                     {@code null}。
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     * @throws NullPointerException 如果 {@code channelClass} 为 {@code null}。
     * @see #channelFactory(io.netty.channel.ChannelFactory)
     * @see ReflectiveChannelFactory
     */
    public B channel(Class<? extends C> channelClass) {
        return channelFactory(new ReflectiveChannelFactory<C>(
                ObjectUtil.checkNotNull(channelClass, "channelClass")));
    }

    /**
     * 设置用于创建 {@link Channel} 实例的自定义 {@link ChannelFactory}。
     * 当 {@link ReflectiveChannelFactory}（通过 {@link #channel(Class)} 间接使用）不适用时
     * (例如，{@link Channel} 实现没有无参构造函数，或者需要更复杂的实例化逻辑)，此方法非常有用。
     * <p>
     * <b>注意:</b> 此方法已被标记为 {@code @Deprecated}，建议使用
     * {@link #channelFactory(io.netty.channel.ChannelFactory)}。
     * 这里的 {@code io.netty.bootstrap.ChannelFactory} 是一个旧的、已废弃的接口。
     * <p>
     * 此方法只能调用一次。如果 {@code channelFactory} 已经被设置，再次调用将抛出
     * {@link IllegalStateException}。
     *
     * @param channelFactory 要使用的自定义 {@link ChannelFactory}。不能为 {@code null}。
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     * @throws NullPointerException  如果 {@code channelFactory} 为 {@code null}。
     * @throws IllegalStateException 如果 {@code channelFactory} 已经被设置过。
     * @deprecated 请改用 {@link #channelFactory(io.netty.channel.ChannelFactory)} 并传入
     *             {@code io.netty.channel.ChannelFactory} 的实例。
     */
    @Deprecated
    public B channelFactory(ChannelFactory<? extends C> channelFactory) {
        ObjectUtil.checkNotNull(channelFactory, "channelFactory");
        if (this.channelFactory != null) {
            // 防止重复设置 ChannelFactory，这通常表示配置错误。
            throw new IllegalStateException("channelFactory set already");
        }
        this.channelFactory = channelFactory;
        return self();
    }

    /**
     * 设置用于创建 {@link Channel} 实例的自定义 {@link io.netty.channel.ChannelFactory} (注意是
     * {@code io.netty.channel} 包下的)。
     * 这是推荐的设置自定义 {@link Channel} 创建逻辑的方法，取代了已废弃的采用
     * {@code io.netty.bootstrap.ChannelFactory} 的版本。
     * <p>
     * 当 {@link #channel(Class)}（内部使用 {@link ReflectiveChannelFactory}）无法满足需求时
     * (例如，{@link Channel} 实现没有无参构造函数，或者需要更复杂的实例化过程)，应使用此方法。
     * <p>
     * 此方法只能调用一次。如果 {@code channelFactory} 已经被设置，再次调用将抛出
     * {@link IllegalStateException}。
     *
     * @param channelFactory 要使用的自定义 {@link io.netty.channel.ChannelFactory}。不能为
     *                       {@code null}。
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     * @throws NullPointerException  如果 {@code channelFactory} 为 {@code null}。
     * @throws IllegalStateException 如果 {@code channelFactory} 已经被设置过。
     */
    @SuppressWarnings({ "unchecked", "deprecation" })
    public B channelFactory(io.netty.channel.ChannelFactory<? extends C> channelFactory) {
        // 此处强制类型转换是为了兼容旧的 channelFactory 字段类型。
        // 实际上，新的 io.netty.channel.ChannelFactory 是对旧 io.netty.bootstrap.ChannelFactory
        // 的改进和替代。
        return channelFactory((ChannelFactory<C>) channelFactory);
    }

    /**
     * 设置 {@link Channel} 将绑定到的本地 {@link SocketAddress}。
     * <p>
     * 对于服务端 ({@link ServerBootstrap})，这通常是服务器将监听传入连接的地址和端口。
     * 对于客户端 ({@link Bootstrap})，这指定了传出连接应源自的本地地址和端口 (可选)。
     * 如果不指定，操作系统会自动选择一个合适的本地地址和端口。
     * <p>
     * 如果在调用 {@link #bind()} (对于服务端) 或 {@link #bind(SocketAddress)}
     * (对于客户端，如果需要特定本地绑定) 时
     * {@code localAddress} 为 {@code null}，将会导致操作失败或行为未定义 (取决于具体实现，通常会抛出异常)。
     *
     * @param localAddress 要绑定的本地 {@link SocketAddress}。可以为 {@code null} 表示不指定。
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     */
    public B localAddress(SocketAddress localAddress) {
        this.localAddress = localAddress;
        return self();
    }

    /**
     * {@link #localAddress(SocketAddress)} 的便捷方法，用于通过指定端口号来设置本地地址。
     * 主机部分将默认为通配符地址 (any local address)。
     *
     * @param inetPort 本地端口号。
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     */
    public B localAddress(int inetPort) {
        return localAddress(new InetSocketAddress(inetPort));
    }

    /**
     * {@link #localAddress(SocketAddress)} 的便捷方法，用于通过指定主机名和端口号来设置本地地址。
     * 主机名将被解析为 IP 地址。
     *
     * @param inetHost 本地主机名或 IP 地址字符串。
     * @param inetPort 本地端口号。
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     */
    public B localAddress(String inetHost, int inetPort) {
        return localAddress(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * {@link #localAddress(SocketAddress)} 的便捷方法，用于通过指定 {@link InetAddress}
     * 和端口号来设置本地地址。
     *
     * @param inetHost 本地 {@link InetAddress}。
     * @param inetPort 本地端口号。
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     */
    public B localAddress(InetAddress inetHost, int inetPort) {
        return localAddress(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * 为引导器创建的 {@link Channel} 设置一个 {@link ChannelOption}。
     * {@link ChannelOption} 用于配置底层的网络套接字参数，例如 {@code SO_KEEPALIVE},
     * {@code TCP_NODELAY} 等。
     * <p>
     * 如果 {@code value} 为 {@code null}，则指定的 {@link ChannelOption} 将从此引导器的配置中移除。
     * <p>
     * 对 {@code options} 集合的访问是同步的，以确保线程安全。
     *
     * @param option 要设置的 {@link ChannelOption}。不能为 {@code null}。
     * @param value  要为该选项设置的值。如果为 {@code null}，则表示移除该选项。
     * @param <T>    选项值的类型。
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     * @throws NullPointerException 如果 {@code option} 为 {@code null}。
     */
    public <T> B option(ChannelOption<T> option, T value) {
        ObjectUtil.checkNotNull(option, "option");
        synchronized (options) {
            if (value == null) {
                options.remove(option);
            } else {
                options.put(option, value);
            }
        }
        return self();
    }

    /**
     * 为引导器创建的 {@link Channel} 设置一个初始的 {@link AttributeKey} 及其对应的值。
     * {@link AttributeKey} 允许将自定义数据附加到 {@link Channel} 上，这些数据可以在
     * {@link ChannelHandler} 之间共享。
     * <p>
     * 如果 {@code value} 为 {@code null}，则指定的 {@link AttributeKey} 将从此引导器的配置中移除。
     * <p>
     * {@code attrs} 集合 ({@link ConcurrentHashMap}) 本身是线程安全的，因此不需要额外的同步。
     *
     * @param key   要设置的 {@link AttributeKey}。不能为 {@code null}。
     * @param value 要为该属性设置的值。如果为 {@code null}，则表示移除该属性。
     * @param <T>   属性值的类型。
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     * @throws NullPointerException 如果 {@code key} 为 {@code null}。
     */
    public <T> B attr(AttributeKey<T> key, T value) {
        ObjectUtil.checkNotNull(key, "key");
        if (value == null) {
            attrs.remove(key);
        } else {
            attrs.put(key, value);
        }
        return self();
    }

    /**
     * 设置用于加载 {@link io.netty.channel.ChannelInitializerExtension} 的
     * {@link ClassLoader}。
     * {@link io.netty.channel.ChannelInitializerExtension} 是一种 SPI (Service
     * Provider Interface) 机制，
     * 允许在标准的 {@link io.netty.channel.ChannelInitializer} 调用之外，对
     * {@link ChannelPipeline} 进行额外的配置。
     * <p>
     * 如果不设置，将使用默认的类加载器 (通常是 {@code Thread.currentThread().getContextClassLoader()})
     * 或此引导器类自身的类加载器来发现和加载扩展。
     *
     * @param classLoader 用于加载扩展的 {@link ClassLoader}。可以为 {@code null}，表示使用默认加载机制。
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     */
    public B extensionsClassLoader(ClassLoader classLoader) {
        this.extensionsClassLoader = classLoader;
        return self();
    }

    /**
     * 验证此引导器的配置是否有效。
     * 此方法通常在执行实际的网络操作 (如 {@code bind} 或 {@code connect}) 之前被内部调用。
     * <p>
     * <b>基本验证规则:</b>
     * <ul>
     * <li>{@link #group()} 必须已经被设置。</li>
     * <li>{@link #channelFactory()} (或通过 {@link #channel(Class)} 间接设置的工厂)
     * 必须已经被设置。</li>
     * </ul>
     * 子类 (如 {@link ServerBootstrap}) 可能会添加额外的验证规则。
     * <p>
     * 如果配置无效，将抛出 {@link IllegalStateException}。
     *
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     * @throws IllegalStateException 如果配置无效 (例如，缺少必要的设置)。
     */
    public B validate() {
        if (group == null) {
            throw new IllegalStateException("group not set");
        }
        if (channelFactory == null) {
            throw new IllegalStateException("channel or channelFactory not set");
        }
        return self();
    }

    /**
     * 创建并返回此引导器配置的一个深拷贝副本。
     * 子类必须实现此方法以提供正确的克隆行为。
     * <p>
     * <b>克隆约定:</b>
     * <ul>
     * <li>返回的克隆实例应该是一个新的引导器对象。</li>
     * <li>配置字段 (如 {@code group}, {@code channelFactory}, {@code options},
     * {@code attrs}, {@code handler}, {@code localAddress})
     * 应该被适当地复制。对于集合或 Map 类型的配置 (如 {@code options} 和
     * {@code attrs})，通常需要创建新的集合实例并复制其内容，
     * 以确保修改克隆实例的配置不会影响原始实例。对于 {@code group} 等共享资源，通常进行浅拷贝 (引用复制)。</li>
     * </ul>
     * {@link AbstractBootstrap#AbstractBootstrap(AbstractBootstrap)}
     * 拷贝构造函数提供了大部分字段的复制逻辑。
     *
     * @return 此引导器的一个克隆副本。
     */
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public abstract B clone();

    /**
     * 创建一个新的 {@link Channel} 并将其注册到其 {@link EventLoop}。
     * 此方法执行以下操作:
     * <ol>
     * <li>调用 {@link #validate()} 验证配置。</li>
     * <li>调用 {@link #initAndRegister()}，该方法负责:
     * <ul>
     * <li>通过配置的 {@link #channelFactory()} 创建一个新的 {@link Channel} 实例。</li>
     * <li>调用 {@link #init(Channel)} 方法来初始化新创建的 {@link Channel}
     * (通常是设置处理器、选项和属性)。</li>
     * <li>将初始化后的 {@link Channel} 注册到其 {@link EventLoopGroup} 中的一个
     * {@link EventLoop}。</li>
     * </ul>
     * </li>
     * </ol>
     * 这是一个异步操作，返回一个 {@link ChannelFuture}，可用于监听注册完成的事件。
     * <p>
     * <b>使用场景:</b>
     * 此方法主要用于那些需要在 {@link Channel} 实际绑定到地址或连接到远程端点之前，就将其创建并注册的场景。
     * 对于标准的客户端连接或服务端绑定，通常直接使用 {@code connect()} 或 {@code bind()} 方法，它们内部会处理注册逻辑。
     * <p>
     * <b>错误处理:</b>
     * 如果在创建、初始化或注册过程中发生错误，返回的 {@link ChannelFuture} 将会失败，
     * 并且可以通过 {@link ChannelFuture#cause()} 获取到具体的异常。
     * 如果 {@link Channel} 创建或初始化失败，它会被强制关闭。
     *
     * @return 一个 {@link ChannelFuture}，表示异步的注册操作。当注册完成时，此 future 会被通知。
     */
    public ChannelFuture register() {
        validate();
        return initAndRegister();
    }

    /**
     * 创建、注册一个新的 {@link Channel}，并将其绑定到配置的 {@link #localAddress()}。
     * 此方法是服务端启动监听的主要入口。
     * <p>
     * <b>操作流程:</b>
     * <ol>
     * <li>调用 {@link #validate()} 验证配置。</li>
     * <li>检查 {@link #localAddress()} 是否已设置，如果为 {@code null} 则抛出
     * {@link IllegalStateException}。</li>
     * <li>调用 {@link #doBind(SocketAddress)} 执行实际的绑定操作，传入配置的
     * {@code localAddress}。</li>
     * </ol>
     * 这是一个异步操作，返回一个 {@link ChannelFuture}，可用于监听绑定完成的事件。
     * <p>
     * <b>重要提示 (服务端):</b>
     * 对于 {@link ServerBootstrap}，此方法将启动服务器，使其开始在指定的 {@code localAddress}
     * 上监听传入的连接请求。
     *
     * @return 一个 {@link ChannelFuture}，表示异步的绑定操作。当绑定完成或失败时，此 future 会被通知。
     * @throws IllegalStateException 如果 {@link #localAddress()} 未设置。
     */
    public ChannelFuture bind() {
        validate();
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            throw new IllegalStateException("localAddress not set");
        }
        return doBind(localAddress);
    }

    /**
     * {@link #bind()} 的便捷方法，通过指定端口号来绑定 {@link Channel}。
     * {@link Channel} 将绑定到通配符地址 (any local address) 和指定的端口。
     *
     * @param inetPort 要绑定的本地端口号。
     * @return 一个 {@link ChannelFuture}，表示异步的绑定操作。
     */
    public ChannelFuture bind(int inetPort) {
        return bind(new InetSocketAddress(inetPort));
    }

    /**
     * {@link #bind()} 的便捷方法，通过指定主机名/IP地址字符串和端口号来绑定 {@link Channel}。
     *
     * @param inetHost 要绑定的本地主机名或 IP 地址字符串。
     * @param inetPort 要绑定的本地端口号。
     * @return 一个 {@link ChannelFuture}，表示异步的绑定操作。
     */
    public ChannelFuture bind(String inetHost, int inetPort) {
        return bind(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * {@link #bind()} 的便捷方法，通过指定 {@link InetAddress} 和端口号来绑定 {@link Channel}。
     *
     * @param inetHost 要绑定的本地 {@link InetAddress}。
     * @param inetPort 要绑定的本地端口号。
     * @return 一个 {@link ChannelFuture}，表示异步的绑定操作。
     */
    public ChannelFuture bind(InetAddress inetHost, int inetPort) {
        return bind(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * 创建、注册一个新的 {@link Channel}，并将其绑定到指定的 {@code localAddress}。
     * 这是执行绑定操作的核心公共入口。
     * <p>
     * <b>操作流程:</b>
     * <ol>
     * <li>调用 {@link #validate()} 验证配置。</li>
     * <li>检查传入的 {@code localAddress} 是否为 {@code null}，如果是则抛出
     * {@link NullPointerException}。</li>
     * <li>调用 {@link #doBind(SocketAddress)} 执行实际的绑定逻辑。</li>
     * </ol>
     * 这是一个异步操作，返回一个 {@link ChannelFuture}。
     *
     * @param localAddress {@link Channel} 将绑定到的 {@link SocketAddress}。不能为
     *                     {@code null}。
     * @return 一个 {@link ChannelFuture}，表示异步的绑定操作。
     * @throws NullPointerException 如果 {@code localAddress} 为 {@code null}。
     */
    public ChannelFuture bind(SocketAddress localAddress) {
        validate();
        ObjectUtil.checkNotNull(localAddress, "localAddress");
        return doBind(localAddress);
    }

    /**
     * 执行实际的绑定操作。此方法协调 {@link Channel} 的初始化、注册和绑定过程。
     * <p>
     * <b>核心逻辑:</b>
     * <ol>
     * <li>调用 {@link #initAndRegister()} 来创建并注册 {@link Channel}。
     * 返回的 {@code regFuture} 表示注册操作的结果。</li>
     * <li>获取注册后的 {@link Channel} 实例。</li>
     * <li>检查注册操作是否失败 (通过 {@code regFuture.cause()})。
     * 如果注册失败，直接返回此失败的 {@code regFuture}。</li>
     * <li>如果注册操作已同步完成 ({@code regFuture.isDone()}): 创建一个新的 {@link ChannelPromise}，
     * 并调用 {@link #doBind0(ChannelFuture, Channel, SocketAddress, ChannelPromise)}
     * 来执行实际的绑定。
     * 此 {@link ChannelPromise} 将用于通知绑定操作的结果。</li>
     * <li>如果注册操作尚未完成 (异步进行中): 创建一个 {@link PendingRegistrationPromise}，
     * 并向 {@code regFuture} 添加一个监听器。
     * 当注册操作完成后，此监听器会:
     * <ul>
     * <li>如果注册失败，则将 {@code PendingRegistrationPromise} 标记为失败。</li>
     * <li>如果注册成功，则将 {@code PendingRegistrationPromise} 标记为已注册，
     * 并调用 {@link #doBind0(ChannelFuture, Channel, SocketAddress, ChannelPromise)}
     * 执行实际的绑定。</li>
     * </ul>
     * 返回此 {@link PendingRegistrationPromise}。</li>
     * </ol>
     * 这种处理方式确保了绑定操作总是在 {@link Channel} 成功注册到 {@link EventLoop} 之后才执行，
     * 并且正确处理了注册和绑定过程中的异步性和潜在错误。
     *
     * @param localAddress 要绑定到的 {@link SocketAddress}。
     * @return 一个 {@link ChannelFuture}，表示最终的绑定操作结果。
     */
    private ChannelFuture doBind(final SocketAddress localAddress) {
        // 1. 初始化并注册 Channel。这是一个异步操作，返回一个 ChannelFuture。
        final ChannelFuture regFuture = initAndRegister();
        final Channel channel = regFuture.channel();

        // 2. 检查注册操作是否立即失败 (例如，配置错误导致 Channel 无法创建或初始化)。
        if (regFuture.cause() != null) {
            // 如果注册失败，则整个绑定操作也失败，直接返回注册失败的 Future。
            return regFuture;
        }

        // 3. 检查注册操作是否已经同步完成。
        // 通常情况下，注册是异步的，但如果 EventLoopGroup 是一个立即执行的类型，或者 Channel 类型特殊，注册可能同步完成。
        if (regFuture.isDone()) {
            // 注册已完成 (且成功，因为 regFuture.cause() 为 null)。
            // 创建一个新的 Promise 用于绑定操作。
            ChannelPromise promise = channel.newPromise();
            // 直接在当前线程（如果 EventLoop 允许）或提交到 EventLoop 执行实际的绑定逻辑。
            doBind0(regFuture, channel, localAddress, promise);
            return promise;
        } else {
            // 4. 注册操作正在异步进行中。
            // 我们需要等待注册完成后再执行绑定。
            // 使用 PendingRegistrationPromise 来桥接注册和绑定操作。
            // PendingRegistrationPromise 特殊处理了在注册完成前，其 executor() 方法的行为。
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            // 向注册 Future 添加监听器，以便在注册完成后继续执行绑定。
            regFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    Throwable cause = future.cause();
                    if (cause != null) {
                        // 注册失败，将 PendingRegistrationPromise 标记为失败。
                        promise.setFailure(cause);
                    } else {
                        // 注册成功。
                        // 通知 PendingRegistrationPromise 它现在已经注册，这会影响其 executor() 的行为。
                        promise.registered();
                        // 在 Channel 的 EventLoop 中执行实际的绑定逻辑。
                        doBind0(regFuture, channel, localAddress, promise);
                    }
                }
            });
            return promise;
        }
    }

    /**
     * 初始化并注册一个新的 {@link Channel}。
     * 这是 {@link Channel} 生命周期中非常关键的一步，负责创建 {@link Channel} 实例，
     * 调用用户定义的初始化逻辑 (通过 {@link #init(Channel)})，然后将其注册到 {@link EventLoopGroup}。
     * <p>
     * <b>详细步骤:</b>
     * <ol>
     * <li>尝试通过配置的 {@link #channelFactory} 创建一个新的 {@link Channel} 实例
     * ({@code channelFactory.newChannel()})。
     * </li>
     * <li>如果 {@link Channel} 创建成功，则调用抽象方法 {@link #init(Channel)}，允许子类
     * ({@link Bootstrap}, {@link ServerBootstrap})
     * 和用户进一步配置 {@link Channel} (例如，设置处理器、选项、属性)。</li>
     * <li>如果创建或初始化过程中发生任何异常 ({@link Throwable} t):
     * <ul>
     * <li>如果 {@link Channel} 实例已创建但初始化失败，则调用
     * {@code channel.unsafe().closeForcibly()} 来强制关闭它，
     * 以释放资源。返回一个已失败的 {@link DefaultChannelPromise}，其执行器为
     * {@link GlobalEventExecutor#INSTANCE}。</li>
     * <li>如果 {@link Channel} 实例本身创建失败 ({@code channel == null})，则返回一个包装了
     * {@link FailedChannel} 的、
     * 已失败的 {@link DefaultChannelPromise}。</li>
     * </ul>
     * </li>
     * <li>如果 {@link Channel} 创建和初始化都成功，则调用
     * {@code config().group().register(channel)} 将其注册到
     * 配置的 {@link EventLoopGroup} 中。这是一个异步操作，返回一个 {@link ChannelFuture}
     * ({@code regFuture})。</li>
     * <li>检查注册操作 ({@code regFuture}) 是否失败 (通过 {@code regFuture.cause()})。
     * 如果注册失败，需要确保 {@link Channel} 被正确关闭:
     * <ul>
     * <li>如果 {@link Channel} 已经处于注册状态 ({@code channel.isRegistered()})，则调用其标准的
     * {@code channel.close()} 方法。</li>
     * <li>如果 {@link Channel} 尚未注册，则调用 {@code channel.unsafe().closeForcibly()}
     * 进行强制关闭。</li>
     * </ul>
     * </li>
     * <li>最终返回表示注册操作结果的 {@code regFuture}。</li>
     * </ol>
     *
     * @return 一个 {@link ChannelFuture}，表示异步的注册操作。当注册完成或失败时，此 future 会被通知。
     *         如果创建或初始化阶段就发生严重错误，可能返回一个已立即失败的 future。
     */
    final ChannelFuture initAndRegister() {
        Channel channel = null;
        try {
            // 1. 通过工厂创建 Channel 实例。
            // channelFactory 是在配置阶段通过 channel() 或 channelFactory() 方法设置的。
            channel = channelFactory.newChannel();

            // 2. 调用子类实现的初始化方法 init(channel)。
            // 这是模板方法模式的应用，允许子类自定义 Channel 的初始化逻辑，
            // 例如，Bootstrap 会设置 ChannelOption、Attribute 和 handler，
            // ServerBootstrap 也会进行类似的设置，并可能配置子 Channel 的参数。
            init(channel);
        } catch (Throwable t) {
            // 创建或初始化过程中发生异常。
            if (channel != null) {
                // 如果 Channel 实例已创建但初始化失败，需要确保其资源被释放。
                // closeForcibly() 会尝试立即关闭 Channel，不保证执行所有正常的关闭流程。
                channel.unsafe().closeForcibly();
                // 返回一个已失败的 Promise，使用 GlobalEventExecutor，因为此时 Channel 可能尚未与特定的 EventLoop 关联。
                // 日志记录：此处应由调用者或更高层处理 regFuture.cause()，或在此处添加日志。
                // logger.warn("Failed to initialize channel: {}", channel, t); // 示例日志
                return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
            }
            // 如果 Channel 实例本身创建失败 (channel == null)，则无法在其上创建 Promise。
            // 创建一个包装了 FailedChannel 的、已失败的 Promise。
            // FailedChannel 是一个特殊的 Channel 实现，表示一个无法操作的 Channel。
            // logger.warn("Failed to create channel factory an create a new channel.", t);
            // // 示例日志
            return new DefaultChannelPromise(new FailedChannel(), GlobalEventExecutor.INSTANCE).setFailure(t);
        }

        // 3. 将成功创建并初始化的 Channel 注册到 EventLoopGroup。
        // config().group() 获取配置的 EventLoopGroup。
        // register(channel) 是一个异步操作。
        final ChannelFuture regFuture = config().group().register(channel);

        // 4. 检查注册操作是否立即失败。
        // 有些 EventLoopGroup 实现或 Channel 类型可能导致注册操作同步失败。
        if (regFuture.cause() != null) {
            // 如果注册失败，需要确保 Channel 被关闭。
            if (channel.isRegistered()) {
                // 如果 Channel 已经错误地处于注册状态，则正常关闭。
                channel.close();
            } else {
                // 如果 Channel 尚未注册，则强制关闭。
                channel.unsafe().closeForcibly();
            }
            // 此时 regFuture 已经包含了失败原因，调用者会处理它。
            // logger.warn("Failed to register channel: {}", channel, regFuture.cause()); //
            // 示例日志
        }

        // 5. 返回注册操作的 ChannelFuture。
        // 调用者可以通过监听此 Future 来获知注册是否成功以及何时完成。
        // 注意：即使注册失败 (regFuture.cause() != null)，仍然返回此 regFuture。
        // 这是因为 Future 的契约是通知操作的结果，无论是成功还是失败。
        return regFuture;
    }

    /**
     * 初始化给定的 {@link Channel}。
     * 此方法由 {@link #initAndRegister()} 在 {@link Channel} 实例创建之后、注册到
     * {@link EventLoop} 之前调用。
     * 子类 ({@link Bootstrap} 和 {@link ServerBootstrap}) 必须实现此方法来应用所有配置的
     * {@link ChannelOption}、{@link AttributeKey} 和 {@link ChannelHandler} 到新创建的
     * {@link Channel} 上。
     * <p>
     * <b>实现者注意事项:</b>
     * <ul>
     * <li>应通过 {@code channel.config().setOption(...)} 设置
     * {@link ChannelOption}。</li>
     * <li>应通过 {@code channel.attr(...).set(...)} 设置 {@link AttributeKey}。</li>
     * <li>应通过 {@code channel.pipeline().addLast(...)} 添加配置的 {@link ChannelHandler}
     * (通常是 {@link #handler()})。</li>
     * <li>对于 {@link ServerBootstrap}，此方法还需要处理父 {@link Channel} (服务器通道) 和子
     * {@link Channel} (已接受的连接)
     * 相关的特定配置。</li>
     * </ul>
     *
     * @param channel 要初始化的 {@link Channel}。
     * @throws Exception 如果在初始化过程中发生任何错误。
     */
    abstract void init(Channel channel) throws Exception;

    /**
     * 获取通过 {@link #extensionsClassLoader(ClassLoader)} 配置的或默认的类加载器来加载的
     * {@link io.netty.channel.ChannelInitializerExtension} 集合。
     * <p>
     * {@link io.netty.channel.ChannelInitializerExtension} 提供了一种服务提供者接口 (SPI) 机制，
     * 允许在标准的 {@link io.netty.channel.ChannelInitializer} 初始化流程之外，对
     * {@link ChannelPipeline}
     * 进行模块化和可插拔的扩展配置。
     * <p>
     * 如果 {@link #extensionsClassLoader} 未设置，则首先尝试使用当前线程的上下文类加载器，
     * 如果仍然为 {@code null}，则使用加载 {@code AbstractBootstrap} 类自身的类加载器。
     *
     * @return 一个包含所有已发现和加载的 {@link io.netty.channel.ChannelInitializerExtension}
     *         的集合。可能为空集合。
     */
    Collection<ChannelInitializerExtension> getInitializerExtensions() {
        ClassLoader loader = extensionsClassLoader;
        if (loader == null) {
            // 优先使用当前线程的上下文类加载器，这在某些容器环境中更合适。
            loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                // 如果上下文类加载器也为 null，则回退到加载此类 (AbstractBootstrap) 的类加载器。
                loader = getClass().getClassLoader();
            }
        }
        // ChannelInitializerExtensions 是一个工具类，负责通过 SPI 机制加载扩展。
        return ChannelInitializerExtensions.getExtensions().extensions(loader);
    }

    /**
     * 在 {@link Channel} 的 {@link EventLoop} 中执行实际的绑定操作。
     * 此方法确保绑定逻辑在正确的事件循环线程中执行，这对于 Netty 的线程模型至关重要。
     * <p>
     * <b>执行流程:</b>
     * <ol>
     * <li>将一个 {@link Runnable} 任务提交到 {@code channel.eventLoop()}。</li>
     * <li>在该 {@link Runnable} 内部:
     * <ul>
     * <li>检查注册操作 (由 {@code regFuture} 表示) 是否成功。</li>
     * <li>如果注册成功，则调用 {@code channel.bind(localAddress, promise)} 来发起实际的绑定。
     * 同时，向绑定操作返回的 {@link ChannelFuture} 添加一个
     * {@link ChannelFutureListener#CLOSE_ON_FAILURE} 监听器，
     * 这意味着如果绑定失败，{@link Channel} 将会被自动关闭。</li>
     * <li>如果注册失败，则将传入的 {@code promise} 标记为失败，并将注册失败的原因 ({@code regFuture.cause()})
     * 传递给它。</li>
     * </ul>
     * </li>
     * </ol>
     *
     * @param regFuture    表示 {@link Channel} 注册操作的 {@link ChannelFuture}。
     * @param channel      要绑定的 {@link Channel}。
     * @param localAddress 要绑定到的 {@link SocketAddress}。
     * @param promise      用于通知绑定操作结果的 {@link ChannelPromise}。
     */
    private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) {

        // 确保绑定操作在 Channel 自己的 EventLoop 中执行。
        // 这是 Netty 线程模型的关键：所有对 Channel 的操作（包括生命周期事件如 bind, connect, close）
        // 都应该在其分配的 EventLoop 线程上执行，以避免并发问题。
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (regFuture.isSuccess()) {
                    // 注册成功，现在可以执行绑定操作了。
                    // channel.bind() 会触发 ChannelPipeline 中的 bind 事件传播。
                    // promise 用于异步通知绑定操作的结果。
                    // ChannelFutureListener.CLOSE_ON_FAILURE 确保如果绑定操作失败，相关的 Channel 会被关闭，
                    // 这是一种常见的错误处理策略，防止资源泄露或处于不一致状态。
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else {
                    // 注册失败，因此绑定操作也无法进行。
                    // 将绑定 Promise 标记为失败，并将注册失败的原因传播出去。
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }

    /**
     * 设置将应用于新创建的 {@link Channel} 的 {@link ChannelHandler}。
     * 通常，这会是一个 {@link io.netty.channel.ChannelInitializer}，它负责在 {@link Channel} 注册到
     * {@link EventLoop}
     * 之后，向其 {@link io.netty.channel.ChannelPipeline} 中添加其他的 {@link ChannelHandler}。
     * <p>
     * 对于客户端 ({@link Bootstrap})，这个处理器将配置用于单个连接的 {@link ChannelPipeline}。
     * 对于服务端 ({@link ServerBootstrap})，这个处理器 (称为 {@code handler()}) 通常用于配置接受新连接的服务器
     * {@link Channel}
     * (例如 {@link io.netty.channel.socket.ServerSocketChannel}) 的
     * {@link ChannelPipeline}。
     * 而用于配置已接受的子 {@link Channel} 的处理器则通过
     * {@link ServerBootstrap#childHandler(ChannelHandler)} 设置。
     *
     * @param handler 要设置的 {@link ChannelHandler}。不能为 {@code null}。
     * @return 返回引导器实例自身 ({@code B})，以支持链式调用。
     * @throws NullPointerException 如果 {@code handler} 为 {@code null}。
     */
    public B handler(ChannelHandler handler) {
        this.handler = ObjectUtil.checkNotNull(handler, "handler");
        return self();
    }

    /**
     * 返回此引导器配置的 {@link EventLoopGroup}。
     *
     * @return 配置的 {@link EventLoopGroup}，如果尚未配置则为 {@code null}。
     * @deprecated 请改用 {@link #config()}.{@link AbstractBootstrapConfig#group()
     *             group()}。这样可以访问到引导器的完整配置快照。
     */
    @Deprecated
    public final EventLoopGroup group() {
        return group;
    }

    /**
     * 返回此引导器的一个不可变的配置视图 ({@link AbstractBootstrapConfig})。
     * {@link AbstractBootstrapConfig} 提供了对引导器所有配置参数的只读访问，
     * 例如 {@link EventLoopGroup}, {@link ChannelFactory}, {@link ChannelOption}s,
     * {@link AttributeKey}s, handler 等。
     * <p>
     * 这对于调试、日志记录或基于现有配置创建新配置非常有用。
     * 子类必须实现此方法以返回其特定的配置对象 (例如 {@link BootstrapConfig} 或
     * {@link ServerBootstrapConfig})。
     *
     * @return 此引导器配置的一个不可变视图。
     */
    public abstract AbstractBootstrapConfig<B, C> config();

    /**
     * 返回一个包含当前配置的所有 {@link ChannelOption} 条目的数组副本。
     * 这个数组通常在初始化 {@link Channel} 时，用于将配置的选项批量应用到
     * {@link io.netty.channel.ChannelConfig}。
     * <p>
     * 此方法会同步访问内部的 {@code options} map 以创建副本，确保线程安全。
     * 返回的数组是一个快照，后续对引导器选项的修改不会影响此数组。
     *
     * @return 包含 {@link ChannelOption} 和对应值的 {@link java.util.Map.Entry} 数组。
     *         如果未配置任何选项，则返回一个空数组。
     */
    final Map.Entry<ChannelOption<?>, Object>[] newOptionsArray() {
        return newOptionsArray(options);
    }

    /**
     * 将给定的 {@link ChannelOption} map 转换为 {@link java.util.Map.Entry} 数组。
     * 这是一个静态辅助方法，用于创建选项数组的快照。
     *
     * @param options 要转换的 {@link ChannelOption} map。
     * @return 转换后的 {@link java.util.Map.Entry} 数组。如果输入 map 为空，则返回空数组。
     */
    static Map.Entry<ChannelOption<?>, Object>[] newOptionsArray(Map<ChannelOption<?>, Object> options) {
        // 同步访问传入的 options map，因为无法保证其外部访问的线程安全性。
        // 创建 LinkedHashMap 的副本以保持顺序，然后转换为数组。
        synchronized (options) {
            return new LinkedHashMap<ChannelOption<?>, Object>(options).entrySet().toArray(EMPTY_OPTION_ARRAY);
        }
    }

    /**
     * 返回一个包含当前配置的所有 {@link AttributeKey} 条目的数组副本。
     * 这个数组通常在初始化 {@link Channel} 时，用于将配置的属性批量应用到 {@link Channel}。
     * <p>
     * 此方法直接访问内部的 {@code attrs} map (通常是 {@link ConcurrentHashMap}) 来创建数组快照。
     * {@link ConcurrentHashMap#entrySet()} 返回的是一个弱一致性的视图，转换为数组时是线程安全的。
     * 返回的数组是一个快照，后续对引导器属性的修改不会影响此数组。
     *
     * @return 包含 {@link AttributeKey} 和对应值的 {@link java.util.Map.Entry} 数组。
     *         如果未配置任何属性，则返回一个空数组。
     */
    final Map.Entry<AttributeKey<?>, Object>[] newAttributesArray() {
        return newAttributesArray(attrs0());
    }

    /**
     * 将给定的 {@link AttributeKey} map 转换为 {@link java.util.Map.Entry} 数组。
     * 这是一个静态辅助方法，用于创建属性数组的快照。
     *
     * @param attributes 要转换的 {@link AttributeKey} map。
     * @return 转换后的 {@link java.util.Map.Entry} 数组。如果输入 map 为空，则返回空数组。
     */
    static Map.Entry<AttributeKey<?>, Object>[] newAttributesArray(Map<AttributeKey<?>, Object> attributes) {
        // attributes map 通常是 ConcurrentHashMap，其 entrySet().toArray() 是线程安全的。
        return attributes.entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY);
    }

    /**
     * 返回内部存储的原始 {@link ChannelOption} map。
     * <b>警告:</b> 此方法仅供内部使用或子类在严格控制的同步环境下访问。
     * 不应直接修改返回的 map，因为这可能绕过 {@link #option(ChannelOption, Object)} 方法中的同步逻辑。
     * 获取只读副本应使用 {@link #options()}。
     *
     * @return 内部的 {@link ChannelOption} map。
     */
    final Map<ChannelOption<?>, Object> options0() {
        return options;
    }

    /**
     * 返回内部存储的原始 {@link AttributeKey} map。
     * <b>警告:</b> 此方法仅供内部使用或子类访问。
     * 由于 {@code attrs} 通常是 {@link ConcurrentHashMap}，对其进行迭代或读取是相对安全的，
     * 但直接修改仍应谨慎，建议通过 {@link #attr(AttributeKey, Object)} 方法进行。
     * 获取只读副本应使用 {@link #attrs()}。
     *
     * @return 内部的 {@link AttributeKey} map。
     */
    final Map<AttributeKey<?>, Object> attrs0() {
        return attrs;
    }

    /**
     * 返回配置的本地 {@link SocketAddress}。
     *
     * @return 配置的本地地址，如果未配置则为 {@code null}。
     */
    final SocketAddress localAddress() {
        return localAddress;
    }

    /**
     * 返回配置的 {@link ChannelFactory}。
     *
     * @return 配置的 {@link ChannelFactory}，如果未配置则为 {@code null}。
     * @deprecated 请使用 {@link AbstractBootstrapConfig#channelFactory()}。
     */
    @SuppressWarnings("deprecation")
    final ChannelFactory<? extends C> channelFactory() {
        return channelFactory;
    }

    /**
     * 返回配置的 {@link ChannelHandler}。
     *
     * @return 配置的 {@link ChannelHandler}，如果未配置则为 {@code null}。
     */
    final ChannelHandler handler() {
        return handler;
    }

    /**
     * 返回当前配置的 {@link ChannelOption} 的一个不可修改的副本。
     * 这提供了一种安全的方式来查看已配置的选项，而不会意外修改它们。
     *
     * @return 包含当前所有 {@link ChannelOption} 配置的不可修改的 map。
     *         如果未配置任何选项，则返回一个空 map。
     */
    final Map<ChannelOption<?>, Object> options() {
        synchronized (options) {
            return copiedMap(options);
        }
    }

    /**
     * 返回当前配置的 {@link AttributeKey} 的一个不可修改的副本。
     * 这提供了一种安全的方式来查看已配置的属性，而不会意外修改它们。
     *
     * @return 包含当前所有 {@link AttributeKey} 配置的不可修改的 map。
     *         如果未配置任何属性，则返回一个空 map。
     */
    final Map<AttributeKey<?>, Object> attrs() {
        // attrs 是 ConcurrentHashMap，copiedMap 会创建一个新的 HashMap 副本，然后使其不可修改。
        return copiedMap(attrs);
    }

    /**
     * 创建并返回给定 map 的一个不可修改的浅拷贝副本。
     * 如果原始 map 为空，则返回一个不可修改的空 map ({@link Collections#emptyMap()})。
     * 否则，创建一个新的 {@link HashMap}，复制原始 map 的所有条目，然后用
     * {@link Collections#unmodifiableMap(Map)}
     * 包装它，使其不可修改。
     *
     * @param map 要复制的源 map。
     * @param <K> map 中键的类型。
     * @param <V> map 中值的类型。
     * @return 源 map 的一个不可修改的副本。
     */
    static <K, V> Map<K, V> copiedMap(Map<K, V> map) {
        if (map.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<K, V>(map));
    }

    /**
     * 将配置的属性批量设置到给定的 {@link Channel} 上。
     * 此方法通常在 {@link Channel} 初始化期间 (例如在 {@link #init(Channel)} 方法内部) 调用。
     *
     * @param channel 要设置属性的 {@link Channel}。
     * @param attrs   一个包含 {@link AttributeKey} 和对应值的 {@link java.util.Map.Entry}
     *                数组。
     */
    static void setAttributes(Channel channel, Map.Entry<AttributeKey<?>, Object>[] attrs) {
        for (Map.Entry<AttributeKey<?>, Object> e : attrs) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            channel.attr(key).set(e.getValue());
        }
    }

    /**
     * 将配置的 {@link ChannelOption} 批量设置到给定的 {@link Channel} 上。
     * 此方法通常在 {@link Channel} 初始化期间调用。
     * 如果设置某个选项失败或选项不被识别，会记录警告日志。
     *
     * @param channel 要设置选项的 {@link Channel}。
     * @param options 一个包含 {@link ChannelOption} 和对应值的 {@link java.util.Map.Entry}
     *                数组。
     * @param logger  用于记录警告和错误的 {@link InternalLogger}。
     */
    static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options, InternalLogger logger) {
        for (Map.Entry<ChannelOption<?>, Object> e : options) {
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }

    /**
     * 设置单个 {@link ChannelOption} 到给定的 {@link Channel} 上。
     * 提供了详细的错误处理和日志记录：
     * <ul>
     * <li>如果 {@link ChannelOption} 不被 {@link ChannelConfig} 识别
     * ({@code channel.config().setOption()} 返回 {@code false})，
     * 则记录一个警告 (logger.warn)。</li>
     * <li>如果在设置选项过程中发生任何 {@link Throwable}，则记录一个包含完整堆栈信息的警告 (logger.warn)。</li>
     * </ul>
     *
     * @param channel 要设置选项的 {@link Channel}。
     * @param option  要设置的 {@link ChannelOption}。
     * @param value   选项的值。
     * @param logger  用于记录警告和错误的 {@link InternalLogger}。
     */
    @SuppressWarnings("unchecked")
    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value, InternalLogger logger) {
        try {
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) {
                // 根据Netty日志规范，当选项未知时，使用warn级别记录。
                logger.warn("Unknown channel option '{}' for channel '{}'", option, channel);
            }
        } catch (Throwable t) {
            // 根据Netty日志规范，当设置选项失败时，使用warn级别记录，并包含异常堆栈。
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}'", option, value, channel, t);
        }
    }

    /**
     * 返回此 {@link AbstractBootstrap} 实例的字符串表示形式。
     * 通常包含引导器的简单类名及其配置信息 (通过 {@link #config().toString()} 获取)。
     *
     * @return 此引导器的字符串表示形式。
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
                .append(StringUtil.simpleClassName(this))
                .append('(').append(config()).append(')');
        return buf.toString();
    }

    /**
     * 一个特殊的 {@link DefaultChannelPromise} 实现，用于处理 {@link Channel} 注册操作尚未完成时的场景。
     * <p>
     * 当 {@link Channel} 的注册操作是异步的，并且我们需要在注册完成后执行后续操作 (例如绑定或连接) 时，
     * 此 Promise 用于确保后续操作的回调 (如 listener 通知) 在正确的 {@link EventExecutor} 上执行。
     * <p>
     * <b>核心行为:</b>
     * 在 {@link #registered()} 方法被调用之前 (即 {@link Channel} 尚未完全注册到其 {@link EventLoop}
     * 时)，
     * {@link #executor()} 方法会返回 {@link GlobalEventExecutor#INSTANCE}。
     * 一旦 {@link #registered()} 被调用 (表示 {@link Channel} 已成功注册)，{@link #executor()}
     * 方法将返回
     * {@link Channel} 实际关联的 {@link EventLoop} (通过 {@code super.executor()})。
     * <p>
     * 这种机制确保了在 {@link Channel} 注册完成之前，与此 Promise 相关的任何异步通知（例如，如果注册失败）
     * 都会在一个通用的执行器上进行，而一旦注册成功，所有后续的通知都会在 {@link Channel} 的专用 {@link EventLoop}
     * 线程上执行，这符合 Netty 的线程模型要求。
     */
    static final class PendingRegistrationPromise extends DefaultChannelPromise {

        // volatile 确保多线程间的可见性。当 Channel 注册完成后，此标志被设置为 true。
        private volatile boolean registered;

        /**
         * 创建一个新的 {@link PendingRegistrationPromise} 实例。
         *
         * @param channel 与此 Promise 关联的 {@link Channel}。
         */
        PendingRegistrationPromise(Channel channel) {
            super(channel);
        }

        /**
         * 当关联的 {@link Channel} 成功注册到其 {@link EventLoop} 后，调用此方法。
         * 将 {@code registered} 标志设置为 {@code true}，从而改变后续 {@link #executor()} 方法的行为。
         */
        void registered() {
            registered = true;
        }

        /**
         * 返回用于执行与此 Promise 相关的监听器回调的 {@link EventExecutor}。
         * <p>
         * 如果 {@link #registered} 为 {@code true} (即 {@link Channel} 已注册)，则返回
         * {@link Channel} 的 {@link EventLoop}。
         * 否则 (即 {@link Channel} 注册尚未完成或失败)，返回 {@link GlobalEventExecutor#INSTANCE}。
         *
         * @return 合适的 {@link EventExecutor}。
         */
        @Override
        protected EventExecutor executor() {
            if (registered) {
                // Channel 已注册，使用其自身的 EventLoop 执行回调，确保线程安全和顺序性。
                return super.executor();
            }
            // Channel 尚未注册 (或注册失败)，使用全局的 EventExecutor。
            // 这对于处理注册失败的通知是安全的，因为此时 Channel 可能尚未完全初始化或关联到特定的 EventLoop。
            return GlobalEventExecutor.INSTANCE;
        }
    }
}

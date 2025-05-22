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
 * {@link Bootstrap} 的一个特殊化子类，用于简化服务器端 {@link ServerChannel} 的引导和启动过程。
 * <p>
 * {@link ServerBootstrap} 继承了 {@link AbstractBootstrap}
 * 的大部分通用配置能力，并增加了针对服务器特定的一些配置，例如用于处理已接受连接的子 {@link Channel} 的
 * {@link EventLoopGroup} ({@code childGroup}) 和 {@link ChannelHandler}
 * ({@code childHandler})。
 * </p>
 * <p>
 * <b>核心职责:</b>
 * <ul>
 * <li>配置服务器 {@link Channel} (父 {@link Channel})，例如指定 {@link ServerChannel}
 * 的实现类、监听地址、选项和处理器。</li>
 * <li>配置将处理已接受连接的子 {@link Channel} 的 {@link EventLoopGroup}
 * ({@link #childGroup(EventLoopGroup)})。</li>
 * <li>配置将应用于每个已接受的子 {@link Channel} 的 {@link ChannelHandler}
 * ({@link #childHandler(ChannelHandler)})、
 * {@link ChannelOption} ({@link #childOption(ChannelOption, Object)}) 和
 * {@link AttributeKey} ({@link #childAttr(AttributeKey, Object)})。</li>
 * <li>通过 {@link #bind()} 方法启动服务器，使其开始监听和接受传入连接。</li>
 * </ul>
 * </p>
 * <p>
 * <b>典型用法:</b>
 * 
 * <pre>
 * EventLoopGroup bossGroup = new NioEventLoopGroup(1);
 * EventLoopGroup workerGroup = new NioEventLoopGroup();
 * try {
 *     ServerBootstrap b = new ServerBootstrap();
 *     b.group(bossGroup, workerGroup) // 设置父子 EventLoopGroup
 *      .channel(NioServerSocketChannel.class) // 设置 ServerChannel 类型
 *      .option(ChannelOption.SO_BACKLOG, 100) // 设置 ServerChannel 选项
 *      .handler(new LoggingHandler(LogLevel.INFO)) // 设置 ServerChannel 的处理器
 *      .childHandler(new ChannelInitializer&lt;SocketChannel&gt;() { // 设置子 Channel 的处理器
 *          {@code @Override}
 *          public void initChannel(SocketChannel ch) throws Exception {
 *              ChannelPipeline p = ch.pipeline();
 *              // p.addLast(new MyCustomCodec());
 *              // p.addLast(new MyBusinessLogicHandler());
 *          }
 *      });
 *
 *     // 绑定端口并启动服务器
 *     ChannelFuture f = b.bind(8080).sync();
 *
 *     // 等待服务器套接字关闭
 *     f.channel().closeFuture().sync();
 * } finally {
 *     bossGroup.shutdownGracefully();
 *     workerGroup.shutdownGracefully();
 * }
 * </pre>
 * </p>
 * <p>
 * 此类是理解和使用 Netty 构建服务器应用程序的起点，强烈建议从阅读此类及其父类 {@link AbstractBootstrap} 的源码开始。
 * </p>
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    // 使用 Netty 内部日志工厂获取 logger 实例，用于记录 ServerBootstrap 相关的日志信息。
    // 日志级别和内容应遵循 Netty 的日志规范。
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    // 用于存储将应用于每个已接受的子 Channel 的 ChannelOption。
    // 使用 LinkedHashMap 来保持选项插入的顺序，因为某些选项的设置可能依赖于顺序。
    // 对此 Map 的访问通过 synchronized (childOptions) 进行同步，以确保线程安全。
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();

    // 用于存储将应用于每个已接受的子 Channel 的 Attribute。
    // 使用 ConcurrentHashMap 以允许并发访问和修改，尽管在配置阶段通常是单线程的。
    private final Map<AttributeKey<?>, Object> childAttrs = new ConcurrentHashMap<AttributeKey<?>, Object>();

    // ServerBootstrap 的配置对象，提供对所有配置参数的只读访问。
    // 这是 ServerBootstrapConfig 实例，包含了 ServerBootstrap 及其父类 AbstractBootstrap 的所有配置。
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this);

    // 用于处理已接受的子 Channel 的 I/O 事件的 EventLoopGroup。
    // 如果未明确设置，则在 validate() 期间会尝试使用父 Channel 的 EventLoopGroup (group 字段)，并记录警告。
    // volatile 确保多线程环境下的可见性。
    private volatile EventLoopGroup childGroup;

    // 将添加到每个已接受的子 Channel 的 ChannelPipeline 中的 ChannelHandler。
    // 通常是一个 ChannelInitializer，用于配置子 Channel 的 Pipeline。
    // volatile 确保多线程环境下的可见性。
    private volatile ChannelHandler childHandler;

    /**
     * 默认构造函数，创建一个新的 {@link ServerBootstrap} 实例。
     */
    public ServerBootstrap() {
        // 无特殊初始化逻辑
    }

    /**
     * 私有拷贝构造函数，主要用于 {@link #clone()} 方法的实现。
     * 它创建一个新的 {@link ServerBootstrap} 实例，并从传入的 {@code bootstrap} 实例中复制所有配置。
     *
     * @param bootstrap 要从中拷贝配置的源 {@link ServerBootstrap} 实例。
     */
    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap); // 调用父类的拷贝构造函数，复制通用配置
        this.childGroup = bootstrap.childGroup;
        this.childHandler = bootstrap.childHandler;
        // 同步访问 childOptions 以确保线程安全地复制
        synchronized (bootstrap.childOptions) {
            this.childOptions.putAll(bootstrap.childOptions);
        }
        // childAttrs 是 ConcurrentHashMap，其 putAll 操作本身是线程安全的
        this.childAttrs.putAll(bootstrap.childAttrs);
    }

    /**
     * 设置用于父 {@link ServerChannel} (接受连接) 和子 {@link Channel} (处理已接受的连接) 的
     * {@link EventLoopGroup}。
     * 此方法是一种便捷方式，当希望父子 {@link Channel} 共享同一个 {@link EventLoopGroup} 时使用。
     * 通常，在更复杂的应用中，会为父子 {@link Channel} 分配不同的 {@link EventLoopGroup}，
     * 例如，父 {@link Channel} 使用一个较小的线程池 (甚至单线程) 专门用于接受连接，
     * 而子 {@link Channel} 使用一个较大的线程池来处理 I/O 密集型任务。
     * <p>
     * 此方法等效于调用 {@code group(group, group)}。
     *
     * @param group 要同时用于父 {@link ServerChannel} 和子 {@link Channel} 的
     *              {@link EventLoopGroup}。
     * @return 此 {@link ServerBootstrap} 实例，以支持链式调用。
     * @see #group(EventLoopGroup, EventLoopGroup)
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group); // 将父子 EventLoopGroup 设置为同一个
    }

    /**
     * 分别设置用于父 {@link ServerChannel} ({@code parentGroup}) 和子 {@link Channel}
     * ({@code childGroup}) 的 {@link EventLoopGroup}。
     * <ul>
     * <li>{@code parentGroup} (也称为 "boss group"): 负责处理服务器 {@link ServerChannel}
     * 上的事件，主要是接受新的客户端连接。</li>
     * <li>{@code childGroup} (也称为 "worker group"): 负责处理所有已接受的子 {@link Channel} 上的
     * I/O 事件和任务，
     * 例如数据读写、编码解码、业务逻辑处理等。</li>
     * </ul>
     * 通常建议为 {@code parentGroup} 分配较少的线程 (例如，对于单个监听端口，1个线程通常足够)，
     * 而为 {@code childGroup} 分配较多的线程以处理并发连接的 I/O 操作。
     * <p>
     * 如果 {@code childGroup} 已经被设置过，再次调用此方法将抛出 {@link IllegalStateException}。
     *
     * @param parentGroup 用于父 {@link ServerChannel} (接受连接) 的
     *                    {@link EventLoopGroup}。不能为 {@code null}。
     * @param childGroup  用于子 {@link Channel} (处理已接受连接) 的 {@link EventLoopGroup}。不能为
     *                    {@code null}。
     * @return 此 {@link ServerBootstrap} 实例，以支持链式调用。
     * @throws NullPointerException  如果 {@code parentGroup} 或 {@code childGroup} 为
     *                               {@code null}。
     * @throws IllegalStateException 如果 {@code childGroup} 已经被设置过。
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        super.group(parentGroup); // 设置父 EventLoopGroup (在 AbstractBootstrap 中实现)
        ObjectUtil.checkNotNull(childGroup, "childGroup");
        if (this.childGroup != null) {
            // 防止重复设置 childGroup，这通常是配置错误。
            // 日志记录：可以考虑添加一个 warn 级别的日志，如果允许覆盖的话，但 Netty 通常不允许重复设置。
            // logger.warn("childGroup has already been set. Overwriting with new group.");
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = childGroup;
        return this;
    }

    /**
     * 为将要创建的子 {@link Channel} (即已接受的客户端连接) 设置一个 {@link ChannelOption}。
     * 这些选项将在子 {@link Channel} 被接受并初始化时应用到其 {@link ChannelConfig}。
     * <p>
     * 如果 {@code value} 为 {@code null}，则指定的 {@code childOption} 将从此引导器的子通道配置中移除。
     * <p>
     * 由于 {@code childOptions} map (一个 {@link LinkedHashMap}) 不是本身线程安全的，
     * 对其的修改操作 (put/remove) 在此方法中通过 {@code synchronized (childOptions)} 块进行保护，
     * 以确保在配置阶段（可能涉及多线程，尽管不常见）的线程安全。
     *
     * @param childOption 要为子 {@link Channel} 设置的 {@link ChannelOption}。不能为
     *                    {@code null}。
     * @param value       要为该选项设置的值。如果为 {@code null}，则表示移除该选项。
     * @param <T>         选项值的类型。
     * @return 此 {@link ServerBootstrap} 实例，以支持链式调用。
     * @throws NullPointerException 如果 {@code childOption} 为 {@code null}。
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
     * 为将要创建的子 {@link Channel} (即已接受的客户端连接) 设置一个初始的 {@link AttributeKey} 及其对应的值。
     * 这些属性将在子 {@link Channel} 被接受并初始化时附加到它上面。
     * <p>
     * 如果 {@code value} 为 {@code null}，则指定的 {@code childKey} 将从此引导器的子通道属性配置中移除。
     * <p>
     * {@code childAttrs} map (一个 {@link ConcurrentHashMap})
     * 本身是线程安全的，因此这里的操作不需要额外的外部同步。
     *
     * @param childKey 要为子 {@link Channel} 设置的 {@link AttributeKey}。不能为
     *                 {@code null}。
     * @param value    要为该属性设置的值。如果为 {@code null}，则表示移除该属性。
     * @param <T>      属性值的类型。
     * @return 此 {@link ServerBootstrap} 实例，以支持链式调用。
     * @throws NullPointerException 如果 {@code childKey} 为 {@code null}。
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
     * 设置将应用于每个新接受的子 {@link Channel} 的 {@link ChannelHandler}。
     * 通常，这会是一个 {@link ChannelInitializer}，它负责在子 {@link Channel} 注册到其
     * {@link EventLoop}
     * 之后，向其 {@link ChannelPipeline} 中添加用于处理该连接的业务逻辑处理器、编解码器等。
     * <p>
     * 这个处理器是处理客户端连接的核心。
     *
     * @param childHandler 要为每个子 {@link Channel} 设置的 {@link ChannelHandler}。不能为
     *                     {@code null}。
     * @return 此 {@link ServerBootstrap} 实例，以支持链式调用。
     * @throws NullPointerException 如果 {@code childHandler} 为 {@code null}。
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
        return this;
    }

    /**
     * 初始化父 {@link ServerChannel} (即监听通道)。
     * 此方法由 {@link AbstractBootstrap#initAndRegister()} 在 {@link ServerChannel}
     * 实例创建之后、
     * 注册到其 {@link EventLoop} 之前调用。
     * <p>
     * <b>主要职责:</b>
     * <ol>
     * <li>应用通过 {@link #option(ChannelOption, Object)} 和
     * {@link #attr(AttributeKey, Object)} 方法配置的
     * {@link ChannelOption} 和 {@link AttributeKey} 到父 {@link ServerChannel}。</li>
     * <li>向父 {@link ServerChannel} 的 {@link ChannelPipeline} 中添加一个特殊的
     * {@link ChannelInitializer}。
     * 这个内部的 {@link ChannelInitializer} 主要做两件事:
     * <ul>
     * <li>如果用户通过 {@link #handler(ChannelHandler)} 配置了父 {@link ServerChannel}
     * 的处理器，则将其添加到 Pipeline 中。</li>
     * <li>异步地（通过 {@code eventLoop().execute()}）向 Pipeline 中添加一个
     * {@link ServerBootstrapAcceptor} 实例。
     * {@link ServerBootstrapAcceptor} 是一个关键的内部处理器，负责在父 {@link ServerChannel} 接受到新的子
     * {@link Channel}
     * 时，对该子 {@link Channel} 进行初始化 (应用 {@code childOptions}, {@code childAttrs},
     * {@code childHandler})
     * 并将其注册到 {@code childGroup}。</li>
     * </ul>
     * </li>
     * <li>如果配置了 {@link ChannelInitializerExtension}，则调用其
     * {@code postInitializeServerListenerChannel} 方法，
     * 允许对父 {@link ServerChannel} 进行额外的 SPI 驱动的初始化。</li>
     * </ol>
     *
     * @param channel 要初始化的父 {@link ServerChannel}。
     * @throws Exception 如果在初始化过程中发生任何错误。
     */
    @Override
    void init(Channel channel) {
        // 应用父 Channel 的选项和属性 (这些是在 AbstractBootstrap 中定义的 options 和 attrs)
        setChannelOptions(channel, newOptionsArray(), logger);
        setAttributes(channel, newAttributesArray());

        ChannelPipeline p = channel.pipeline();

        // 获取当前为子 Channel 配置的 EventLoopGroup, Handler, Options 和 Attributes。
        // 这些是在调用 init 之前用户通过 childGroup(), childHandler(), childOption(), childAttr()
        // 设置的。
        // 将它们作为 final 变量捕获，以便在下面的匿名内部类中使用。
        final EventLoopGroup currentChildGroup = childGroup;
        final ChannelHandler currentChildHandler = childHandler;
        final Entry<ChannelOption<?>, Object>[] currentChildOptions = newOptionsArray(childOptions); // 注意：这里调用的是
                                                                                                     // newOptionsArray(childOptions)
                                                                                                     // 而非父类的
                                                                                                     // newOptionsArray()
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs = newAttributesArray(childAttrs); // 同上，针对 childAttrs
        final Collection<ChannelInitializerExtension> extensions = getInitializerExtensions();

        // 向父 ServerChannel 的 Pipeline 中添加一个 ChannelInitializer。
        // 这个 Initializer 的主要目的是在 ServerChannel 准备好后，向其 Pipeline 中添加
        // ServerBootstrapAcceptor。
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) throws Exception { // ch 即为父 ServerChannel
                final ChannelPipeline pipeline = ch.pipeline();
                // 获取为父 ServerChannel 配置的 handler (通过 ServerBootstrap.handler() 设置的)
                ChannelHandler handler = config.handler(); // config() 返回 ServerBootstrapConfig，它能访问父类的 handler
                if (handler != null) {
                    pipeline.addLast(handler); // 将父 ServerChannel 的 handler 添加到其 Pipeline
                }

                // ServerBootstrapAcceptor 是实际处理新连接 (child Channel) 的关键组件。
                // 它需要异步添加到 Pipeline 中，以确保在其之前的 handler (如果有) 已经添加并可能已执行了 channelRegistered。
                // 将 ServerBootstrapAcceptor 的添加操作提交到 EventLoop 中执行，确保了操作的顺序性和线程安全性。
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
        // 应用 ChannelInitializerExtension 扩展点 (如果存在)
        // 这允许通过 SPI 机制对 ServerChannel 进行额外的初始化配置。
        if (!extensions.isEmpty() && channel instanceof ServerChannel) {
            ServerChannel serverChannel = (ServerChannel) channel;
            for (ChannelInitializerExtension extension : extensions) {
                try {
                    extension.postInitializeServerListenerChannel(serverChannel);
                } catch (Exception e) {
                    // 根据Netty日志规范，记录warn级别日志，包含异常堆栈信息。
                    logger.warn(
                            "Exception thrown from ChannelInitializerExtension.postInitializeServerListenerChannel() for listener channel: {}",
                            serverChannel, e);
                }
            }
        }
    }

    /**
     * 验证 {@link ServerBootstrap} 的配置是否有效。
     * 此方法在 {@link AbstractBootstrap#validate()} 的基础上增加了对 {@code childHandler} 的检查，
     * 并处理了 {@code childGroup} 未设置的情况。
     * <p>
     * <b>验证规则:</b>
     * <ol>
     * <li>调用 {@code super.validate()} 执行 {@link AbstractBootstrap} 中的基本验证
     * (例如，{@code group} 和 {@code channelFactory} 必须设置)。</li>
     * <li>检查 {@link #childHandler()} 是否已设置。如果没有，则抛出 {@link IllegalStateException}，
     * 因为子 {@link Channel} 必须有一个处理器来处理其事件。</li>
     * <li>检查 {@link #childGroup()} 是否已设置。如果未设置 (为 {@code null})，则会记录一条警告日志，
     * 并将父 {@link Channel} 的 {@link EventLoopGroup} (即 {@code config().group()}) 用作
     * {@code childGroup}。
     * 这允许在简单场景下省略 {@code childGroup} 的配置，但通常建议显式配置。
     * 日志级别：warn，场景：childGroup 未设置，自动使用 parentGroup。
     * </li>
     * </ol>
     *
     * @return 此 {@link ServerBootstrap} 实例，以支持链式调用。
     * @throws IllegalStateException 如果基本配置无效，或者 {@code childHandler} 未设置。
     */
    @Override
    public ServerBootstrap validate() {
        super.validate(); // 执行父类的验证逻辑 (检查 group, channelFactory)
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            // 日志级别：warn。场景：childGroup 未设置时，ServerBootstrap 会默认使用 parentGroup。
            // 这在单 Reactor 模式或简单场景下可行，但通常建议显式配置 childGroup 以获得更好的性能和隔离性。
            logger.warn("childGroup is not set. Using parentGroup ({}) as childGroup.", config.group());
            childGroup = config.group();
        }
        return this;
    }

    /**
     * {@link ServerBootstrap} 的一个核心内部类，作为 {@link ChannelInboundHandlerAdapter} 添加到父
     * {@link ServerChannel} 的
     * {@link ChannelPipeline} 中。它的主要职责是在父 {@link ServerChannel} 接受一个新的子
     * {@link Channel} (即客户端连接) 时，
     * 对该子 {@link Channel} 进行初始化和注册。
     * <p>
     * <b>工作流程 (当 {@code channelRead} 被调用时):</b>
     * <ol>
     * <li>当父 {@link ServerChannel} 的 Pipeline 接收到一个代表新接受的子 {@link Channel} 的消息 (通常是
     * {@link Channel} 对象本身)
     * 时，此处理器的 {@link #channelRead(ChannelHandlerContext, Object)} 方法会被调用。</li>
     * <li>将配置的 {@code childHandler} 添加到子 {@link Channel} 的 {@link ChannelPipeline}
     * 中。</li>
     * <li>应用所有通过 {@link ServerBootstrap#childOption(ChannelOption, Object)} 配置的
     * {@link ChannelOption}
     * 到子 {@link Channel} 的 {@link ChannelConfig}。</li>
     * <li>应用所有通过 {@link ServerBootstrap#childAttr(AttributeKey, Object)} 配置的
     * {@link AttributeKey}
     * 到子 {@link Channel}。</li>
     * <li>如果配置了 {@link ChannelInitializerExtension}，则调用其
     * {@code postInitializeServerChildChannel} 方法
     * 对子 {@link Channel} 进行额外的 SPI 驱动的初始化。</li>
     * <li>最后，将子 {@link Channel} 注册到配置的 {@code childGroup} ({@link EventLoopGroup})。
     * 注册操作是异步的，并添加了一个监听器来处理注册失败的情况 (通过调用
     * {@link #forceClose(Channel, Throwable)})。</li>
     * </ol>
     * </p>
     * <p>
     * <b>异常处理:</b>
     * <ul>
     * <li>如果在注册子 {@link Channel} 过程中发生错误，会调用
     * {@link #forceClose(Channel, Throwable)} 来强制关闭该子 {@link Channel}
     * 并记录一条警告日志。</li>
     * <li>如果在其自身的 {@link #exceptionCaught(ChannelHandlerContext, Throwable)}
     * 方法中捕获到异常，
     * 并且父 {@link ServerChannel} 的 {@link ChannelConfig#isAutoRead()} 为
     * {@code true}，
     * 它会临时禁用 {@code autoRead} 一小段时间 (1秒)，以防止因连续快速失败而导致的资源耗尽 (例如，不断接受连接然后立即因错误关闭)。
     * 之后，异常会继续在 Pipeline 中传播。这是为了处理类似 "accept() non-stop" 的问题 (Netty issue
     * #1328)。</li>
     * </ul>
     * </p>
     */
    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

        // 用于处理已接受的子 Channel 的 EventLoopGroup。
        private final EventLoopGroup childGroup;
        // 将添加到每个子 Channel Pipeline 中的处理器。
        private final ChannelHandler childHandler;
        // 将应用于每个子 Channel 的 ChannelOption 数组。
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        // 将应用于每个子 Channel 的 AttributeKey 数组。
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        // 一个 Runnable 任务，用于在因异常暂时禁用 autoRead 后重新启用它。
        private final Runnable enableAutoReadTask;
        // ChannelInitializerExtension 扩展集合。
        private final Collection<ChannelInitializerExtension> extensions;

        /**
         * 构造一个新的 {@link ServerBootstrapAcceptor} 实例。
         *
         * @param channel      父 {@link ServerChannel} (监听通道)，主要用于其
         *                     {@link ChannelConfig} 和 {@link EventLoop}。
         * @param childGroup   将用于注册子 {@link Channel} 的 {@link EventLoopGroup}。
         * @param childHandler 将添加到每个子 {@link Channel} Pipeline 的
         *                     {@link ChannelHandler}。
         * @param childOptions 要应用于每个子 {@link Channel} 的 {@link ChannelOption} 数组。
         * @param childAttrs   要应用于每个子 {@link Channel} 的 {@link AttributeKey} 数组。
         * @param extensions   {@link ChannelInitializerExtension} 的集合，用于对子 Channel
         *                     进行扩展初始化。
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

            // 创建一个 Runnable 任务，用于在因异常暂时禁用父 Channel 的 autoRead 后，
            // 在一段时间后 (例如1秒) 重新启用它。这是一种防止 CPU 空转的保护机制，
            // 如果 accept 循环中持续快速抛出异常 (例如，文件描述符耗尽)，
            // 暂时停止接受新连接可以给系统恢复的机会。
            // 参考 Netty Issue #1328。
            this.enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        /**
         * 当父 {@link ServerChannel} 接受一个新的子 {@link Channel} (客户端连接) 时，此方法被调用。
         * 参数 {@code msg} 就是新接受的子 {@link Channel} 对象。
         *
         * @param ctx 父 {@link ServerChannel} 的 {@link ChannelHandlerContext}。
         * @param msg 新接受的子 {@link Channel} 对象。
         */
        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            final Channel child = (Channel) msg; // msg 就是新接受的子 Channel

            // 将用户配置的 childHandler 添加到子 Channel 的 Pipeline 中。
            // 这是处理子 Channel 业务逻辑的核心入口。
            child.pipeline().addLast(childHandler);

            // 将 ServerBootstrap 中为子 Channel 配置的 ChannelOption 应用到子 Channel。
            // 使用 ServerBootstrap.logger 进行日志记录。
            setChannelOptions(child, childOptions, logger);
            // 将 ServerBootstrap 中为子 Channel 配置的 Attribute 应用到子 Channel。
            setAttributes(child, childAttrs);

            // 应用 ChannelInitializerExtension 扩展点
            if (!extensions.isEmpty()) {
                for (ChannelInitializerExtension extension : extensions) {
                    try {
                        extension.postInitializeServerChildChannel(child);
                    } catch (Exception e) {
                        // 日志级别：warn。记录扩展初始化子 Channel 时的异常。
                        logger.warn(
                                "Exception thrown from ChannelInitializerExtension.postInitializeServerChildChannel() for child channel: {}",
                                child, e);
                    }
                }
            }

            try {
                // 将子 Channel 注册到 childGroup (worker group)。
                // 这是一个异步操作，我们添加一个监听器来处理注册失败的情况。
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            // 如果注册失败，强制关闭子 Channel 并记录错误。
                            forceClose(child, future.cause());
                        }
                        // 如果注册成功，子 Channel 将开始由 childGroup 中的 EventLoop 处理。
                        // Pipeline 中的 handler (包括 childHandler) 的生命周期方法 (如 channelActive) 将被调用。
                    }
                });
            } catch (Throwable t) {
                // 如果 childGroup.register() 本身同步抛出异常 (理论上不常见，因为通常是异步的)，
                // 也需要强制关闭子 Channel 并记录错误。
                forceClose(child, t);
            }
        }

        /**
         * 当注册子 {@link Channel} 到 {@link EventLoopGroup} 失败时，强制关闭该子 {@link Channel}。
         * 同时记录一条警告日志，包含失败的 {@link Channel} 和异常信息。
         *
         * @param child 注册失败的子 {@link Channel}。
         * @param t     导致注册失败的 {@link Throwable}。
         */
        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly(); // 强制关闭，不保证执行正常的关闭流程
            // 日志级别：warn。记录注册失败的详细信息，便于问题排查。
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        /**
         * 当父 {@link ServerChannel} 的 Pipeline 中发生异常时，此方法被调用。
         * 如果父 {@link ServerChannel} 当前配置为自动读取 (autoRead)，则会暂时禁用它1秒钟，
         * 以防止因连续快速失败 (例如，在 accept 循环中不断抛出异常) 而导致 CPU 空转。
         * 之后，异常会继续在 Pipeline 中传播 (通过 {@code ctx.fireExceptionCaught(cause)})，
         * 以便 Pipeline 中的其他处理器或用户自定义的处理器有机会处理它。
         *
         * @param ctx   父 {@link ServerChannel} 的 {@link ChannelHandlerContext}。
         * @param cause 捕获到的异常。
         * @throws Exception 如果父处理器无法处理此异常。
         */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // 如果当前是自动读取状态，暂时关闭它，以避免因 accept() 循环中的错误导致 CPU 100%
                // (参考 Netty Issue #1328)。
                config.setAutoRead(false);
                // 计划一个任务，在1秒后重新启用 autoRead。
                // 日志级别：warn，记录暂时禁用 autoRead 的事件和原因。
                logger.warn(
                        "Temporarily disabling autoRead for listener channel {} due to exception caught: {}",
                        ctx.channel(), cause.getMessage(), cause);
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // 将异常继续在 Pipeline 中传播，以便其他处理器可以处理它。
            ctx.fireExceptionCaught(cause);
        }
    }

    /**
     * 创建并返回此 {@link ServerBootstrap} 实例的一个深拷贝副本。
     * 此方法通过调用私有的拷贝构造函数 {@link #ServerBootstrap(ServerBootstrap)} 来实现。
     * 克隆操作会复制所有配置字段，其中集合类型的字段 (如 {@code childOptions}, {@code childAttrs})
     * 会被深拷贝，而其他如 {@code childGroup}, {@code childHandler} 等是浅拷贝 (引用复制)。
     *
     * @return 此 {@link ServerBootstrap} 的一个克隆副本。
     */
    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone") // super.clone() is not called by design in AbstractBootstrap
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * 返回为子 {@link Channel} 配置的 {@link EventLoopGroup}。
     *
     * @return 配置的子 {@link EventLoopGroup}，如果尚未配置则可能为 {@code null}
     *         (但在 {@link #validate()} 之后，如果为 {@code null} 会被设置为父 {@code group})。
     * @deprecated 请改用 {@link #config()}.{@link ServerBootstrapConfig#childGroup()
     *             childGroup()}。
     *             通过 {@link ServerBootstrapConfig} 访问配置可以确保获取到的是一个一致的配置快照。
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    /**
     * 返回为子 {@link Channel} 配置的 {@link ChannelHandler}。
     * 此方法主要供内部或测试使用。
     *
     * @return 配置的子 {@link ChannelHandler}，如果尚未配置则为 {@code null}。
     */
    final ChannelHandler childHandler() {
        return childHandler;
    }

    /**
     * 返回为子 {@link Channel} 配置的 {@link ChannelOption} 的一个不可修改的副本。
     * 此方法主要供内部或测试使用。
     *
     * @return 包含为子 {@link Channel} 配置的所有 {@link ChannelOption} 的不可修改的 map。
     */
    final Map<ChannelOption<?>, Object> childOptions() {
        synchronized (childOptions) {
            return copiedMap(childOptions); // copiedMap 返回的是不可修改的副本
        }
    }

    /**
     * 返回为子 {@link Channel} 配置的 {@link AttributeKey} 的一个不可修改的副本。
     * 此方法主要供内部或测试使用。
     *
     * @return 包含为子 {@link Channel} 配置的所有 {@link AttributeKey} 的不可修改的 map。
     */
    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServerBootstrapConfig config() {
        return config;
    }
}

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
import io.netty.util.concurrent.EventExecutorGroup;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;


/**
 * 一系列 {@link ChannelHandler} 的列表，用于处理或拦截 {@link Channel} 的入站事件和出站操作。
 * {@link ChannelPipeline} 实现了一种高级形式的
 * <a href="https://www.oracle.com/technetwork/java/interceptingfilter-142169.html">拦截过滤器</a> 模式，
 * 允许用户完全控制事件的处理方式以及 Pipeline 中的 {@link ChannelHandler} 如何相互交互。
 *
 * <p><b>核心作用与职责:</b></p>
 * <ul>
 *     <li><b>事件处理链:</b> {@link ChannelPipeline} 维护了一个双向链表结构的 {@link ChannelHandler} 序列。
 *         入站事件 (Inbound events) 从链表的头部流向尾部，而出站事件 (Outbound events) 从链表的尾部流向头部。</li>
 *     <li><b>拦截与处理:</b> 每个 {@link ChannelHandler} 都有机会检查、修改、处理或传递事件。
 *         它可以决定是否将事件传递给链中的下一个 Handler。</li>
 *     <li><b>动态修改:</b> 可以在运行时动态地向 Pipeline 中添加、删除或替换 {@link ChannelHandler}，
 *         这为实现灵活的协议切换、动态功能增强等提供了可能。</li>
 *     <li><b>上下文关联:</b> 每个 {@link ChannelHandler} 都会被包装在一个 {@link ChannelHandlerContext} 对象中，
 *         该上下文对象持有 Handler 的状态信息，并提供了与 Pipeline 和其他 Handler 交互的方法。</li>
 *     <li><b>线程安全:</b> {@link ChannelPipeline} 的方法通常是线程安全的，允许在不同的线程中对其进行修改。
 *         然而，单个 {@link ChannelHandler} 实例的线程安全性需要由其自身保证，除非它被标记为 {@link ChannelHandler.Sharable}。</li>
 * </ul>
 *
 * <h3>Pipeline 的创建</h3>
 *
 * 每个 Channel 都有其自己的 Pipeline，并在创建新 Channel 时自动创建。
 * 通常在 {@link ChannelInitializer#initChannel(Channel)} 方法中向 Pipeline 添加初始的 {@link ChannelHandler}。
 *
 * <h3>事件在 Pipeline 中的流向</h3>
 *
 * 下图描述了 I/O 事件通常如何在 {@link ChannelPipeline} 中的 {@link ChannelHandler} 之间处理。
 * I/O 事件由 {@link ChannelInboundHandler} 或 {@link ChannelOutboundHandler} 处理，
 * 并通过调用 {@link ChannelHandlerContext} 中定义的事件传播方法（例如 {@link ChannelHandlerContext#fireChannelRead(Object)} 和
 * {@link ChannelHandlerContext#write(Object)}）转发到其最近的 Handler。
 *
 * <pre>
 *                                                 I/O 请求
 *                                            通过 {@link Channel} 或
 *                                        {@link ChannelHandlerContext}
 *                                                      |
 *  +---------------------------------------------------+---------------+      Pipeline 头部
 *  |                           ChannelPipeline         |               |
 *  |                                                  \|/              |      Outbound 方向 (从头到尾)
 *  |    +---------------------+            +-----------+----------+    |      Inbound 方向 (从尾到头)
 *  |    | Inbound Handler  N  |            | Outbound Handler  1  |    |
 *  |    +----------+----------+            +-----------+----------+    |
 *  |              /|\                                  |               |
 *  |               |                                  \|/              |
 *  |    +----------+----------+            +-----------+----------+    |
 *  |    | Inbound Handler N-1 |            | Outbound Handler  2  |    |
 *  |    +----------+----------+            +-----------+----------+    |
 *  |              /|\                                  .               |
 *  |               .                                   .               |
 *  | ChannelHandlerContext.fireIN_EVT() ChannelHandlerContext.OUT_EVT()|
 *  |        [ 方法调用 ]                       [ 方法调用 ]        |
 *  |               .                                   .               |
 *  |               .                                  \|/              |
 *  |    +----------+----------+            +-----------+----------+    |
 *  |    | Inbound Handler  2  |            | Outbound Handler M-1 |    |
 *  |    +----------+----------+            +-----------+----------+    |
 *  |              /|\                                  |               |
 *  |               |                                  \|/              |
 *  |    +----------+----------+            +-----------+----------+    |
 *  |    | Inbound Handler  1  |            | Outbound Handler  M  |    |
 *  |    +----------+----------+            +-----------+----------+    |
 *  |              /|\                                  |               |      Pipeline 尾部
 *  +---------------+-----------------------------------+---------------+
 *                  |                                  \|/
 *  +---------------+-----------------------------------+---------------+      网络层 / Socket
 *  |               |                                   |               |
 *  |       [ Socket.read() ]                    [ Socket.write() ]     |
 *  |                                                                   |
 *  |  Netty 内部 I/O 线程 (传输层实现)                                 |
 *  +-------------------------------------------------------------------+
 * </pre>
 * 入站事件由入站处理器按自底向上（图中左侧所示）的方向处理。
 * 入站处理器通常处理由图底部 I/O 线程生成的入站数据。
 * 入站数据通常是通过实际的输入操作（例如 {@link SocketChannel#read(ByteBuffer)}）从远程对端读取的。
 * 如果一个入站事件超出了顶部的入站处理器，它会被静默丢弃，或者如果需要您的注意，则会记录日志。
 * <p>
 * 出站事件由出站处理器按自顶向下（图中右侧所示）的方向处理。
 * 出站处理器通常生成或转换出站流量，例如写请求。
 * 如果一个出站事件超出了底部的出站处理器，它将由与 {@link Channel} 关联的 I/O 线程处理。
 * I/O 线程通常执行实际的输出操作，例如 {@link SocketChannel#write(ByteBuffer)}。
 * <p>
 * 例如，假设我们创建了以下 Pipeline：
 * <pre>
 * {@link ChannelPipeline} p = ...;
 * p.addLast("1", new InboundHandlerA());
 * p.addLast("2", new InboundHandlerB());
 * p.addLast("3", new OutboundHandlerA());
 * p.addLast("4", new OutboundHandlerB());
 * p.addLast("5", new InboundOutboundHandlerX());
 * </pre>
 * 在上面的示例中，名称以 {@code Inbound} 开头的类表示它是一个入站处理器。
 * 名称以 {@code Outbound} 开头的类表示它是一个出站处理器。
 * <p>
 * 在给定的示例配置中，当事件入站时，处理器的评估顺序是 1, 2, 3, 4, 5。
 * 当事件出站时，顺序是 5, 4, 3, 2, 1。在此原则之上，{@link ChannelPipeline} 会跳过
 * 某些处理器的评估以缩短堆栈深度：
 * <ul>
 * <li>3 和 4 没有实现 {@link ChannelInboundHandler}，因此入站事件的实际评估顺序将是：1, 2, 和 5。</li>
 * <li>1 和 2 没有实现 {@link ChannelOutboundHandler}，因此出站事件的实际评估顺序将是：5, 4, 和 3。</li>
 * <li>如果 5 同时实现了 {@link ChannelInboundHandler} 和 {@link ChannelOutboundHandler}，则入站和出站事件的评估顺序
 *     可能分别是 125 和 543。</li>
 * </ul>
 *
 * <h3>将事件转发到下一个 Handler</h3>
 *
 * 正如图中所示，一个 Handler 必须调用 {@link ChannelHandlerContext} 中的事件传播方法才能将事件转发到其下一个 Handler。
 * 这些方法包括：
 * <ul>
 * <li>入站事件传播方法：
 *     <ul>
 *     <li>{@link ChannelHandlerContext#fireChannelRegistered()}</li>
 *     <li>{@link ChannelHandlerContext#fireChannelActive()}</li>
 *     <li>{@link ChannelHandlerContext#fireChannelRead(Object)}</li>
 *     <li>{@link ChannelHandlerContext#fireChannelReadComplete()}</li>
 *     <li>{@link ChannelHandlerContext#fireExceptionCaught(Throwable)}</li>
 *     <li>{@link ChannelHandlerContext#fireUserEventTriggered(Object)}</li>
 *     <li>{@link ChannelHandlerContext#fireChannelWritabilityChanged()}</li>
 *     <li>{@link ChannelHandlerContext#fireChannelInactive()}</li>
 *     <li>{@link ChannelHandlerContext#fireChannelUnregistered()}</li>
 *     </ul>
 * </li>
 * <li>出站事件传播方法：
 *     <ul>
 *     <li>{@link ChannelHandlerContext#bind(SocketAddress, ChannelPromise)}</li>
 *     <li>{@link ChannelHandlerContext#connect(SocketAddress, SocketAddress, ChannelPromise)}</li>
 *     <li>{@link ChannelHandlerContext#write(Object, ChannelPromise)}</li>
 *     <li>{@link ChannelHandlerContext#flush()}</li>
 *     <li>{@link ChannelHandlerContext#read()}</li>
 *     <li>{@link ChannelHandlerContext#disconnect(ChannelPromise)}</li>
 *     <li>{@link ChannelHandlerContext#close(ChannelPromise)}</li>
 *     <li>{@link ChannelHandlerContext#deregister(ChannelPromise)}</li>
 *     </ul>
 * </li>
 * </ul>
 *
 * 以下示例显示了通常如何进行事件传播：
 *
 * <pre>
 * public class MyInboundHandler extends {@link ChannelInboundHandlerAdapter} {
 *     {@code @Override}
 *     public void channelActive({@link ChannelHandlerContext} ctx) {
 *         System.out.println("已连接!");
 *         ctx.fireChannelActive(); // 将 channelActive 事件传播到下一个 Inbound Handler
 *     }
 * }
 *
 * public class MyOutboundHandler extends {@link ChannelOutboundHandlerAdapter} {
 *     {@code @Override}
 *     public void close({@link ChannelHandlerContext} ctx, {@link ChannelPromise} promise) {
 *         System.out.println("正在关闭 ..");
 *         ctx.close(promise); // 将 close 请求传播到下一个 Outbound Handler
 *     }
 * }
 * </pre>
 *
 * <h3>构建 Pipeline</h3>
 * <p>
 * 用户应该在 Pipeline 中拥有一个或多个 {@link ChannelHandler} 来接收 I/O 事件（例如读）和
 * 请求 I/O 操作（例如写和关闭）。例如，一个典型的服务器将在每个 Channel 的 Pipeline 中包含以下 Handler，
 * 但具体情况可能因协议和业务逻辑的复杂性和特性而异：
 *
 * <ol>
 * <li>协议解码器 - 将二进制数据（例如 {@link ByteBuf}）转换为 Java 对象。</li>
 * <li>协议编码器 - 将 Java 对象转换为二进制数据。</li>
 * <li>业务逻辑处理器 - 执行实际的业务逻辑。</li>
 * </ol>
 *
 * 并且可以用以下示例表示：
 *
 * <pre>
 * ...
 *
 * {@link ChannelPipeline} pipeline = ch.pipeline();
 *
 * pipeline.addLast("decoder", new MyProtocolDecoder());
 * pipeline.addLast("encoder", new MyProtocolEncoder());
 * pipeline.addLast("handler", new MyBusinessLogicHandler());
 * </pre>
 *
 * <h3>线程安全</h3>
 * <p>
 * {@link ChannelHandler} 可以随时添加或删除，因为 {@link ChannelPipeline} 是线程安全的。
 * 例如，您可以在即将交换敏感信息时插入一个加密 Handler，并在交换后将其删除。
 * 但是，请注意，虽然 Pipeline 自身的结构修改是线程安全的，但其包含的 {@link ChannelHandler} 实例
 * 如果不是 {@link ChannelHandler.Sharable} 的，则不应该被多个 Pipeline 共享，并且其内部状态的并发访问
 * 需要由 Handler 自身来保证线程安全。
 */
public interface ChannelPipeline
        extends ChannelInboundInvoker, ChannelOutboundInvoker, Iterable<Entry<String, ChannelHandler>> {

    /**
     * 在此 Pipeline 的第一个位置插入一个 {@link ChannelHandler}。
     *
     * @param name     要插入的 Handler 的名称。此名称在 Pipeline 中必须是唯一的。
     * @param handler  要插入的 {@link ChannelHandler}。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     *
     * @throws IllegalArgumentException 如果 Pipeline 中已存在同名的条目。
     * @throws NullPointerException     如果指定的 handler 为 {@code null}。
     */
    ChannelPipeline addFirst(String name, ChannelHandler handler);

    /**
     * 在此 Pipeline 的第一个位置插入一个 {@link ChannelHandler}。
     * <b>注意:</b> 此方法已废弃。{@link ChannelHandler} 的执行将始终由其关联的 {@link Channel} 的 {@link EventLoop} 执行。
     * 如果需要将 Handler 的执行委托给不同的 {@link EventExecutorGroup}，应在该 Handler 内部实现。
     *
     * @param group    将用于执行 {@link ChannelHandler} 方法的 {@link EventExecutorGroup} (已废弃，不起作用)。
     * @param name     要插入的 Handler 的名称。
     * @param handler  要插入的 {@link ChannelHandler}。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     *
     * @throws IllegalArgumentException 如果 Pipeline 中已存在同名的条目。
     * @throws NullPointerException     如果指定的 handler 为 {@code null}。
     * @deprecated 请使用 {@link #addFirst(String, ChannelHandler)}。 Handler 将由 Channel 的 EventLoop 执行。
     */
    @Deprecated
    ChannelPipeline addFirst(EventExecutorGroup group, String name, ChannelHandler handler);

    /**
     * 在此 Pipeline 的最后一个位置追加一个 {@link ChannelHandler}。
     *
     * @param name     要追加的 Handler 的名称。此名称在 Pipeline 中必须是唯一的。
     * @param handler  要追加的 {@link ChannelHandler}。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     *
     * @throws IllegalArgumentException 如果 Pipeline 中已存在同名的条目。
     * @throws NullPointerException     如果指定的 handler 为 {@code null}。
     */
    ChannelPipeline addLast(String name, ChannelHandler handler);

    /**
     * 在此 Pipeline 的最后一个位置追加一个 {@link ChannelHandler}。
     * <b>注意:</b> 此方法已废弃。{@link ChannelHandler} 的执行将始终由其关联的 {@link Channel} 的 {@link EventLoop} 执行。
     *
     * @param group    将用于执行 {@link ChannelHandler} 方法的 {@link EventExecutorGroup} (已废弃，不起作用)。
     * @param name     要追加的 Handler 的名称。
     * @param handler  要追加的 {@link ChannelHandler}。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     *
     * @throws IllegalArgumentException 如果 Pipeline 中已存在同名的条目。
     * @throws NullPointerException     如果指定的 handler 为 {@code null}。
     * @deprecated 请使用 {@link #addLast(String, ChannelHandler)}。 Handler 将由 Channel 的 EventLoop 执行。
     */
    @Deprecated
    ChannelPipeline addLast(EventExecutorGroup group, String name, ChannelHandler handler);

    /**
     * 在此 Pipeline 中指定的现有 Handler (由 {@code baseName} 标识) 之前插入一个新的 {@link ChannelHandler}。
     *
     * @param baseName  现有 Handler 的名称。
     * @param name      要插入的新 Handler 的名称。此名称在 Pipeline 中必须是唯一的。
     * @param handler   要插入的 {@link ChannelHandler}。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     *
     * @throws NoSuchElementException   如果 Pipeline 中不存在名为 {@code baseName} 的 Handler。
     * @throws IllegalArgumentException 如果 Pipeline 中已存在与 {@code name} 同名的 Handler。
     * @throws NullPointerException     如果指定的 {@code baseName} 或 {@code handler} 为 {@code null}。
     */
    ChannelPipeline addBefore(String baseName, String name, ChannelHandler handler);

    /**
     * 在此 Pipeline 中指定的现有 Handler (由 {@code baseName} 标识) 之前插入一个新的 {@link ChannelHandler}。
     * <b>注意:</b> 此方法已废弃。{@link ChannelHandler} 的执行将始终由其关联的 {@link Channel} 的 {@link EventLoop} 执行。
     *
     * @param group     将用于执行 {@link ChannelHandler} 方法的 {@link EventExecutorGroup} (已废弃，不起作用)。
     * @param baseName  现有 Handler 的名称。
     * @param name      要插入的新 Handler 的名称。
     * @param handler   要插入的 {@link ChannelHandler}。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     *
     * @throws NoSuchElementException   如果 Pipeline 中不存在名为 {@code baseName} 的 Handler。
     * @throws IllegalArgumentException 如果 Pipeline 中已存在与 {@code name} 同名的 Handler。
     * @throws NullPointerException     如果指定的 {@code baseName} 或 {@code handler} 为 {@code null}。
     * @deprecated 请使用 {@link #addBefore(String, String, ChannelHandler)}。 Handler 将由 Channel 的 EventLoop 执行。
     */
    @Deprecated
    ChannelPipeline addBefore(EventExecutorGroup group, String baseName, String name, ChannelHandler handler);

    /**
     * 在此 Pipeline 中指定的现有 Handler (由 {@code baseName} 标识) 之后插入一个新的 {@link ChannelHandler}。
     *
     * @param baseName  现有 Handler 的名称。
     * @param name      要插入的新 Handler 的名称。此名称在 Pipeline 中必须是唯一的。
     * @param handler   要插入的 {@link ChannelHandler}。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     *
     * @throws NoSuchElementException   如果 Pipeline 中不存在名为 {@code baseName} 的 Handler。
     * @throws IllegalArgumentException 如果 Pipeline 中已存在与 {@code name} 同名的 Handler。
     * @throws NullPointerException     如果指定的 {@code baseName} 或 {@code handler} 为 {@code null}。
     */
    ChannelPipeline addAfter(String baseName, String name, ChannelHandler handler);

    /**
     * 在此 Pipeline 中指定的现有 Handler (由 {@code baseName} 标识) 之后插入一个新的 {@link ChannelHandler}。
     * <b>注意:</b> 此方法已废弃。{@link ChannelHandler} 的执行将始终由其关联的 {@link Channel} 的 {@link EventLoop} 执行。
     *
     * @param group     将用于执行 {@link ChannelHandler} 方法的 {@link EventExecutorGroup} (已废弃，不起作用)。
     * @param baseName  现有 Handler 的名称。
     * @param name      要插入的新 Handler 的名称。
     * @param handler   要插入的 {@link ChannelHandler}。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     *
     * @throws NoSuchElementException   如果 Pipeline 中不存在名为 {@code baseName} 的 Handler。
     * @throws IllegalArgumentException 如果 Pipeline 中已存在与 {@code name} 同名的 Handler。
     * @throws NullPointerException     如果指定的 {@code baseName} 或 {@code handler} 为 {@code null}。
     * @deprecated 请使用 {@link #addAfter(String, String, ChannelHandler)}。 Handler 将由 Channel 的 EventLoop 执行。
     */
    @Deprecated
    ChannelPipeline addAfter(EventExecutorGroup group, String baseName, String name, ChannelHandler handler);

    /**
     * 在此 Pipeline 的第一个位置插入多个 {@link ChannelHandler}。
     * Handler 将按照它们在数组中出现的顺序插入。
     * 每个 Handler 都会被分配一个自动生成的名称。
     *
     * @param handlers  要插入的 {@link ChannelHandler} 数组。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     */
    ChannelPipeline addFirst(ChannelHandler... handlers);

    /**
     * 在此 Pipeline 的第一个位置插入多个 {@link ChannelHandler}。
     * <b>注意:</b> 此方法已废弃。{@link ChannelHandler} 的执行将始终由其关联的 {@link Channel} 的 {@link EventLoop} 执行。
     *
     * @param group     将用于执行 {@link ChannelHandler} 方法的 {@link EventExecutorGroup} (已废弃，不起作用)。
     * @param handlers  要插入的 {@link ChannelHandler} 数组。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     * @deprecated 请使用 {@link #addFirst(ChannelHandler...)}。 Handler 将由 Channel 的 EventLoop 执行。
     */
    @Deprecated
    ChannelPipeline addFirst(EventExecutorGroup group, ChannelHandler... handlers);

    /**
     * 在此 Pipeline 的最后一个位置追加多个 {@link ChannelHandler}。
     * Handler 将按照它们在数组中出现的顺序追加。
     * 每个 Handler 都会被分配一个自动生成的名称。
     *
     * @param handlers  要追加的 {@link ChannelHandler} 数组。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     */
    ChannelPipeline addLast(ChannelHandler... handlers);

    /**
     * 在此 Pipeline 的最后一个位置追加多个 {@link ChannelHandler}。
     * <b>注意:</b> 此方法已废弃。{@link ChannelHandler} 的执行将始终由其关联的 {@link Channel} 的 {@link EventLoop} 执行。
     *
     * @param group     将用于执行 {@link ChannelHandler} 方法的 {@link EventExecutorGroup} (已废弃，不起作用)。
     * @param handlers  要追加的 {@link ChannelHandler} 数组。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     * @deprecated 请使用 {@link #addLast(ChannelHandler...)}。 Handler 将由 Channel 的 EventLoop 执行。
     */
    @Deprecated
    ChannelPipeline addLast(EventExecutorGroup group, ChannelHandler... handlers);

    /**
     * 从此 Pipeline中移除指定的 {@link ChannelHandler}。
     *
     * @param  handler          要移除的 {@link ChannelHandler} 实例。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     *
     * @throws NoSuchElementException 如果 Pipeline 中不存在此 Handler。
     * @throws NullPointerException   如果指定的 handler 为 {@code null}。
     */
    ChannelPipeline remove(ChannelHandler handler);

    /**
     * 从此 Pipeline 中移除具有指定名称的 {@link ChannelHandler}。
     *
     * @param  name             存储 {@link ChannelHandler} 时使用的名称。
     * @return 被移除的 {@link ChannelHandler}。
     *
     * @throws NoSuchElementException 如果 Pipeline 中不存在具有指定名称的 Handler。
     * @throws NullPointerException   如果指定的 name 为 {@code null}。
     */
    ChannelHandler remove(String name);

    /**
     * 从此 Pipeline 中移除指定类型的第一个 {@link ChannelHandler}。
     *
     * @param <T>           Handler 的类型。
     * @param handlerType   Handler 的类型。
     * @return 被移除的 {@link ChannelHandler}。
     *
     * @throws NoSuchElementException 如果 Pipeline 中不存在指定类型的 Handler。
     * @throws NullPointerException   如果指定的 handlerType 为 {@code null}。
     */
    <T extends ChannelHandler> T remove(Class<T> handlerType);

    /**
     * 移除此 Pipeline 中的第一个 {@link ChannelHandler}。
     *
     * @return 被移除的 {@link ChannelHandler}。
     * @throws NoSuchElementException 如果此 Pipeline 为空。
     */
    ChannelHandler removeFirst();

    /**
     * 移除此 Pipeline 中的最后一个 {@link ChannelHandler}。
     *
     * @return 被移除的 {@link ChannelHandler}。
     * @throws NoSuchElementException 如果此 Pipeline 为空。
     */
    ChannelHandler removeLast();

    /**
     * 在此 Pipeline 中用一个新的 Handler 替换指定的 {@link ChannelHandler}。
     *
     * @param  oldHandler    要被替换的 {@link ChannelHandler} 实例。
     * @param  newName       替换后的 Handler 的新名称。如果为 {@code null}，则尝试重用旧 Handler 的名称（如果适用）。
     * @param  newHandler    用于替换的 {@link ChannelHandler}。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     *
     * @throws NoSuchElementException   如果指定的旧 Handler 不在此 Pipeline 中。
     * @throws IllegalArgumentException 如果具有指定新名称的 Handler 已存在于此 Pipeline 中（要被替换的 Handler 除外）。
     * @throws NullPointerException     如果指定的 oldHandler 或 newHandler 为 {@code null}。
     */
    ChannelPipeline replace(ChannelHandler oldHandler, String newName, ChannelHandler newHandler);

    /**
     * 在此 Pipeline 中用一个新的 Handler 替换具有指定名称的 {@link ChannelHandler}。
     *
     * @param  oldName       要被替换的 {@link ChannelHandler} 的名称。
     * @param  newName       替换后的 Handler 的新名称。如果为 {@code null}，则使用 {@code oldName}。
     * @param  newHandler    用于替换的 {@link ChannelHandler}。
     * @return 被移除的 (旧的) {@link ChannelHandler}。
     *
     * @throws NoSuchElementException   如果具有指定旧名称的 Handler 不在此 Pipeline 中。
     * @throws IllegalArgumentException 如果具有指定新名称的 Handler 已存在于此 Pipeline 中（要被替换的 Handler 除外）。
     * @throws NullPointerException     如果指定的 oldName 或 newHandler 为 {@code null}。
     */
    ChannelHandler replace(String oldName, String newName, ChannelHandler newHandler);

    /**
     * 在此 Pipeline 中用一个新的 Handler 替换指定类型的第一个 {@link ChannelHandler}。
     *
     * @param  oldHandlerType   要被移除的 Handler 的类型。
     * @param  newName          替换后的 Handler 的新名称。如果为 {@code null}，则分配一个自动生成的名称。
     * @param  newHandler       用于替换的 {@link ChannelHandler}。
     * @return 被移除的 (旧的) {@link ChannelHandler}。
     *
     * @throws NoSuchElementException   如果指定旧 Handler 类型的 Handler 不在此 Pipeline 中。
     * @throws IllegalArgumentException 如果具有指定新名称的 Handler 已存在于此 Pipeline 中（要被替换的 Handler 除外）。
     * @throws NullPointerException     如果指定的 oldHandlerType 或 newHandler 为 {@code null}。
     */
    <T extends ChannelHandler> T replace(Class<T> oldHandlerType, String newName,
                                         ChannelHandler newHandler);

    /**
     * 返回此 Pipeline 中的第一个 {@link ChannelHandler}。
     *
     * @return 第一个 Handler。如果此 Pipeline 为空，则返回 {@code null}。
     */
    ChannelHandler first();

    /**
     * 返回此 Pipeline 中第一个 {@link ChannelHandler} 的 {@link ChannelHandlerContext}。
     *
     * @return 第一个 Handler 的上下文。如果此 Pipeline 为空，则返回 {@code null}。
     */
    ChannelHandlerContext firstContext();

    /**
     * 返回此 Pipeline 中的最后一个 {@link ChannelHandler}。
     *
     * @return 最后一个 Handler。如果此 Pipeline 为空，则返回 {@code null}。
     */
    ChannelHandler last();

    /**
     * 返回此 Pipeline 中最后一个 {@link ChannelHandler} 的 {@link ChannelHandlerContext}。
     *
     * @return 最后一个 Handler 的上下文。如果此 Pipeline 为空，则返回 {@code null}。
     */
    ChannelHandlerContext lastContext();

    /**
     * 返回此 Pipeline 中具有指定名称的 {@link ChannelHandler}。
     *
     * @param name Handler 的名称。
     * @return 具有指定名称的 Handler。如果 Pipeline 中没有这样的 Handler，则返回 {@code null}。
     */
    ChannelHandler get(String name);

    /**
     * 返回此 Pipeline 中指定类型的第一个 {@link ChannelHandler}。
     *
     * @param <T>           Handler 的类型。
     * @param handlerType   Handler 的类型。
     * @return 指定 Handler 类型的 Handler。如果 Pipeline 中没有这样的 Handler，则返回 {@code null}。
     */
    <T extends ChannelHandler> T get(Class<T> handlerType);

    /**
     * 返回此 Pipeline 中指定 {@link ChannelHandler} 实例的 {@link ChannelHandlerContext}。
     *
     * @param handler 要获取其上下文的 {@link ChannelHandler} 实例。
     * @return 指定 Handler 的上下文对象。如果 Pipeline 中没有此 Handler，则返回 {@code null}。
     */
    ChannelHandlerContext context(ChannelHandler handler);

    /**
     * 返回此 Pipeline 中具有指定名称的 {@link ChannelHandler} 的 {@link ChannelHandlerContext}。
     *
     * @param name Handler 的名称。
     * @return 具有指定名称的 Handler 的上下文对象。如果 Pipeline 中没有这样的 Handler，则返回 {@code null}。
     */
    ChannelHandlerContext context(String name);

    /**
     * 返回此 Pipeline 中指定类型的第一个 {@link ChannelHandler} 的 {@link ChannelHandlerContext}。
     *
     * @param handlerType Handler 的类型。
     * @return 指定类型的 Handler 的上下文对象。如果 Pipeline 中没有这样的 Handler，则返回 {@code null}。
     */
    ChannelHandlerContext context(Class<? extends ChannelHandler> handlerType);

    /**
     * 返回此 Pipeline 附加到的 {@link Channel}。
     *
     * @return Channel。如果此 Pipeline 尚未附加到任何 Channel，则返回 {@code null}。
     */
    Channel channel();

    /**
     * 返回此 Pipeline 中所有 Handler 名称的 {@link List}，顺序与它们在 Pipeline 中的顺序一致。
     *
     * @return Handler 名称的列表。
     */
    List<String> names();

    /**
     * 将此 Pipeline 转换为一个有序的 {@link Map}，其键是 Handler 名称，值是 Handler 实例。
     * 返回的 Map 的迭代顺序与 Handler 在 Pipeline 中的顺序一致。
     *
     * @return 表示 Pipeline 内容的 Map。
     */
    Map<String, ChannelHandler> toMap();

    /**
     * 触发一个 {@code channelRegistered} 事件到 Pipeline 中的下一个 {@link ChannelInboundHandler}。
     * 当 {@link Channel} 成功注册到其 {@link EventLoop} 时调用。
     *
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     */
    @Override
    ChannelPipeline fireChannelRegistered();

    /**
     * 触发一个 {@code channelUnregistered} 事件到 Pipeline 中的下一个 {@link ChannelInboundHandler}。
     * 当 {@link Channel} 从其 {@link EventLoop} 注销时调用。
     *
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     */
    @Override
    ChannelPipeline fireChannelUnregistered();

    /**
     * 触发一个 {@code channelActive} 事件到 Pipeline 中的下一个 {@link ChannelInboundHandler}。
     * 当 {@link Channel} 变为活动状态（例如，连接已建立）时调用。
     *
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     */
    @Override
    ChannelPipeline fireChannelActive();

    /**
     * 触发一个 {@code channelInactive} 事件到 Pipeline 中的下一个 {@link ChannelInboundHandler}。
     * 当 {@link Channel} 变为非活动状态（例如，连接已关闭）时调用。
     *
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     */
    @Override
    ChannelPipeline fireChannelInactive();

    /**
     * 触发一个 {@code exceptionCaught} 事件到 Pipeline 中的下一个 {@link ChannelHandler} (无论是 Inbound 还是 Outbound)。
     * 当在处理过程中发生异常时调用。
     *
     * @param cause 捕获到的异常。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     */
    @Override
    ChannelPipeline fireExceptionCaught(Throwable cause);

    /**
     * 触发一个用户自定义事件到 Pipeline 中的下一个 {@link ChannelInboundHandler}。
     * 这允许用户定义和传播自定义的特定于应用程序的事件。
     *
     * @param event 用户自定义事件对象。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     */
    @Override
    ChannelPipeline fireUserEventTriggered(Object event);

    /**
     * 触发一个 {@code channelRead} 事件到 Pipeline 中的下一个 {@link ChannelInboundHandler}。
     * 当从 {@link Channel} 读取到数据时调用。
     *
     * @param msg 读取到的消息 (数据)。
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     */
    @Override
    ChannelPipeline fireChannelRead(Object msg);

    /**
     * 触发一个 {@code channelReadComplete} 事件到 Pipeline 中的下一个 {@link ChannelInboundHandler}。
     * 在当前批次的读取操作完成后调用。例如，当 {@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)} 被多次调用后，
     * 这个方法会被调用一次，表示这一轮读取已经结束。
     *
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     */
    @Override
    ChannelPipeline fireChannelReadComplete();

    /**
     * 触发一个 {@code channelWritabilityChanged} 事件到 Pipeline 中的下一个 {@link ChannelInboundHandler}。
     * 当 {@link Channel} 的可写状态发生变化时调用。可以通过 {@link Channel#isWritable()} 检查当前的可写状态。
     * 用户可以使用此事件来控制写入操作的速率，避免因发送过多数据而导致内存溢出。
     *
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     */
    @Override
    ChannelPipeline fireChannelWritabilityChanged();

    /**
     * 请求将之前通过 {@link #write(Object)} 或 {@link ChannelHandlerContext#write(Object)} 写入的挂起数据刷出到底层传输。
     * 此操作会触发 Pipeline 中的 {@link ChannelOutboundHandler#flush(ChannelHandlerContext)} 方法。
     *
     * @return 返回当前的 {@link ChannelPipeline} 以支持链式调用。
     */
    @Override
    ChannelPipeline flush();

    /**
     * 返回一个新的 {@link ChannelPromise}，与此 Pipeline 关联的 {@link Channel} 相关联。
     * 这个 Promise 可用于异步操作的结果通知。
     *
     * @return 新的 {@link ChannelPromise}。
     */
    @Override
    default ChannelPromise newPromise() {
        return new DefaultChannelPromise(channel());
    }

    /**
     * 返回一个新的 {@link ChannelProgressivePromise}，与此 Pipeline 关联的 {@link Channel} 相关联。
     * 这个 Promise 用于支持可以报告进度的异步操作 (例如文件传输)。
     *
     * @return 新的 {@link ChannelProgressivePromise}。
     */
    @Override
    default ChannelProgressivePromise newProgressivePromise() {
        return new DefaultChannelProgressivePromise(channel());
    }

    /**
     * 创建一个表示失败操作的 {@link ChannelFuture}，与此 Pipeline 关联的 {@link Channel} 相关联。
     * 这个 Future 立即处于失败状态，并携带指定的异常原因。
     *
     * @param cause 失败的原因。
     * @return 一个已失败的 {@link ChannelFuture}。
     */
    @Override
    default ChannelFuture newFailedFuture(Throwable cause) {
        return new FailedChannelFuture(channel(), null, cause);
    }
}

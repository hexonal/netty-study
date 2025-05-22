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

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.SocketChannelConfig;

import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Map;

/**
 * {@link Channel} 的配置属性集合。
 * <p>
 * 可通过向下转型为更具体的配置类型（如 {@link SocketChannelConfig}），或使用 {@link #setOptions(Map)} 设置特定传输属性：
 * <pre>
 * {@code
 * Channel ch = ...;
 * SocketChannelConfig cfg = (SocketChannelConfig) ch.getConfig();
 * cfg.setTcpNoDelay(false);
 * }
 * </pre>
 *
 * <h3>选项映射（Option map）</h3>
 * <p>
 * 选项映射属性是一种动态的只写属性，允许无需向下转型即可配置 {@link Channel}。
 * 可通过 {@link #setOptions(Map)} 批量设置。
 * <br>
 * 所有 {@link ChannelConfig} 都支持如下选项：
 * <table border="1" cellspacing="0" cellpadding="6">
 * <tr>
 * <th>名称</th><th>关联设置方法</th>
 * </tr><tr>
 * <td>{@link ChannelOption#CONNECT_TIMEOUT_MILLIS}</td><td>{@link #setConnectTimeoutMillis(int)}</td>
 * </tr><tr>
 * <td>{@link ChannelOption#WRITE_SPIN_COUNT}</td><td>{@link #setWriteSpinCount(int)}</td>
 * </tr><tr>
 * <td>{@link ChannelOption#WRITE_BUFFER_WATER_MARK}</td><td>{@link #setWriteBufferWaterMark(WriteBufferWaterMark)}</td>
 * </tr><tr>
 * <td>{@link ChannelOption#ALLOCATOR}</td><td>{@link #setAllocator(ByteBufAllocator)}</td>
 * </tr><tr>
 * <td>{@link ChannelOption#AUTO_READ}</td><td>{@link #setAutoRead(boolean)}</td>
 * </tr>
 * </table>
 * <br>
 * 更多选项可参考子类（如 {@link SocketChannelConfig}）的文档。
 *
 * <h3>设计意图</h3>
 * <ul>
 *   <li>统一管理 Channel 的各类配置参数，便于灵活扩展和动态调整。</li>
 *   <li>支持批量设置、查询、动态扩展等能力。</li>
 * </ul>
 */
public interface ChannelConfig {

    /**
     * 返回所有已设置的 {@link ChannelOption} 及其对应值。
     * <p>
     * 典型用法：遍历所有配置项，做动态适配。
     * </p>
     */
    Map<ChannelOption<?>, Object> getOptions();

    /**
     * 根据指定的 {@link Map} 批量设置配置属性。
     * <p>
     * 适用于动态配置、批量初始化等场景。
     * <br>
     * <b>重要说明：</b>
     * <ul>
     *   <li>仅支持当前 ChannelConfig 支持的 ChannelOption，未知选项将被忽略。</li>
     *   <li>批量设置时，部分配置项可能立即生效，部分需在 Channel 初始化或重启后生效，具体以实现为准。</li>
     *   <li>线程安全：通常在 Channel 初始化阶段调用，若在运行时动态调整，需确保线程安全。</li>
     * </ul>
     * </p>
     */
    boolean setOptions(Map<ChannelOption<?>, ?> options);

    /**
     * 获取指定 {@link ChannelOption} 的值。
     * <p>
     * 常用于查询当前配置状态。
     * </p>
     */
    <T> T getOption(ChannelOption<T> option);

    /**
     * 设置指定 {@link ChannelOption} 的值。
     * <p>
     * 若需重写此方法，建议调用父类实现。
     * <br>
     * <b>重要说明：</b>
     * <ul>
     *   <li>部分配置项（如 AUTO_READ）会立即影响 Channel 行为，部分则在下次 I/O 操作时生效。</li>
     *   <li>返回 true 表示设置成功，false 表示该选项不被支持或设置失败。</li>
     *   <li>线程安全：建议在 Channel 初始化或事件循环线程内调用，避免并发冲突。</li>
     * </ul>
     * </p>
     */
    <T> boolean setOption(ChannelOption<T> option, T value);

    /**
     * 获取 Channel 的连接超时时间（毫秒）。
     * <p>
     * 若 Channel 不支持连接操作，则该属性无效。
     * </p>
     * @return 超时时间，0 表示禁用
     */
    int getConnectTimeoutMillis();

    /**
     * 设置 Channel 的连接超时时间（毫秒）。
     * <p>
     * 若 Channel 不支持连接操作，则该属性无效。
     * </p>
     * @param connectTimeoutMillis 超时时间，0 表示禁用
     */
    ChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis);

    /**
     * @deprecated 建议使用 {@link MaxMessagesRecvByteBufAllocator} 及其 maxMessagesPerRead()。
     * <p>
     * 获取每次读循环最多读取的消息数。
     * 若大于 1，事件循环可能多次读取以获取多条消息。
     * </p>
     */
    @Deprecated
    int getMaxMessagesPerRead();

    /**
     * @deprecated 建议使用 {@link MaxMessagesRecvByteBufAllocator} 及其 maxMessagesPerRead(int)。
     * <p>
     * 设置每次读循环最多读取的消息数。
     * 若大于 1，事件循环可能多次读取以获取多条消息。
     * </p>
     */
    @Deprecated
    ChannelConfig setMaxMessagesPerRead(int maxMessagesPerRead);

    /**
     * 获取写操作自旋次数上限。
     * <p>
     * 类似并发编程中的自旋锁，用于提升内存利用率和写吞吐量。
     * 默认值为 16。
     * </p>
     */
    int getWriteSpinCount();

    /**
     * 设置写操作自旋次数上限。
     * <p>
     * 类似并发编程中的自旋锁，用于提升内存利用率和写吞吐量。
     * 默认值为 16。
     * </p>
     * @throws IllegalArgumentException 若值小于等于 0
     */
    ChannelConfig setWriteSpinCount(int writeSpinCount);

    /**
     * 获取用于分配缓冲区的 {@link ByteBufAllocator}。
     * <p>
     * <b>重要说明：</b>
     * <ul>
     *   <li>ByteBufAllocator 决定了所有 ByteBuf 的分配策略，影响内存管理和性能。</li>
     *   <li>Netty 默认提供 Pooled 和 Unpooled 两种实现，推荐使用池化分配以提升性能。</li>
     * </ul>
     * </p>
     */
    ByteBufAllocator getAllocator();

    /**
     * 设置用于分配缓冲区的 {@link ByteBufAllocator}。
     * <p>
     * <b>重要说明：</b>
     * <ul>
     *   <li>建议在 Channel 初始化前设置，运行时动态切换可能导致内存管理不一致。</li>
     *   <li>池化分配器有助于减少 GC 压力，提升高并发场景下的吞吐量。</li>
     * </ul>
     * </p>
     */
    ChannelConfig setAllocator(ByteBufAllocator allocator);

    /**
     * 获取用于分配接收缓冲区的 {@link RecvByteBufAllocator}。
     */
    <T extends RecvByteBufAllocator> T getRecvByteBufAllocator();

    /**
     * 设置用于分配接收缓冲区的 {@link RecvByteBufAllocator}。
     */
    ChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator allocator);

    /**
     * 是否自动调用 {@link ChannelHandlerContext#read()}，无需用户手动触发。
     * <p>
     * 默认值为 true。
     * <br>
     * <b>重要说明：</b>
     * <ul>
     *   <li>开启后，Channel 每次数据读取完成后会自动发起下一次读取，适合流式数据场景。</li>
     *   <li>关闭后，需用户手动调用 read() 触发下一次读取，适合背压控制或特殊业务需求。</li>
     *   <li>动态切换该配置会立即影响 Channel 的读行为。</li>
     * </ul>
     * </p>
     */
    boolean isAutoRead();

    /**
     * 设置是否自动调用 {@link ChannelHandlerContext#read()}，无需用户手动触发。
     * <p>
     * 默认值为 true。
     * <br>
     * <b>重要说明：</b>
     * <ul>
     *   <li>设置为 false 可实现应用层流控（背压），防止数据过载。</li>
     *   <li>切换该配置会立即影响 Channel 的读事件调度。</li>
     * </ul>
     * </p>
     */
    ChannelConfig setAutoRead(boolean autoRead);

    /**
     * 写操作失败时，Channel 是否自动立即关闭。
     * <p>
     * 默认值为 true。
     * </p>
     */
    boolean isAutoClose();

    /**
     * 设置写操作失败时，Channel 是否自动立即关闭。
     * <p>
     * 默认值为 true。
     * </p>
     */
    ChannelConfig setAutoClose(boolean autoClose);

    /**
     * 获取写缓冲区高水位线。
     * <p>
     * 若写缓冲区字节数超过该值，{@link Channel#isWritable()} 将返回 false。
     * <br>
     * <b>重要说明：</b>
     * <ul>
     *   <li>高水位线用于流控，防止写入过快导致内存溢出。</li>
     *   <li>可结合低水位线动态调整写入速率，实现高效的异步写入。</li>
     * </ul>
     * </p>
     */
    int getWriteBufferHighWaterMark();

    /**
     * 设置写缓冲区高水位线。
     * <p>
     * 若写缓冲区字节数超过该值，{@link Channel#isWritable()} 将返回 false。
     * <br>
     * <b>重要说明：</b>
     * <ul>
     *   <li>建议根据业务流量和内存情况合理设置，过大可能导致内存占用过高，过小可能影响吞吐。</li>
     *   <li>动态调整时需关注当前写缓冲区状态，避免频繁切换。</li>
     * </ul>
     * </p>
     */
    ChannelConfig setWriteBufferHighWaterMark(int writeBufferHighWaterMark);

    /**
     * 获取写缓冲区低水位线。
     * <p>
     * 超过高水位线后，字节数降到低水位线以下，{@link Channel#isWritable()} 将恢复为 true。
     * <br>
     * <b>重要说明：</b>
     * <ul>
     *   <li>低水位线配合高水位线实现写缓冲区的"水位流控"机制。</li>
     *   <li>合理设置低水位线有助于提升写入效率，减少频繁的可写/不可写切换。</li>
     * </ul>
     * </p>
     */
    int getWriteBufferLowWaterMark();

    /**
     * 设置写缓冲区低水位线。
     * <p>
     * 超过高水位线后，字节数降到低水位线以下，{@link Channel#isWritable()} 将恢复为 true。
     * <br>
     * <b>重要说明：</b>
     * <ul>
     *   <li>低水位线应小于高水位线，建议根据实际业务流量动态调整。</li>
     *   <li>动态调整时需关注当前写缓冲区状态，避免频繁切换。</li>
     * </ul>
     * </p>
     */
    ChannelConfig setWriteBufferLowWaterMark(int writeBufferLowWaterMark);

    /**
     * 获取用于估算消息大小的 {@link MessageSizeEstimator}。
     */
    MessageSizeEstimator getMessageSizeEstimator();

    /**
     * 设置用于估算消息大小的 {@link MessageSizeEstimator}。
     */
    ChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator);

    /**
     * 获取用于设置写缓冲区高低水位线的 {@link WriteBufferWaterMark}。
     * <p>
     * <b>重要说明：</b>
     * <ul>
     *   <li>WriteBufferWaterMark 封装了高低水位线，便于统一管理和动态调整。</li>
     *   <li>可通过 setWriteBufferWaterMark() 一次性设置高低水位线，提升配置灵活性。</li>
     * </ul>
     * </p>
     */
    WriteBufferWaterMark getWriteBufferWaterMark();

    /**
     * 设置用于设置写缓冲区高低水位线的 {@link WriteBufferWaterMark}。
     * <p>
     * <b>重要说明：</b>
     * <ul>
     *   <li>建议结合业务流量和内存情况动态调整，提升系统健壮性和吞吐能力。</li>
     *   <li>该方法会同时影响高低水位线，适合批量配置或动态切换场景。</li>
     * </ul>
     * </p>
     */
    ChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark);
}

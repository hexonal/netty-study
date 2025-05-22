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
package io.netty.channel.socket;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOption;
import io.netty.channel.MessageSizeEstimator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;

import java.net.Socket;
import java.net.StandardSocketOptions;

/**
 * {@link SocketChannel} 专用的 {@link ChannelConfig} 配置接口。
 * <p>
 * <h3>可用选项</h3>
 * 除了 {@link DuplexChannelConfig} 提供的选项外，SocketChannelConfig 还支持如下选项：
 * <table border="1" cellspacing="0" cellpadding="6">
 * <tr>
 * <th>名称</th><th>关联设置方法</th>
 * </tr><tr>
 * <td>{@link ChannelOption#SO_KEEPALIVE}</td><td>{@link #setKeepAlive(boolean)}</td>
 * </tr><tr>
 * <td>{@link ChannelOption#SO_REUSEADDR}</td><td>{@link #setReuseAddress(boolean)}</td>
 * </tr><tr>
 * <td>{@link ChannelOption#SO_LINGER}</td><td>{@link #setSoLinger(int)}</td>
 * </tr><tr>
 * <td>{@link ChannelOption#TCP_NODELAY}</td><td>{@link #setTcpNoDelay(boolean)}</td>
 * </tr><tr>
 * <td>{@link ChannelOption#SO_RCVBUF}</td><td>{@link #setReceiveBufferSize(int)}</td>
 * </tr><tr>
 * <td>{@link ChannelOption#SO_SNDBUF}</td><td>{@link #setSendBufferSize(int)}</td>
 * </tr><tr>
 * <td>{@link ChannelOption#IP_TOS}</td><td>{@link #setTrafficClass(int)}</td>
 * </tr><tr>
 * <td>{@link ChannelOption#ALLOW_HALF_CLOSURE}</td><td>{@link #setAllowHalfClosure(boolean)}</td>
 * </tr>
 * </table>
 * <br>
 * <h3>设计意图</h3>
 * <ul>
 *   <li>为 SocketChannel 提供 TCP 层常用参数的统一配置入口。</li>
 *   <li>支持平台相关的 socket 选项，便于性能调优和兼容性处理。</li>
 * </ul>
 */
public interface SocketChannelConfig extends DuplexChannelConfig {

    /**
     * 获取 {@link StandardSocketOptions#TCP_NODELAY} 配置。
     * <p>
     * <b>重要说明：</b> 默认值为 true（与操作系统默认 false 不同），但部分平台（如 Android）为 false 以规避 Nagle 算法兼容性问题。
     * </p>
     */
    boolean isTcpNoDelay();

    /**
     * 设置 {@link StandardSocketOptions#TCP_NODELAY} 配置。
     * <p>
     * <b>重要说明：</b> 默认值为 true（与操作系统默认 false 不同），但部分平台（如 Android）为 false 以规避 Nagle 算法兼容性问题。
     * <br>
     * <b>Nagle 算法说明：</b>
     * <ul>
     *   <li>Nagle 算法是一种 TCP 优化算法，目的是减少小包数量，提升带宽利用率。</li>
     *   <li>其原理为：当有小数据包需要发送时，若前一个数据包尚未被确认（ACK），则暂缓发送新包，等待 ACK 或缓冲区满后再一起发送。</li>
     *   <li>优点：减少网络拥塞，提升吞吐量，适合大数据量或批量传输场景。</li>
     *   <li>缺点：会引入额外延迟，导致小包（如 IM、游戏、交互式应用）响应变慢。</li>
     * </ul>
     * <b>相关 TCP 优化算法对比：</b>
     * <ul>
     *   <li><b>TCP_NODELAY：</b> 关闭 Nagle 算法，所有小包立即发送，适合低延迟场景，但可能增加网络拥塞。</li>
     *   <li><b>Delayed ACK：</b> TCP 默认启用，接收方延迟发送 ACK 以合并确认，减少 ACK 包数量，但可能与 Nagle 算法叠加导致"ACK阻塞"问题，进一步增加延迟。</li>
     *   <li><b>TCP_QUICKACK：</b> 某些操作系统支持，临时关闭 Delayed ACK，收到数据后立即发送 ACK，进一步降低交互延迟，适合高频交互场景（如 RPC、游戏）。</li>
     * </ul>
     * <b>选择建议：</b>
     * <ul>
     *   <li>对低延迟敏感的应用（如 IM、游戏、金融交易）建议开启 TCP_NODELAY，并结合操作系统支持考虑 TCP_QUICKACK。</li>
     *   <li>对带宽利用率要求高、可容忍延迟的场景（如大文件传输、批量同步）可关闭 TCP_NODELAY，利用 Nagle 算法提升吞吐。</li>
     * </ul>
     * <b>行为影响：</b>
     * <ul>
     *   <li>开启后（禁用 Nagle 算法），数据包会尽快发送，适合低延迟场景（如 IM、游戏等）。</li>
     *   <li>关闭后，可能合并小包，提升带宽利用率，但增加延迟。</li>
     * </ul>
     * </p>
     */
    SocketChannelConfig setTcpNoDelay(boolean tcpNoDelay);

    /**
     * 获取 {@link StandardSocketOptions#SO_LINGER} 配置。
     * <p>
     * 控制 socket 关闭时的延迟行为，-1 表示禁用。
     * </p>
     */
    int getSoLinger();

    /**
     * 设置 {@link StandardSocketOptions#SO_LINGER} 配置。
     * <p>
     * 控制 socket 关闭时的延迟行为，-1 表示禁用。
     * <br>
     * <b>行为影响：</b>
     * <ul>
     *   <li>大于 0 时，close() 会阻塞指定秒数，等待数据发送完毕，适合需要保证数据完整性的场景。</li>
     *   <li>为 0 时，close() 立即返回，未发送数据将被丢弃，适合高实时性但可容忍丢包的场景。</li>
     *   <li>-1 表示采用操作系统默认行为。</li>
     * </ul>
     * </p>
     */
    SocketChannelConfig setSoLinger(int soLinger);

    /**
     * 获取 {@link StandardSocketOptions#SO_SNDBUF} 配置。
     * <p>
     * 设置 socket 发送缓冲区大小，影响大流量场景下的吞吐能力。
     * </p>
     */
    int getSendBufferSize();

    /**
     * 设置 {@link StandardSocketOptions#SO_SNDBUF} 配置。
     * <p>
     * 设置 socket 发送缓冲区大小，影响大流量场景下的吞吐能力。
     * </p>
     */
    SocketChannelConfig setSendBufferSize(int sendBufferSize);

    /**
     * 获取 {@link StandardSocketOptions#SO_RCVBUF} 配置。
     * <p>
     * 设置 socket 接收缓冲区大小，影响高并发接收场景下的性能。
     * </p>
     */
    int getReceiveBufferSize();

    /**
     * 设置 {@link StandardSocketOptions#SO_RCVBUF} 配置。
     * <p>
     * 设置 socket 接收缓冲区大小，影响高并发接收场景下的性能。
     * </p>
     */
    SocketChannelConfig setReceiveBufferSize(int receiveBufferSize);

    /**
     * 获取 {@link StandardSocketOptions#SO_KEEPALIVE} 配置。
     * <p>
     * 启用后，TCP 层会定期发送 keepalive 探测包，检测连接存活性。
     * </p>
     */
    boolean isKeepAlive();

    /**
     * 设置 {@link StandardSocketOptions#SO_KEEPALIVE} 配置。
     * <p>
     * 启用后，TCP 层会定期发送 keepalive 探测包，检测连接存活性。
     * <br>
     * <b>行为影响：</b>
     * <ul>
     *   <li>适合长连接场景，及时发现断开或异常连接，减少资源浪费。</li>
     *   <li>部分云平台或防火墙对长时间无数据连接会强制断开，建议开启。</li>
     * </ul>
     * </p>
     */
    SocketChannelConfig setKeepAlive(boolean keepAlive);

    /**
     * 获取 {@link StandardSocketOptions#IP_TOS} 配置。
     * <p>
     * 设置 IP 层服务类型（TOS），用于流量优先级控制。
     * </p>
     */
    int getTrafficClass();

    /**
     * 设置 {@link StandardSocketOptions#IP_TOS} 配置。
     * <p>
     * 设置 IP 层服务类型（TOS），用于流量优先级控制。
     * </p>
     */
    SocketChannelConfig setTrafficClass(int trafficClass);

    /**
     * 获取 {@link StandardSocketOptions#SO_REUSEADDR} 配置。
     * <p>
     * 启用后，允许 socket 端口复用，常用于高可用或端口抢占场景。
     * </p>
     */
    boolean isReuseAddress();

    /**
     * 设置 {@link StandardSocketOptions#SO_REUSEADDR} 配置。
     * <p>
     * 启用后，允许 socket 端口复用，常用于高可用或端口抢占场景。
     * <br>
     * <b>行为影响：</b>
     * <ul>
     *   <li>服务端重启时可立即绑定同一端口，减少端口占用等待时间。</li>
     *   <li>多进程/多实例监听同一端口时需谨慎使用，避免端口冲突。</li>
     * </ul>
     * </p>
     */
    SocketChannelConfig setReuseAddress(boolean reuseAddress);

    /**
     * 设置 socket 性能偏好，参考 {@link Socket#setPerformancePreferences(int, int, int)}。
     * <p>
     * 可根据连接时间、延迟、带宽三者权重优化 socket 行为。
     * <br>
     * <b>行为影响：</b>
     * <ul>
     *   <li>适合对不同性能指标有特殊需求的场景，如低延迟优先、带宽优先等。</li>
     *   <li>大多数场景下无需设置，保持默认即可。</li>
     * </ul>
     * </p>
     */
    SocketChannelConfig setPerformancePreferences(int connectionTime, int latency, int bandwidth);

    /**
     * 设置是否允许半关闭（half-closure）模式。
     * <p>
     * <b>行为影响：</b>
     * <ul>
     *   <li>允许半关闭时，远端关闭输出流（FIN）后，本地仍可写入数据，适合协议层需要半关闭语义的场景（如 HTTP/1.1、FTP）。</li>
     *   <li>不允许时，远端关闭输出流后，本地 Channel 也会立即关闭。</li>
     *   <li>典型用法：setAllowHalfClosure(true) 可实现"半双工"通信。</li>
     * </ul>
     * </p>
     */
    @Override
    SocketChannelConfig setAllowHalfClosure(boolean allowHalfClosure);

    @Override
    SocketChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis);

    @Override
    @Deprecated
    SocketChannelConfig setMaxMessagesPerRead(int maxMessagesPerRead);

    @Override
    SocketChannelConfig setWriteSpinCount(int writeSpinCount);

    @Override
    SocketChannelConfig setAllocator(ByteBufAllocator allocator);

    @Override
    SocketChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator allocator);

    @Override
    SocketChannelConfig setAutoRead(boolean autoRead);

    @Override
    SocketChannelConfig setAutoClose(boolean autoClose);

    @Override
    SocketChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator);

    @Override
    SocketChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark);
}

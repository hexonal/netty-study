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

import static io.netty.util.internal.ObjectUtil.checkPositive;

import java.net.SocketAddress;

/**
 * 表示 {@link Channel} 实现的元数据信息。
 * <p>
 * <b>设计意图：</b>
 * <ul>
 *   <li>用于描述 Channel 的通用特性，如是否支持 disconnect 操作、默认最大消息读取数等。</li>
 *   <li>便于上层根据不同 Channel 类型动态适配行为（如 UDP 支持 disconnect，TCP 不支持）。</li>
 *   <li>为 Channel 配置和调优提供基础元数据支撑。</li>
 * </ul>
 * <b>典型用法：</b>
 * <pre>
 * {@code
 * ChannelMetadata metadata = channel.metadata();
 * if (metadata.hasDisconnect()) {
 *     // 支持 disconnect 操作
 * }
 * int maxRead = metadata.defaultMaxMessagesPerRead();
 * }
 * </pre>
 */
public final class ChannelMetadata {

    private final boolean hasDisconnect;
    private final int defaultMaxMessagesPerRead;

    /**
     * 创建 ChannelMetadata 实例。
     *
     * @param hasDisconnect 是否支持 disconnect 操作（如 UDP 支持，TCP 不支持）
     */
    public ChannelMetadata(boolean hasDisconnect) {
        this(hasDisconnect, 16);
    }

    /**
     * 创建 ChannelMetadata 实例。
     *
     * @param hasDisconnect 是否支持 disconnect 操作（如 UDP 支持，TCP 不支持）
     * @param defaultMaxMessagesPerRead 若使用 {@link MaxMessagesRecvByteBufAllocator}，则该值为默认的每次最大消息读取数，必须大于 0。
     */
    public ChannelMetadata(boolean hasDisconnect, int defaultMaxMessagesPerRead) {
        checkPositive(defaultMaxMessagesPerRead, "defaultMaxMessagesPerRead");
        this.hasDisconnect = hasDisconnect;
        this.defaultMaxMessagesPerRead = defaultMaxMessagesPerRead;
    }

    /**
     * 是否支持 disconnect 操作。
     * <p>
     * 典型场景：UDP/IP 支持 disconnect，允许断开后重新 connect；TCP/IP 通常不支持。
     * </p>
     * @return true 表示支持 disconnect，false 表示不支持
     */
    public boolean hasDisconnect() {
        return hasDisconnect;
    }

    /**
     * 获取默认的每次最大消息读取数。
     * <p>
     * 若使用 {@link MaxMessagesRecvByteBufAllocator}，该值作为 {@link MaxMessagesRecvByteBufAllocator#maxMessagesPerRead()} 的默认值。
     * </p>
     * @return 默认最大消息读取数，始终大于 0
     */
    public int defaultMaxMessagesPerRead() {
        return defaultMaxMessagesPerRead;
    }
}

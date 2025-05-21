/*
 * Copyright 2016 The Netty Project
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
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

import java.net.SocketAddress;
import java.util.Map;

/**
 * {@link AbstractBootstrapConfig} 用于暴露和封装 {@link AbstractBootstrap} 的配置信息，
 * 便于只读访问和调试，防止外部直接修改引导配置。
 * <p>
 * <b>设计理念：</b>
 * <ul>
 *   <li>只读视图：所有配置均通过 getter 只读访问，防止外部修改。</li>
 *   <li>解耦主流程与配置读取：便于在 handler、扩展等场景下安全获取引导参数。</li>
 *   <li>便于调试和日志输出：toString() 输出完整配置信息。</li>
 * </ul>
 *
 * <b>泛型说明：</b>
 * <ul>
 *   <li>B：具体的 Bootstrap 类型，必须继承自 AbstractBootstrap。常见如 Bootstrap、ServerBootstrap。</li>
 *   <li>C：Channel 类型，指定网络通信的通道实现，如 NioSocketChannel、NioServerSocketChannel。</li>
 * </ul>
 *
 * <b>典型用法：</b>
 * <pre>
 *   AbstractBootstrapConfig config = bootstrap.config();
 *   EventLoopGroup group = config.group();
 *   Map<ChannelOption<?>, Object> opts = config.options();
 * </pre>
 *
 * <b>线程安全说明：</b>
 * - 该类仅提供只读访问，不涉及并发写操作，线程安全。
 *
 * <b>与 AbstractBootstrap 的关系：</b>
 * - 持有对 AbstractBootstrap 的引用，所有 getter 均委托给 bootstrap 实例。
 * - 适合在 handler、扩展、调试等场景下安全获取配置。
 */
public abstract class AbstractBootstrapConfig<B extends AbstractBootstrap<B, C>, C extends Channel> {

    /**
     * 持有的引导对象，类型为 B（如 Bootstrap、ServerBootstrap）。
     * 只读引用，防止外部修改。
     */
    protected final B bootstrap;

    /**
     * 构造方法，传入具体的 bootstrap 实例。
     * @param bootstrap 具体的引导对象，不能为空
     */
    protected AbstractBootstrapConfig(B bootstrap) {
        this.bootstrap = ObjectUtil.checkNotNull(bootstrap, "bootstrap");
    }

    /**
     * 获取已配置的本地绑定地址。
     * @return SocketAddress，若未配置则为 null
     */
    public final SocketAddress localAddress() {
        return bootstrap.localAddress();
    }

    /**
     * 获取已配置的 ChannelFactory。
     * @return ChannelFactory，若未配置则为 null
     */
    @SuppressWarnings("deprecation")
    public final ChannelFactory<? extends C> channelFactory() {
        return bootstrap.channelFactory();
    }

    /**
     * 获取已配置的 ChannelHandler。
     * @return ChannelHandler，若未配置则为 null
     */
    public final ChannelHandler handler() {
        return bootstrap.handler();
    }

    /**
     * 获取已配置的 ChannelOption 只读副本。
     * @return Map，包含所有 option 配置
     */
    public final Map<ChannelOption<?>, Object> options() {
        return bootstrap.options();
    }

    /**
     * 获取已配置的 Attribute 只读副本。
     * @return Map，包含所有 attribute 配置
     */
    public final Map<AttributeKey<?>, Object> attrs() {
        return bootstrap.attrs();
    }

    /**
     * 获取已配置的 EventLoopGroup。
     * @return EventLoopGroup，若未配置则为 null
     */
    @SuppressWarnings("deprecation")
    public final EventLoopGroup group() {
        return bootstrap.group();
    }

    /**
     * 输出完整配置信息，便于调试和日志分析。
     * @return 配置字符串
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
                .append(StringUtil.simpleClassName(this))
                .append('(');
        EventLoopGroup group = group();
        if (group != null) {
            buf.append("group: ")
                    .append(StringUtil.simpleClassName(group))
                    .append(", ");
        }
        @SuppressWarnings("deprecation")
        ChannelFactory<? extends C> factory = channelFactory();
        if (factory != null) {
            buf.append("channelFactory: ")
                    .append(factory)
                    .append(", ");
        }
        SocketAddress localAddress = localAddress();
        if (localAddress != null) {
            buf.append("localAddress: ")
                    .append(localAddress)
                    .append(", ");
        }

        Map<ChannelOption<?>, Object> options = options();
        if (!options.isEmpty()) {
            buf.append("options: ")
                    .append(options)
                    .append(", ");
        }
        Map<AttributeKey<?>, Object> attrs = attrs();
        if (!attrs.isEmpty()) {
            buf.append("attrs: ")
                    .append(attrs)
                    .append(", ");
        }
        ChannelHandler handler = handler();
        if (handler != null) {
            buf.append("handler: ")
                    .append(handler)
                    .append(", ");
        }
        if (buf.charAt(buf.length() - 1) == '(') {
            buf.append(')');
        } else {
            buf.setCharAt(buf.length() - 2, ')');
            buf.setLength(buf.length() - 1);
        }
        return buf.toString();
    }
}

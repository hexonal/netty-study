/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.marshalling;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.Unmarshaller;

/**
 * 一个 {@link UnmarshallerProvider} 实现，它将 {@link Unmarshaller} 实例的引用存储在
 * {@link ChannelHandlerContext} 关联的 {@link Channel} 的属性 (Attribute) 中（通过
 * {@link Channel#attr(AttributeKey)} 方法）。
 * 这样可以确保在特定 {@link Channel} 的整个生命周期内，对于给定的 {@link ChannelHandler}（更准确地说是该
 * Channel 上的 JBoss Marshalling 解码器），
 * 始终使用同一个 {@link Unmarshaller} 实例进行解组操作。
 * <p>
 * 这种机制对于需要维护解组状态（例如，对象引用跟踪）的场景非常有用，因为它可以保证后续的解组操作能够利用先前解组操作中建立的状态。
 * </p>
 * <p>
 * <b>工作原理：</b>
 * <ol>
 * <li>当首次为某个 {@link ChannelHandlerContext} 请求 {@link Unmarshaller} 时，会调用父类
 * {@link DefaultUnmarshallerProvider#getUnmarshaller(ChannelHandlerContext)}
 * 创建一个新的 {@link Unmarshaller} 实例。</li>
 * <li>这个新创建的 {@link Unmarshaller} 实例会被存储到与该 {@code ctx} 关联的 {@link Channel}
 * 的一个特定 {@link AttributeKey} (即 {@code UNMARSHALLER}) 下。</li>
 * <li>后续对同一個 {@link ChannelHandlerContext} (或者说，同一个 {@link Channel} 上的其他相关上下文)
 * 的 {@code getUnmarshaller} 调用，
 * 会首先尝试从 {@link Channel} 的属性中检索已存储的 {@link Unmarshaller} 实例。</li>
 * <li>如果找到了已存储的实例，则直接返回该实例，从而复用它。</li>
 * </ol>
 * <b>日志记录考虑:</b>
 * <ul>
 * <li>在首次创建并缓存 {@link Unmarshaller} 实例时，可以记录 DEBUG 级别日志。</li>
 * <li>在从缓存中成功检索到 {@link Unmarshaller} 实例时，也可以记录 DEBUG 级别日志。</li>
 * <li>如果父类创建 {@link Unmarshaller} 失败 (抛出异常)，则异常应由调用栈向上传播，调用者可能会记录 ERROR
 * 级别日志。</li>
 * </ul>
 * </p>
 */
public class ContextBoundUnmarshallerProvider extends DefaultUnmarshallerProvider {

    // 用于在 Channel 属性中存储和检索 Unmarshaller 实例的 AttributeKey。
    // 这个键是静态 final 的，确保了所有 ContextBoundUnmarshallerProvider 实例都使用相同的键名来访问 Channel
    // 的属性，
    // 从而使得 Unmarshaller 在 Channel 级别是共享的（如果多个 Handler 都使用这个 Provider
    // 的话，但通常是单个解码器使用）。
    private static final AttributeKey<Unmarshaller> UNMARSHALLER = AttributeKey.valueOf(
            ContextBoundUnmarshallerProvider.class, "UNMARSHALLER"); // Key 的名称包含了类名以增加唯一性

    /**
     * 构造一个新的 {@link ContextBoundUnmarshallerProvider} 实例。
     *
     * @param factory 用于创建 {@link Unmarshaller} 实例的 {@link MarshallerFactory}。
     * @param config  JBoss Marshalling 的配置对象。
     */
    public ContextBoundUnmarshallerProvider(MarshallerFactory factory, MarshallingConfiguration config) {
        super(factory, config); // 调用父类构造函数，传递工厂和配置
    }

    /**
     * 获取与给定 {@link ChannelHandlerContext} 关联的 {@link Unmarshaller} 实例。
     * <p>
     * 此方法首先检查与 {@code ctx} 关联的 {@link Channel} 的属性中是否已存在缓存的 {@link Unmarshaller}
     * 实例。
     * 如果存在，则返回该缓存实例。
     * 如果不存在，则调用父类的 {@link #getUnmarshaller(ChannelHandlerContext)} 方法创建一个新的
     * {@link Unmarshaller}，
     * 然后将此新实例存储到 {@link Channel} 的属性中以供后续使用，并返回该新实例。
     * </p>
     *
     * @param ctx 用于获取 {@link Unmarshaller} 的 {@link ChannelHandlerContext}。
     * @return 与该上下文（或其关联的 Channel）绑定的 {@link Unmarshaller} 实例。
     * @throws Exception 如果创建 {@link Unmarshaller} 实例失败。
     */
    @Override
    public Unmarshaller getUnmarshaller(ChannelHandlerContext ctx) throws Exception {
        // 从 Channel 的属性中获取 Unmarshaller 的 Attribute
        Attribute<Unmarshaller> attr = ctx.channel().attr(UNMARSHALLER);
        Unmarshaller unmarshaller = attr.get(); // 尝试获取已缓存的 Unmarshaller

        if (unmarshaller == null) {
            // 如果缓存中没有，则通过父类创建一个新的 Unmarshaller
            unmarshaller = super.getUnmarshaller(ctx);
            // 将新创建的 Unmarshaller 存储到 Channel 的属性中，以便后续复用
            attr.set(unmarshaller);
            // 日志记录：可以考虑在此处添加 DEBUG 日志，表明为新的 Channel 创建并缓存了 Unmarshaller。
            // 例如: logger.debug("Created and cached new Unmarshaller for channel: {}",
            // ctx.channel());
        } else {
            // 日志记录：可以考虑在此处添加 TRACE 或 DEBUG 日志，表明从缓存中获取了 Unmarshaller。
            // 例如: logger.trace("Retrieved Unmarshaller from cache for channel: {}",
            // ctx.channel());
        }
        return unmarshaller;
    }
}

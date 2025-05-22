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
package io.netty.util;

/**
 * 用于存储 {@link Attribute} 的容器，这些属性可以通过 {@link AttributeKey} 进行访问。
 * 这个接口通常由需要附加任意数据（属性）的组件实现，例如 {@link io.netty.channel.Channel} 或
 * {@link io.netty.channel.ChannelHandlerContext}。
 * <p>
 * 实现必须是线程安全的，因为属性可能会被多个线程并发访问和修改。
 */
public interface AttributeMap {
    /**
     * 获取或创建一个与给定 {@link AttributeKey} 关联的 {@link Attribute}。
     * <p>
     * 如果 {@link AttributeMap} 中已存在与指定 {@code key} 关联的 {@link Attribute}，则返回该现有实例。
     * 否则，创建一个新的 {@link Attribute} 实例，将其与 {@code key} 关联，并存储在此 {@link AttributeMap}
     * 中，然后返回新创建的实例。
     * <p>
     * 此方法保证永远不会返回 {@code null}。
     * 返回的 {@link Attribute} 可能尚未设置任何值（即 {@link Attribute#get()} 可能返回 {@code null}），
     * 直到用户显式调用 {@link Attribute#set(Object)} 或相关方法设置一个值。
     *
     * @param key 用于查找或创建属性的 {@link AttributeKey}。此键唯一标识一个属性。
     * @param <T> 属性值的类型，由 {@link AttributeKey} 的泛型参数决定。
     * @return 与给定键关联的 {@link Attribute} 实例。如果不存在，则会创建一个新的实例并返回。
     */
    <T> Attribute<T> attr(AttributeKey<T> key);

    /**
     * 检查此 {@link AttributeMap} 是否包含与给定的 {@link AttributeKey} 关联的属性。
     * <p>
     * "包含"意味着一个 {@link Attribute} 实例已经因为之前的 {@link #attr(AttributeKey)}
     * 调用而被创建并与该键关联。
     * 这并不意味着该属性一定有一个非 {@code null} 的值。
     * 一个属性即使其值为 {@code null}，也被认为是存在的。
     * <p>
     * 此方法通常用于避免不必要的 {@link Attribute} 对象创建，如果调用者只想检查属性是否存在而不打算获取或创建它。
     * 然而，在大多数情况下，直接调用 {@link #attr(AttributeKey)} 然后检查其值通常更直接，因为
     * {@link #attr(AttributeKey)}
     * 本身就是幂等的（如果属性已存在则返回现有实例）。
     *
     * @param key 要检查其存在性的 {@link AttributeKey}。
     * @param <T> 属性值的类型。
     * @return 如果与给定键关联的 {@link Attribute} 实例已存在于此映射中，则返回 {@code true}；否则返回
     *         {@code false}。
     */
    <T> boolean hasAttr(AttributeKey<T> key);
}

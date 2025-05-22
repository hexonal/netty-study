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
 * 一个属性，允许存储一个值的引用。它可以原子地更新，因此是线程安全的。
 * 通常与 {@link AttributeMap} 一起使用，例如在 {@link io.netty.channel.Channel} 上存储自定义数据。
 *
 * @param <T> 此属性持有的值的类型。
 */
public interface Attribute<T> {

    /**
     * 返回此属性的 {@link AttributeKey}。
     * 这个键用于在 {@link AttributeMap} 中唯一标识此属性。
     *
     * @return 此属性的键
     */
    AttributeKey<T> key();

    /**
     * 返回当前存储在此属性中的值。
     * 如果属性未设置或已被显式设置为 {@code null}，则返回 {@code null}。
     *
     * @return 当前属性值，如果未设置则为 {@code null}
     */
    T get();

    /**
     * 设置此属性的值。
     * 如果传入 {@code null}，则表示清除当前值。
     *
     * @param value 要设置的新值，可以为 {@code null}
     */
    void set(T value);

    /**
     * 原子地将属性值设置为给定的新值，并返回设置前（即更新前）的旧值。
     * 如果之前没有设置过值 (例如，属性是新创建的或者之前被移除了)，旧值可能为 {@code null}。
     * 此操作是原子的，确保了在并发环境下的正确性。
     *
     * @param value 要设置的新值，可以为 {@code null} 以清除属性值
     * @return 更新前的旧值；如果属性是新创建的或之前未设置值，则可能为 {@code null}
     */
    T getAndSet(T value);

    /**
     * 如果此 {@link Attribute} 的当前值为 {@code null}，则原子地将其设置为给定的新值。
     * 这个方法通常用于"首次初始化"的场景，确保某个值只被设置一次。
     * <p>
     * 如果因为属性已经包含一个非 {@code null} 的值而导致无法设置新值（即当前值不是 {@code null}），
     * 则此方法不会修改属性值，并仅返回当前已存在的值。
     * <p>
     * 如果属性的当前值为 {@code null}，则新值被成功设置，并且此方法返回 {@code null}。
     *
     * @param value 尝试设置的值。如果此值为 {@code null}，则此方法等效于 {@link #get()} 并且不会改变属性状态，
     *              但通常不建议传入 {@code null}，因为这会使方法的意图变得不明确。
     * @return 如果新值被成功设置（即原值为 {@code null}），则返回 {@code null}。
     *         如果原值不是 {@code null}，则返回当前已存在的值，并且属性值保持不变。
     */
    T setIfAbsent(T value);

    /**
     * 从其所属的 {@link AttributeMap} 中原子地移除此属性，并返回移除前的旧值。
     * 移除后，后续对 {@link #get()} 的调用将返回 {@code null}。
     * <p>
     * <strong>警告：</strong> 此方法会将 {@link Attribute} 实例本身从 {@link AttributeMap}
     * 中彻底移除。
     * 这意味着如果其他地方仍然持有对此 {@link Attribute} 实例的引用，它们将继续操作这个（现在已经分离的）实例。
     * 如果之后通过相同的 {@link AttributeKey} 从 {@link AttributeMap#attr(AttributeKey)}
     * 再次请求属性，
     * 将会创建一个全新的 {@link Attribute} 实例。
     * <p>
     * 如果您的意图仅仅是清除属性的值，同时希望 {@link Attribute} 实例仍然保留在 {@link AttributeMap} 中以供后续使用
     * (例如，避免重新创建 {@link Attribute} 对象)，那么应该使用 {@link #getAndSet(Object)} 并传入
     * {@code null} 作为参数，
     * 而不是调用此方法。
     * <p>
     * 例如：{@code attribute.getAndSet(null);} 将清除值但保留属性实例。
     * <p>
     * 由于这种行为可能导致混淆，此方法已被标记为 {@link Deprecated}。
     *
     * @return 移除前的旧值；如果属性之前未设置值，则可能为 {@code null}
     * @deprecated 建议使用 {@link #getAndSet(Object)} 并传入 {@code null}
     *             来代替，以仅清除属性值而不是从映射中移除属性本身。
     *             这样做可以避免因 {@link Attribute} 实例被替换而可能导致的意外行为。
     *             例如： {@code T oldValue = attribute.getAndSet(null);}。
     */
    @Deprecated
    T getAndRemove();

    /**
     * 原子地比较当前值与 {@code oldValue}，如果它们相等（通过 {@code ==} 引用比较，或者如果两者都为 {@code null}），
     * 则将值更新为 {@code newValue}。
     * 此操作是原子的，可用于实现乐观锁或无锁算法中的状态转换。
     *
     * @param oldValue 期望的旧值 (用于比较的值)
     * @param newValue 如果当前值等于 {@code oldValue}，则设置为此新值
     * @return 如果比较成功并且值被更新，则返回 {@code true}；否则返回 {@code false} (表示当前值不等于
     *         {@code oldValue})。
     */
    boolean compareAndSet(T oldValue, T newValue);

    /**
     * 从其所属的 {@link AttributeMap} 中移除此属性。
     * 移除后，后续对 {@link #get()} 的调用将返回 {@code null}。
     * <p>
     * <strong>警告：</strong> 与 {@link #getAndRemove()} 类似，此方法会将 {@link Attribute}
     * 实例本身从
     * {@link AttributeMap} 中彻底移除。其行为和潜在问题与 {@link #getAndRemove()} 中描述的相同。
     * <p>
     * 如果您的意图仅仅是清除属性的值，同时希望 {@link Attribute} 实例仍然保留在 {@link AttributeMap} 中，
     * 应该使用 {@link #set(Object)} 并传入 {@code null} 作为参数。
     * 例如：{@code attribute.set(null);} 将清除值但保留属性实例。
     * <p>
     * 由于这种行为可能导致混淆，此方法已被标记为 {@link Deprecated}。
     *
     * @deprecated 建议使用 {@link #set(Object)} 并传入 {@code null}
     *             来代替，以仅清除属性值而不是从映射中移除属性本身。
     *             例如： {@code attribute.set(null);}。
     */
    @Deprecated
    void remove();
}

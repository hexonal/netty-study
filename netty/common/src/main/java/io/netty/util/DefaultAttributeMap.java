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

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * {@link AttributeMap} 的默认线程安全实现。
 * 它在属性查找时表现出非阻塞行为，并在修改路径上采用写时复制（copy-on-write）策略，以确保并发读取的无锁访问。
 * <br>
 * 此实现的核心思想是将 {@link DefaultAttribute} 对象存储在一个按其关联的 {@link AttributeKey#id()}
 * 排序的数组中。
 * 属性的查找操作通过对该数组进行二分搜索来完成
 * ({@link #searchAttributeByKey(DefaultAttribute[], AttributeKey)})。
 * 当添加新属性或移除现有属性时，会创建一个新的数组副本（写时复制）。旧数组的引用会被原子地替换为新数组的引用。
 * 这种机制确保了并发的读取操作总是访问一个不可变的数组快照，从而避免了加锁的需要。
 *
 * <p>
 * <strong>复杂度分析:</strong>
 * </p>
 * <ul>
 * <li><b>时间复杂度:</b>
 * <ul>
 * <li>读操作 ({@link #hasAttr(AttributeKey)} 以及 {@link #attr(AttributeKey)}
 * 中不涉及修改的部分):
 * <strong>O(log N)</strong>，其中 N 是属性的数量。
 * 这是因为内部使用了基于 {@link AttributeKey#id()} 的二分搜索。
 * </li>
 * <li>写操作 ({@link #attr(AttributeKey)} 中涉及修改的部分，以及由
 * {@link DefaultAttribute#remove()} 或
 * {@link DefaultAttribute#getAndRemove()} 间接触发的属性移除操作，即
 * {@link #removeAttributeIfMatch(AttributeKey, DefaultAttribute)}):
 * <strong>O(N)</strong>。
 * 写操作涉及到创建属性数组的新副本，并将现有元素复制到新数组中。
 * 例如，在
 * {@link #orderedCopyOnInsert(DefaultAttribute[], int, DefaultAttribute[], DefaultAttribute)}
 * 中进行插入复制，
 * 或者在 {@link #removeAttributeIfMatch(AttributeKey, DefaultAttribute)} 中进行移除复制。
 * 虽然最初的定位（通过二分搜索）是 O(log N)，但数组复制的成本是 O(N)，因此整体写操作复杂度为 O(N)。
 * </li>
 * </ul>
 * </li>
 * <li><b>空间复杂度:</b> <strong>O(N)</strong>，其中 N 是存储在映射中的属性数量。
 * 在写操作期间，由于创建了数组的副本，瞬时空间复杂度可能达到 O(2N)，但稳定状态下为 O(N)。
 * </li>
 * </ul>
 *
 * <p>
 * <strong>设计特点与权衡:</strong>
 * </p>
 * <ul>
 * <li><b>读优化:</b> 读操作（查找）通过二分搜索实现，速度相对较快 (O(log N))。由于 {@code attributes} 数组是
 * {@code volatile} 类型，并且其引用仅在写操作时被原子地替换（而不是直接修改数组内容），
 * 因此读操作不需要任何锁，这显著提高了并发读取的性能和可伸缩性。</li>
 * <li><b>写时复制 (Copy-On-Write):</b> 写操作（添加新属性、因 {@link DefaultAttribute}
 * 被标记为已移除而进行的"更新"、或实际移除属性）的成本较高 (O(N))，
 * 因为它们需要复制整个底层属性数组。
 * 然而，这种策略是实现无锁读取的关键，因为它确保了读取者总是在操作一个一致且不可变的数组快照。</li>
 * <li><b>线程安全机制:</b>
 * <ul>
 * <li>对 {@code attributes} 数组（即属性集合的引用）的更新是通过
 * {@link AtomicReferenceFieldUpdater} (具体为 {@code ATTRIBUTES_UPDATER}) 执行的比较并交换
 * (CAS) 操作来完成的，
 * 这确保了对属性集合引用的原子性更新。</li>
 * <li>每个 {@link DefaultAttribute} 实例内部使用 {@link AtomicReference} (通过继承)
 * 来原子地管理其存储的实际属性值。
 * 此外，{@link DefaultAttribute} 还使用另一个 {@link AtomicReferenceFieldUpdater} (具体为
 * {@code MAP_UPDATER})
 * 来原子地更新其对所属 {@code DefaultAttributeMap} 的引用。这个引用在属性被移除时会被设置为 {@code null}，
 * 从而有效地将属性标记为"已移除"状态。这些机制共同保证了单个属性操作的线程安全性。</li>
 * </ul>
 * </li>
 * <li><b>适用场景:</b>
 * 这种数据结构非常适合读操作远多于写操作的并发环境。
 * 频繁的读操作可以从无锁设计中获得显著的性能优势。
 * 虽然写操作的开销相对较大，但它们不会阻塞读操作，从而有助于维持整个系统的高响应性和高吞吐量。
 * Netty 中的 Channel 和 ChannelHandlerContext 都使用了这种机制来管理属性，这些场景通常符合读多写少的模式。
 * </li>
 * </ul>
 *
 * <p>
 * <strong>关于属性移除的说明:</strong>
 * </p>
 * 当调用 {@link Attribute#remove()} 或 {@link Attribute#getAndRemove()} 时，对应的
 * {@link DefaultAttribute}
 * 实例会首先在内部标记自己为"已移除"（通过将其内部的 {@code attributeMap} 引用设置为 {@code null}），
 * 然后调用
 * {@link DefaultAttributeMap#removeAttributeIfMatch(AttributeKey, DefaultAttribute)}
 * 来尝试从外部的
 * {@code DefaultAttributeMap} 的属性数组中实际移除该实例。
 * 类似地，当调用 {@link Attribute#set(Object)} 并传入 {@code null} 时，虽然其主要目的是清除属性值，
 * 但在 {@link DefaultAttribute} 的实现中，如果该属性先前已被标记为移除，则 {@code set(null)} 的行为
 * 与清除值类似，并不会再次触发 {@code removeAttributeIfMatch}。
 * 不论是显式移除还是通过 {@code set(null)} 清除值后被移除，这些操作最终都可能导致对 {@code attributes} 数组的修改，
 * 因此其复杂度为 O(N)。
 */
public class DefaultAttributeMap implements AttributeMap {

    // 使用 InternalLoggerFactory 获取日志记录器实例，符合 Netty 日志规范
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultAttributeMap.class);

    // 用于原子更新 attributes 字段的 AtomicReferenceFieldUpdater 实例。
    // 这确保了对 attributes 数组引用的修改是线程安全的。
    private static final AtomicReferenceFieldUpdater<DefaultAttributeMap, DefaultAttribute[]> ATTRIBUTES_UPDATER = AtomicReferenceFieldUpdater
            .newUpdater(DefaultAttributeMap.class, DefaultAttribute[].class, "attributes");

    // 表示一个空的 DefaultAttribute 数组的常量，用于初始化或当 map 为空时。
    // 作为一个不可变对象，它可以安全地被共享，避免了重复创建空数组的开销。
    private static final DefaultAttribute[] EMPTY_ATTRIBUTES = new DefaultAttribute[0];

    /**
     * 在已排序的 {@code sortedAttributes} 数组中通过二分搜索查找具有指定 {@link AttributeKey} 的
     * {@link DefaultAttribute}。
     * 此方法针对此特定用例进行了优化，旨在减少多态调用和不必要的类型检查。
     *
     * @param sortedAttributes 已按 {@link AttributeKey#id()} 排序的
     *                         {@link DefaultAttribute} 数组。
     * @param key              要搜索的 {@link AttributeKey}。
     * @return 如果找到键，则返回该键在数组中的索引；否则，返回 {@code (-(insertion point) - 1)}，
     *         其中 {@code insertion point} 是键应该被插入以保持数组有序的位置。
     *         这个返回值约定与 {@link Arrays#binarySearch(Object[], Object)} 一致。
     */
    private static int searchAttributeByKey(DefaultAttribute[] sortedAttributes, AttributeKey<?> key) {
        int low = 0;
        int high = sortedAttributes.length - 1;

        while (low <= high) {
            // (low + high) >>> 1 用于计算中间索引，并能有效防止 (low + high) 可能发生的整数溢出。
            int mid = low + high >>> 1;
            DefaultAttribute midVal = sortedAttributes[mid];
            AttributeKey<?> midValKey = midVal.key; // 注意：这里是 DefaultAttribute.key 而非 midVal.key()

            // 快速路径：如果 key 实例相同，则直接返回索引。
            // 由于 AttributeKey 通常是单例（通过 ConstantPool 创建），这种引用比较非常有效。
            if (midValKey == key) {
                return mid;
            }

            // 比较 AttributeKey 的 ID。ID 是唯一的，并且用于排序。
            int midValKeyId = midValKey.id();
            int keyId = key.id();

            // 断言：正常情况下，不同的 AttributeKey 实例应该具有不同的 ID。
            // 如果出现 ID 冲突，则表明 AttributeKey 的常量池实现或 ID 分配逻辑存在问题。
            assert midValKeyId != keyId : "AttributeKey ID collision. This should not happen and indicates a bug.";

            boolean searchRight = midValKeyId < keyId;
            if (searchRight) {
                low = mid + 1; // 目标 key 在右半部分
            } else {
                high = mid - 1; // 目标 key 在左半部分
            }
        }

        // 如果未找到，则返回 -(insertion point) - 1。
        // low 此时表示如果 key 不存在，它应该被插入的位置 (insertion point)。
        return -(low + 1);
    }

    /**
     * 在插入新属性时，创建一个新的有序数组副本。
     * 此方法将 {@code toInsert} 属性插入到从 {@code sortedSrc} 复制而来的 {@code copy} 数组的正确位置，
     * 以保持 {@code copy} 数组按 {@link AttributeKey#id()} 排序。
     *
     * @param sortedSrc 源排序数组 ({@link DefaultAttribute} 列表)。
     * @param srcLength 源数组中实际元素的数量。
     * @param copy      目标数组，其大小应为 {@code srcLength + 1}，用于存放复制和插入后的结果。
     * @param toInsert  要插入的新 {@link DefaultAttribute}。
     */
    private static void orderedCopyOnInsert(
            DefaultAttribute<?>[] sortedSrc,
            int srcLength,
            DefaultAttribute<?>[] copy,
            DefaultAttribute<?> toInsert) {

        // 从后向前遍历源数组来查找插入点并将元素右移。
        // 这种从后向前的策略基于一个经验性假设：新插入的 AttributeKey 的 ID 通常较大，
        // 这可能使得插入点更靠近数组末尾，从而减少移动元素的数量。
        final int id = toInsert.key.id();
        int i;
        for (i = srcLength - 1; i >= 0; i--) {
            DefaultAttribute<?> attribute = sortedSrc[i];
            assert attribute.key.id() != id : "AttributeKey ID collision on insert. This should not happen.";
            if (attribute.key.id() < id) {
                // 找到了插入点：当前 sortedSrc[i] 的 ID 小于 toInsert 的 ID，
                // 所以 toInsert 应该放在 sortedSrc[i] 的后面，即 copy[i+1]。
                break;
            }
            // 当前 sortedSrc[i] 的 ID 大于或等于 toInsert 的 ID（理论上不应等于，因为有断言），
            // 将 sortedSrc[i] 向右移动到 copy[i+1] 的位置，为 toInsert 腾出空间。
            copy[i + 1] = sortedSrc[i];
        }

        // 将 toInsert 放置在找到的插入位置 (i + 1)。
        // 如果循环未执行 (srcLength = 0 或所有元素都比 toInsert 大)，i 会是 -1，i+1 是 0。
        // 如果 toInsert 比所有元素都大，i 会是 srcLength - 1，i+1 是 srcLength。
        copy[i + 1] = toInsert;

        // 复制插入点左侧的所有元素（如果存在）。
        // toCopy 表示需要从 sortedSrc 的开头复制到 copy 的开头的元素数量。
        final int toCopy = i + 1;
        if (toCopy > 0) {
            System.arraycopy(sortedSrc, 0, copy, 0, toCopy);
        }
    }

    // 使用 volatile 关键字确保 attributes 数组的引用在多线程环境下的可见性。
    // 当一个线程修改了 attributes 的引用 (指向一个新的数组副本)，其他线程能够立即看到这个变化。
    // 初始化为空数组，而不是 null，以避免在读取时进行 null 检查。
    private volatile DefaultAttribute<?>[] attributes = EMPTY_ATTRIBUTES;

    @SuppressWarnings("unchecked")
    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        ObjectUtil.checkNotNull(key, "key"); // 确保传入的 key 不为 null

        DefaultAttribute<T> newAttribute = null; // 用于在需要时创建新的 DefaultAttribute 实例

        // 无限循环 (CAS-loop / retry-loop): 用于处理并发更新。
        // 如果 CAS 操作失败，意味着其他线程在此期间修改了 attributes 数组，
        // 此时需要重新读取最新的 attributes 数组并重试整个逻辑。
        for (;;) {
            // 读取当前 attributes 数组的 volatile 引用。
            // 这是线程安全的，因为 volatile 保证了读取到的是最新的值。
            final DefaultAttribute<?>[] currentAttributes = this.attributes;
            final int index = searchAttributeByKey(currentAttributes, key); // 在当前数组中二分查找指定的 key

            final DefaultAttribute<?>[] newAttributesArrayHolder; // 用于持有将要替换当前数组的新数组

            if (index >= 0) {
                // 情况 1: key 已存在于数组中。
                final DefaultAttribute<?> foundAttribute = currentAttributes[index];
                // 断言：确保找到的属性的 key 与传入的 key 是同一个实例 (通过引用比较)。
                // 这依赖于 AttributeKey 的单例特性。
                assert foundAttribute.key() == key;

                if (!foundAttribute.isRemoved()) {
                    // 情况 1a: 找到的属性未被标记为移除。
                    // 这是最常见的成功查找路径。
                    if (logger.isDebugEnabled()) {
                        // 根据 Netty 日志规范，使用参数化日志，避免不必要的字符串拼接
                        logger.debug("Attribute found for key: {}", key);
                    }
                    return (Attribute<T>) foundAttribute; // 直接返回该属性
                }

                // 情况 1b: 找到的属性已被标记为移除 (isRemoved() == true)。
                // 这意味着该 key 之前存在过，但其对应的 Attribute 实例已被移除。
                // 我们需要创建一个新的 Attribute 实例来替换这个"已移除"的占位符。
                if (newAttribute == null) {
                    // 延迟创建 newAttribute 实例，仅在确实需要时创建。
                    newAttribute = new DefaultAttribute<T>(this, key);
                }

                final int count = currentAttributes.length;
                newAttributesArrayHolder = Arrays.copyOf(currentAttributes, count); // 创建当前数组的副本
                newAttributesArrayHolder[index] = newAttribute; // 在副本中用新的 Attribute 实例替换掉已移除的实例

            } else {
                // 情况 2: key 不存在于数组中。
                // 需要创建一个新的 Attribute 实例并将其添加到数组中。
                if (newAttribute == null) {
                    // 延迟创建 newAttribute 实例。
                    newAttribute = new DefaultAttribute<T>(this, key);
                }

                final int count = currentAttributes.length;
                newAttributesArrayHolder = new DefaultAttribute[count + 1]; // 创建一个比当前数组大1的新数组

                // 将旧数组的内容和新属性实例复制到新数组中，并保持新数组的有序性。
                // orderedCopyOnInsert 会处理正确的插入位置。
                orderedCopyOnInsert(currentAttributes, count, newAttributesArrayHolder, newAttribute);

                if (logger.isDebugEnabled()) {
                    logger.debug("New attribute created and added for key: {}", key);
                }
            }

            // 尝试使用 CAS 原子地更新 attributes 字段，将其指向新创建或修改后的数组。
            // 如果 this.attributes 自上次读取以来没有被其他线程修改过 (即仍然是 currentAttributes)，
            // 则 CAS 操作成功，attributes 字段被更新为 newAttributesArrayHolder。
            if (ATTRIBUTES_UPDATER.compareAndSet(this, currentAttributes, newAttributesArrayHolder)) {
                // CAS 成功，操作完成。
                return newAttribute; // 返回新创建的或用于替换的 Attribute 实例
            }

            // CAS 失败：意味着在执行此循环的期间，有其他线程修改了 this.attributes。
            // 记录一个 debug 日志，然后循环将重试整个过程。
            if (logger.isDebugEnabled()) {
                logger.debug("CAS failed while trying to update attributes for key: {}. Retrying...", key);
            }
        }
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        ObjectUtil.checkNotNull(key, "key"); // 确保传入的 key 不为 null

        // 读取 attributes 数组的 volatile 引用。
        DefaultAttribute<?>[] currentAttributes = this.attributes;

        // 直接使用二分查找判断 key 是否存在于当前属性数组中。
        // searchAttributeByKey 返回非负值表示找到了 key。
        // 注意：此方法不检查属性是否被标记为 isRemoved()。
        // 它仅表示该 key 在某个时间点被加入过 AttributeMap。
        // 如果需要区分"存在但已移除"和"从未存在"，则需要调用 attr(key).get() 并检查结果。
        // 然而，hasAttr 的语义通常就是指"是否存在对应的 Attribute 实例（不论其状态）"。
        int index = searchAttributeByKey(currentAttributes, key);
        if (index >= 0) {
            // 进一步检查找到的属性是否未被移除。一个"已移除"的属性不应算作"hasAttr"。
            // 这是为了与 attr(key) 的行为保持一致：如果一个key对应的attribute被移除了，
            // 再次调用 attr(key) 会得到一个新的attribute实例。
            // 因此，hasAttr 应该只在属性实际存在且可用时返回 true。
            return !currentAttributes[index].isRemoved();
        }
        return false; // key 未找到
    }

    /**
     * 如果指定的 {@code key} 存在，并且其对应的 {@link DefaultAttribute} 实例与
     * {@code valueToRemove} 是同一个实例，
     * 则从属性数组中移除该属性。这是一个内部辅助方法，主要由 {@link DefaultAttribute#remove()} 和
     * {@link DefaultAttribute#getAndRemove()} 调用。
     *
     * <p>
     * 此操作是线程安全的，并使用 CAS 循环来处理并发修改。
     * </p>
     *
     * @param key           要移除的属性的 {@link AttributeKey}。
     * @param valueToRemove 期望移除的 {@link DefaultAttribute} 实例。只有当数组中对应 {@code key}
     *                      的属性
     *                      与此实例完全相同时，移除操作才会执行。这是为了防止意外移除一个已被替换的属性。
     * @param <T>           属性值的类型。
     */
    private <T> void removeAttributeIfMatch(AttributeKey<T> key, DefaultAttribute<T> valueToRemove) {
        // CAS 循环，用于处理并发修改。
        for (;;) {
            final DefaultAttribute<?>[] currentAttributes = this.attributes; // 读取当前属性数组
            final int index = searchAttributeByKey(currentAttributes, key); // 查找 key 对应的索引

            if (index < 0) {
                // 属性不存在于数组中，无需执行任何操作。
                // 这可能发生在：属性从未被添加，或者已经被其他线程移除了。
                if (logger.isDebugEnabled()) {
                    logger.debug("Attribute for key: {} not found for removal (already removed or never existed).",
                            key);
                }
                return; // 结束方法
            }

            // 属性存在于数组中，获取该属性实例。
            final DefaultAttribute<?> attributeInArray = currentAttributes[index];
            // 断言：确保在数组中找到的属性的 key 与传入的 key 是同一个实例。
            assert attributeInArray.key() == key;

            // 检查数组中的属性实例是否与期望移除的实例 (valueToRemove) 是同一个对象。
            // 这是非常重要的一步：确保我们只移除我们打算移除的那个特定的 DefaultAttribute 实例。
            // 如果它们不相同，意味着在当前线程尝试移除之前，该 key 对应的 Attribute 可能已经被替换了
            // (例如，旧的被移除，然后又用同一个 key 添加了一个新的 Attribute)。
            // 在这种情况下，我们不应该移除新的、不相关的 Attribute 实例。
            if (attributeInArray != valueToRemove) {
                if (logger.isDebugEnabled()) {
                    logger.debug(
                            "Attribute instance mismatch for key: {}. Expected instance for removal: {}, Found instance in map: {}. No removal will be performed for this attempt.",
                            key, valueToRemove, attributeInArray);
                }
                return; // 实例不匹配，不执行移除，结束方法
            }

            // 准备移除操作：计算新数组的大小。
            final int count = currentAttributes.length;
            final int newCount = count - 1;
            final DefaultAttribute<?>[] newAttributes;

            if (newCount == 0) {
                // 如果移除后数组为空，则将新数组设置为空数组常量。
                newAttributes = EMPTY_ATTRIBUTES;
            } else {
                // 创建一个比当前数组小1的新数组。
                newAttributes = new DefaultAttribute[newCount];
                // 将旧数组中位于被移除元素之前的部分复制到新数组。
                System.arraycopy(currentAttributes, 0, newAttributes, 0, index);
                // 计算被移除元素之后剩余元素的数量。
                final int remaining = count - index - 1;
                if (remaining > 0) {
                    // 将旧数组中位于被移除元素之后的部分复制到新数组。
                    System.arraycopy(currentAttributes, index + 1, newAttributes, index, remaining);
                }
                
            }

            // 尝试使用 CAS 原子地更新 attributes 字段，将其指向新的、移除了元素的数组。
            if (ATTRIBUTES_UPDATER.compareAndSet(this, currentAttributes, newAttributes)) {
                // CAS 成功，属性已成功移除。
                if (logger.isDebugEnabled()) {
                    logger.debug("Attribute for key: {} successfully removed.", key);
                }
                return; // 结束方法
            }

            // CAS 失败：意味着在执行此循环的期间，有其他线程修改了 this.attributes。
            // 记录一个 debug 日志，然后循环将重试整个移除过程。
            if (logger.isDebugEnabled()) {
                logger.debug("CAS failed during removal of attribute for key: {}. Retrying...", key);
            }
        }
    }

    /**
     * {@link Attribute} 接口的默认具体实现。
     * 此类继承自 {@link AtomicReference}，因此其持有的属性值本身是原子更新的，保证了设值和获取值的线程安全。
     * 同时，它也维护了一个指向其所属 {@link DefaultAttributeMap} 的引用，用于在移除时通知外部 Map。
     *
     * @param <T> 属性值的类型
     */
    @SuppressWarnings("serial") // Suppress warnings for serializable class not declaring serialVersionUID if
                                // not needed
    private static final class DefaultAttribute<T> extends AtomicReference<T> implements Attribute<T> {

        // 用于原子更新 DefaultAttribute 内部的 attributeMap 字段。
        // 当属性被移除时，这个字段会被设置为 null。
        private static final AtomicReferenceFieldUpdater<DefaultAttribute, DefaultAttributeMap> MAP_UPDATER = AtomicReferenceFieldUpdater
                .newUpdater(
                        DefaultAttribute.class, // 操作的类
                        DefaultAttributeMap.class, // 字段的类型
                        "attributeMap" // 字段的名称
                );
        // 序列化版本UID，对于不需要精细控制序列化兼容性的情况，可以使用默认生成的或固定的值。
        private static final long serialVersionUID = -2661411462200283011L;

        // volatile 关键字确保 attributeMap 字段在多线程环境下的可见性。
        // 当一个线程将此属性标记为移除 (将 attributeMap 设置为 null) 时，其他线程能立即看到这一变化。
        // 如果此属性已被移除 (即从 DefaultAttributeMap 的内部数组中移除)，则此字段为 null。
        private volatile DefaultAttributeMap attributeMap;
        // 此属性的键，一旦设置后不可变，final 保证其在构造后不会被修改。
        private final AttributeKey<T> key;

        /**
         * 构造一个新的 {@link DefaultAttribute} 实例。
         *
         * @param attributeMap 拥有此属性的 {@link DefaultAttributeMap} 实例。不能为 null。
         * @param key          此属性的 {@link AttributeKey}。不能为 null。
         */
        DefaultAttribute(DefaultAttributeMap attributeMap, AttributeKey<T> key) {
            // ObjectUtil.checkNotNull(attributeMap, "attributeMap"); //
            // 初始创建时不应为null，但移除后会变null
            // ObjectUtil.checkNotNull(key, "key");
            this.attributeMap = attributeMap;
            this.key = key;
        }

        @Override
        public AttributeKey<T> key() {
            return key;
        }

        /**
         * 检查此 {@link DefaultAttribute} 实例是否已被逻辑上标记为"已移除"。
         * 当属性的 {@code attributeMap} 引用为 {@code null} 时，表示它已被移除。
         *
         * @return 如果此属性已被标记为移除 (即其 {@code attributeMap} 字段为 {@code null})，则返回
         *         {@code true}；否则返回 {@code false}。
         */
        private boolean isRemoved() {
            return attributeMap == null;
        }

        @Override
        public T setIfAbsent(T value) {
            // 尝试原子地将当前值从 null 设置为指定的 value。
            // 这是一个循环，因为 compareAndSet 可能因为其他线程的并发修改而失败。
            while (!compareAndSet(null, value)) {
                // 如果 compareAndSet(null, value) 失败，意味着当前值不是 null，或者在比较和设置之间被其他线程修改了。
                T oldValue = get(); // 获取当前值 (可能是 null，也可能是其他线程设置的值)
                if (oldValue != null) {
                    // 如果当前值不是 null，说明其他线程已经成功设置了一个值。
                    // 根据 setIfAbsent 的语义，此时应返回已存在的值，并且不进行修改。
                    return oldValue;
                }
                // 如果 oldValue 仍为 null，但 CAS 失败，这通常意味着有其他线程也在尝试设置此值。
                // 循环将继续，直到 CAS 成功或 oldValue 变为非 null。
                // 理论上，如果 oldValue 为 null 但 CAS 失败，get() 紧接着应该能读到 null，
                // 除非在 get() 和下一次 compareAndSet() 之间又被其他线程修改了。
                // 这种竞争情况通过循环来解决。
            }
            // CAS(null, value) 成功，意味着之前的值确实是 null，并且现在已经被设置为 value。
            // 根据 setIfAbsent 的语义，此时应返回 null，表示值是新设置的。
            return null;
        }

        @Override
        public T getAndRemove() {
            final DefaultAttributeMap map = this.attributeMap; // 读取 volatile 字段
            boolean removedSuccessfully = false;
            if (map != null) {
                // 尝试原子地将 attributeMap 字段设置为 null，从而将此 Attribute 实例标记为"已移除"。
                // 只有当 this.attributeMap 当前值确实是 map (即未被其他线程修改) 时，CAS才会成功。
                removedSuccessfully = MAP_UPDATER.compareAndSet(this, map, null);
            }

            // 原子地将当前属性值设置为 null，并获取设置前的旧值。
            // 这是通过继承 AtomicReference 的 getAndSet(null) 实现的。
            T oldValue = getAndSet(null);

            if (removedSuccessfully) {
                // 如果成功将此 Attribute 实例标记为"已移除" (即 MAP_UPDATER.compareAndSet 成功)，
                // 那么还需要通知其原属的 DefaultAttributeMap，从其内部的属性数组中实际移除此 Attribute 实例。
                // 注意：这里传递的是 this (即当前的 DefaultAttribute 实例)，
                // removeAttributeIfMatch 会检查 map 中的实例是否与此实例相同，以避免移除错误的实例。
                map.removeAttributeIfMatch(key, this);
            }
            return oldValue; // 返回移除前的值
        }

        @Override
        public void remove() {
            final DefaultAttributeMap map = this.attributeMap; // 读取 volatile 字段
            boolean removedSuccessfully = false;
            if (map != null) {
                // 尝试原子地将 attributeMap 字段设置为 null，标记为"已移除"。
                removedSuccessfully = MAP_UPDATER.compareAndSet(this, map, null);
            }

            // 将属性值设置为 null。注意：这与 getAndRemove() 的区别在于，它不返回旧值。
            set(null);

            if (removedSuccessfully) {
                // 如果成功标记为"已移除"，则通知 DefaultAttributeMap 从其数组中移除此实例。
                map.removeAttributeIfMatch(key, this);
            }
        }
    }
}
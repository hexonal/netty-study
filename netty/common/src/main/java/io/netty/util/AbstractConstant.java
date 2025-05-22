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

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link Constant} 接口的基础抽象实现。
 * 此类提供了 {@link Constant} 的核心功能，包括 ID、名称的存储以及比较逻辑。
 * 它旨在被具体的常量类（如 {@link AttributeKey}）继承。
 *
 * @param <T> 常量自身的类型，继承自 {@link AbstractConstant}。
 */
public abstract class AbstractConstant<T extends AbstractConstant<T>> implements Constant<T> {

    // 用于生成全局唯一的 uniquifier 值，确保即使 hashCode 冲突，不同实例的比较结果也稳定。
    private static final AtomicLong uniqueIdGenerator = new AtomicLong();

    // 常量的唯一数字标识符，通常由 ConstantPool 分配。
    private final int id;
    // 常量的名称，通常在创建时指定。
    private final String name;
    // 一个全局唯一的 long 值，用于在 hashCode 冲突时提供最终的比较依据，确保比较的稳定性。
    // 每个 AbstractConstant 实例在创建时都会获得一个唯一的 uniquifier。
    private final long uniquifier;

    /**
     * 创建一个新的 {@link AbstractConstant} 实例。
     *
     * @param id   此常量的唯一数字 ID。通常由其所属的 {@link ConstantPool} 提供。
     * @param name 此常量的名称。实现者应确保传入的 name 非 null。
     */
    protected AbstractConstant(int id, String name) {
        this.id = id;
        this.name = name;
        // 为此常量实例分配一个全局唯一的 long 值，用于比较时的最终区分。
        // 即使两个不同名称的常量碰巧具有相同的 id (理论上不应发生于同一个 ConstantPool)，
        // 或者更常见的是，如果它们的 hashCode() 发生冲突，uniquifier 也能保证比较结果的一致性。
        this.uniquifier = uniqueIdGenerator.getAndIncrement();
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final int id() {
        return id;
    }

    /**
     * 返回此常量的字符串表示形式，默认为其名称。
     *
     * @return 此常量的名称。
     */
    @Override
    public final String toString() {
        return name();
    }

    /**
     * 返回此对象的哈希码。
     * <p>
     * <strong>注意:</strong> {@link AbstractConstant} 的子类通常由 {@link ConstantPool} 管理，
     * 这确保了对于给定的名称，常量实例是单例的。因此，可以直接使用 {@code ==} 进行比较，
     * 而不是依赖于 {@code equals()} 或 {@code hashCode()}。
     * 此处返回 {@code super.hashCode()} 是因为对于单例对象，其对象标识哈希码是稳定且唯一的。
     * 如果子类需要自定义哈希码（不推荐，因为破坏了 {@code ==} 比较的初衷），则应同时重写 {@code equals()}。
     *
     * @return 此对象的哈希码值。
     */
    @Override
    public final int hashCode() {
        return super.hashCode();
    }

    /**
     * 指示某个其他对象是否“等于”此对象。
     * <p>
     * <strong>注意:</strong> 由于 {@link AbstractConstant} 的实例通常是单例（由
     * {@link ConstantPool} 管理），
     * 强烈建议使用 {@code ==} 操作符进行比较，因为它更快且能正确反映实例的唯一性。
     * 此处保留 {@code super.equals(obj)} (即 {@code Object.equals(Object)})
     * 是因为如果两个引用指向同一个对象，
     * 它们自然是相等的。
     * 不建议子类重写此方法来提供基于值的相等性，因为这会与 {@link ConstantPool} 的单例保证和 {@code ==}
     * 比较的预期行为产生混淆。
     *
     * @param obj 要与之比较的引用对象。
     * @return 如果此对象与 obj 参数相同，则为 {@code true}；否则为 {@code false}。
     */
    @Override
    public final boolean equals(Object obj) {
        // 对于由 ConstantPool 管理的单例常量，直接使用引用比较 (==) 是最高效且正确的。
        // Object.equals() 的默认行为就是引用比较 (this == obj)。
        return super.equals(obj);
    }

    /**
     * 将此常量与另一个常量进行比较以确定顺序。
     * 比较顺序首先基于 {@link #hashCode()} 的差异，然后基于 {@link #uniquifier} 的差异。
     * 这种比较机制确保了即使不同实例的 {@code hashCode()} 偶然相同，
     * 它们之间仍然有一个确定且稳定的排序关系，这对于将常量存储在有序集合中非常重要。
     *
     * @param o 要比较的另一个 {@link AbstractConstant} 对象。
     * @return 一个负整数、零或一个正整数，表示此对象小于、等于或大于指定的对象。
     * @throws Error 如果比较逻辑未能区分两个不同的常量实例（理论上不应发生，因为 uniquifier 应保证唯一性）。
     */
    @Override
    public final int compareTo(T o) {
        // 首先进行引用比较，如果指向同一个实例，则它们相等。
        if (this == o) {
            return 0;
        }

        // 类型转换，因为我们知道 T 继承自 AbstractConstant<T>。
        @SuppressWarnings("UnnecessaryLocalVariable")
        AbstractConstant<T> other = o;
        int returnCode;

        // 第一比较级别：比较哈希码。
        // 注意：这里使用的是 Object.hashCode()，对于单例，这是其实例的标识哈希码。
        returnCode = hashCode() - other.hashCode();
        if (returnCode != 0) {
            return returnCode;
        }

        // 第二比较级别：如果哈希码相同（极小概率发生于不同实例），则比较 uniquifier。
        // uniquifier 是一个在 AbstractConstant 实例化时分配的全局唯一 long 值，
        // 确保了即使哈希码冲突，不同实例之间也能有确定的排序。
        if (uniquifier < other.uniquifier) {
            return -1;
        }
        if (uniquifier > other.uniquifier) {
            return 1;
        }

        // 理论上不应该执行到这里，因为 uniquifier 保证了唯一性。
        // 如果执行到这里，说明两个不同的 AbstractConstant 实例具有相同的 hashCode 和相同的 uniquifier，
        // 这违反了 uniquifier 的设计初衷，可能表示 AtomicLong 的行为异常或存在并发问题（尽管 AtomicLong 是线程安全的）。
        // 抛出 Error 表示这是一个严重的内部状态错误。
        throw new Error("failed to compare two different constants");
    }
}

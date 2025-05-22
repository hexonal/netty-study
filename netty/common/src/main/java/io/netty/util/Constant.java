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
 * 一个通过 {@link ConstantPool} 创建和管理的单例对象，可以安全地使用 {@code ==} 操作符进行比较。
 * <p>
 * {@link Constant} 的实现类（例如 {@link AttributeKey}）通常利用 {@link ConstantPool} 来确保
 * 对于给定的名称，只存在一个 {@link Constant} 实例。这使得可以使用引用相等性 ({@code ==}) 来高效地比较这些常量，
 * 而不是依赖于 {@code equals()} 方法，这在性能敏感的代码中尤其重要。
 * <p>
 * 实现此接口的类还必须实现 {@link Comparable} 接口，以便能够在需要排序的集合中使用。
 *
 * @param <T> 常量自身的类型，用于类型安全和比较。
 */
public interface Constant<T extends Constant<T>> extends Comparable<T> {

    /**
     * 返回分配给此 {@link Constant} 的唯一数字标识符。
     * 这个 ID 通常由 {@link ConstantPool} 在创建常量时分配，并在该池的上下文中是唯一的。
     *
     * @return 此常量的唯一 ID。
     */
    int id();

    /**
     * 返回此 {@link Constant} 的名称。
     * 这个名称通常是在通过 {@link ConstantPool} 创建常量时指定的，并且在该池的上下文中是唯一的。
     *
     * @return 此常量的名称。
     */
    String name();
}

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
 * 用于从 {@link AttributeMap} 中访问 {@link Attribute} 的键。
 * 请注意，不允许存在多个具有相同名称的键。
 *
 * @param <T> 可通过此 {@link AttributeKey} 访问的 {@link Attribute} 的类型。
 */
@SuppressWarnings("UnusedDeclaration") // 'T' 仅在编译时使用
public final class AttributeKey<T> extends AbstractConstant<AttributeKey<T>> {

    // 用于存储和管理 AttributeKey 实例的常量池。
    // 这确保了具有相同名称的 AttributeKey 实例是单例的。
    private static final ConstantPool<AttributeKey<Object>> pool = new ConstantPool<AttributeKey<Object>>() {
        @Override
        protected AttributeKey<Object> newConstant(int id, String name) {
            return new AttributeKey<Object>(id, name);
        }
    };

    /**
     * 返回具有指定 {@code name} 的 {@link AttributeKey} 的单例实例。
     * 如果池中已存在具有该名称的键，则返回现有实例。
     * 否则，创建一个新的实例并将其添加到池中。
     *
     * @param name 键的名称
     * @param <T>  属性值的类型
     * @return 对应名称的 {@link AttributeKey} 实例
     */
    @SuppressWarnings("unchecked")
    public static <T> AttributeKey<T> valueOf(String name) {
        return (AttributeKey<T>) pool.valueOf(name);
    }

    /**
     * 检查是否存在具有给定 {@code name} 的 {@link AttributeKey}。
     *
     * @param name 要检查的键的名称
     * @return 如果存在则返回 {@code true}，否则返回 {@code false}
     */
    public static boolean exists(String name) {
        return pool.exists(name);
    }

    /**
     * 为给定的 {@code name} 创建一个新的 {@link AttributeKey}。
     * 如果已存在具有给定 {@code name} 的 {@link AttributeKey}，则会抛出
     * {@link IllegalArgumentException}。
     * 此方法用于明确需要创建新实例且不希望复用池中现有实例的场景。
     *
     * @param name 新键的名称
     * @param <T>  属性值的类型
     * @return 新创建的 {@link AttributeKey} 实例
     * @throws IllegalArgumentException 如果具有相同名称的键已存在
     */
    @SuppressWarnings("unchecked")
    public static <T> AttributeKey<T> newInstance(String name) {
        return (AttributeKey<T>) pool.newInstance(name);
    }

    /**
     * 返回由 {@code firstNameComponent} 的名称和 {@code secondNameComponent} 组成的复合名称对应的
     * {@link AttributeKey} 单例实例。
     * 最终名称的格式为 "firstNameComponent#secondNameComponent"。
     *
     * @param firstNameComponent  名称的第一个组成部分，通常是一个类
     * @param secondNameComponent 名称的第二个组成部分
     * @param <T>                 属性值的类型
     * @return 对应复合名称的 {@link AttributeKey} 实例
     */
    @SuppressWarnings("unchecked")
    public static <T> AttributeKey<T> valueOf(Class<?> firstNameComponent, String secondNameComponent) {
        return (AttributeKey<T>) pool.valueOf(firstNameComponent, secondNameComponent);
    }

    /**
     * 私有构造函数，用于通过常量池创建实例。
     * 
     * @param id   常量的唯一ID
     * @param name 常量的名称
     */
    private AttributeKey(int id, String name) {
        super(id, name);
    }
}

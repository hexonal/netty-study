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
package io.netty.util.concurrent;

/**
 * 用于创建新的 {@link EventExecutorChooser} 的工厂接口。
 * {@link EventExecutorChooser} 负责从一组 {@link EventExecutor} 中选择一个来执行任务。
 * 这个工厂模式允许用户根据需要实现自定义的选择策略。
 */
public interface EventExecutorChooserFactory {

    /**
     * 根据给定的 {@link EventExecutor} 数组，返回一个新的 {@link EventExecutorChooser} 实例。
     *
     * @param executors 一组 {@link EventExecutor}，选择器将从中进行选择。
     * @return 一个新的 {@link EventExecutorChooser} 实例。
     */
    EventExecutorChooser newChooser(EventExecutor[] executors);

    /**
     * 负责选择下一个要使用的 {@link EventExecutor} 的接口。
     * 实现此接口的类定义了具体的选择逻辑，例如轮询、随机或基于负载的选择等。
     */
    interface EventExecutorChooser {

        /**
         * 返回下一个要使用的 {@link EventExecutor}。
         * 每次调用此方法都可能返回不同的 {@link EventExecutor}，具体取决于实现的选择策略。
         *
         * @return 下一个 {@link EventExecutor}。
         */
        EventExecutor next();
    }
}

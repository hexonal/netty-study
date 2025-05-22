/*
 * Copyright 2024 The Netty Project
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

import io.netty.util.concurrent.ThreadAwareExecutor;

/**
 * {@link IoHandler} 实例的工厂接口。
 * 用于创建处理 I/O 事件的 {@link IoHandler}。
 */
public interface IoHandlerFactory {

    /**
     * 创建一个新的 {@link IoHandler} 实例。
     *
     * @param ioExecutor        用于 {@link IoHandler} 的 {@link ThreadAwareExecutor}。
     *                          此执行器将负责执行 {@link IoHandler} 中的任务。
     * @return                  一个新的 {@link IoHandler} 实例。
     */
    IoHandler newHandler(ThreadAwareExecutor ioExecutor);
}

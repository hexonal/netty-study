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
package io.netty.channel;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

/**
 * 特殊的 {@link ChannelFuture}，具备可写能力。
 * <p>
 * ChannelPromise 代表一个可写的 ChannelFuture，允许用户主动设置异步操作的结果（成功或失败）。
 * 主要用于 Netty 异步 IO 操作的结果通知与回调机制，常见于 pipeline、handler 处理链中。
 * </p>
 *
 * <h3>设计意图</h3>
 * <ul>
 *   <li>允许用户在异步操作完成后主动通知结果（setSuccess/setFailure）。</li>
 *   <li>支持链式回调（addListener），便于事件驱动模型下的异步处理。</li>
 *   <li>继承 {@link Promise}，具备通用的 Future/Promise 能力。</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>
 * {@code
 * ChannelPromise promise = channel.newPromise();
 * promise.addListener(future -> {
 *     if (future.isSuccess()) {
 *         // 处理成功逻辑
 *     } else {
 *         // 处理失败逻辑
 *     }
 * });
 * channel.writeAndFlush(msg, promise);
 * }
 * </pre>
 *
 * <h3>线程安全性</h3>
 * <ul>
 *   <li>ChannelPromise 的实现需保证线程安全，支持多线程并发设置和监听。</li>
 * </ul>
 *
 * @see ChannelFuture
 * @see Promise
 * @see io.netty.channel.Channel#newPromise()
 */
public interface ChannelPromise extends ChannelFuture, Promise<Void> {

    /**
     * 返回与该 Promise 关联的 Channel。
     * <p>
     * 该方法用于获取当前 Promise 绑定的 Channel 实例，便于后续链式操作或上下文获取。
     * </p>
     *
     * @return 关联的 Channel
     */
    @Override
    Channel channel();

    /**
     * 设置异步操作成功，并通知所有监听器。
     * <p>
     * 如果操作已完成（无论成功或失败），再次调用无效。
     * </p>
     *
     * @param result 操作结果（通常为 null）
     * @return 当前 ChannelPromise 实例，便于链式调用
     */
    @Override
    ChannelPromise setSuccess(Void result);

    /**
     * 设置异步操作成功，并通知所有监听器。
     * <p>
     * 该方法为无参快捷方式，等价于 setSuccess(null)。
     * </p>
     *
     * @return 当前 ChannelPromise 实例，便于链式调用
     */
    ChannelPromise setSuccess();

    /**
     * 尝试设置异步操作成功。
     * <p>
     * 如果操作尚未完成，则设置为成功并返回 true；否则返回 false。
     * </p>
     *
     * @return 是否成功设置为成功状态
     */
    boolean trySuccess();

    /**
     * 设置异步操作失败，并通知所有监听器。
     * <p>
     * 失败原因通过参数传递，便于后续异常处理和日志记录。
     * </p>
     *
     * @param cause 失败原因（异常）
     * @return 当前 ChannelPromise 实例，便于链式调用
     */
    @Override
    ChannelPromise setFailure(Throwable cause);

    /**
     * 添加一个监听器，在异步操作完成（成功或失败）时回调。
     * <p>
     * 典型场景：用于处理操作完成后的业务逻辑，如资源释放、后续操作等。
     * </p>
     *
     * @param listener 监听器实例
     * @return 当前 ChannelPromise 实例，便于链式调用
     */
    @Override
    ChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener);

    /**
     * 批量添加监听器。
     * <p>
     * 适用于需要同时注册多个回调的场景。
     * </p>
     *
     * @param listeners 监听器数组
     * @return 当前 ChannelPromise 实例，便于链式调用
     */
    @Override
    ChannelPromise addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners);

    /**
     * 移除指定监听器，后续操作完成时将不再回调该监听器。
     * <p>
     * 适用于动态管理监听器的场景。
     * </p>
     *
     * @param listener 需移除的监听器
     * @return 当前 ChannelPromise 实例，便于链式调用
     */
    @Override
    ChannelPromise removeListener(GenericFutureListener<? extends Future<? super Void>> listener);

    /**
     * 批量移除监听器。
     * <p>
     * 适用于需要同时移除多个监听器的场景。
     * </p>
     *
     * @param listeners 需移除的监听器数组
     * @return 当前 ChannelPromise 实例，便于链式调用
     */
    @Override
    ChannelPromise removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners);

    /**
     * 阻塞当前线程，直到异步操作完成。
     * <p>
     * 如果操作失败，将抛出异常。
     * </p>
     *
     * @return 当前 ChannelPromise 实例，便于链式调用
     * @throws InterruptedException 如果线程被中断
     */
    @Override
    ChannelPromise sync() throws InterruptedException;

    /**
     * 阻塞当前线程，直到异步操作完成（不可中断）。
     * <p>
     * 如果操作失败，将抛出异常。
     * </p>
     *
     * @return 当前 ChannelPromise 实例，便于链式调用
     */
    @Override
    ChannelPromise syncUninterruptibly();

    /**
     * 阻塞当前线程，直到异步操作完成。
     * <p>
     * 不会抛出异常，仅用于等待操作完成。
     * </p>
     *
     * @return 当前 ChannelPromise 实例，便于链式调用
     * @throws InterruptedException 如果线程被中断
     */
    @Override
    ChannelPromise await() throws InterruptedException;

    /**
     * 阻塞当前线程，直到异步操作完成（不可中断）。
     * <p>
     * 不会抛出异常，仅用于等待操作完成。
     * </p>
     *
     * @return 当前 ChannelPromise 实例，便于链式调用
     */
    @Override
    ChannelPromise awaitUninterruptibly();

    /**
     * 如果当前 Promise 是 void 类型，则返回一个新的 ChannelPromise，否则返回自身。
     * <p>
     * 适用于需要保证 Promise 可用性的场景。
     * </p>
     *
     * @return 新的或当前的 ChannelPromise 实例
     */
    ChannelPromise unvoid();
}

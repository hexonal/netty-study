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

import io.netty.channel.ChannelFlushPromiseNotifier.FlushCheckpoint;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * {@link DefaultChannelPromise} 是 Netty 中最常用的 {@link ChannelPromise} 实现，
 * 结合了 Channel 语义与异步通知能力，广泛用于 IO 操作的结果回调与链式处理。
 * <p>
 * <b>设计理念：</b>
 * <ul>
 *   <li>结合 Channel 与 Promise，既能感知 Channel 生命周期，又能异步通知操作结果。</li>
 *   <li>支持链式调用、监听器注册、同步/异步等待等多种用法。</li>
 *   <li>与 Channel 绑定，自动感知 EventLoop，避免死锁。</li>
 * </ul>
 *
 * <b>泛型说明：</b>
 * <ul>
 *   <li>继承自 {@link DefaultPromise}&lt;Void&gt;，即本 Promise 只关心操作是否成功/失败，不关心返回值。</li>
 *   <li>所有 setSuccess/setFailure 只传递 Void 类型。</li>
 * </ul>
 *
 * <b>典型用法：</b>
 * <pre>
 *   ChannelPromise promise = channel.newPromise();
 *   promise.addListener(future -> { ... });
 *   // 或用于写操作
 *   channel.writeAndFlush(msg, promise);
 * </pre>
 *
 * <b>线程安全说明：</b>
 * - 所有回调、状态变更均通过 EventLoop 串行化，线程安全。
 * - 支持多线程安全监听、等待。
 *
 * <b>与 Channel/Promise 的关系：</b>
 * - 该类实现了 ChannelPromise，既是 Promise（异步通知），又与 Channel 强绑定（可感知 Channel 状态）。
 * - 推荐通过 Channel#newPromise() 创建，避免直接 new。
 */
public class DefaultChannelPromise extends DefaultPromise<Void> implements ChannelPromise, FlushCheckpoint {

    /**
     * 关联的 Channel 实例，生命周期与 Promise 绑定。
     */
    private final Channel channel;
    /**
     * 用于批量 flush 时的检查点，内部机制。
     */
    private long checkpoint;

    /**
     * 构造方法，推荐通过 Channel#newPromise() 创建。
     * @param channel 关联的 Channel，不能为空
     */
    public DefaultChannelPromise(Channel channel) {
        this.channel = checkNotNull(channel, "channel");
    }

    /**
     * 构造方法，指定执行器。
     * @param channel 关联的 Channel，不能为空
     * @param executor 指定的 EventExecutor
     */
    public DefaultChannelPromise(Channel channel, EventExecutor executor) {
        super(executor);
        this.channel = checkNotNull(channel, "channel");
    }

    /**
     * 获取用于回调的执行器。
     * 优先使用父类 executor，否则自动获取 Channel 的 EventLoop。
     * @return EventExecutor
     */
    @Override
    protected EventExecutor executor() {
        EventExecutor e = super.executor();
        if (e == null) {
            return channel().eventLoop();
        } else {
            return e;
        }
    }

    /**
     * 获取关联的 Channel。
     * @return Channel
     */
    @Override
    public Channel channel() {
        return channel;
    }

    /**
     * 设置成功（无返回值）。
     * @return 当前 Promise
     */
    @Override
    public ChannelPromise setSuccess() {
        return setSuccess(null);
    }

    /**
     * 设置成功（无返回值）。
     * @param result 必须为 null
     * @return 当前 Promise
     */
    @Override
    public ChannelPromise setSuccess(Void result) {
        // 设置 Promise 状态为成功，通知所有监听器。
        // 典型场景：IO 操作完成后回调。
        super.setSuccess(result);
        return this;
    }

    /**
     * 尝试设置成功。
     * @return 是否设置成功
     */
    @Override
    public boolean trySuccess() {
        // 尝试设置 Promise 状态为成功，若已完成则返回 false。
        // 典型场景：并发场景下只允许第一个成功生效。
        return trySuccess(null);
    }

    /**
     * 设置失败。
     * @param cause 失败原因
     * @return 当前 Promise
     */
    @Override
    public ChannelPromise setFailure(Throwable cause) {
        // 设置 Promise 状态为失败，记录异常并通知所有监听器。
        // 典型场景：IO 操作异常、连接失败等。
        super.setFailure(cause);
        return this;
    }

    /**
     * 添加监听器。
     * @param listener 监听器
     * @return 当前 Promise
     */
    @Override
    public ChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        // 添加异步监听器，操作完成后自动回调。
        // 典型场景：注册回调处理IO结果。
        super.addListener(listener);
        return this;
    }

    /**
     * 批量添加监听器。
     * @param listeners 监听器数组
     * @return 当前 Promise
     */
    @Override
    public ChannelPromise addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        super.addListeners(listeners);
        return this;
    }

    /**
     * 移除监听器。
     * @param listener 监听器
     * @return 当前 Promise
     */
    @Override
    public ChannelPromise removeListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        super.removeListener(listener);
        return this;
    }

    /**
     * 批量移除监听器。
     * @param listeners 监听器数组
     * @return 当前 Promise
     */
    @Override
    public ChannelPromise removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        super.removeListeners(listeners);
        return this;
    }

    /**
     * 同步等待完成。
     * @return 当前 Promise
     * @throws InterruptedException 中断异常
     */
    @Override
    public ChannelPromise sync() throws InterruptedException {
        // 阻塞当前线程直到操作完成，若失败则抛出异常。
        // 典型场景：测试或特殊同步需求，生产环境建议用异步监听。
        super.sync();
        return this;
    }

    /**
     * 同步等待完成（不可中断）。
     * @return 当前 Promise
     */
    @Override
    public ChannelPromise syncUninterruptibly() {
        super.syncUninterruptibly();
        return this;
    }

    /**
     * 异步等待完成。
     * @return 当前 Promise
     * @throws InterruptedException 中断异常
     */
    @Override
    public ChannelPromise await() throws InterruptedException {
        // 阻塞当前线程直到操作完成，不抛出异常。
        // 典型场景：需要等待结果但不关心异常。
        super.await();
        return this;
    }

    /**
     * 异步等待完成（不可中断）。
     * @return 当前 Promise
     */
    @Override
    public ChannelPromise awaitUninterruptibly() {
        super.awaitUninterruptibly();
        return this;
    }

    /**
     * 获取 flush 检查点（内部机制）。
     * @return 检查点
     */
    @Override
    public long flushCheckpoint() {
        // 获取当前 flush 检查点，供批量 flush 机制使用。
        return checkpoint;
    }

    /**
     * 设置 flush 检查点（内部机制）。
     * @param checkpoint 检查点
     */
    @Override
    public void flushCheckpoint(long checkpoint) {
        // 设置 flush 检查点，供批量 flush 机制使用。
        this.checkpoint = checkpoint;
    }

    /**
     * 返回自身，便于链式调用。
     * @return 当前 Promise
     */
    @Override
    public ChannelPromise promise() {
        return this;
    }

    /**
     * 死锁检测，仅在 Channel 已注册时才检测。
     */
    @Override
    protected void checkDeadLock() {
        // 死锁检测：仅在 Channel 已注册到 EventLoop 时才检测，防止在 EventLoop 线程内阻塞等待自身完成，导致死锁。
        // 典型易错点：在 handler 或回调线程内调用 sync/await。
        if (channel().isRegistered()) {
            super.checkDeadLock();
        }
    }

    /**
     * 返回自身，便于链式调用。
     * @return 当前 Promise
     */
    @Override
    public ChannelPromise unvoid() {
        return this;
    }

    /**
     * 标识该 Promise 永远不会是 void。
     * @return false
     */
    @Override
    public boolean isVoid() {
        return false;
    }
}

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
package io.netty.util.concurrent;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link EventExecutorGroup} 负责通过其 {@link #next()} 方法提供要使用的 {@link EventExecutor}。
 * 除此之外，它还负责处理它们的生命周期，并允许以全局方式关闭它们。
 *
 * <p><b>核心职责：</b></p>
 * <ul>
 *     <li><b>{@link EventExecutor} 的提供者：</b> 作为一组 {@link EventExecutor} 的管理者，它通过 {@link #next()} 方法轮询或
 *         通过其他策略选择一个 {@link EventExecutor} 来执行任务。</li>
 *     <li><b>生命周期管理：</b> 提供了启动、平滑关闭 (graceful shutdown) 和立即关闭 (shutdownNow) 其管理的所有
 *         {@link EventExecutor} 的机制。</li>
 *     <li><b>任务提交与调度：</b> 继承自 {@link ScheduledExecutorService}，因此支持提交立即执行的任务 ({@code submit}, {@code execute})
 *         以及定时执行的任务 ({@code schedule}, {@code scheduleAtFixedRate}, {@code scheduleWithFixedDelay})。
 *         所有提交的任务最终都会被分发到其管理的某个 {@link EventExecutor} 上执行。</li>
 *     <li><b>迭代能力：</b> 实现 {@link Iterable<EventExecutor>} 接口，允许迭代访问组内所有的 {@link EventExecutor}。</li>
 * </ul>
 *
 * <p><b>与 {@link java.util.concurrent.ExecutorService} 的关系：</b></p>
 * {@link EventExecutorGroup} 扩展了 {@link ScheduledExecutorService}，后者又扩展了 {@link java.util.concurrent.ExecutorService}。
 * 这意味着它具备了标准 Java 执行器服务的所有功能，并增加了调度能力和 Netty 特有的 {@link Future} 类型和生命周期管理。
 *
 * <p><b>平滑关闭 (Graceful Shutdown)：</b></p>
 * Netty 推荐使用 {@link #shutdownGracefully()} 系列方法来关闭 {@link EventExecutorGroup}。
 * 平滑关闭会首先进入一个"静默期" (quiet period)，在此期间不再接受新的任务，但会尝试完成已提交和正在执行的任务。
 * 静默期结束后，如果还有任务未完成，会等待一个指定的超时时间，之后强制关闭。
 * 这确保了资源的有序释放和任务的尽可能完成。
 *
 * @see EventExecutor
 * @see ScheduledExecutorService
 * @see Future
 * @see Promise
 */
public interface EventExecutorGroup extends ScheduledExecutorService, Iterable<EventExecutor> {

    /**
     * 当且仅当此 {@link EventExecutorGroup} 管理的所有 {@link EventExecutor} 都在
     * {@linkplain #shutdownGracefully() 平滑关闭}过程中或已经{@linkplain #isShutdown() 关闭}时，返回 {@code true}。
     *
     * @return 如果正在关闭或已关闭，则为 {@code true}；否则为 {@code false}。
     */
    boolean isShuttingDown();

    /**
     * {@link #shutdownGracefully(long, long, TimeUnit)} 方法的快捷方式，使用合理的默认值。
     * 通常，静默期为2秒，超时时间为15秒。
     *
     * @return {@link #terminationFuture()}，用于获知关闭操作何时完成。
     */
    Future<?> shutdownGracefully();

    /**
     * 通知此执行器，调用者希望执行器被关闭。一旦调用此方法，{@link #isShuttingDown()} 开始返回 {@code true}，
     * 并且执行器准备自行关闭。
     * 与 {@link #shutdown()} 不同，平滑关闭确保在关闭自身之前的"静默期"（通常为几秒钟）内不提交任何任务。
     * 如果在静默期内提交了任务，则保证该任务被接受，并且静默期将重新开始。
     *
     * @param quietPeriod 静默期，如文档中所述。在此期间，执行器仍然接受新任务，但每次接受新任务都会重置静默期的计时。
     * @param timeout     最大等待时间，直到执行器被{@linkplain #shutdown() 关闭}，无论在静默期内是否提交了任务。
     * @param unit        {@code quietPeriod} 和 {@code timeout} 的时间单位。
     *
     * @return {@link #terminationFuture()}，用于获知关闭操作何时完成。
     */
    Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit);

    /**
     * 返回一个 {@link Future}，当此 {@link EventExecutorGroup} 管理的所有 {@link EventExecutor} 都已终止时，
     * 该 Future 会收到通知。
     *
     * @return 表示终止完成的 {@link Future}。
     */
    Future<?> terminationFuture();

    /**
     * @deprecated 请改用 {@link #shutdownGracefully(long, long, TimeUnit)} 或 {@link #shutdownGracefully()}。
     *             此方法尝试立即关闭所有正在执行的任务，并返回等待执行的任务列表，可能导致任务丢失或状态不一致。
     */
    @Override
    @Deprecated
    void shutdown();

    /**
     * @deprecated 请改用 {@link #shutdownGracefully(long, long, TimeUnit)} 或 {@link #shutdownGracefully()}。
     *             此方法尝试立即停止所有活动执行的任务，暂停等待任务的处理，并返回等待执行的任务列表。
     *             这种关闭方式非常激进，可能导致正在处理的数据丢失或状态不一致。
     */
    @Override
    @Deprecated
    List<Runnable> shutdownNow();

    /**
     * 返回此 {@link EventExecutorGroup} 管理的 {@link EventExecutor} 之一。
     * 通常通过轮询 (round-robin) 或其他负载均衡策略选择。
     * 返回的 {@link EventExecutor} 将用于执行提交给该 Group 的任务。
     *
     * @return {@link EventExecutor} 实例。
     */
    EventExecutor next();

    /**
     * 返回一个迭代器，用于遍历此 {@link EventExecutorGroup} 管理的所有 {@link EventExecutor}。
     *
     * @return {@link EventExecutor} 的迭代器。
     */
    @Override
    Iterator<EventExecutor> iterator();

    /**
     * 提交一个 {@link Runnable} 任务以供执行，并返回一个表示该任务的 Future。
     * 该任务将由 {@link #next()} 方法选择的 {@link EventExecutor} 执行。
     *
     * @param task 要提交的任务。
     * @return 代表任务挂起完成的 {@link Future}。
     */
    @Override
    Future<?> submit(Runnable task);

    /**
     * 提交一个 {@link Runnable} 任务以供执行，并返回一个表示该任务的 Future。
     * 该任务将由 {@link #next()} 方法选择的 {@link EventExecutor} 执行。
     *
     * @param task   要提交的任务。
     * @param result 当任务成功完成时，Future 的 {@code get()} 方法将返回此结果。
     * @param <T>    结果的类型。
     * @return 代表任务挂起完成的 {@link Future}。
     */
    @Override
    <T> Future<T> submit(Runnable task, T result);

    /**
     * 提交一个带返回值的任务以供执行，并返回一个表示该任务的挂起结果的 Future。
     * 该任务将由 {@link #next()} 方法选择的 {@link EventExecutor} 执行。
     *
     * @param task 要提交的任务。
     * @param <T>  任务结果的类型。
     * @return 代表任务挂起完成的 {@link Future}。
     */
    @Override
    <T> Future<T> submit(Callable<T> task);

    /**
     * 此执行器的计时器 (ticker)。通常，{@link #schedule} 方法将遵循
     * {@link Ticker#systemTicker() 系统计时器} (即 {@link System#nanoTime()})，
     * 但特别是在测试时，有时对计时器有更多控制会很有用。在这种情况下，此方法将被覆盖。
     * 在此执行器上调度任务的代码应使用此计时器以与执行器保持一致
     * (例如，不会对计划任务"提前"运行感到惊讶)。
     *
     * @return 此调度器的计时器。
     */
    default Ticker ticker() {
        return Ticker.systemTicker();
    }

    /**
     * 创建并执行一个在给定延迟后启用的单次操作。
     * 该任务将由 {@link #next()} 方法选择的 {@link EventExecutor} 调度和执行。
     *
     * @param command 要执行的任务。
     * @param delay   从现在开始延迟执行的时间。
     * @param unit    延迟参数的时间单位。
     * @return 一个 {@link ScheduledFuture}，可用于提取结果或取消任务。
     */
    @Override
    ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);

    /**
     * 创建并执行一个在给定延迟后启用的 {@link ScheduledFuture}。
     * 该任务将由 {@link #next()} 方法选择的 {@link EventExecutor} 调度和执行。
     *
     * @param callable 要执行的函数。
     * @param delay    从现在开始延迟执行的时间。
     * @param unit     延迟参数的时间单位。
     * @param <V>      callable 的结果类型。
     * @return 一个 {@link ScheduledFuture}，可用于提取结果或取消任务。
     */
    @Override
    <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit);

    /**
     * 创建并执行一个在给定初始延迟后首次启用的周期性操作，
     * 随后在给定的周期内启用。
     * 该任务将由 {@link #next()} 方法选择的 {@link EventExecutor} 调度和执行。
     *
     * @param command      要执行的任务。
     * @param initialDelay 首次执行的延迟时间。
     * @param period       一次执行终止和下一次执行开始之间的周期。
     * @param unit         initialDelay 和 period 参数的时间单位。
     * @return 一个 {@link ScheduledFuture}，可用于取消任务。
     */
    @Override
    ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);

    /**
     * 创建并执行一个在给定初始延迟后首次启用的周期性操作，
     * 随后在一次执行终止和下一次执行开始之间存在给定延迟的情况下启用。
     * 该任务将由 {@link #next()} 方法选择的 {@link EventExecutor} 调度和执行。
     *
     * @param command      要执行的任务。
     * @param initialDelay 首次执行的延迟时间。
     * @param delay        一次执行终止和下一次执行开始之间的延迟。
     * @param unit         initialDelay 和 delay 参数的时间单位。
     * @return 一个 {@link ScheduledFuture}，可用于取消任务。
     */
    @Override
    ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);
}

/*
 * Copyright 2014 The Netty Project
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

package io.netty.resolver;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.Closeable;
import java.net.SocketAddress;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link NameResolver} 的分组工厂，负责为每个 {@link EventExecutor} 创建和管理独立的地址解析器实例。
 * <p>
 * <b>设计意图：</b>
 * <ul>
 *   <li>为每个事件执行器（EventExecutor）分配独立的 AddressResolver，避免多线程竞争和资源冲突。</li>
 *   <li>统一管理 resolver 的生命周期，确保资源及时释放。</li>
 *   <li>支持 resolver 的自动回收与关闭，提升系统健壮性。</li>
 * </ul>
 */
public abstract class AddressResolverGroup<T extends SocketAddress> implements Closeable {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AddressResolverGroup.class);

    /**
     * 由于 resolver 实例通常较重，这里不使用 ConcurrentMap，而采用同步块保护的 IdentityHashMap。
     */
    private final Map<EventExecutor, AddressResolver<T>> resolvers =
            new IdentityHashMap<EventExecutor, AddressResolver<T>>();

    private final Map<EventExecutor, GenericFutureListener<Future<Object>>> executorTerminationListeners =
            new IdentityHashMap<EventExecutor, GenericFutureListener<Future<Object>>>();

    protected AddressResolverGroup() { }

    /**
     * 获取与指定 {@link EventExecutor} 关联的 {@link AddressResolver}。
     * <p>
     * <b>关键节点说明：</b>
     * <ul>
     *   <li>若当前 executor 尚无 resolver，则通过 {@link #newResolver(EventExecutor)} 创建新实例，并缓存复用。</li>
     *   <li>每个 EventExecutor 只会分配一个 resolver，避免多线程竞争。</li>
     *   <li>为 executor 注册终止监听器，确保 executor 关闭时自动移除并关闭 resolver，防止资源泄漏。</li>
     *   <li>线程安全：所有操作均在同步块内完成，保证多线程环境下的正确性。</li>
     *   <li>若 executor 正在关闭，抛出 IllegalStateException，防止无效任务提交。</li>
     * </ul>
     * </p>
     */
    public AddressResolver<T> getResolver(final EventExecutor executor) {
        ObjectUtil.checkNotNull(executor, "executor");

        // 检查 executor 是否正在关闭，若是则拒绝新任务，防止资源泄漏
        if (executor.isShuttingDown()) {
            throw new IllegalStateException("executor not accepting a task");
        }

        AddressResolver<T> r;
        synchronized (resolvers) {
            // 1. 查询当前 executor 是否已有 resolver，若有则直接复用
            //    性能优化：resolver 实例较重，避免重复创建，提升资源利用率
            r = resolvers.get(executor);
            if (r == null) {
                final AddressResolver<T> newResolver;
                try {
                    // 2. 创建新的 resolver 实例，子类实现具体逻辑
                    //    性能优化：仅在必要时创建，减少不必要的对象分配
                    newResolver = newResolver(executor);
                } catch (Exception e) {
                    throw new IllegalStateException("failed to create a new resolver", e);
                }

                // 3. 缓存新创建的 resolver，确保同一 executor 只分配一个实例
                //    性能优化：IdentityHashMap 作为缓存，查找和插入效率高，且避免 key 冲突
                resolvers.put(executor, newResolver);

                // 4. 注册 executor 终止监听器，确保 executor 关闭时自动移除并关闭 resolver
                //    性能优化：自动回收机制，避免内存泄漏，无需手动管理 resolver 生命周期
                final FutureListener<Object> terminationListener = new FutureListener<Object>() {
                    @Override
                    public void operationComplete(Future<Object> future) {
                        synchronized (resolvers) {
                            // 5. executor 关闭时，移除 resolver 和监听器，防止内存泄漏
                            resolvers.remove(executor);
                            executorTerminationListeners.remove(executor);
                        }
                        // 6. 关闭 resolver，释放底层资源
                        newResolver.close();
                    }
                };

                executorTerminationListeners.put(executor, terminationListener);
                executor.terminationFuture().addListener(terminationListener);

                r = newResolver;
            }
        }

        // 7. 返回与 executor 关联的 resolver，供上层使用
        //    性能优化：全程复用同一 resolver，减少对象创建和销毁的开销
        return r;
    }

    /**
     * 由 {@link #getResolver(EventExecutor)} 调用，用于创建新的 {@link AddressResolver} 实例。
     * <p>
     * <b>关键节点说明：</b>
     * <ul>
     *   <li>子类需实现具体的 resolver 创建逻辑，支持自定义 DNS、hosts 文件、本地缓存等多种解析方式。</li>
     *   <li>每个 EventExecutor 只会调用一次，后续复用。</li>
     * </ul>
     * </p>
     */
    protected abstract AddressResolver<T> newResolver(EventExecutor executor) throws Exception;

    /**
     * 关闭本组内所有已创建的 {@link NameResolver} 实例。
     * <p>
     * <b>关键节点说明：</b>
     * <ul>
     *   <li>遍历所有已分配的 resolver，依次关闭，释放底层资源（如线程、网络连接等）。</li>
     *   <li>移除所有 executor 的终止监听器，防止内存泄漏。</li>
     *   <li>关闭过程中如有异常，通过 logger 记录警告，便于排查。</li>
     *   <li>线程安全：所有操作均在同步块内完成，保证多线程环境下的正确性。</li>
     * </ul>
     * </p>
     */
    @Override
    @SuppressWarnings({ "unchecked", "SuspiciousToArrayCall" })
    public void close() {
        final AddressResolver<T>[] rArray;
        final Map.Entry<EventExecutor, GenericFutureListener<Future<Object>>>[] listeners;

        synchronized (resolvers) {
            rArray = (AddressResolver<T>[]) resolvers.values().toArray(new AddressResolver[0]);
            resolvers.clear();
            listeners = executorTerminationListeners.entrySet().toArray(new Map.Entry[0]);
            executorTerminationListeners.clear();
        }

        for (final Map.Entry<EventExecutor, GenericFutureListener<Future<Object>>> entry : listeners) {
            entry.getKey().terminationFuture().removeListener(entry.getValue());
        }

        for (final AddressResolver<T> r: rArray) {
            try {
                r.close();
            } catch (Throwable t) {
                logger.warn("Failed to close a resolver:", t);
            }
        }
    }
}

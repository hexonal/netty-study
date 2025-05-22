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
package io.netty.channel.nio;

import io.netty.channel.ChannelException;
import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.IoHandlerContext;
import io.netty.channel.IoHandle;
import io.netty.channel.IoHandler;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.IoOps;
import io.netty.channel.IoRegistration;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SelectStrategyFactory;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.ThreadAwareExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link IoHandler} 的实现，它将 {@link IoHandle} 注册到 Java NIO {@link Selector}。
 * 这个类是 Netty NIO 传输的核心，负责处理所有基于 NIO 的 I/O 操作。
 *
 * <p><b>主要职责：</b></p>
 * <ul>
 *     <li>管理一个 Java NIO {@link Selector} 实例。</li>
 *     <li>将 {@link NioIoHandle} (通常代表一个 Channel) 注册到此 Selector，并监听指定的 I/O 事件 (如 OP_READ, OP_WRITE, OP_CONNECT, OP_ACCEPT)。</li>
 *     <li>在事件循环中调用 {@link Selector#select()} 或 {@link Selector#selectNow()} 来检测已就绪的 I/O 事件。</li>
 *     <li>处理已选择的 {@link SelectionKey}，并将事件分发给相应的 {@link NioIoHandle}。</li>
 *     <li>处理 Selector 的唤醒 (wakeup) 逻辑，允许其他线程中断阻塞的 select 操作。</li>
 *     <li>包含针对 JDK NIO bug 的解决方法，例如空轮询 (epoll bug) 和 {@link SelectedSelectionKeySet} 优化。</li>
 * </ul>
 *
 * <p><b>Selector 优化：</b></p>
 * <ul>
 *     <li><b>SelectedSelectionKeySet 优化：</b> 默认情况下，Netty 会尝试通过反射替换 Selector 内部的 {@code selectedKeys} 和
 *         {@code publicSelectedKeys} 集合为一个自定义的 {@link SelectedSelectionKeySet} 实例。
 *         这个优化旨在减少垃圾回收和提高处理已选择键的效率。可以通过系统属性 {@code io.netty.noKeySetOptimization=true} 禁用此优化。</li>
 *     <li><b>Selector 自动重建：</b> 为了解决某些 JDK 版本中 Selector 可能因 epoll bug 导致 {@code select()} 方法过早返回 (空轮询) 的问题，
 *         Netty 实现了一个 Selector 自动重建机制。如果 {@code select()} 方法连续过早返回的次数超过阈值
 *         ({@code io.netty.selectorAutoRebuildThreshold}，默认为 512)，Netty 会尝试创建一个新的 Selector 并将所有已注册的 Channel 迁移过去。
 *         可以通过将此属性设置为0来禁用此功能。</li>
 * </ul>
 *
 * <p><b>线程模型：</b></p>
 * 每个 {@link NioIoHandler} 通常由一个专用的 {@link ThreadAwareExecutor} (通常是 {@link NioEventLoop}) 驱动。
 * 所有的 Selector 操作和事件处理都在这个单线程中执行，以确保线程安全并避免复杂的并发控制。
 *
 * @see Selector
 * @see SelectionKey
 * @see NioIoHandle
 * @see NioEventLoop
 * @see SelectedSelectionKeySet
 */
public final class NioIoHandler implements IoHandler {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioIoHandler.class);

    /**
     * 清理已取消键的间隔。当已取消的键数量达到此值时，会触发一次 {@code selectAgain()} 来清理它们。
     * 这有助于防止已取消的键在 Selector 中累积过多。
     */
    private static final int CLEANUP_INTERVAL = 256; // XXX 硬编码值，但不需要自定义。

    /**
     * 是否禁用 {@link SelectedSelectionKeySet} 优化。
     * 通过系统属性 {@code io.netty.noKeySetOptimization} 控制，默认为 {@code false} (即启用优化)。
     */
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    /**
     * {@code selector.select()} 方法过早返回的最小次数，用于触发 Selector 重建的警告日志。
     */
    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    /**
     * Selector 自动重建的阈值。
     * 如果 {@code selector.select()} 方法连续过早返回的次数达到此阈值，Netty 会尝试重建 Selector。
     * 通过系统属性 {@code io.netty.selectorAutoRebuildThreshold} 控制，默认为 512。
     * 如果设置为0，则禁用自动重建功能。
     */
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            // 直接调用 selectNow()，用于 SelectStrategy 计算选择策略
            return selectNow();
        }
    };

    // 针对 JDK NIO bug 的解决方法。
    // 详情请参考:
    // - https://bugs.openjdk.java.net/browse/JDK-6427854 (针对 JDK 7 的早期开发版本)
    // - https://bugs.openjdk.java.net/browse/JDK-6527572 (针对 5.0u15-rev 和 6u10 之前的 JDK 版本)
    // - https://github.com/netty/netty/issues/203 (Netty issue)
    // 此 bug 可能导致 Selector.select() 在没有实际事件就绪的情况下意外唤醒（空轮询），
    // 特别是在 Linux (epoll) 上，从而导致 CPU 100% 占用。
    static {
        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            // 如果用户设置的值过小，则将其视为0，即禁用此功能，以避免过于频繁的重建。
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * NIO {@link Selector}。可能是原始 Selector 或包装后的 {@link SelectedSelectionKeySetSelector}。
     */
    private Selector selector;
    /**
     * 未经包装的原始 NIO {@link Selector}。
     * 即使应用了 {@link SelectedSelectionKeySet} 优化，此字段也始终指向原始的 Selector 实例。
     */
    private Selector unwrappedSelector;
    /**
     * 优化的已选择键集合。如果优化成功应用，则此字段指向 {@link SelectedSelectionKeySet} 实例；否则为 {@code null}。
     */
    private SelectedSelectionKeySet selectedKeys;

    /**
     * 用于创建 Selector 的 {@link SelectorProvider}。
     */
    private final SelectorProvider provider;

    /**
     * 控制阻塞的 {@code Selector.select} 是否应中断其选择过程的布尔值。
     * 在 Netty 中，我们为 select 方法使用超时，并且 select 方法将阻塞该时间，除非被唤醒。
     * {@code wakenUp} 标志用于跟踪是否发生了外部唤醒请求 (例如，有新任务提交到事件循环)。
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();

    /**
     * {@link Selector#select(long)} 或 {@link Selector#selectNow()} 的选择策略。
     * 用于决定在事件循环的当前迭代中是否以及如何调用 select 操作。
     */
    private final SelectStrategy selectStrategy;
    /**
     * 驱动此 {@link NioIoHandler} 的执行器，通常是 {@link NioEventLoop}。
     */
    private final ThreadAwareExecutor executor;
    /**
     * 已取消的 {@link SelectionKey} 的数量。
     * 用于触发 {@link #selectAgain()} 来清理已取消的键。
     */
    private int cancelledKeys;
    /**
     * 标志是否需要在当前事件处理周期结束后再次调用 {@code selectNow()}。
     * 通常在有键被取消时设置为 {@code true}，以确保 Selector 内部状态得到更新。
     */
    private boolean needsToSelectAgain;

    /**
     * 创建一个新的 {@link NioIoHandler} 实例。
     *
     * @param executor           驱动此 Handler 的 {@link ThreadAwareExecutor}。
     * @param selectorProvider   用于创建 Selector 的 {@link SelectorProvider}。
     * @param strategy           选择策略。
     */
    private NioIoHandler(ThreadAwareExecutor executor, SelectorProvider selectorProvider,
                         SelectStrategy strategy) {
        this.executor = ObjectUtil.checkNotNull(executor, "executionContext");
        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");
        // 打开并可能优化 Selector
        final SelectorTuple selectorTuple = openSelector();
        this.selector = selectorTuple.selector;
        this.unwrappedSelector = selectorTuple.unwrappedSelector;
    }

    /**
     * 包含原始 Selector 和可能被包装的 Selector 的元组。
     */
    private static final class SelectorTuple {
        /**
         * 未包装的原始 Selector。
         */
        final Selector unwrappedSelector;
        /**
         * 实际使用的 Selector，可能是原始的，也可能是 {@link SelectedSelectionKeySetSelector}。
         */
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector; // 如果没有优化，两者相同
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector; // 如果优化，selector 是包装后的
        }
    }

    /**
     * 打开一个新的 Selector，并尝试应用 {@link SelectedSelectionKeySet} 优化。
     *
     * @return 一个 {@link SelectorTuple}，包含原始 Selector 和实际使用的 Selector (可能已优化)。
     * @throws ChannelException 如果打开 Selector 失败。
     */
    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            // 使用指定的 SelectorProvider 打开一个新的 Selector
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        // 如果禁用了 SelectedKeySet 优化，则直接返回原始 Selector
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        // 尝试获取 sun.nio.ch.SelectorImpl 类，这是 Oracle/OpenJDK 中 Selector 的具体实现。
        // 这个操作在 PrivilegedAction 中执行，以处理可能的安全限制。
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    // 如果类加载失败，返回异常
                    return cause;
                }
            }
        });

        // 检查是否成功加载了 SelectorImpl 类，并且当前 unwrappedSelector 是否是其子类。
        if (!(maybeSelectorImplClass instanceof Class) ||
                !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            // 如果不满足条件，则不进行优化，返回原始 Selector。
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet(); // 创建自定义的 KeySet

        // 再次在 PrivilegedAction 中执行，尝试通过反射修改 SelectorImpl 内部的 selectedKeys 和 publicSelectedKeys 字段。
        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    // 在 Java 9+ 中，如果 Unsafe 可用，尝试使用 Unsafe 直接修改字段，以绕过模块系统的访问限制。
                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null; // 成功
                        }
                        // 如果获取偏移量失败，则回退到反射。
                    }

                    // 尝试使字段可访问
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    // 设置自定义的 KeySet
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null; // 成功
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            // 如果反射操作失败，记录日志并放弃优化。
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        // 优化成功
        this.selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        // 返回包含包装后的 Selector 的元组
        return new SelectorTuple(unwrappedSelector,
                new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * 返回此 {@link NioIoHandler} 用于获取 {@link Selector} 的 {@link SelectorProvider}。
     *
     * @return {@link SelectorProvider} 实例。
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    /**
     * 返回此 {@link NioIoHandler} 使用的 {@link Selector}。
     * 这可能是原始的 Selector，或者如果优化成功，则是 {@link SelectedSelectionKeySetSelector}。
     *
     * @return 使用的 {@link Selector}。
     */
    Selector selector() {
        return selector;
    }

    /**
     * 返回当前注册到 Selector 上的 {@link NioIoHandle} (Channel) 数量。
     * 计算方式为 Selector 中键的总数减去已取消但尚未被 Selector 清理的键的数量。
     *
     * @return 注册的 {@link NioIoHandle} 数量。
     */
    int numRegistered() {
        // selector.keys() 返回的是当前所有注册的键（包括有效的和已取消的）
        // cancelledKeys 是我们自己记录的调用了 key.cancel() 但 Selector 可能尚未处理的数量
        return selector().keys().size() - cancelledKeys;
    }

    /**
     * 返回 Selector 中所有 {@link SelectionKey} 的集合。
     * 注意：此集合可能包含已取消的键。
     *
     * @return {@link SelectionKey} 的集合。
     */
    Set<SelectionKey> registeredSet() {
        return selector().keys();
    }

    /**
     * 重建 Selector。
     * 当检测到 JDK 的 epoll bug (Selector.select() 持续过早返回) 时，会调用此方法。
     * 它会创建一个新的 Selector，并将所有当前 Channel 重新注册到新的 Selector 上，然后关闭旧的 Selector。
     * 这是一个昂贵的操作，应尽量避免。
     */
    void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            // 如果当前没有 Selector (例如，在关闭过程中)，则不执行任何操作。
            return;
        }

        try {
            // 打开一个新的 Selector，并尝试应用优化。
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // 将所有 Channel 从旧 Selector 迁移到新 Selector。
        int nChannels = 0;
        // 遍历旧 Selector 上的所有键
        for (SelectionKey key : oldSelector.keys()) {
            DefaultNioRegistration registration = (DefaultNioRegistration) key.attachment();
            try {
                // 如果键无效 (例如 Channel 已关闭)，或者 Channel 已经在新的 Selector 上注册 (不太可能发生，但作为防御措施)，则跳过。
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                // 在新的 Selector 上重新注册 Channel
                registration.register(newSelectorTuple.unwrappedSelector);
                nChannels++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a NioHandle to the new Selector.", e);
                // 如果重新注册失败，则取消该 Channel 的注册并关闭它。
                registration.cancel();
            }
        }

        // 更新当前的 selector 和 unwrappedSelector 为新的实例。
        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // 关闭旧的 Selector。
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /**
     * 将 {@link IoHandle} 转换为 {@link NioIoHandle}。
     * @param handle 要转换的 {@link IoHandle}。
     * @return 转换后的 {@link NioIoHandle}。
     * @throws IllegalArgumentException 如果 handle 不是 {@link NioIoHandle} 类型。
     */
    private static NioIoHandle nioHandle(IoHandle handle) {
        if (handle instanceof NioIoHandle) {
            return (NioIoHandle) handle;
        }
        throw new IllegalArgumentException("IoHandle of type " + StringUtil.simpleClassName(handle) + " not supported");
    }
    /**
     * 将 {@link IoOps} 转换为 {@link NioIoOps}。
     * @param ops 要转换的 {@link IoOps}。
     * @return 转换后的 {@link NioIoOps}。
     * @throws IllegalArgumentException 如果 ops 不是 {@link NioIoOps} 类型。
     */
    private static NioIoOps cast(IoOps ops) {
        if (ops instanceof NioIoOps) {
            return (NioIoOps) ops;
        }
        throw new IllegalArgumentException("IoOps of type " + StringUtil.simpleClassName(ops) + " not supported");
    }

    /**
     * {@link IoRegistration} 的 NIO 实现。
     * 每个 {@link NioIoHandle} (通常是 Channel) 注册到 {@link NioIoHandler} 的 Selector 时，
     * 都会创建一个 {@link DefaultNioRegistration} 实例。
     * 它封装了与特定 {@link NioIoHandle} 相关的 {@link SelectionKey} 和注册状态。
     */
    final class DefaultNioRegistration implements IoRegistration {
        private final AtomicBoolean canceled = new AtomicBoolean(); // 标志此注册是否已取消
        private final NioIoHandle handle; // 关联的 NioIoHandle
        private volatile SelectionKey key; // 关联的 SelectionKey

        /**
         * 创建一个新的 {@link DefaultNioRegistration}。
         *
         * @param executor   关联的执行器。
         * @param handle     要注册的 {@link NioIoHandle}。
         * @param initialOps 初始感兴趣的 I/O 操作。
         * @param selector   要注册到的 {@link Selector}。
         * @throws IOException 如果注册失败。
         */
        DefaultNioRegistration(ThreadAwareExecutor executor, NioIoHandle handle, NioIoOps initialOps, Selector selector)
                throws IOException {
            this.handle = handle;
            // 将 NioIoHandle 关联的 SelectableChannel 注册到 Selector
            // initialOps.value 是整数形式的 interestOps
            // this (DefaultNioRegistration 实例) 作为 attachment 附加到 SelectionKey
            this.key = handle.selectableChannel().register(selector, initialOps.value, this);
        }

        /**
         * 返回关联的 {@link NioIoHandle}。
         * @return {@link NioIoHandle}。
         */
        NioIoHandle handle() {
            return handle;
        }

        /**
         * 将此注册的 {@link NioIoHandle} 注册到新的 {@link Selector}。
         * 这在 {@link NioIoHandler#rebuildSelector0()} 期间使用。
         *
         * @param selector 新的 {@link Selector}。
         * @throws IOException 如果注册失败。
         */
        void register(Selector selector) throws IOException {
            // 在新的 selector 上注册，保留当前的 interestOps
            SelectionKey newKey = handle.selectableChannel().register(selector, key.interestOps(), this);
            // 取消旧的 key (这只是标记它为已取消，实际的清理由 Selector 在下次 select 操作时完成)
            key.cancel();
            // 更新为新的 key
            key = newKey;
        }

        /**
         * 返回附加到此注册的对象，即 {@link SelectionKey}。
         * @param <T> 附件的类型。
         * @return {@link SelectionKey}。
         */
        @SuppressWarnings("unchecked")
        @Override
        public <T> T attachment() {
            return (T) key;
        }

        /**
         * 检查此注册是否仍然有效。
         * 一个注册有效，如果它没有被显式取消 ({@code canceled} 为 false)，并且其关联的 {@link SelectionKey} 仍然有效。
         *
         * @return 如果有效则为 {@code true}，否则为 {@code false}。
         */
        @Override
        public boolean isValid() {
            return !canceled.get() && key.isValid();
        }

        /**
         * 提交 (更新) 此注册感兴趣的 I/O 操作。
         *
         * @param ops 新的感兴趣的 I/O 操作。
         * @return {@link NioIoOps#value} (整数形式的 interestOps)。
         */
        @Override
        public long submit(IoOps ops) {
            int v = cast(ops).value; // 获取整数形式的 interestOps
            key.interestOps(v); // 更新 SelectionKey 的 interestOps
            return v;
        }

        /**
         * 取消此注册。
         * 将 {@link SelectionKey} 标记为已取消，并增加 {@link NioIoHandler#cancelledKeys} 计数。
         * 如果已取消的键数量达到 {@link NioIoHandler#CLEANUP_INTERVAL}，则会触发一次 {@code selectAgain()}。
         *
         * @return 如果成功取消 (之前未取消)，则为 {@code true}；如果已经取消，则为 {@code false}。
         */
        @Override
        public boolean cancel() {
            // 使用 CAS 确保只取消一次
            if (!canceled.compareAndSet(false, true)) {
                return false;
            }
            key.cancel(); // 标记 SelectionKey 为已取消
            cancelledKeys++; // 增加已取消键的计数
            // 如果达到清理间隔，则设置 needsToSelectAgain 标志，以便在事件循环的下一轮中
            // 调用 selectNow() 来强制 Selector 处理已取消的键。
            if (cancelledKeys >= CLEANUP_INTERVAL) {
                cancelledKeys = 0;
                needsToSelectAgain = true;
            }
            return true;
        }

        /**
         * 关闭此注册，包括取消注册并尝试关闭关联的 {@link NioIoHandle}。
         * 通常在 Channel 关闭时调用。
         */
        void close() {
            cancel(); // 先取消注册
            try {
                handle.close(); // 然后关闭 NioIoHandle
            } catch (Exception e) {
                logger.debug("Exception during closing " + handle, e);
            }
        }

        /**
         * 处理已就绪的 I/O 事件。
         * 此方法由 {@link NioIoHandler#processSelectedKey(SelectionKey)} 调用。
         *
         * @param ready 就绪的操作集 (readyOps)。
         */
        void handle(int ready) {
            // 将就绪操作委托给 NioIoHandle 进行处理
            handle.handle(this, NioIoOps.eventOf(ready));
        }
    }

    @Override
    public IoRegistration register(IoHandle handle)
            throws Exception {
        NioIoHandle nioHandle = nioHandle(handle);
        NioIoOps ops = NioIoOps.NONE; // 初始时不关注任何操作
        boolean selected = false; // 标记是否已经执行过 selectNow()
        for (;;) { // 无限循环，直到注册成功或抛出未处理的异常
            try {
                // 尝试在 unwrappedSelector (原始 Selector) 上创建新的 DefaultNioRegistration
                return new DefaultNioRegistration(executor, nioHandle, ops, unwrappedSelector());
            } catch (CancelledKeyException e) {
                // 如果捕获到 CancelledKeyException，这通常意味着 Selector 的内部状态可能存在问题，
                // 或者尝试注册一个已经被取消的 Channel。
                if (!selected) {
                    // 如果之前没有执行过 selectNow()，则现在执行一次。
                    // 这是为了强制 Selector 更新其内部状态，清理掉可能仍然缓存的已取消的 SelectionKey。
                    // 有些 Selector 实现在没有 select 操作的情况下，可能不会立即移除已取消的键。
                    selectNow();
                    selected = true;
                    // 在下一次循环中重试注册。
                } else {
                    // 如果已经执行过 selectNow()，但仍然收到 CancelledKeyException，
                    // 这可能表明存在更深层次的问题，例如 JDK 的 bug，或者 Channel 状态确实有问题。
                    // 此时，不再重试，直接抛出异常。
                    throw e;
                }
            }
        }
    }

    @Override
    public int run(IoHandlerContext context) {
        int handled = 0; // 记录处理的事件数量
        try {
            try {
                // 根据选择策略计算下一步操作
                switch (selectStrategy.calculateStrategy(selectNowSupplier, !context.canBlock())) {
                    case SelectStrategy.CONTINUE:
                        // 如果策略是 CONTINUE，表示当前不进行 select 操作，直接返回。
                        // 这通常意味着事件循环中有其他任务需要优先处理。
                        return 0;

                    case SelectStrategy.BUSY_WAIT:
                        // NIO 不支持真正的 BUSY_WAIT (像 epoll 那样不阻塞地轮询)，
                        // 所以这里会 fall-through 到 SELECT 逻辑。
                        // 对于 NIO，BUSY_WAIT 实际上会表现为 selectNow()。

                    case SelectStrategy.SELECT:
                        // 执行 select 操作，并传入当前的 wakenUp 状态。
                        // wakenUp.getAndSet(false) 会获取当前值并将其设置为 false，
                        // 表示我们即将处理唤醒信号（如果有的话）。
                        select(context, wakenUp.getAndSet(false));

                        // 处理 Selector.wakeup() 的竞争条件。
                        // Selector.wakeup() 是一个昂贵的操作，我们尽量减少其调用。
                        // wakenUp.compareAndSet(false, true) 通常在提交任务时调用，如果成功，则调用 selector.wakeup()。
                        // 但这里存在一个竞争：
                        // 1. 如果在 wakenUp.set(false) (在 select 方法开始时) 和 selector.select(...) 之间发生 wakeup，
                        //    wakenUp 会被设为 true，然后 selector.select(...) 立即返回。
                        //    之后，直到下一轮的 wakenUp.set(false)，任何 wakenUp.compareAndSet(false, true) 都会失败，
                        //    导致后续的 wakeup 请求失效，selector.select(...) 可能会不必要地阻塞。 (BAD)
                        // 2. 如果在 selector.select(...) 和 if (wakenUp.get()) { ... } 之间发生 wakeup。 (OK)
                        //
                        // 为了解决第一种情况 (BAD)，我们在 selector.select(...) 之后立即检查 wakenUp.get()。
                        // 如果为 true，我们再次调用 selector.wakeup()。
                        // 这样做效率不高，因为它在两种情况下都会唤醒（BAD 情况需要，OK 情况不需要），但能确保正确性。
                        if (wakenUp.get()) {
                            selector.wakeup();
                        }
                        // fall through 到 default 处理已选择的键
                    default:
                        // 如果没有显式返回，则执行到这里，处理已选择的键。
                }
            } catch (IOException e) {
                // 如果在 select 操作期间发生 IOException，通常意味着 Selector 本身出了问题 (例如，epoll bug)。
                // Netty 会尝试重建 Selector。
                // 这是一个严重的错误，可能导致 Selector 不可用。
                logger.warn("Selector.select() failed, will attempt to rebuild Selector");
                rebuildSelector0();
                handleLoopException(e); // 记录异常并休眠一段时间
                return 0; // 本轮不处理事件
            }

            // 重置计数器和标志
            cancelledKeys = 0;
            needsToSelectAgain = false;
            // 处理已就绪的 SelectionKey
            handled = processSelectedKeys();
        } catch (Error e) {
            // 如果是 Error (例如 OutOfMemoryError)，直接抛出，让上层处理。
            throw e;
        } catch (Throwable t) {
            // 捕获其他所有 Throwable，记录日志并休眠，防止事件循环因意外异常而终止。
            handleLoopException(t);
        }
        return handled; // 返回本轮处理的事件数量
    }

    /**
     * 处理在事件循环中发生的未捕获异常。
     * 记录警告日志，并使当前线程休眠1秒，以防止因连续快速失败而导致 CPU 过度消耗。
     *
     * @param t 捕获到的异常。
     */
    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // 防止可能的连续立即故障导致过度的 CPU 消耗。
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // 忽略中断异常。
        }
    }

    /**
     * 处理已选择的 {@link SelectionKey}。
     * 根据 {@link #selectedKeys} 是否为 {@code null} (即是否应用了优化)，
     * 调用 {@link #processSelectedKeysOptimized()} 或 {@link #processSelectedKeysPlain(Set)}。
     *
     * @return 处理的键的数量。
     */
    private int processSelectedKeys() {
        if (selectedKeys != null) {
            // 如果 selectedKeys 字段不为 null，说明 Netty 的 SelectedSelectionKeySet 优化已启用。
            // 此时，selector.selectedKeys() 返回的是我们自定义的 SelectedSelectionKeySet 实例。
            return processSelectedKeysOptimized();
        } else {
            // 否则，使用 JDK 原生的 selectedKeys() 方法返回的 Set。
            return processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    public void destroy() {
        try {
            // 关闭 Selector，这将取消所有注册的键并释放相关资源。
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    /**
     * 处理普通的 (未优化的) {@link SelectionKey} 集合。
     * 遍历 {@code selectedKeys} 集合，对每个键调用 {@link #processSelectedKey(SelectionKey)}，
     * 并在处理后从集合中移除该键。
     *
     * @param selectedKeys 从 {@code selector.selectedKeys()} 获取的原始 {@link SelectionKey} 集合。
     * @return 处理的键的数量。
     */
    private int processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // 优化：如果集合为空，直接返回，避免创建迭代器的开销。
        // 参考: https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return 0;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        int handled = 0;
        for (;;) {
            final SelectionKey k = i.next();
            // 重要：处理完一个键后，必须从 selectedKeys 集合中移除它。
            // 否则，下次调用 select() 时，如果该键仍然就绪，它会再次出现在 selectedKeys 中。
            i.remove();

            processSelectedKey(k); // 处理单个键
            ++handled;

            if (!i.hasNext()) {
                // 如果没有更多键，则退出循环。
                break;
            }

            // 如果在处理过程中 needsToSelectAgain 标志被设置 (通常是由于有键被取消)，
            // 则需要立即调用 selectAgain() 来更新 Selector 的状态。
            if (needsToSelectAgain) {
                selectAgain();
                // selectAgain() 后，selectedKeys 集合可能已更改，需要重新获取。
                selectedKeys = selector.selectedKeys();

                // 重新创建迭代器，以避免 ConcurrentModificationException。
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
        return handled;
    }

    /**
     * 处理优化的 {@link SelectedSelectionKeySet}。
     * {@link SelectedSelectionKeySet} 是一个基于数组的实现，遍历它比遍历 JDK 的 Set 更高效。
     *
     * @return 处理的键的数量。
     */
    private int processSelectedKeysOptimized() {
        int handled = 0;
        // selectedKeys.keys 是一个 SelectionKey 数组，selectedKeys.size 是实际数量。
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // 将数组中的条目置为 null，以便在 Channel 关闭后可以被 GC。
            // 参考: https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            processSelectedKey(k); // 处理单个键
            ++handled;

            if (needsToSelectAgain) {
                // 如果需要再次 select，则重置 SelectedSelectionKeySet (清空已处理的键)。
                // selectedKeys.reset(i + 1) 会将 size 设置为 0，并保留数组中未处理的部分 (如果适用)。
                // 但实际上，因为我们是从头开始遍历，这里 i + 1 之后的元素应该还没有被处理。
                // 这里的逻辑似乎是清空整个数组，以便下次 selectAgain() 后重新填充。
                // 实际上，SelectedSelectionKeySet 的设计是每次 select 后，keys 数组会被 Selector 重新填充。
                // reset 的作用主要是将 size 置零。
                selectedKeys.reset(i + 1);

                selectAgain(); // 立即再次 select
                i = -1; // 重置循环索引，以便从头开始处理新选择的键。
            }
        }
        return handled;
    }

    /**
     * 处理单个 {@link SelectionKey}。
     * 从键中获取附加的 {@link DefaultNioRegistration}，检查其有效性，
     * 如果有效，则调用其 {@code handle(k.readyOps())} 方法来处理就绪的 I/O 事件。
     *
     * @param k 要处理的 {@link SelectionKey}。
     */
    private void processSelectedKey(SelectionKey k) {
        final DefaultNioRegistration registration = (DefaultNioRegistration) k.attachment();
        if (!registration.isValid()) {
            // 如果注册无效 (例如，Channel 已关闭或 SelectionKey 已取消)
            try {
                // 尝试关闭关联的 NioIoHandle (Channel)
                registration.handle.close();
            } catch (Exception e) {
                logger.debug("Exception during closing " + registration.handle, e);
            }
            return;
        }
        // 调用 NioRegistration 的 handle 方法，传入就绪的操作集
        registration.handle(k.readyOps());
    }

    /**
     * 准备销毁此 {@link NioIoHandler}。
     * 在实际销毁之前调用，用于执行清理操作，例如关闭所有已注册的 Channel。
     * 它会首先调用 {@code selectAgain()} 来处理任何挂起的已取消键，
     * 然后遍历所有注册的键，并关闭它们关联的 {@link DefaultNioRegistration} (进而关闭 Channel)。
     */
    @Override
    public void prepareToDestroy() {
        selectAgain(); // 确保所有已取消的键都被处理
        Set<SelectionKey> keys = selector.keys();
        Collection<DefaultNioRegistration> registrations = new ArrayList<>(keys.size());
        // 将所有 DefaultNioRegistration 收集到一个列表中，以避免在迭代时修改 Selector 的 keys 集合。
        for (SelectionKey k: keys) {
            DefaultNioRegistration handle = (DefaultNioRegistration) k.attachment();
            registrations.add(handle);
        }

        // 关闭所有收集到的注册
        for (DefaultNioRegistration reg: registrations) {
            reg.close();
        }
    }

    /**
     * 唤醒阻塞在 {@link Selector#select()} 方法上的 {@link NioIoHandler} 线程。
     * 如果调用此方法的线程不是 {@link NioIoHandler} 的执行线程，并且 {@code wakenUp} 标志成功从未被设置 (false)
     * 原子地设置为已设置 (true)，则调用 {@code selector.wakeup()}。
     * 这种机制用于从其他线程安全地唤醒事件循环。
     */
    @Override
    public void wakeup() {
        // 只有当调用者不是当前事件循环线程，并且 wakenUp 标志成功从 false 变为 true 时才执行 wakeup。
        // compareAndSet 确保 wakeup() 只被调用一次，即使有多个线程同时尝试唤醒。
        if (!executor.isExecutorThread(Thread.currentThread()) && wakenUp.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    /**
     * 检查给定的 {@link IoHandle} 类型是否与此 {@link NioIoHandler} 兼容。
     * 对于 {@link NioIoHandler}，它只兼容 {@link NioIoHandle} 及其子类型。
     *
     * @param handleType {@link IoHandle} 的类型。
     * @return 如果兼容则为 {@code true}，否则为 {@code false}。
     */
    @Override
    public boolean isCompatible(Class<? extends IoHandle> handleType) {
        return NioIoHandle.class.isAssignableFrom(handleType);
    }

    /**
     * 返回未包装的 (原始的) {@link Selector} 实例。
     * 即使应用了 {@link SelectedSelectionKeySet} 优化，此方法也始终返回原始的 Selector。
     *
     * @return 原始的 {@link Selector}。
     */
    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    /**
     * 执行 {@link Selector#select(long)} 操作。
     * 此方法是事件循环的核心，负责等待 I/O 事件。
     *
     * @param runner       {@link IoHandlerContext}，提供定时信息。
     * @param oldWakenUp   在调用此方法之前 {@code wakenUp} 标志的值。用于处理唤醒逻辑。
     * @throws IOException 如果 {@code selector.select(long)} 抛出异常。
     */
    private void select(IoHandlerContext runner, boolean oldWakenUp) throws IOException {
        Selector selector = this.selector;
        try {
            int selectCnt = 0; // 记录 select 调用次数，用于检测 JDK epoll bug
            long currentTimeNanos = System.nanoTime();
            // 计算 select 操作的截止时间
            long selectDeadLineNanos = currentTimeNanos + runner.delayNanos(currentTimeNanos);

            for (;;) { // 无限循环，直到 select 操作完成或超时
                // 计算 select 的超时时间 (毫秒)
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                if (timeoutMillis <= 0) { // 如果超时时间已过或为0
                    if (selectCnt == 0) { // 如果是第一次尝试且已超时，则执行一次非阻塞的 selectNow
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break; // 超时，退出循环
                }

                // 如果事件循环中有任务挂起 (runner.canBlock() 为 false)，并且 wakenUp 成功从未设置变为已设置，
                // 这意味着在准备 select 之前，有新任务被提交并尝试唤醒。
                // 此时应立即执行 selectNow() 并退出，以处理这些任务。
                if (!runner.canBlock() && wakenUp.compareAndSet(false, true)) {
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                // 执行阻塞的 select 操作
                int selectedKeys = selector.select(timeoutMillis);
                selectCnt++; // 增加 select 调用计数

                // 如果 select 返回非零值 (有事件就绪)，或者之前被唤醒 (oldWakenUp)，
                // 或者当前被唤醒 (wakenUp.get())，或者有任务挂起 (!runner.canBlock())，
                // 则表示 select 操作可以结束。
                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || !runner.canBlock()) {
                    break;
                }
                if (Thread.interrupted()) {
                    // 如果线程被中断 (例如，通过外部调用 Thread.interrupt())，
                    // 这通常是一个错误的使用方式，因为事件循环应该通过其自身的关闭机制来停止。
                    // 记录日志并中断 select 循环，以防止 CPU 忙等待。
                    // 参考: https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioHandler.shutdownGracefully() to shutdown the NioHandler.");
                    }
                    selectCnt = 1; // 将 selectCnt 重置为1，避免触发不必要的 Selector 重建
                    break;
                }

                long time = System.nanoTime();
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // 尽管 select(timeoutMillis) 返回0，但实际经过的时间已经超过了 timeoutMillis。
                    // 这可能发生在某些计时精度较低的系统上，或者 select 实现本身的问题。
                    // 将 selectCnt 设为1，表示此次 select 视为有效（即使没有返回键）。
                    selectCnt = 1;
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // 如果 select() 连续过早返回的次数达到了阈值，则尝试重建 Selector。
                    // 这是一个针对 JDK epoll bug 的 workaround。
                    // selectRebuildSelector 方法会处理重建逻辑。
                    logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding selector.", selectCnt);
                    selector = selectRebuildSelector(selectCnt);
                    selectCnt = 1; // 重建后重置计数
                    break; // 重建后退出当前 select 循环
                }

                currentTimeNanos = time; // 更新当前时间，为下一次循环的超时计算做准备
            }

            // 如果 select() 过早返回的次数超过了 MIN_PREMATURE_SELECTOR_RETURNS，记录一个调试日志。
            // 这有助于诊断潜在的 epoll bug。
            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            // CancelledKeyException 通常是无害的，例如在 Channel 关闭后，其键被取消。
            // 但在某些情况下，它也可能指示 JDK Selector 的 bug。
            // 记录一个调试日志。
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // 尽管是无害的，但还是记录下来。
        }
    }

    /**
     * 执行一次非阻塞的 {@link Selector#selectNow()} 操作。
     *
     * @return {@code selector.selectNow()} 的返回值，即选择到的键的数量。
     * @throws IOException 如果 {@code selector.selectNow()} 抛出异常。
     */
    int selectNow() throws IOException {
        try {
            return selector.selectNow();
        } finally {
            // 确保在 selectNow 之后，如果 wakenUp 标志为 true，则调用 selector.wakeup()。
            // 这是为了处理在 selectNow 执行期间，有新的唤醒请求到来的情况。
            // 虽然 selectNow 是非阻塞的，但这个 wakeup 调用是为了确保唤醒状态的一致性。
            if (wakenUp.get()) {
                selector.wakeup();
            }
        }
    }

    /**
     * 当 {@code Selector.select()} 被检测到连续过早返回多次时，重建 Selector。
     *
     * @param selectCnt 连续过早返回的次数。
     * @return 新的 {@link Selector} 实例。
     * @throws IOException 如果重建过程中发生错误。
     */
    private Selector selectRebuildSelector(int selectCnt) throws IOException {
        logger.warn(
                "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                selectCnt, selector);

        rebuildSelector0(); // 执行核心的重建逻辑
        Selector selector = this.selector; // 获取新的 Selector

        // 在新的 Selector 上立即执行一次 selectNow()，以填充其 selectedKeys 集合。
        selector.selectNow();
        return selector;
    }

    /**
     * 立即执行一次 {@link Selector#selectNow()} 操作。
     * 通常在有 {@link SelectionKey} 被取消后调用，以强制 Selector 更新其内部状态并清理已取消的键。
     * 将 {@code needsToSelectAgain} 标志重置为 {@code false}。
     */
    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            // 如果 selectNow 失败，记录警告。这不应该经常发生。
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }

    /**
     * 返回一个新的 {@link IoHandlerFactory}，用于创建 {@link NioIoHandler} 实例。
     * 使用默认的 {@link SelectorProvider#provider()} 和 {@link DefaultSelectStrategyFactory#INSTANCE}。
     *
     * @return {@link IoHandlerFactory} 实例。
     */
    public static IoHandlerFactory newFactory() {
        return newFactory(SelectorProvider.provider(), DefaultSelectStrategyFactory.INSTANCE);
    }

    /**
     * 返回一个新的 {@link IoHandlerFactory}，用于创建 {@link NioIoHandler} 实例。
     * 使用指定的 {@link SelectorProvider} 和默认的 {@link DefaultSelectStrategyFactory#INSTANCE}。
     *
     * @param selectorProvider 要使用的 {@link SelectorProvider}。
     * @return {@link IoHandlerFactory} 实例。
     */
    public static IoHandlerFactory newFactory(SelectorProvider selectorProvider) {
        return newFactory(selectorProvider, DefaultSelectStrategyFactory.INSTANCE);
    }

    /**
     * 返回一个新的 {@link IoHandlerFactory}，用于创建 {@link NioIoHandler} 实例。
     *
     * @param selectorProvider      要使用的 {@link SelectorProvider}。
     * @param selectStrategyFactory 要使用的 {@link SelectStrategyFactory}。
     * @return {@link IoHandlerFactory} 实例。
     */
    public static IoHandlerFactory newFactory(final SelectorProvider selectorProvider,
                                              final SelectStrategyFactory selectStrategyFactory) {
        ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        ObjectUtil.checkNotNull(selectStrategyFactory, "selectStrategyFactory");
        // 返回一个 lambda 表达式，该表达式在被调用时创建并返回一个新的 NioIoHandler 实例。
        // context 是传递给工厂的 ThreadAwareExecutor (通常是 NioEventLoop)。
        return context ->  new NioIoHandler(context, selectorProvider, selectStrategyFactory.newSelectStrategy());
    }
}

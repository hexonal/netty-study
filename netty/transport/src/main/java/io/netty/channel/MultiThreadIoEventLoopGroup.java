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

import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.internal.EmptyArrays;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * {@link IoEventLoopGroup} 的一个实现，它使用多个线程来处理其任务。
 * 这个类继承自 {@link MultithreadEventLoopGroup}，并实现了 {@link IoEventLoopGroup} 接口，
 * 表明它是一个专门用于处理 I/O 事件的多线程事件循环组。
 *
 * <p><b>主要职责：</b></p>
 * <ul>
 *     <li>管理一组 {@link IoEventLoop} 实例。</li>
 *     <li>将传入的 I/O 事件（例如，新的连接、数据读写）分发给其管理的 {@link IoEventLoop} 进行处理。</li>
 *     <li>提供创建和管理用于 I/O 操作的线程的能力。</li>
 * </ul>
 *
 * <p><b>线程模型：</b></p>
 * 每个 {@link IoEventLoop} 通常由一个专用线程驱动，负责处理分配给它的所有 {@link Channel} 的 I/O 事件。
 * 这种模型确保了一个 {@link Channel} 的所有 I/O 操作都在同一个线程中执行，从而避免了并发问题，简化了状态管理。
 *
 * <p><b>使用场景：</b></p>
 * 通常在服务器端（例如使用 {@link ServerBootstrap}）或需要处理大量并发连接的客户端应用程序中使用。
 * 通过调整线程数（{@code nThreads}），可以根据硬件资源和应用需求来优化性能。
 *
 * @see MultithreadEventLoopGroup
 * @see IoEventLoopGroup
 * @see IoEventLoop
 * @see IoHandlerFactory
 */
public class MultiThreadIoEventLoopGroup extends MultithreadEventLoopGroup implements IoEventLoopGroup {

    /**
     * 使用默认线程数和默认 {@link ThreadFactory} 创建 {@link MultiThreadIoEventLoopGroup} 的新实例。
     *
     * @param ioHandlerFactory 用于创建处理 I/O 的 {@link IoHandler} 的 {@link IoHandlerFactory}。
     *                         每个 {@link IoEventLoop} 将使用此工厂创建一个 {@link IoHandler} 来处理其实际的 I/O 操作。
     */
    public MultiThreadIoEventLoopGroup(IoHandlerFactory ioHandlerFactory) {
        this(0, ioHandlerFactory); // 0 表示使用默认线程数
    }

    /**
     * 使用指定的线程数和默认的 {@link ThreadFactory} 创建 {@link MultiThreadIoEventLoopGroup} 的新实例。
     *
     * @param nThreads          创建的线程数，也即 {@link EventLoop} 的数量。
     *                          如果为0，则使用 Netty 的默认线程数计算规则（通常是 CPU 核心数的两倍）。
     * @param ioHandlerFactory  用于创建处理 I/O 的 {@link IoHandler} 的 {@link IoHandlerFactory}。
     */
    public MultiThreadIoEventLoopGroup(int nThreads, IoHandlerFactory ioHandlerFactory) {
        this(nThreads, (Executor) null, ioHandlerFactory); // 传入 null executor，让父类创建默认的线程工厂
    }

    /**
     * 使用默认线程数和指定的 {@link ThreadFactory} 创建 {@link MultiThreadIoEventLoopGroup} 的新实例。
     *
     * @param threadFactory     用于创建线程的 {@link ThreadFactory}。
     * @param ioHandlerFactory  用于创建处理 I/O 的 {@link IoHandler} 的 {@link IoHandlerFactory}。
     */
    public MultiThreadIoEventLoopGroup(ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory) {
        this(0, threadFactory, ioHandlerFactory); // 0 表示使用默认线程数
    }

    /**
     * 使用默认线程数和指定的 {@link Executor} 创建 {@link MultiThreadIoEventLoopGroup} 的新实例。
     *
     * @param executor          用于执行任务的 {@link Executor}。
     *                          如果提供，则 {@link EventLoop} 将使用此执行器来驱动其任务；否则将创建新的线程。
     * @param ioHandlerFactory  用于创建处理 I/O 的 {@link IoHandler} 的 {@link IoHandlerFactory}。
     */
    public MultiThreadIoEventLoopGroup(Executor executor,
                                       IoHandlerFactory ioHandlerFactory) {
        super(0, executor, ioHandlerFactory); // 0 表示使用默认线程数
    }

    /**
     * 创建 {@link MultiThreadIoEventLoopGroup} 的新实例。
     *
     * @param nThreads          创建的线程数，也即 {@link EventLoop} 的数量。
     * @param executor          用于执行任务的 {@link Executor}。
     * @param ioHandlerFactory  用于创建处理 I/O 的 {@link IoHandler} 的 {@link IoHandlerFactory}。
     */
    public MultiThreadIoEventLoopGroup(int nThreads, Executor executor,
                                       IoHandlerFactory ioHandlerFactory) {
        super(nThreads, executor, ioHandlerFactory);
    }

    /**
     * 创建 {@link MultiThreadIoEventLoopGroup} 的新实例。
     *
     * @param nThreads          创建的线程数，也即 {@link EventLoop} 的数量。
     * @param threadFactory     用于创建线程的 {@link ThreadFactory}。
     * @param ioHandlerFactory  用于创建处理 I/O 的 {@link IoHandler} 的 {@link IoHandlerFactory}。
     */
    public MultiThreadIoEventLoopGroup(int nThreads, ThreadFactory threadFactory,
                                       IoHandlerFactory ioHandlerFactory) {
        super(nThreads, threadFactory, ioHandlerFactory);
    }

    /**
     * 创建 {@link MultiThreadIoEventLoopGroup} 的新实例。
     *
     * @param nThreads          创建的线程数，也即 {@link EventLoop} 的数量。
     * @param executor          用于执行任务的 {@link Executor}。
     * @param chooserFactory    用于在调用 {@link MultiThreadIoEventLoopGroup#next()} 时选择 {@link IoEventLoop} 的 {@link EventExecutorChooserFactory}。
     * @param ioHandlerFactory  用于创建处理 I/O 的 {@link IoHandler} 的 {@link IoHandlerFactory}。
     */
    public MultiThreadIoEventLoopGroup(int nThreads, Executor executor,
                                       EventExecutorChooserFactory chooserFactory,
                                       IoHandlerFactory ioHandlerFactory) {
        super(nThreads, executor, chooserFactory, ioHandlerFactory);
    }

    /**
     * 创建 {@link MultiThreadIoEventLoopGroup} 的新实例。
     * 此构造函数允许传递额外的参数给 {@link #newChild(Executor, Object...)} 方法。
     *
     * @param nThreads          创建的线程数，也即 {@link EventLoop} 的数量。
     * @param executor          用于执行任务的 {@link Executor}。
     * @param ioHandlerFactory  用于创建处理 I/O 的 {@link IoHandler} 的 {@link IoHandlerFactory}。
     * @param args              传递给 {@link #newChild(Executor, Object...)} 方法的额外参数。
     *                         这些参数会与 {@code ioHandlerFactory} 一起传递，用于自定义 {@link IoEventLoop} 的创建。
     */
    protected MultiThreadIoEventLoopGroup(int nThreads, Executor executor,
                                          IoHandlerFactory ioHandlerFactory, Object... args) {
        super(nThreads, executor, combine(ioHandlerFactory, args)); // 将 ioHandlerFactory 和其他参数合并后传递给父类
    }

    /**
     * 创建 {@link MultiThreadIoEventLoopGroup} 的新实例。
     * 此构造函数允许传递额外的参数给 {@link #newChild(Executor, Object...)} 方法。
     *
     * @param nThreads          创建的线程数，也即 {@link EventLoop} 的数量。
     * @param threadFactory     用于创建线程的 {@link ThreadFactory}。
     * @param ioHandlerFactory  用于创建处理 I/O 的 {@link IoHandler} 的 {@link IoHandlerFactory}。
     * @param args              传递给 {@link #newChild(Executor, Object...)} 方法的额外参数。
     */
    protected MultiThreadIoEventLoopGroup(int nThreads, ThreadFactory threadFactory,
                                          IoHandlerFactory ioHandlerFactory, Object... args) {
        super(nThreads, threadFactory, combine(ioHandlerFactory, args));
    }

    /**
     * 创建 {@link MultiThreadIoEventLoopGroup} 的新实例。
     * 此构造函数允许传递额外的参数给 {@link #newChild(Executor, Object...)} 方法。
     *
     * @param nThreads          创建的线程数，也即 {@link EventLoop} 的数量。
     * @param threadFactory     用于创建线程的 {@link ThreadFactory}。
     * @param ioHandlerFactory  用于创建处理 I/O 的 {@link IoHandler} 的 {@link IoHandlerFactory}。
     * @param chooserFactory    用于选择 {@link EventLoop} 的 {@link EventExecutorChooserFactory}。
     * @param args              传递给 {@link #newChild(Executor, Object...)} 方法的额外参数。
     */
    protected MultiThreadIoEventLoopGroup(int nThreads, ThreadFactory threadFactory,
                                          IoHandlerFactory ioHandlerFactory,
                                          EventExecutorChooserFactory chooserFactory,
                                          Object... args) {
        super(nThreads, threadFactory, chooserFactory, combine(ioHandlerFactory, args));
    }

    /**
     * 创建 {@link MultiThreadIoEventLoopGroup} 的新实例。
     * 此构造函数允许传递额外的参数给 {@link #newChild(Executor, Object...)} 方法。
     *
     * @param nThreads          创建的线程数，也即 {@link EventLoop} 的数量。
     * @param executor          用于执行任务的 {@link Executor}。
     * @param ioHandlerFactory  用于创建处理 I/O 的 {@link IoHandler} 的 {@link IoHandlerFactory}。
     * @param chooserFactory    用于选择 {@link EventLoop} 的 {@link EventExecutorChooserFactory}。
     * @param args              传递给 {@link #newChild(Executor, Object...)} 方法的额外参数。
     */
    protected MultiThreadIoEventLoopGroup(int nThreads, Executor executor,
                                          IoHandlerFactory ioHandlerFactory,
                                          EventExecutorChooserFactory chooserFactory,
                                          Object... args) {
        super(nThreads, executor, chooserFactory, combine(ioHandlerFactory, args));
    }

    // 返回类型应该是 IoEventLoop，但我们选择 EventLoop 以允许我们在不破坏 API 的情况下引入 IoHandle 概念。
    // 这是为了保持向后兼容性，同时逐步引入新的 I/O 处理机制。
    @Override
    protected EventLoop newChild(Executor executor, Object... args) throws Exception {
        // 从参数数组的第一个元素获取 IoHandlerFactory。
        // 这是因为 combine 方法将 ioHandlerFactory放在了参数数组的开头。
        IoHandlerFactory handlerFactory = (IoHandlerFactory) args[0];
        Object[] argsCopy;
        if (args.length > 1) {
            // 如果除了 IoHandlerFactory 外还有其他参数，则复制这些参数。
            argsCopy = new Object[args.length - 1];
            System.arraycopy(args, 1, argsCopy, 0, argsCopy.length);
        } else {
            // 如果没有其他参数，则使用空数组。
            argsCopy = EmptyArrays.EMPTY_OBJECTS;
        }
        // 调用重载的 newChild 方法，传入分离出的 IoHandlerFactory 和其余参数。
        return newChild(executor, handlerFactory, argsCopy);
    }

    /**
     * 使用给定的 {@link Executor} 和 {@link IoHandlerFactory} 创建一个新的 {@link IoEventLoop}。
     * 这个方法是实际创建 {@link IoEventLoop} 实例的地方，子类可以覆盖此方法以创建自定义的 {@link IoEventLoop} 类型。
     *
     * @param executor              应该用于处理任务和 I/O 执行的 {@link Executor}。
     * @param ioHandlerFactory      应该用于获取处理 I/O 的 {@link IoHandler} 的 {@link IoHandlerFactory}。
     * @param args                  构造函数传递的额外参数。这些参数可以用于进一步配置创建的 {@link IoEventLoop}。
     *                              {@code @SuppressWarnings("unused")} 注解表明这些参数当前未被此默认实现使用，但可能被子类使用。
     * @return                      创建的 {@link IoEventLoop} 实例。
     *                              默认情况下，返回一个新的 {@link SingleThreadIoEventLoop} 实例。
     */
    protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory,
                                   @SuppressWarnings("unused") Object... args) {
        return new SingleThreadIoEventLoop(this, executor, ioHandlerFactory);
    }

    @Override
    public IoEventLoop next() {
        // 从父类获取下一个 EventLoop，并将其强制转换为 IoEventLoop。
        // 因为这个类专门处理 IoEventLoop，所以这个转换是安全的。
        return (IoEventLoop) super.next();
    }

    /**
     * 将 {@link IoHandlerFactory} 和其他参数合并到一个对象数组中。
     * 这个方法主要用于将构造函数接收到的 {@code ioHandlerFactory} 和可变参数 {@code args}
     * 整合后传递给父类的构造函数或 {@link #newChild(Executor, Object...)} 方法。
     *
     * @param handlerFactory 要合并的 {@link IoHandlerFactory}。
     * @param args           要合并的其他参数数组。
     * @return 一个新的对象数组，其中第一个元素是 {@code handlerFactory}，后续元素是 {@code args} 中的元素。
     */
    private static Object[] combine(IoHandlerFactory handlerFactory, Object... args) {
        List<Object> combinedList = new ArrayList<Object>();
        combinedList.add(handlerFactory); // 将 IoHandlerFactory 作为第一个元素添加
        if (args != null) {
            Collections.addAll(combinedList, args); // 添加其余参数
        }
        return combinedList.toArray(new Object[0]); // 转换为对象数组
    }
}

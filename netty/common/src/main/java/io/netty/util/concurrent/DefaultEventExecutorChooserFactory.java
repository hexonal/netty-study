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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link EventExecutorChooserFactory} 的默认实现，它使用简单的轮询方式来选择下一个 {@link EventExecutor}。
 * 这个工厂类提供了两种选择器：
 * 1. {@link PowerOfTwoEventExecutorChooser}：当执行器数量是2的幂时使用，通过位运算进行选择，效率更高。
 * 2. {@link GenericEventExecutorChooser}：当执行器数量不是2的幂时使用，通过取模运算进行选择。
 */
public final class DefaultEventExecutorChooserFactory implements EventExecutorChooserFactory {

    /**
     * {@link DefaultEventExecutorChooserFactory} 的单例实例。
     */
    public static final DefaultEventExecutorChooserFactory INSTANCE = new DefaultEventExecutorChooserFactory();

    private DefaultEventExecutorChooserFactory() { }

    @Override
    public EventExecutorChooser newChooser(EventExecutor[] executors) {
        if (isPowerOfTwo(executors.length)) {
            // 如果执行器数量是2的幂，则使用 PowerOfTwoEventExecutorChooser 以获得更好的性能。
            // 位运算通常比取模运算更快。
            return new PowerOfTwoEventExecutorChooser(executors);
        } else {
            // 否则，使用通用的 GenericEventExecutorChooser。
            return new GenericEventExecutorChooser(executors);
        }
    }

    /**
     * 检查给定的整数是否是2的幂。
     * @param val 要检查的整数。
     * @return 如果 {@code val} 是2的幂，则返回 {@code true}；否则返回 {@code false}。
     */
    private static boolean isPowerOfTwo(int val) {
        // 一个数如果是2的幂，那么它的二进制表示中只有一个1。
        // val & -val 会保留 val 二进制表示中最右边的1，并将其余位置零。
        // 例如：val = 8 (1000), -val = -8 (二进制补码表示为 ...11111000)
        // val & -val = 1000 & ...11111000 = 1000 (即 8)
        // 如果 val = 6 (0110), -val = -6 (二进制补码表示为 ...11111010)
        // val & -val = 0110 & ...11111010 = 0010 (即 2), 不等于 val
        return (val & -val) == val;
    }

    /**
     * 当执行器数量是2的幂时使用的 {@link EventExecutorChooser} 实现。
     * 它使用位运算（按位与）来代替取模运算，以提高选择下一个 {@link EventExecutor} 的效率。
     */
    private static final class PowerOfTwoEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        PowerOfTwoEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {
            // 使用 getAndIncrement() 获取当前值并自增，然后通过位与操作选择执行器。
            // executors.length - 1 是一个掩码，例如，如果 length 是 8，则掩码是 7 (0111)。
            // idx & (executors.length - 1) 的结果等效于 idx % executors.length，但效率更高。
            return executors[idx.getAndIncrement() & executors.length - 1];
        }
    }

    /**
     * 通用的 {@link EventExecutorChooser} 实现，当执行器数量不是2的幂时使用。
     * 它使用取模运算来选择下一个 {@link EventExecutor}。
     */
    private static final class GenericEventExecutorChooser implements EventExecutorChooser {
        // 使用 'long' 类型的计数器是为了避免在32位整数溢出边界时出现非轮询行为。
        // 64位的 long 通过将溢出点推向遥远的未来，解决了这个问题，
        // 使得实际系统中几乎不会遇到这种情况。
        private final AtomicLong idx = new AtomicLong();
        private final EventExecutor[] executors;

        GenericEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {
            // 使用 getAndIncrement() 获取当前值并自增，然后通过取模操作选择执行器。
            // Math.abs() 用于确保索引为正数，尽管 AtomicLong 应该不会产生负数，这是一种防御性编程。
            return executors[(int) Math.abs(idx.getAndIncrement() % executors.length)];
        }
    }
}

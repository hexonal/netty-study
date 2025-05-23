/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import io.netty.util.internal.UnstableApi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation which uses simple round-robin to choose next {@link EventExecutor}.
 */
@UnstableApi
public final class DefaultEventExecutorChooserFactory implements EventExecutorChooserFactory {

    public static final DefaultEventExecutorChooserFactory INSTANCE = new DefaultEventExecutorChooserFactory();

    private DefaultEventExecutorChooserFactory() { }


    // executors 就是 反应器组里边的 children
    @SuppressWarnings("unchecked")
    @Override
    public EventExecutorChooser newChooser(EventExecutor[] executors) {
        if (isPowerOfTwo(executors.length)) {
            return new PowerOfTwoEventExecutorChooser(executors);
        } else {
            return new GenericEventExecutorChooser(executors);
        }
    }

    // val 是不是 2个幂次方  ，比如1 2 4 8 16
    private static boolean isPowerOfTwo(int val) {
        return (val & -val) == val;
    }


    // 位运算 取模 轮询
    private static final class PowerOfTwoEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        PowerOfTwoEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {

            //  executors.length  为 2的幂次方
            //   假如 executors.length=8,  executors.length -1 =7 , 意味着后面的 3位全是 1， 二进制为 111
            //  executors.length= 16= 2^4 , 2^4-1 = 1111
            //  executors.length= 32= 2^5 , 2^5-1 = 11111

            // 高性能的取模
            return executors[idx.getAndIncrement() &  executors.length - 1 ];
        }
    }

    // 简单的轮询 策略
    private static final class GenericEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        GenericEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {

            // 简单的轮询
            // 使用的算法，是累加 取模
            // 0  1 2  3.... executors.length -1
            return executors[Math.abs(idx.getAndIncrement() % executors.length)];
        }
    }
}

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
package io.netty.channel;

import io.netty.util.IntSupplier;

/**
 * Default select strategy.
 */
final class DefaultSelectStrategy implements SelectStrategy {
    static final SelectStrategy INSTANCE = new DefaultSelectStrategy();

    private DefaultSelectStrategy() { }

    // 返回 io 事件查询策略
    @Override
    public int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception {

        //如果有普通事件，则返回selector查询出来的 感兴趣的IO事件数量
        // 所以接下来就会有一个 非阻塞查询 selector.selectNow()

        //如果没有普通事件，则返回 表示使用Select进行阻塞式的策略

        //下面这一行，才是原始的代码
//        return hasTasks ? selectSupplier.get() : SelectStrategy.SELECT;


        int ioEvents = selectSupplier.get();


        // 如果有任务， hasTasks 为true，下一步一定进行 查询的  落空处理   fall through
        // 3.1:  strategy =ioEvents > 0  表示查到了io事件，  接下来 ， 要进行io处理，还要进行任务处理
        // 3.2:  strategy =ioEvents == 0 表示没有查到了io事件，但是接下来 ，要进行普通任务 处理

        return hasTasks ? ioEvents : SelectStrategy.SELECT;
    }
}

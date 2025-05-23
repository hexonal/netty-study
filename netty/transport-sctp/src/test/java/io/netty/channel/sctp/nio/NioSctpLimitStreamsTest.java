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
package io.netty.channel.sctp.nio;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.sctp.SctpChannel;
import io.netty.channel.sctp.SctpLimitStreamsTest;
import io.netty.channel.sctp.SctpServerChannel;

public class NioSctpLimitStreamsTest extends SctpLimitStreamsTest {
    @Override
    protected EventLoopGroup newEventLoopGroup() {
        return new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    @Override
    protected Class<? extends SctpChannel> clientClass() {
        return NioSctpChannel.class;
    }

    @Override
    protected Class<? extends SctpServerChannel> serverClass() {
        return NioSctpServerChannel.class;
    }
}

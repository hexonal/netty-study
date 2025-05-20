/*
 * Copyright 2015 The Netty Project
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
package io.netty.bootstrap;

import io.netty.channel.*;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.spi.SelectorProvider;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ServerBootstrapWithEventLoopTest {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrapWithEventLoopTest.class);

    @Test
    public void testNioEventLoopGroupWithNoKeySetOptimization() throws Exception {
        System.setProperty("io.netty.noKeySetOptimization", String.valueOf(true));
        EventLoopGroup group = new NioEventLoopGroup(1);
        testServerAndClient(group);
    }
   @Test
    public void testNioEventLoopGroup() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        testServerAndClient(group);
    }

    @Test
    public void testNioEventLoopGroupWithSelectStrategy() throws Exception {
        SelectStrategyFactory selectStrategyFactory = new SelectStrategyFactory() {
            @Override
            public SelectStrategy newSelectStrategy() {
                return new SelectStrategy() {


                    @Override
                    public int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception {
                        //如果有普通事件，则返回selector查询出来的 感兴趣的IO事件数量
                        // 所以接下来就会有一个 非阻塞查询 selector.selectNow()

                        //如果没有普通事件，则返回 表示使用Select进行阻塞式的策略

//        return hasTasks ? selectSupplier.get() : SelectStrategy.SELECT;

//                        int ioEvents = selectSupplier.get();
//                        return hasTasks ? ioEvents : SelectStrategy.SELECT;
                        return SelectStrategy.SELECT;
                    }
                };
            }
        };

        EventLoopGroup group = new NioEventLoopGroup(1, new DefaultThreadFactory("疯狂创客圈-ioPool"),
                SelectorProvider.provider(), selectStrategyFactory);


        testServerAndClient(group);
    }

    private static void testServerAndClient(EventLoopGroup group) throws Exception {

        InetSocketAddress addr = new InetSocketAddress(9000);
        final CountDownLatch readLatch = new CountDownLatch(1);
        final CountDownLatch initLatch = new CountDownLatch(1);


        final ChannelHandler handler = new ChannelInboundHandlerAdapter() {

            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                logger.debug(" channelActive ");
                super.channelActive(ctx);
            }

            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                initLatch.countDown();
                logger.debug(" handlerAdded ");
                super.handlerAdded(ctx);
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                logger.debug(" channelRead ");
                readLatch.countDown();
                super.channelRead(ctx, msg);
            }
        };

        Channel sch = null;
        Channel cch = null;
        try {
            ServerBootstrap sb = new ServerBootstrap();
            sb.channel(NioServerSocketChannel.class)
                    .group(group)
                    .childHandler(new ChannelInboundHandlerAdapter()); //传输通道
            sb.handler(new ChannelInitializer<Channel>() { //监听通道 boss
                @Override
                protected void initChannel(Channel ch) throws Exception {
                    ch.pipeline().addLast(handler);
                }
            });

            EventLoopGroup cgroup = new NioEventLoopGroup(1);
            Bootstrap cb = new Bootstrap();
            cb.group(cgroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInboundHandlerAdapter());

            sch = sb.bind(addr).syncUninterruptibly().channel();

            cch = cb.connect(addr).syncUninterruptibly().channel();

            initLatch.await();
            readLatch.await();
        } finally {
            if (sch != null) {
                sch.close().syncUninterruptibly();
            }
            if (cch != null) {
                cch.close().syncUninterruptibly();
            }
            group.shutdownGracefully();
        }
    }
}

package io.netty.bootstrap.s2025_5_22;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 客户端的启动类
 *
 * @author flink
 * @date 22 5月 2025 16:10
 */
public class ServerBootstrapStudy {
	EventLoopGroup group;

	
	public static SelectStrategyFactory initSelectStrategyFactory() {
		return new SelectStrategyFactory() {
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
		
	}
	
	public static void main(String[] args) {
		// 服务端启动示例
		Executor defaultThreadFactory = Executors.newCachedThreadPool();
		SelectStrategyFactory selectStrategyFactory = initSelectStrategyFactory();
		IoHandlerFactory ioHandlerFactory=new IoHandlerFactory() {
			@Override
			public IoHandler newIoHandler() {
				return new NioIoHandler();
			}
		};
		new MultiThreadIoEventLoopGroup(1, defaultThreadFactory, SelectorProvider.provider(),  );
		ServerBootstrap b = new ServerBootstrap();
		b.group(bossGroup, workerGroup)           // 主从线程组
		 .channel(NioServerSocketChannel.class)   // 服务端通道类型
		 .childHandler(new ChannelInitializer<SocketChannel>() {
			 @Override
			 protected void initChannel(SocketChannel ch) throws Exception {
				
			 }
			 // 子连接处理器配置
		 });
		ChannelFuture f = b.bind(port)
		                   .sync();    // 绑定端口
		
	}
}
package io.netty.s2025_5_22;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.CharsetUtil;

/**
 * 客户端的启动类
 *
 * @author flink
 * @date 22 5月 2025 16:10
 */
public class ServerBootstrapStudy {

	

	
public static void main(String[] args) throws InterruptedException {
    // 创建Boss和Worker线程组 - 这是面试重点！
    EventLoopGroup bossGroup = new NioEventLoopGroup(1);      // Boss线程：负责accept连接
    EventLoopGroup workerGroup = new NioEventLoopGroup();     // Worker线程：负责处理IO
    
    try {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)                        // 主从线程组
         .channel(NioServerSocketChannel.class)                // NIO服务端通道
         .option(ChannelOption.SO_BACKLOG, 128)               // 连接队列大小
         .childOption(ChannelOption.SO_KEEPALIVE, true)       // 保持连接活跃
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) throws Exception {
                 ChannelPipeline pipeline = ch.pipeline();
                 // 添加处理器
                 pipeline.addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                     @Override
                     protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
                         System.out.println("收到数据: " + msg.toString(CharsetUtil.UTF_8));
                     }
                     
                     @Override
                     public void channelActive(ChannelHandlerContext ctx) throws Exception {
                         System.out.println("客户端连接成功: " + ctx.channel().remoteAddress());
                     }
                 });
             }
         });
        
        // 绑定端口并启动服务
        ChannelFuture f = b.bind(8080).sync();
        System.out.println("Netty服务端启动成功，监听端口: 8080");
        
        // 等待服务端关闭
        f.channel().closeFuture().sync();
    } finally {
        // 优雅关闭线程组
        workerGroup.shutdownGracefully();
        bossGroup.shutdownGracefully();
    }
}
}
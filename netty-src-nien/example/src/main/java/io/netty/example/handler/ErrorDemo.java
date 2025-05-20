package io.netty.example.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;

public class ErrorDemo {

    public static class SimpleInHandlerA extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            System.out.println("入站处理器 A: 被回调 ");
            throw new Exception(" 某些 错误");
        }


    }

    public static class ErrorHandlerA extends ChannelInboundHandlerAdapter {
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
                throws Exception {
            cause.fillInStackTrace();
            ctx.fireExceptionCaught(cause);
        }
    }

    public static class SimpleOutHandlerC extends ChannelOutboundHandlerAdapter {


        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
                throws Exception {
            cause.fillInStackTrace();
            ctx.fireExceptionCaught(cause);
        }
    }

    public static void main(String[] args) {


        ChannelInitializer i = new ChannelInitializer<EmbeddedChannel>() {
            protected void initChannel(EmbeddedChannel ch) {
                ch.pipeline().addLast(new SimpleInHandlerA());
                ch.pipeline().addLast(new InPipeline.SimpleInHandlerB());
                ch.pipeline().addLast(new InPipeline.SimpleInHandlerC());
                ch.pipeline().addLast(new OutPipeline.SimpleOutHandlerA());
                ch.pipeline().addLast(new OutPipeline.SimpleOutHandlerB());
                ch.pipeline().addLast(new SimpleOutHandlerC());
                ch.pipeline().addLast(new ErrorHandlerA());
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(i);
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(1);
        //向通道写一个出站报文
//        channel.writeOutbound(buf);
        channel.writeInbound(buf);

        try {
            Thread.sleep(Integer.MAX_VALUE);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}

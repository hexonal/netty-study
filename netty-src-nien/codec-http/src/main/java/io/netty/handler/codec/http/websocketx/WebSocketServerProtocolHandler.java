/*
 * Copyright 2019 The Netty Project
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
package io.netty.handler.codec.http.websocketx;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AttributeKey;

import java.util.List;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig.DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * 此处理器负责处理运行 WebSocket 服务器所需的所有繁重工作。
 *
 * 它处理 WebSocket 握手以及控制帧（Close、Ping、Pong）的处理。文本和二进制数据帧
 * 将传递到 Pipeline 中的下一个处理器（由您实现）进行处理。
 *
 * 有关用法，请参见 {@code io.netty.example.http.websocketx.html5.WebSocketServer}。
 *
 * 此处理器的实现假定您只想运行 WebSocket 服务器，而不处理其他类型的 HTTP 请求（如 GET 和 POST）。
 * 如果您希望在同一服务器中同时支持 HTTP 请求和 WebSocket，请参阅
 * {@code io.netty.example.http.websocketx.server.WebSocketServer} 示例。
 *
 * 要了解握手何时完成，您可以拦截
 * {@link ChannelInboundHandler#userEventTriggered(ChannelHandlerContext, Object)}
 * 事件，并检查事件是否为 {@link HandshakeComplete} 的实例。该事件将包含有关握手的额外信息，例如请求和选择的子协议。
 */
public class WebSocketServerProtocolHandler extends WebSocketProtocolHandler {

    /**
     * 用于通知握手状态的事件。
     */
    public enum ServerHandshakeStateEvent {
        /**
         * 握手成功完成，通道已升级到 WebSocket。
         *
         * @deprecated 已弃用，推荐使用 {@link HandshakeComplete} 类，它提供有关握手的额外信息。
         */
        @Deprecated
        HANDSHAKE_COMPLETE,

        /**
         * 握手超时。
         */
        HANDSHAKE_TIMEOUT
    }

    /**
     * 握手成功完成，通道已升级到 WebSocket 的事件。
     * 包含有关握手过程的详细信息，如请求 URI、请求头和选定的子协议。
     */
    public static final class HandshakeComplete {
        private final String requestUri; // 握手请求的 URI
        private final HttpHeaders requestHeaders; // 握手请求的 HTTP 头部
        private final String selectedSubprotocol; // 服务器选择的子协议，如果没有则为 null

        /**
         * 构造一个新的 HandshakeComplete 事件实例。
         *
         * @param requestUri          请求的 URI。
         * @param requestHeaders      请求的 HTTP 头部。
         * @param selectedSubprotocol 服务器选择的子协议，如果未选择则为 null。
         */
        HandshakeComplete(String requestUri, HttpHeaders requestHeaders, String selectedSubprotocol) {
            this.requestUri = requestUri;
            this.requestHeaders = requestHeaders;
            this.selectedSubprotocol = selectedSubprotocol;
        }

        /**
         * 返回发起握手的客户端请求的 URI。
         * 
         * @return 请求 URI。
         */
        public String requestUri() {
            return requestUri;
        }

        /**
         * 返回发起握手的客户端请求的 HTTP 头部。
         * 
         * @return HTTP 请求头部。
         */
        public HttpHeaders requestHeaders() {
            return requestHeaders;
        }

        /**
         * 返回服务器在握手期间选择的子协议。
         * 如果没有选择子协议，则返回 {@code null}。
         * 
         * @return 选择的子协议，或 {@code null}。
         */
        public String selectedSubprotocol() {
            return selectedSubprotocol;
        }
    }

    // 用于在 Channel 上存储 WebSocketServerHandshaker 实例的 AttributeKey。
    // HANDSHAKER_ATTR_KEY 用于在 Channel 的属性映射中存储和检索 WebSocketServerHandshaker。
    // 这允许在握手过程的不同阶段共享握手器实例。
    private static final AttributeKey<WebSocketServerHandshaker> HANDSHAKER_ATTR_KEY = AttributeKey
            .valueOf(WebSocketServerHandshaker.class, "HANDSHAKER");

    private final WebSocketServerProtocolConfig serverConfig; // WebSocket 服务器协议配置

    /**
     * 基础构造函数。
     *
     * @param serverConfig 服务器协议配置对象，包含了 WebSocket 路径、子协议、超时等设置。
     *                     不能为空。
     */
    public WebSocketServerProtocolHandler(WebSocketServerProtocolConfig serverConfig) {
        super(checkNotNull(serverConfig, "serverConfig").dropPongFrames(),
                serverConfig.sendCloseFrame(),
                serverConfig.forceCloseTimeoutMillis());
        this.serverConfig = serverConfig;
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径和默认的握手超时时间。
     * 
     * @param websocketPath WebSocket 的路径。
     */
    public WebSocketServerProtocolHandler(String websocketPath) {
        this(websocketPath, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径和握手超时时间。
     * 
     * @param websocketPath          WebSocket 的路径。
     * @param handshakeTimeoutMillis 握手超时时间（毫秒）。
     */
    public WebSocketServerProtocolHandler(String websocketPath, long handshakeTimeoutMillis) {
        this(websocketPath, false, handshakeTimeoutMillis);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径和是否检查路径是否以指定路径开头。
     * 
     * @param websocketPath   WebSocket 的路径。
     * @param checkStartsWith 如果为 true，则检查请求的 URI 是否以 websocketPath 开头，而不仅仅是精确匹配。
     */
    public WebSocketServerProtocolHandler(String websocketPath, boolean checkStartsWith) {
        this(websocketPath, checkStartsWith, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径、是否检查路径前缀和握手超时时间。
     * 
     * @param websocketPath          WebSocket 的路径。
     * @param checkStartsWith        如果为 true，则检查请求的 URI 是否以 websocketPath 开头。
     * @param handshakeTimeoutMillis 握手超时时间（毫秒）。
     */
    public WebSocketServerProtocolHandler(String websocketPath, boolean checkStartsWith, long handshakeTimeoutMillis) {
        this(websocketPath, null, false, 65536, false, checkStartsWith, handshakeTimeoutMillis);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径和支持的子协议。
     * 
     * @param websocketPath WebSocket 的路径。
     * @param subprotocols  支持的子协议列表，以逗号分隔。如果为 null 或空，则表示不支持任何子协议。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols) {
        this(websocketPath, subprotocols, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径、支持的子协议和握手超时时间。
     * 
     * @param websocketPath          WebSocket 的路径。
     * @param subprotocols           支持的子协议列表，以逗号分隔。
     * @param handshakeTimeoutMillis 握手超时时间（毫秒）。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, false, handshakeTimeoutMillis);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径、支持的子协议和是否允许扩展。
     * 
     * @param websocketPath   WebSocket 的路径。
     * @param subprotocols    支持的子协议列表，以逗号分隔。
     * @param allowExtensions 如果为 true，则允许 WebSocket 扩展。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean allowExtensions) {
        this(websocketPath, subprotocols, allowExtensions, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径、支持的子协议、是否允许扩展和握手超时时间。
     * 
     * @param websocketPath          WebSocket 的路径。
     * @param subprotocols           支持的子协议列表，以逗号分隔。
     * @param allowExtensions        如果为 true，则允许 WebSocket 扩展。
     * @param handshakeTimeoutMillis 握手超时时间（毫秒）。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean allowExtensions,
            long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, allowExtensions, 65536, handshakeTimeoutMillis);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径、支持的子协议、是否允许扩展和最大帧大小。
     * 
     * @param websocketPath   WebSocket 的路径。
     * @param subprotocols    支持的子协议列表，以逗号分隔。
     * @param allowExtensions 如果为 true，则允许 WebSocket 扩展。
     * @param maxFrameSize    WebSocket 帧的最大负载大小。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
            boolean allowExtensions, int maxFrameSize) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径、支持的子协议、是否允许扩展、最大帧大小和握手超时时间。
     * 
     * @param websocketPath          WebSocket 的路径。
     * @param subprotocols           支持的子协议列表，以逗号分隔。
     * @param allowExtensions        如果为 true，则允许 WebSocket 扩展。
     * @param maxFrameSize           WebSocket 帧的最大负载大小。
     * @param handshakeTimeoutMillis 握手超时时间（毫秒）。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
            boolean allowExtensions, int maxFrameSize, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, false, handshakeTimeoutMillis);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径、支持的子协议、是否允许扩展、最大帧大小和是否允许掩码不匹配。
     * 
     * @param websocketPath     WebSocket 的路径。
     * @param subprotocols      支持的子协议列表，以逗号分隔。
     * @param allowExtensions   如果为 true，则允许 WebSocket 扩展。
     * @param maxFrameSize      WebSocket 帧的最大负载大小。
     * @param allowMaskMismatch 如果为 true，则允许客户端帧的掩码不匹配（不推荐，除非特殊情况）。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
            boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch,
                DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径、支持的子协议、是否允许扩展、最大帧大小、是否允许掩码不匹配和握手超时时间。
     * 
     * @param websocketPath          WebSocket 的路径。
     * @param subprotocols           支持的子协议列表，以逗号分隔。
     * @param allowExtensions        如果为 true，则允许 WebSocket 扩展。
     * @param maxFrameSize           WebSocket 帧的最大负载大小。
     * @param allowMaskMismatch      如果为 true，则允许客户端帧的掩码不匹配。
     * @param handshakeTimeoutMillis 握手超时时间（毫秒）。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean allowExtensions,
            int maxFrameSize, boolean allowMaskMismatch, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, false,
                handshakeTimeoutMillis);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径、支持的子协议、是否允许扩展、最大帧大小、是否允许掩码不匹配和是否检查路径前缀。
     * 
     * @param websocketPath     WebSocket 的路径。
     * @param subprotocols      支持的子协议列表，以逗号分隔。
     * @param allowExtensions   如果为 true，则允许 WebSocket 扩展。
     * @param maxFrameSize      WebSocket 帧的最大负载大小。
     * @param allowMaskMismatch 如果为 true，则允许客户端帧的掩码不匹配。
     * @param checkStartsWith   如果为 true，则检查请求的 URI 是否以 websocketPath 开头。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
            boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch, boolean checkStartsWith) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, checkStartsWith,
                DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径、支持的子协议、是否允许扩展、最大帧大小、是否允许掩码不匹配、是否检查路径前缀和握手超时时间。
     * 
     * @param websocketPath          WebSocket 的路径。
     * @param subprotocols           支持的子协议列表，以逗号分隔。
     * @param allowExtensions        如果为 true，则允许 WebSocket 扩展。
     * @param maxFrameSize           WebSocket 帧的最大负载大小。
     * @param allowMaskMismatch      如果为 true，则允许客户端帧的掩码不匹配。
     * @param checkStartsWith        如果为 true，则检查请求的 URI 是否以 websocketPath 开头。
     * @param handshakeTimeoutMillis 握手超时时间（毫秒）。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
            boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch,
            boolean checkStartsWith, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, checkStartsWith, true,
                handshakeTimeoutMillis);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径、支持的子协议、是否允许扩展、最大帧大小、是否允许掩码不匹配、是否检查路径前缀和是否丢弃 Pong 帧。
     * 
     * @param websocketPath     WebSocket 的路径。
     * @param subprotocols      支持的子协议列表，以逗号分隔。
     * @param allowExtensions   如果为 true，则允许 WebSocket 扩展。
     * @param maxFrameSize      WebSocket 帧的最大负载大小。
     * @param allowMaskMismatch 如果为 true，则允许客户端帧的掩码不匹配。
     * @param checkStartsWith   如果为 true，则检查请求的 URI 是否以 websocketPath 开头。
     * @param dropPongFrames    如果为 true，则自动丢弃 Pong 帧，不传递给后续处理器。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
            boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch,
            boolean checkStartsWith, boolean dropPongFrames) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, checkStartsWith,
                dropPongFrames, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    /**
     * 构造函数，使用指定的 WebSocket 路径、支持的子协议、是否允许扩展、最大帧大小、是否允许掩码不匹配、是否检查路径前缀、是否丢弃 Pong
     * 帧和握手超时时间。
     * 这是通过参数构建 {@link WebSocketServerProtocolConfig} 的便捷构造函数之一。
     *
     * @param websocketPath          WebSocket 的路径。
     * @param subprotocols           支持的子协议列表，以逗号分隔。
     * @param allowExtensions        如果为 true，则允许 WebSocket 扩展。
     * @param maxFrameSize           WebSocket 帧的最大负载大小。
     * @param allowMaskMismatch      如果为 true，则允许客户端帧的掩码不匹配。
     * @param checkStartsWith        如果为 true，则检查请求的 URI 是否以 websocketPath 开头。
     * @param dropPongFrames         如果为 true，则自动丢弃 Pong 帧。
     * @param handshakeTimeoutMillis 握手超时时间（毫秒）。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean allowExtensions,
            int maxFrameSize, boolean allowMaskMismatch, boolean checkStartsWith,
            boolean dropPongFrames, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, checkStartsWith, dropPongFrames, handshakeTimeoutMillis,
                WebSocketDecoderConfig.newBuilder()
                        .maxFramePayloadLength(maxFrameSize)
                        .allowMaskMismatch(allowMaskMismatch)
                        .allowExtensions(allowExtensions)
                        .build());
    }

    /**
     * 构造函数，使用 WebSocket 路径、子协议、路径检查、Pong 帧丢弃、握手超时和自定义解码器配置。
     * 此构造函数允许更细粒度地控制解码过程。
     *
     * @param websocketPath          WebSocket 的路径。
     * @param subprotocols           支持的子协议列表，以逗号分隔。
     * @param checkStartsWith        如果为 true，则检查 URI 是否以 websocketPath 开头。
     * @param dropPongFrames         如果为 true，则自动丢弃 Pong 帧。
     * @param handshakeTimeoutMillis 握手超时时间（毫秒）。
     * @param decoderConfig          WebSocket 帧解码器的配置。
     */
    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean checkStartsWith,
            boolean dropPongFrames, long handshakeTimeoutMillis,
            WebSocketDecoderConfig decoderConfig) {
        this(WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(websocketPath)
                .subprotocols(subprotocols)
                .checkStartsWith(checkStartsWith)
                .handshakeTimeoutMillis(handshakeTimeoutMillis)
                .dropPongFrames(dropPongFrames)
                .decoderConfig(decoderConfig)
                .build());
    }

    /**
     * 当此处理器被添加到 {@link ChannelPipeline} 时调用。
     * 它会检查 Pipeline 中是否已存在 {@link WebSocketServerProtocolHandshakeHandler} 和
     * {@link Utf8FrameValidator}。
     * 如果不存在，则会自动将它们添加到当前处理器之前，以确保正确的握手处理和 UTF-8 校验顺序。
     *
     * @param ctx {@link ChannelHandlerContext}
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ChannelPipeline cp = ctx.pipeline();
        if (cp.get(WebSocketServerProtocolHandshakeHandler.class) == null) {
            // 在此处理器之前添加 WebSocketHandshakeHandler 以处理握手逻辑。
            cp.addBefore(ctx.name(), WebSocketServerProtocolHandshakeHandler.class.getName(),
                    new WebSocketServerProtocolHandshakeHandler(serverConfig));
        }
        // 如果配置要求进行UTF-8校验，并且Utf8FrameValidator尚未在Pipeline中，则添加它。
        if (serverConfig.decoderConfig().withUTF8Validator() && cp.get(Utf8FrameValidator.class) == null) {
            // 在此处理器之前添加 UFT8 校验处理器。
            cp.addBefore(ctx.name(), Utf8FrameValidator.class.getName(),
                    new Utf8FrameValidator());
        }
    }

    /**
     * 解码传入的 WebSocket 帧。
     * 如果配置了处理关闭帧 ({@link WebSocketServerProtocolConfig#handleCloseFrames()}) 并且收到了
     * {@link CloseWebSocketFrame}，
     * 则会尝试使用与此 Channel 关联的 {@link WebSocketServerHandshaker} 来执行关闭握手。
     * 如果没有找到握手器（例如，初始握手尚未完成或已失败），则直接写入一个空缓冲区并关闭 Channel。
     * 其他类型的帧会传递给父类的 {@code decode} 方法进行处理。
     *
     * @param ctx   {@link ChannelHandlerContext}
     * @param frame 传入的 {@link WebSocketFrame}
     * @param out   用于输出解码后消息的 {@link List}
     * @throws Exception 如果解码过程中发生错误
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) throws Exception {
        if (serverConfig.handleCloseFrames() && frame instanceof CloseWebSocketFrame) {
            WebSocketServerHandshaker handshaker = getHandshaker(ctx.channel());
            if (handshaker != null) {
                frame.retain(); // 保留帧，因为 close 方法是异步的
                handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame);
            } else {
                // 如果没有握手器，直接关闭连接，可能发生在握手未完成时收到关闭帧
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
            return; // 关闭帧已处理，不再传递
        }
        super.decode(ctx, frame, out); // 其他类型的帧由父类处理
    }

    /**
     * 处理在 Pipeline 中发生的异常。
     * 如果异常是 {@link WebSocketHandshakeException}，则向客户端发送一个带有错误消息的 BAD_REQUEST (400)
     * 响应，
     * 然后关闭连接。这通常表示客户端发送的握手请求无效。
     * 对于其他类型的异常，将异常传递给 Pipeline 中的下一个处理器，并关闭当前 Channel。
     * 日志：建议在捕获到 WebSocketHandshakeException 时记录 warn 级别日志，包含异常信息，便于排查客户端问题。
     * 对于其他未知异常，记录 error 级别日志。
     *
     * @param ctx   {@link ChannelHandlerContext}
     * @param cause 捕获到的 {@link Throwable}
     * @throws Exception 如果后续处理器无法处理此异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof WebSocketHandshakeException) {
            // 日志级别：WARN。记录握手异常的详细信息。
            // logger.warn("WebSocket handshake failed: {}", cause.getMessage(), cause);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, HttpResponseStatus.BAD_REQUEST, Unpooled.wrappedBuffer(cause.getMessage().getBytes()));
            ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            // 日志级别：ERROR。记录未预期的异常，并关闭连接。
            // logger.error("Exception caught in WebSocketServerProtocolHandler, closing
            // channel.", cause);
            ctx.fireExceptionCaught(cause); // 将异常传递给下一个处理器
            ctx.close(); // 关闭通道
        }
    }

    /**
     * 从给定的 {@link Channel} 的属性中获取 {@link WebSocketServerHandshaker}。
     * 
     * @param channel 要从中获取握手器的 Channel。
     * @return 如果存在，则返回 {@link WebSocketServerHandshaker}；否则返回 {@code null}。
     */
    static WebSocketServerHandshaker getHandshaker(Channel channel) {
        return channel.attr(HANDSHAKER_ATTR_KEY).get();
    }

    /**
     * 将 {@link WebSocketServerHandshaker} 设置到给定 {@link Channel} 的属性中。
     * 
     * @param channel    要设置握手器的 Channel。
     * @param handshaker 要设置的 {@link WebSocketServerHandshaker}。
     */
    static void setHandshaker(Channel channel, WebSocketServerHandshaker handshaker) {
        channel.attr(HANDSHAKER_ATTR_KEY).set(handshaker);
    }
}

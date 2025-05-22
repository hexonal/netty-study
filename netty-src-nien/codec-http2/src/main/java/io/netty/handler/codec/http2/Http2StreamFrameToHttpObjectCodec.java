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

package io.netty.handler.codec.http2;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.internal.UnstableApi;

import java.util.List;

/**
 * 此处理器用于在 {@link Http2StreamFrame} (HTTP/2 流帧) 和 {@link HttpObject} (HTTP/1.x
 * 对象) 之间进行双向转换。
 * 它可以与 {@link Http2MultiplexCodec} 结合使用，作为适配器，使得 HTTP/2 连接能够向后兼容那些期望
 * {@link HttpObject} 的 {@link ChannelHandler}。
 * <p>
 * 为简单起见，除非整个流是单个头部帧，否则它会转换为分块编码 (chunked encoding)。
 * <p>
 * <b>日志记录考虑:</b>
 * <ul>
 * <li>在 {@code decode} 和 {@code encode} 方法中，对于转换逻辑的关键步骤或遇到非预期帧类型时，可以添加 DEBUG
 * 级别日志。</li>
 * <li>在处理 100-Continue 响应或头部验证失败时，可以添加 WARN 级别日志。</li>
 * <li>捕获到 {@link EncoderException} 或其他转换相关的 {@link Http2Exception} 时，应记录 ERROR
 * 或 WARN 级别日志，并包含异常信息。</li>
 * </ul>
 */
@UnstableApi // 标记此 API 为不稳定，未来版本可能会有变更。
@Sharable // 标记此 Handler 可以被多个 ChannelPipeline 安全地共享。
public class Http2StreamFrameToHttpObjectCodec extends MessageToMessageCodec<Http2StreamFrame, HttpObject> {

    // 用于在连接 Channel (通常是父 Channel) 上存储当前 HTTP scheme (http/https) 的 AttributeKey。
    private static final AttributeKey<HttpScheme> SCHEME_ATTR_KEY = AttributeKey.valueOf(HttpScheme.class,
            "STREAMFRAMECODEC_SCHEME");

    private final boolean isServer; // 标记此编解码器是在服务器端还是客户端运行。
    private final boolean validateHeaders; // 标记是否需要验证 HTTP 头部。

    /**
     * 构造一个新的 {@link Http2StreamFrameToHttpObjectCodec} 实例。
     *
     * @param isServer        如果为 {@code true}，则此编解码器用于服务器端；否则用于客户端。
     * @param validateHeaders 如果为 {@code true}，则在转换时验证 HTTP 头部的有效性。
     */
    public Http2StreamFrameToHttpObjectCodec(final boolean isServer,
            final boolean validateHeaders) {
        this.isServer = isServer;
        this.validateHeaders = validateHeaders;
    }

    /**
     * 构造一个新的 {@link Http2StreamFrameToHttpObjectCodec} 实例，默认开启头部验证。
     *
     * @param isServer 如果为 {@code true}，则此编解码器用于服务器端；否则用于客户端。
     */
    public Http2StreamFrameToHttpObjectCodec(final boolean isServer) {
        this(isServer, true);
    }

    /**
     * 检查入站消息是否是 {@link Http2HeadersFrame} 或 {@link Http2DataFrame} 类型。
     * 只有这两种类型的帧会被此解码器处理。
     *
     * @param msg 要检查的消息对象。
     * @return 如果消息是可接受的 HTTP/2 流帧类型，则返回 {@code true}；否则返回 {@code false}。
     * @throws Exception 如果在检查过程中发生错误。
     */
    @Override
    public boolean acceptInboundMessage(Object msg) throws Exception {
        return (msg instanceof Http2HeadersFrame) || (msg instanceof Http2DataFrame);
    }

    /**
     * 将入站的 {@link Http2StreamFrame} 解码为 {@link HttpObject} (例如 {@link HttpRequest},
     * {@link HttpResponse}, {@link HttpContent})。
     *
     * @param ctx   {@link ChannelHandlerContext}
     * @param frame 要解码的 {@link Http2StreamFrame}
     * @param out   解码后的 {@link HttpObject} 将被添加到此列表中
     * @throws Exception 如果解码过程中发生错误 (例如，无效的 HTTP/2 头部)
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, Http2StreamFrame frame, List<Object> out) throws Exception {
        if (frame instanceof Http2HeadersFrame) {
            Http2HeadersFrame headersFrame = (Http2HeadersFrame) frame;
            Http2Headers headers = headersFrame.headers();
            Http2FrameStream stream = headersFrame.stream();
            // 如果 stream 为 null (例如，在连接级别的帧上，理论上不应到这里)，则 id 设为 0，但这主要用于流帧。
            int id = stream == null ? 0 : stream.id();

            final CharSequence status = headers.status();

            // 特殊处理 100-continue 响应：
            // 即使 Http2HeadersFrame#isEndStream() 为 false，也需要将其解码为 FullHttpResponse，
            // 以便与 HttpObjectAggregator 兼容。
            if (null != status && HttpResponseStatus.CONTINUE.codeAsText().contentEquals(status)) {
                final FullHttpMessage fullMsg = newFullMessage(id, headers, ctx.alloc());
                out.add(fullMsg);
                return;
            }

            if (headersFrame.isEndStream()) {
                // 如果是流的最后一个头部帧 (没有数据内容)
                if (headers.method() == null && status == null && headers.path() == null) {
                    // 这种情况通常对应于只有 TRAILERS 的情况，或者是一个空的响应体结束。
                    // 对于 HTTP/1.1，这对应于一个 LastHttpContent，其内容为空，但可能包含尾部。
                    LastHttpContent last = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, validateHeaders);
                    HttpConversionUtil.addHttp2ToHttpHeaders(id, headers, last.trailingHeaders(),
                            HttpVersion.HTTP_1_1, true, true);
                    out.add(last);
                } else {
                    // 如果头部帧是流的结束，并且包含方法或状态码，则表示这是一个完整的消息（无后续 DATA 帧）。
                    FullHttpMessage full = newFullMessage(id, headers, ctx.alloc());
                    out.add(full);
                }
            } else {
                // 如果头部帧不是流的结束，则创建一个常规的 HttpMessage (HttpRequest 或 HttpResponse)。
                HttpMessage reqOrRes = newMessage(id, headers);
                // 如果没有设置 Content-Length，则假定为分块传输。
                if (!HttpUtil.isContentLengthSet(reqOrRes)) {
                    reqOrRes.headers().add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                }
                out.add(reqOrRes);
            }
        } else if (frame instanceof Http2DataFrame) {
            // 处理 HTTP/2 DATA 帧
            Http2DataFrame dataFrame = (Http2DataFrame) frame;
            if (dataFrame.isEndStream()) {
                // 如果 DATA 帧是流的结束，则转换为 LastHttpContent。
                out.add(new DefaultLastHttpContent(dataFrame.content().retain(), validateHeaders));
            } else {
                // 否则，转换为 DefaultHttpContent。
                out.add(new DefaultHttpContent(dataFrame.content().retain()));
            }
        }
        // 其他类型的 Http2StreamFrame (如 PRIORITY, RST_STREAM 等) 不会在此处处理。
    }

    /**
     * 辅助方法，用于编码 {@link LastHttpContent}。
     * 如果 {@link LastHttpContent} 包含内容或需要一个空的 DATA 帧来表示流结束 (当没有尾部时)，
     * 则会添加一个 {@link Http2DataFrame}。
     * 如果存在尾部，则会添加一个包含这些尾部的 {@link Http2HeadersFrame}。
     *
     * @param last 要编码的 {@link LastHttpContent}。
     * @param out  编码后的 {@link Http2StreamFrame} 将添加到此列表中。
     */
    private void encodeLastContent(LastHttpContent last, List<Object> out) {
        // 检查是否需要一个"填充"的 DATA 帧。
        // 如果 last 不是 FullHttpMessage (意味着它不是头部和内容一次性发送的)，并且没有尾部，
        // 但其内容为空，我们仍然可能需要发送一个空的 DATA 帧来正确结束流。
        // 然而，当前的逻辑是：如果内容可读，或者 (它不是FullHttpMessage 且没有尾部)，就发送DATA帧。
        // 这意味着即使内容为空，只要不是 FullHttpMessage 且没有尾部，也会发送一个空的 Http2DataFrame
        // (isEndStream=false，因为尾部会单独发)。
        // 如果有尾部，则尾部帧的 isEndStream 会是 true。
        boolean hasContent = last.content().isReadable();
        boolean hasTrailers = !last.trailingHeaders().isEmpty();

        if (hasContent || !hasTrailers) {
            // 如果有内容，或者没有尾部（此时即使内容为空，也需要一个 DATA 帧来标记流结束或传递空内容），
            // 则发送一个 DATA 帧。
            // isEndStream 设置为 !hasTrailers，意味着如果后面没有尾部帧了，这个 DATA 帧就是流的结束。
            out.add(new DefaultHttp2DataFrame(last.content().retain(), !hasTrailers));
        }

        if (hasTrailers) {
            // 如果有尾部，将它们转换为 Http2Headers 并作为流的最后一个 HEADERS 帧发送。
            Http2Headers headers = HttpConversionUtil.toHttp2Headers(last.trailingHeaders(), validateHeaders);
            out.add(new DefaultHttp2HeadersFrame(headers, true)); // isEndStream = true
        }
    }

    /**
     * 将出站的 {@link HttpObject} 编码为 {@link Http2StreamFrame}。
     * 此方法将为每个可由此编码器处理的写入消息调用。
     *
     * 注意：非 {@link FullHttpResponse} 的 100-Continue 响应将被拒绝并抛出
     * {@link EncoderException}。
     *
     * @param ctx 此处理器所属的 {@link ChannelHandlerContext}。
     * @param obj 要编码的 {@link HttpObject} 消息。
     * @param out 编码后的 {@link Http2StreamFrame} 应添加到的 {@link List}。
     * @throws Exception 如果发生错误 (例如，无效的 HTTP 对象进行编码)。
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, HttpObject obj, List<Object> out) throws Exception {
        // 特殊处理 100-Continue 响应。
        // HTTP/1.1 中的 100-Continue 通常是一个单独的响应头。
        // 在 HTTP/2 中，它对应一个 HEADERS 帧，其 endStream 标志应为 false，除非它是最终响应的一部分。
        if (obj instanceof HttpResponse) {
            final HttpResponse res = (HttpResponse) obj;
            if (res.status().equals(HttpResponseStatus.CONTINUE)) {
                if (res instanceof FullHttpResponse) {
                    // 必须是 FullHttpResponse，因为它需要转换头部。
                    final Http2Headers headers = toHttp2Headers(ctx, res);
                    // 对于100-Continue，endStream 总是 false，因为它后面还会有最终的响应。
                    out.add(new DefaultHttp2HeadersFrame(headers, false));
                    return; // 100-Continue 已处理，直接返回。
                } else {
                    // 根据 HTTP/2 规范和 Netty 的处理方式，100-Continue 应该作为一个完整的消息来处理，
                    // 以便正确提取头部。如果它不是 FullHttpResponse，则无法安全地转换。
                    throw new EncoderException(
                            HttpResponseStatus.CONTINUE.toString() + " must be a FullHttpResponse");
                }
            }
        }

        if (obj instanceof HttpMessage) {
            // 处理 HttpRequest 或 HttpResponse (非 100-Continue)
            Http2Headers headers = toHttp2Headers(ctx, (HttpMessage) obj);
            boolean noMoreFrames = false; // 标记此 HEADERS 帧是否是流的最后一个帧
            if (obj instanceof FullHttpMessage) {
                FullHttpMessage full = (FullHttpMessage) obj;
                // 如果是 FullHttpMessage，并且其内容不可读且没有尾部，则此 HEADERS 帧就是流的结束。
                noMoreFrames = !full.content().isReadable() && full.trailingHeaders().isEmpty();
            }
            out.add(new DefaultHttp2HeadersFrame(headers, noMoreFrames));
        }

        if (obj instanceof LastHttpContent) {
            // 处理流的最后一个内容部分，可能包含尾部。
            LastHttpContent last = (LastHttpContent) obj;
            encodeLastContent(last, out);
        } else if (obj instanceof HttpContent) {
            // 处理中间的内容部分。
            HttpContent cont = (HttpContent) obj;
            // DATA 帧的 endStream 为 false，因为这不是最后一个内容部分。
            out.add(new DefaultHttp2DataFrame(cont.content().retain(), false));
        }
        // 其他类型的 HttpObject (例如 HttpRequestDecoder 产生的无效消息对象) 不会在此处处理。
    }

    /**
     * 将 {@link HttpMessage} (HttpRequest 或 HttpResponse) 的头部转换为
     * {@link Http2Headers}。
     * 对于 {@link HttpRequest}，会自动设置 `:scheme` 伪头部。
     *
     * @param ctx ChannelHandlerContext，用于确定 scheme。
     * @param msg 要转换的 {@link HttpMessage}。
     * @return 转换后的 {@link Http2Headers}。
     */
    private Http2Headers toHttp2Headers(final ChannelHandlerContext ctx, final HttpMessage msg) {
        if (msg instanceof HttpRequest) {
            // 对于客户端请求，需要添加 :scheme 伪头部。
            // scheme (http/https) 是从连接 Channel 的属性中获取的，该属性在 handlerAdded 中设置。
            msg.headers().set(
                    HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(),
                    connectionScheme(ctx));
        }
        return HttpConversionUtil.toHttp2Headers(msg, validateHeaders);
    }

    /**
     * 根据 HTTP/2 头部创建一个新的 {@link HttpMessage} (HttpRequest 或 HttpResponse)。
     *
     * @param id      流 ID。
     * @param headers HTTP/2 头部。
     * @return 创建的 {@link HttpMessage}。
     * @throws Http2Exception 如果头部转换失败。
     */
    private HttpMessage newMessage(final int id,
            final Http2Headers headers) throws Http2Exception {
        return isServer ? HttpConversionUtil.toHttpRequest(id, headers, validateHeaders)
                : HttpConversionUtil.toHttpResponse(id, headers, validateHeaders);
    }

    /**
     * 根据 HTTP/2 头部创建一个新的 {@link FullHttpMessage} (FullHttpRequest 或
     * FullHttpResponse)。
     * 通常用于头部帧指示流结束 (isEndStream=true) 或处理100-Continue的情况。
     *
     * @param id      流 ID。
     * @param headers HTTP/2 头部。
     * @param alloc   用于分配内容缓冲区的 {@link ByteBufAllocator}。
     * @return 创建的 {@link FullHttpMessage}。
     * @throws Http2Exception 如果头部转换失败。
     */
    private FullHttpMessage newFullMessage(final int id,
            final Http2Headers headers,
            final ByteBufAllocator alloc) throws Http2Exception {
        return isServer ? HttpConversionUtil.toFullHttpRequest(id, headers, alloc, validateHeaders)
                : HttpConversionUtil.toFullHttpResponse(id, headers, alloc, validateHeaders);
    }

    /**
     * 当此处理器被添加到 ChannelPipeline 时调用。
     * 它会尝试确定连接的 HTTP scheme (http 或 https) 并将其存储在连接 Channel 的属性中，
     * 以便后续在编码请求时使用 (例如，设置 :scheme 伪头部)。
     *
     * @param ctx ChannelHandlerContext
     * @throws Exception 如果发生错误
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);

        // 此处理器通常用于 Http2StreamChannel。在这个阶段，SSL 握手应该已经建立。
        // 通过检查父 Channel (连接 Channel) 的 Pipeline 中是否存在 SslHandler 来确定 HTTP scheme 应该足够了，
        // 即使使用了 SniHandler 的情况也适用。
        final Attribute<HttpScheme> schemeAttribute = connectionSchemeAttribute(ctx);
        if (schemeAttribute.get() == null) {
            // 如果 scheme 尚未设置，则根据是否存在 SslHandler 来判断。
            final HttpScheme scheme = isSsl(ctx) ? HttpScheme.HTTPS : HttpScheme.HTTP;
            schemeAttribute.set(scheme);
            // 日志级别：DEBUG。记录检测到的 scheme。
            // logger.debug("Channel {}: Detected HTTP scheme: {}", connectionChannel(ctx),
            // scheme);
        }
    }

    /**
     * 检查给定的 ChannelHandlerContext 关联的连接是否使用了 SSL/TLS。
     * 它通过查找连接 Channel (通常是父 Channel) 的 Pipeline 中是否存在 {@link SslHandler} 来判断。
     *
     * @param ctx 要检查的 ChannelHandlerContext (通常是 Http2StreamChannel 的上下文)。
     * @return 如果连接是安全的 (HTTPS)，则返回 {@code true}；否则返回 {@code false} (HTTP)。
     */
    protected boolean isSsl(final ChannelHandlerContext ctx) {
        final Channel connChannel = connectionChannel(ctx);
        return null != connChannel.pipeline().get(SslHandler.class);
    }

    /**
     * 获取连接的 HTTP scheme。
     * 如果未在 Channel 属性中明确设置，则默认为 HTTP。
     *
     * @param ctx ChannelHandlerContext
     * @return 连接的 {@link HttpScheme} (HTTP 或 HTTPS)。
     */
    private static HttpScheme connectionScheme(ChannelHandlerContext ctx) {
        final HttpScheme scheme = connectionSchemeAttribute(ctx).get();
        return scheme == null ? HttpScheme.HTTP : scheme;
    }

    /**
     * 获取用于存储 HTTP scheme 的 Channel 属性。
     *
     * @param ctx ChannelHandlerContext
     * @return 与 scheme 关联的 {@link Attribute}。
     */
    private static Attribute<HttpScheme> connectionSchemeAttribute(ChannelHandlerContext ctx) {
        final Channel ch = connectionChannel(ctx);
        return ch.attr(SCHEME_ATTR_KEY);
    }

    /**
     * 获取与当前流关联的连接级 Channel (即父 Channel)。
     * 如果上下文的 Channel 本身就是连接 Channel (例如，不是 Http2StreamChannel)，则返回它自身。
     *
     * @param ctx 当前流的 ChannelHandlerContext。
     * @return 连接 Channel。
     */
    private static Channel connectionChannel(ChannelHandlerContext ctx) {
        final Channel ch = ctx.channel();
        return ch instanceof Http2StreamChannel ? ch.parent() : ch;
    }
}

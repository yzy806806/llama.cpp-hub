package org.mark.project.tools.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class ProxyFrontendHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFrontendHandler.class);

    private volatile Channel backendChannel;
    private volatile HttpRequest pendingRequest;
    private volatile long requestBodyBytes;
    private final List<HttpContent> bufferedChunks = new ArrayList<>();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof HttpRequest) {
                handleHttpRequest(ctx, (HttpRequest) msg);
            } else if (msg instanceof HttpContent) {
                handleHttpContent(ctx, (HttpContent) msg);
            } else {
                ctx.fireChannelRead(msg);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void logRequest(HttpRequest request, long bodyBytes) {
        if (!log.isInfoEnabled()) return;
        StringBuilder sb = new StringBuilder(512);
        sb.append("\n>>> --- REQUEST ---\n");
        sb.append(request.method()).append(' ').append(request.uri()).append(' ').append(request.protocolVersion()).append('\n');
        request.headers().forEach(e -> sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n'));
        String creds = AuthUtil.extractCredentials(request);
        sb.append("[decoded-credentials]: ").append(creds != null ? creds : "null").append('\n');
        sb.append("[body: ").append(bodyBytes).append(" bytes]\n");
        sb.append(">>> --- END REQUEST ---");
        log.info(sb.toString());
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, HttpRequest request) {
        pendingRequest = request;
        requestBodyBytes = 0;

        boolean ok = AuthUtil.authenticate(request);

        if (!ok) {
            log.warn("Auth failed from {}", ctx.channel().remoteAddress());
            ctx.writeAndFlush(AuthUtil.unauthorizedResponse());
            ctx.close();
            return;
        }

        log.info("{} {} from {}", request.method(), request.uri(), ctx.channel().remoteAddress());

        if (request.method() == HttpMethod.CONNECT) {
            handleConnect(ctx, request);
        } else {
            handleHttpProxy(ctx, request);
        }
    }

    private void handleHttpContent(ChannelHandlerContext ctx, HttpContent content) {
        requestBodyBytes += content.content().readableBytes();

        if (backendChannel != null && backendChannel.isActive()) {
            content.retain();
            backendChannel.writeAndFlush(content);
        } else {
            content.retain();
            bufferedChunks.add(content);
        }

        if (content instanceof LastHttpContent && pendingRequest != null) {
            logRequest(pendingRequest, requestBodyBytes);
        }
    }

    private void handleConnect(ChannelHandlerContext ctx, HttpRequest request) {
        String host;
        int port;
        String uri = request.uri();
        int colonIndex = uri.lastIndexOf(':');
        if (colonIndex > 0) {
            host = uri.substring(0, colonIndex);
            port = Integer.parseInt(uri.substring(colonIndex + 1));
        } else {
            host = uri;
            port = 443;
        }

        Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new RelayHandler(ctx.channel()));
                    }
                });

        ChannelFuture f = b.connect(host, port);
        backendChannel = f.channel();

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                log.info("CONNECT tunnel established to {}:{}", host, port);
                ctx.writeAndFlush(new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK));

                ctx.pipeline().remove(HttpServerCodec.class);
                ctx.pipeline().remove(ProxyFrontendHandler.class);
                ctx.pipeline().addLast(new RelayHandler(backendChannel));
            } else {
                log.warn("CONNECT to {}:{} failed: {}", host, port, future.cause().getMessage());
                ctx.writeAndFlush(new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY));
                ctx.close();
            }
        });
    }

    private void handleHttpProxy(ChannelHandlerContext ctx, HttpRequest request) {
        String uri = request.uri();
        URI targetUri;
        try {
            targetUri = new URI(uri);
        } catch (Exception e) {
            ctx.writeAndFlush(new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
            return;
        }

        String host = targetUri.getHost();
        int port = targetUri.getPort();
        if (port < 0) {
            port = "https".equals(targetUri.getScheme()) ? 443 : 80;
        }
        final int finalPort = port;

        request.setUri(targetUri.getRawPath());
        if (targetUri.getRawQuery() != null) {
            request.setUri(request.uri() + "?" + targetUri.getRawQuery());
        }
        request.headers().remove(HttpHeaderNames.PROXY_AUTHORIZATION);
        request.headers().set(HttpHeaderNames.HOST, host);
        String connection = request.headers().get(HttpHeaderNames.CONNECTION);
        if (connection != null) {
            for (String token : connection.split(",")) {
                request.headers().remove(token.trim());
            }
        }
        request.headers().remove(HttpHeaderNames.CONNECTION);
        request.headers().set(HttpHeaderNames.VIA, "1.1 proxy");
        String remoteAddr = ctx.channel().remoteAddress().toString();
        if (remoteAddr.startsWith("/")) {
            remoteAddr = remoteAddr.substring(1);
        }
        request.headers().set("X-Forwarded-For", remoteAddr);
        String scheme = targetUri.getScheme();
        request.headers().set("X-Forwarded-Proto", scheme != null ? scheme : "http");
        boolean standardPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        request.headers().set("X-Forwarded-Host", standardPort ? host : host + ":" + port);

        Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new HttpClientCodec());
                        ch.pipeline().addLast(new ProxyBackendHandler(ctx.channel()));
                    }
                });

        ChannelFuture f = b.connect(host, port);
        backendChannel = f.channel();

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                backendChannel.writeAndFlush(request);
                flushBufferedChunks(backendChannel);
            } else {
                log.warn("Backend connection to {}:{} failed: {}", host, finalPort, future.cause().getMessage());
                releaseBufferedChunks();
                ctx.writeAndFlush(new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY));
                ctx.close();
            }
        });
    }

    private void flushBufferedChunks(Channel channel) {
        for (HttpContent chunk : bufferedChunks) {
            channel.writeAndFlush(chunk);
        }
        bufferedChunks.clear();
    }

    private void releaseBufferedChunks() {
        for (HttpContent chunk : bufferedChunks) {
            ReferenceCountUtil.release(chunk);
        }
        bufferedChunks.clear();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (backendChannel != null && backendChannel.isActive()) {
            backendChannel.close();
        }
        releaseBufferedChunks();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Frontend error", cause);
        releaseBufferedChunks();
        ctx.close();
    }
}

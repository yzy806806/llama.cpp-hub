package org.mark.llamacpp.crawler;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.GenericFutureListener;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP client built on Netty, with proper proxy authentication support.
 *
 * <p>Features:
 * <ul>
 *   <li>GET / POST / PUT / DELETE</li>
 *   <li>Custom headers</li>
 *   <li>Connect and read timeout</li>
 *   <li>HTTP and HTTPS (with CONNECT tunnel)</li>
 *   <li>Proxy support with Basic authentication</li>
 *   <li>Response body as {@code String} or {@code byte[]}</li>
 * </ul>
 */
public final class NettyHttpUtils {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10 * 30);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private static final int MAX_AGGREGATE_LENGTH = 10 * 1024 * 1024;

    private NettyHttpUtils() {}

    // -----------------------------------------------------------------------
    // Response record
    // -----------------------------------------------------------------------

    /**
     * Immutable HTTP response.
     */
    public record Response(int statusCode, byte[] body, Map<String, List<String>> headers) {

        /**
         * Returns the body decoded as a UTF-8 string.
         */
        public String bodyAsString() {
            return new String(body(), DEFAULT_CHARSET);
        }

        /**
         * Returns the body decoded with the given charset.
         */
        public String bodyAsString(Charset charset) {
            return new String(body(), charset);
        }

        /**
         * Returns the first value of the given response header, or {@code null}.
         */
        public String header(String name) {
            if (headers() == null) return null;
            return headers().entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst()
                    .map(e -> e.getValue().isEmpty() ? null : e.getValue().get(0))
                    .orElse(null);
        }

        /**
         * Returns true if the status code is in the 2xx range.
         */
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    /**
     * Starts building an HTTP request.
     */
    public static Request request(String url) {
        return new Request(url);
    }

    /**
     * Fluent builder for configuring and executing an HTTP request.
     */
    public static class Request {

        private final String url;
        private String method = "GET";
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] requestBody;
        private boolean followRedirects = true;
        private ProxyConfig proxyConfig;
        private boolean validateStatus = true;
        private int expectedStatus;

        Request(String url) {
            this.url = url;
        }

        public Request method(String method) {
            this.method = method.toUpperCase();
            return this;
        }

        public Request connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        public Request connectTimeout(int seconds) {
            return connectTimeout(Duration.ofSeconds(seconds));
        }

        public Request readTimeout(Duration timeout) {
            this.readTimeout = timeout;
            return this;
        }

        public Request readTimeout(int seconds) {
            return readTimeout(Duration.ofSeconds(seconds));
        }

        public Request header(String name, String value) {
            if (name != null && value != null) {
                headers.put(name, value);
            }
            return this;
        }

        public Request headers(Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        public Request body(String body) {
            if (body != null) {
                this.requestBody = body.getBytes(DEFAULT_CHARSET);
            }
            return this;
        }

        public Request body(byte[] body) {
            this.requestBody = body;
            return this;
        }

        public Request jsonBody(String json) {
            this.requestBody = json.getBytes(DEFAULT_CHARSET);
            headers.put("Content-Type", "application/json; charset=utf-8");
            return this;
        }

        public Request followRedirects(boolean follow) {
            this.followRedirects = follow;
            return this;
        }

        public Request skipStatusValidation() {
            this.validateStatus = false;
            return this;
        }

        public Request expectStatus(int expectedStatus) {
            this.validateStatus = true;
            this.expectedStatus = expectedStatus;
            return this;
        }

        public Request proxy(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            return this;
        }

        /**
         * Executes the request and returns the response.
         *
         * @throws IOException if the request fails or status validation fails
         */
        public Response execute() throws IOException {
            try {
                return executeAsync().get(Math.max(connectTimeout.toMillis(), readTimeout.toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Request interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("Request failed", cause);
            } catch (TimeoutException e) {
                throw new IOException("Request timed out", e);
            }
        }

        public CompletableFuture<Response> executeAsync() {
            CompletableFuture<Response> future = new CompletableFuture<>();

            EventLoopGroup group = new NioEventLoopGroup(1);
            future.whenComplete((unused, throwable) -> group.shutdownGracefully());
            try {
                URI requestUri = URI.create(url);
                String scheme = requestUri.getScheme();
                boolean isHttps = "https".equalsIgnoreCase(scheme);
                if (!isHttps && !"http".equalsIgnoreCase(scheme)) {
                    throw new IOException("Unsupported scheme: " + scheme);
                }

                String host = requestUri.getHost();
                if (host == null || host.isBlank()) {
                    throw new IOException("Invalid URL host: " + url);
                }

                int parsedPort = requestUri.getPort();
                final int port = parsedPort == -1 ? (isHttps ? 443 : 80) : parsedPort;

                String requestTarget = buildRequestTarget(requestUri, proxyConfig != null && !isHttps);
                String hostHeader = buildHostHeader(host, port, isHttps);
                String connectHost = proxyConfig != null ? proxyConfig.getHost() : host;
                int connectPort = proxyConfig != null ? proxyConfig.getPort() : port;

                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(group)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ChannelPipeline pipeline = ch.pipeline();
                                pipeline.addLast(new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS));

                                if (proxyConfig != null && isHttps) {
                                    // HTTPS through proxy: send CONNECT first
                                    pipeline.addLast(new HttpClientCodec());
                                    pipeline.addLast(new HttpObjectAggregator(MAX_AGGREGATE_LENGTH));
                                    pipeline.addLast(new ConnectProxyHandler(
                                            requestTarget, hostHeader, host, port,
                                            proxyConfig, headers, requestBody, method, followRedirects,
                                            validateStatus, expectedStatus, future, readTimeout));
                                } else if (isHttps) {
                                    // HTTPS direct: add SSL first
                                    try {
                                      SSLContext sslContext = SSLContext.getDefault();
                                         SSLEngine sslEngine = sslContext.createSSLEngine(host, port);
                                         sslEngine.setUseClientMode(true);
                                        pipeline.addLast(new SslHandler(sslEngine));
                                    } catch (Exception e) {
                                        future.completeExceptionally(new IOException("Failed to create SSL context", e));
                                        return;
                                    }
                                    pipeline.addLast(new HttpClientCodec());
                                    pipeline.addLast(new HttpObjectAggregator(MAX_AGGREGATE_LENGTH));
                                    pipeline.addLast(new HttpChannelHandler(
                                            host, hostHeader, requestTarget, proxyConfig, headers, requestBody, method,
                                            followRedirects, validateStatus, expectedStatus, future, readTimeout, true));
                                } else {
                                    // HTTP direct or HTTP through proxy
                                    pipeline.addLast(new HttpClientCodec());
                                    pipeline.addLast(new HttpObjectAggregator(MAX_AGGREGATE_LENGTH));
                                    pipeline.addLast(new HttpChannelHandler(
                                            host, hostHeader, requestTarget, proxyConfig, headers, requestBody, method,
                                            followRedirects, validateStatus, expectedStatus, future, readTimeout, false));
                                }
                            }
                        })
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis());

               ChannelFuture connectFuture = bootstrap.connect(connectHost, connectPort);
                connectFuture.addListener((GenericFutureListener<ChannelFuture>) future1 -> {
                    if (!future1.isSuccess()) {
                        future.completeExceptionally(new IOException("Failed to connect", future1.cause()));
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
            return future;
        }

        private String buildRequestTarget(URI uri, boolean absoluteForm) {
            String rawPath = uri.getRawPath();
            if (rawPath == null || rawPath.isEmpty()) {
                rawPath = "/";
            }
            if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                rawPath += "?" + uri.getRawQuery();
            }

            if (!absoluteForm) {
                return rawPath;
            }

            String authority = uri.getRawAuthority();
            if (authority == null || authority.isBlank()) {
                authority = buildHostHeader(uri.getHost(), uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort(),
                        "https".equalsIgnoreCase(uri.getScheme()));
            }
            return uri.getScheme() + "://" + authority + rawPath;
        }

        private String buildHostHeader(String host, int port, boolean isHttps) {
            int defaultPort = isHttps ? 443 : 80;
            return port == defaultPort ? host : host + ":" + port;
        }
    }

    // -----------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------

    /**
     * Handles CONNECT proxy authentication for HTTPS requests.
     */
    private static class ConnectProxyHandler extends ChannelInboundHandlerAdapter {
        private final String requestTarget;
        private final String hostHeader;
        private final String targetHost;
        private final int targetPort;
        private final Map<String, String> headers;
        private final byte[] requestBody;
        private final String method;
        private final boolean followRedirects;
        private final boolean validateStatus;
        private final int expectedStatus;
        private final CompletableFuture<Response> future;
        private final Duration readTimeout;
        private final ProxyConfig proxyConfig;

        ConnectProxyHandler(String requestTarget, String hostHeader, String targetHost, int targetPort,
                           ProxyConfig proxyConfig, Map<String, String> headers,
                           byte[] requestBody, String method,
                           boolean followRedirects, boolean validateStatus,
                           int expectedStatus, CompletableFuture<Response> future,
                           Duration readTimeout) {
            this.requestTarget = requestTarget;
            this.hostHeader = hostHeader;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.headers = headers;
            this.requestBody = requestBody;
            this.method = method;
            this.followRedirects = followRedirects;
            this.validateStatus = validateStatus;
            this.expectedStatus = expectedStatus;
            this.future = future;
            this.readTimeout = readTimeout;
            this.proxyConfig = proxyConfig;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            sendConnect(ctx);
        }

        private void sendConnect(ChannelHandlerContext ctx) {
            FullHttpRequest connectRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.CONNECT,
                    targetHost + ":" + targetPort);

            connectRequest.headers().set(HttpHeaderNames.HOST, targetHost + ":" + targetPort);

            if (proxyConfig != null && proxyConfig.hasAuth()) {
                String credentials = proxyConfig.getUsername() + ":" + proxyConfig.getPassword();
                String encoded = java.util.Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                connectRequest.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + encoded);
            }

            ctx.writeAndFlush(connectRequest);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpResponse) {
                FullHttpResponse response = (FullHttpResponse) msg;
                int status = response.status().code();

                if (status == 200) {
                    // Tunnel established, switch to HTTPS
                    ctx.pipeline().remove(this);
                    ctx.pipeline().remove(HttpClientCodec.class);
                    ctx.pipeline().remove(HttpObjectAggregator.class);

                    try {
                        SSLContext sslContext = SSLContext.getDefault();
                        SSLEngine sslEngine = sslContext.createSSLEngine(targetHost, targetPort);
                        sslEngine.setUseClientMode(true);

                        ctx.pipeline().addFirst(new SslHandler(sslEngine));
                        ctx.pipeline().addLast(new HttpClientCodec());
                        ctx.pipeline().addLast(new HttpObjectAggregator(MAX_AGGREGATE_LENGTH));
                        ctx.pipeline().addLast(new HttpChannelHandler(
                                targetHost, hostHeader, requestTarget, null, headers, requestBody, method,
                                followRedirects, validateStatus, expectedStatus, future, readTimeout, true));
                    } catch (Exception e) {
                        future.completeExceptionally(new IOException("Failed to establish SSL tunnel", e));
                    }
                } else if (status == 407 && proxyConfig != null && proxyConfig.hasAuth()) {
                    // 407 but we already sent auth - something wrong
                    future.completeExceptionally(new IOException("Proxy authentication failed: " + status));
                } else {
                    future.completeExceptionally(new IOException("CONNECT failed: " + status));
                }

                response.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            future.completeExceptionally(cause);
            ctx.close();
        }
    }

    /**
     * Handles HTTP requests (direct or through proxy).
     */
    private static class HttpChannelHandler extends ChannelInboundHandlerAdapter {
        private final String hostHeader;
        private final String requestTarget;
        private final ProxyConfig proxyConfig;
        private final Map<String, String> headers;
        private final byte[] requestBody;
        private final String method;
        private final boolean validateStatus;
        private final int expectedStatus;
        private final CompletableFuture<Response> future;
        private final boolean waitForTlsHandshake;
        private final AtomicBoolean sent = new AtomicBoolean(false);

        HttpChannelHandler(String host, String hostHeader, String requestTarget, ProxyConfig proxyConfig,
                          Map<String, String> headers, byte[] requestBody, String method,
                          boolean followRedirects, boolean validateStatus, int expectedStatus,
                          CompletableFuture<Response> future, Duration readTimeout, boolean waitForTlsHandshake) {
            this.hostHeader = hostHeader;
            this.requestTarget = requestTarget;
            this.proxyConfig = proxyConfig;
            this.headers = headers;
            this.requestBody = requestBody;
            this.method = method;
            this.validateStatus = validateStatus;
            this.expectedStatus = expectedStatus;
            this.future = future;
            this.waitForTlsHandshake = waitForTlsHandshake;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            if (!waitForTlsHandshake && sent.compareAndSet(false, true)) {
                sendRequest(ctx);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof SslHandshakeCompletionEvent handshakeEvent) {
                if (handshakeEvent.isSuccess()) {
                    if (sent.compareAndSet(false, true)) {
                        sendRequest(ctx);
                    }
                } else {
                    future.completeExceptionally(new IOException("TLS handshake failed", handshakeEvent.cause()));
                    ctx.close();
                }
                return;
            }
            super.userEventTriggered(ctx, evt);
        }

        private void sendRequest(ChannelHandlerContext ctx) {
            FullHttpRequest request;
            if (requestBody != null) {
                request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.valueOf(method),
                        requestTarget);
                request.content().writeBytes(requestBody);
            } else {
                request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.valueOf(method),
                        requestTarget);
            }

            request.headers().set(HttpHeaderNames.HOST, hostHeader);
            request.headers().set(HttpHeaderNames.USER_AGENT, "Mozilla/5.0 (compatible; NettyHttpUtils/1.0)");
            request.headers().set(HttpHeaderNames.ACCEPT, "*/*");

            if (requestBody != null) {
                request.headers().set(HttpHeaderNames.CONTENT_LENGTH, requestBody.length);
            }

            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.headers().set(entry.getKey(), entry.getValue());
            }

            // Add proxy auth for HTTP (non-CONNECT)
            if (proxyConfig != null && proxyConfig.hasAuth()) {
                String credentials = proxyConfig.getUsername() + ":" + proxyConfig.getPassword();
                String encoded = java.util.Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                request.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + encoded);
            }

            ctx.writeAndFlush(request);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpResponse) {
                FullHttpResponse response = (FullHttpResponse) msg;
                int statusCode = response.status().code();

                Map<String, List<String>> respHeaders = new LinkedHashMap<>();
                response.headers().forEach(entry -> {
                    String key = entry.getKey().toString();
                    String value = entry.getValue().toString();
                respHeaders.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                 });

                byte[] body = ByteBufUtil.getBytes(response.content());

                if (validateStatus) {
                    if (expectedStatus != 0) {
                        if (statusCode != expectedStatus) {
                            future.completeExceptionally(new IOException(
                                    "Expected status " + expectedStatus + " but got " + statusCode + " for " + requestTarget));
                            response.release();
                            return;
                        }
                    } else if (statusCode < 200 || statusCode >= 300) {
                        String bodyPreview = new String(body, StandardCharsets.UTF_8);
                        if (bodyPreview.length() > 800) {
                            bodyPreview = bodyPreview.substring(0, 800) + "...";
                        }
                        future.completeExceptionally(new IOException("HTTP " + statusCode + " " + requestTarget + "\n" + bodyPreview));
                        response.release();
                        return;
                    }
                }

                Response resp = new Response(statusCode, body, respHeaders);
                future.complete(resp);
                response.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            future.completeExceptionally(cause);
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!future.isDone()) {
                future.completeExceptionally(new IOException("Connection closed unexpectedly"));
            }
        }
    }
}

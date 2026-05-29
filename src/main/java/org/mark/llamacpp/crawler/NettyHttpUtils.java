package org.mark.llamacpp.crawler;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.GenericFutureListener;



import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.struct.ProxyConfigData;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final int MAX_REDIRECTS = 5;
    private static final Set<String> SENSITIVE_REDIRECT_HEADERS = Set.of(
            HttpHeaderNames.AUTHORIZATION.toString().toLowerCase(Locale.ROOT),
            HttpHeaderNames.COOKIE.toString().toLowerCase(Locale.ROOT),
            HttpHeaderNames.HOST.toString().toLowerCase(Locale.ROOT)
    );
    private static final ExecutorService REDIRECT_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "http-redirect");
        t.setDaemon(true);
        return t;
    });

    private NettyHttpUtils() {}

    /**
     * Creates an SSL context that trusts all certificates (including self-signed).
     */
    private static SslContext createInsecureSslContext() {
        try {
            return SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSL context", e);
        }
    }

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

        private final String originalUrl;
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
            this.originalUrl = url;
        }

        /**
         * Resolves the effective proxy configuration.
         * If no proxy is explicitly set, attempts to load from global config.
         */
        private ProxyConfig resolveProxy() {
            if (proxyConfig != null) {
                return proxyConfig;
            }
            try {
                ProxyConfigData globalCfg = LlamaServer.getProxyConfig();
                if (globalCfg != null && globalCfg.isEnabled() && globalCfg.getHost() != null && !globalCfg.getHost().trim().isEmpty()) {
                    int port = globalCfg.getPort();
                    if (port > 0) {
                        String user = globalCfg.getUsername();
                        if (user != null && !user.isEmpty()) {
                            return ProxyConfig.http(globalCfg.getHost().trim(), port, user, globalCfg.getPassword() != null ? globalCfg.getPassword() : "");
                        } else {
                            return ProxyConfig.http(globalCfg.getHost().trim(), port);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[NettyHttpUtils] Failed to resolve proxy config: " + e.getMessage());
                e.printStackTrace();
            }
            return null;
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
                return executeAsync().get(
                    Math.max(connectTimeout.toMillis(), readTimeout.toMillis()) * MAX_REDIRECTS,
                    TimeUnit.MILLISECONDS);
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
            // Apply global proxy config if not explicitly set
            ProxyConfig effectiveProxy = resolveProxy();
            if (effectiveProxy != null && proxyConfig == null) {
                proxyConfig = effectiveProxy;
                System.err.println("[NettyHttpUtils] Proxy applied: " + effectiveProxy.getHost() + ":" + effectiveProxy.getPort());
            } else if (proxyConfig == null) {
                System.err.println("[NettyHttpUtils] No proxy configured (effectiveProxy=" + effectiveProxy + ")");
            }
            CompletableFuture<Response> root = new CompletableFuture<>();
            REDIRECT_EXECUTOR.execute(() -> {
                try {
                    RedirectRequestState finalState = executeRedirectChain();
                    root.complete(validateResponse(finalState.response(), finalState.url()));
                } catch (Exception e) {
                    root.completeExceptionally(e);
                }
            });
            return root;
        }

        private RedirectRequestState executeRedirectChain() throws IOException {
            RedirectRequestState current = RedirectRequestState.initial(originalUrl, method, headers, requestBody);
            for (int i = 0; i <= MAX_REDIRECTS; i++) {
                Response resp = executeSingle(current);
                current = current.withResponse(resp);
                if (!shouldFollowRedirect(resp.statusCode())) {
                    return current;
                }
                String location = resp.header("Location");
                if (location == null || location.isBlank()) {
                    return current;
                }
                String nextUrl = resolveRedirectUrl(current.url(), location);
                if (nextUrl == null) {
                    throw new IOException("Invalid redirect Location: " + location);
                }
                current = current.follow(nextUrl, resp.statusCode());
            }
            throw new IOException("Too many redirects (max " + MAX_REDIRECTS + ")");
        }

        private Response validateResponse(Response response, String finalUrl) throws IOException {
            if (!validateStatus || response == null) {
                return response;
            }
            int statusCode = response.statusCode();
            if (expectedStatus != 0) {
                if (statusCode != expectedStatus) {
                    throw new IOException("Expected status " + expectedStatus + " but got " + statusCode + " for " + finalUrl);
                }
                return response;
            }
            if (statusCode >= 200 && statusCode < 300) {
                return response;
            }
            String bodyPreview = new String(response.body(), StandardCharsets.UTF_8);
            if (bodyPreview.length() > 800) {
                bodyPreview = bodyPreview.substring(0, 800) + "...";
            }
            throw new IOException("HTTP " + statusCode + " " + finalUrl + "\n" + bodyPreview);
        }

        private boolean shouldFollowRedirect(int statusCode) {
            return followRedirects && statusCode >= 300 && statusCode < 400;
        }

        private Response executeSingle(RedirectRequestState state) throws IOException {
            CompletableFuture<Response> future = new CompletableFuture<>();
            EventLoopGroup group = new NioEventLoopGroup(1);

            try {
                URI requestUri = URI.create(state.url());
                String scheme = requestUri.getScheme();
                boolean isHttps = "https".equalsIgnoreCase(scheme);
                if (!isHttps && !"http".equalsIgnoreCase(scheme)) {
                    throw new IOException("Unsupported scheme: " + scheme);
                }

                String host = requestUri.getHost();
                if (host == null || host.isBlank()) {
                    throw new IOException("Invalid URL host: " + state.url());
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
                                     pipeline.addLast(new HttpClientCodec());
                                     pipeline.addLast(new HttpObjectAggregator(MAX_AGGREGATE_LENGTH));
                                     pipeline.addLast(new ConnectProxyHandler(
                                             requestTarget, hostHeader, host, port,
                                             proxyConfig, state.headers(), state.requestBody(), state.method(),
                                             future, readTimeout));
                                 } else if (isHttps) {
                                     try {
                                         SslContext sslContext = createInsecureSslContext();
                                         pipeline.addLast(sslContext.newHandler(ch.alloc(), host, port));
                                     } catch (Exception e) {
                                         future.completeExceptionally(new IOException("Failed to create SSL context", e));
                                         return;
                                     }
                                     pipeline.addLast(new HttpClientCodec());
                                     pipeline.addLast(new HttpContentDecompressor());
                                     pipeline.addLast(new HttpObjectAggregator(MAX_AGGREGATE_LENGTH));
                                     pipeline.addLast(new HttpChannelHandler(
                                             hostHeader, requestTarget, proxyConfig, state.headers(),
                                             state.requestBody(), state.method(), future, true));
                                 } else {
                                     pipeline.addLast(new HttpClientCodec());
                                     pipeline.addLast(new HttpContentDecompressor());
                                     pipeline.addLast(new HttpObjectAggregator(MAX_AGGREGATE_LENGTH));
                                     pipeline.addLast(new HttpChannelHandler(
                                             hostHeader, requestTarget, proxyConfig, state.headers(),
                                             state.requestBody(), state.method(), future, false));
                                 }
                            }
                        })
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis());

               ChannelFuture connectFuture = bootstrap.connect(connectHost, connectPort);
                connectFuture.addListener((GenericFutureListener<ChannelFuture>) f -> {
                    if (!f.isSuccess()) {
                        future.completeExceptionally(new IOException("Failed to connect", f.cause()));
                    }
                });

                Response resp = future.get(Math.max(connectTimeout.toMillis(), readTimeout.toMillis()) * 2, TimeUnit.MILLISECONDS);
                group.shutdownGracefully();
                return resp;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                group.shutdownGracefully();
                throw new IOException("Request interrupted", e);
            } catch (ExecutionException e) {
                group.shutdownGracefully();
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("Request failed", cause);
            } catch (TimeoutException e) {
                group.shutdownGracefully();
                throw new IOException("Request timed out", e);
            } catch (IOException e) {
                group.shutdownGracefully();
                throw e;
            }
        }

        private String resolveRedirectUrl(String requestUrl, String location) {
            if (location == null || location.isBlank()) {
                return null;
            }
            try {
                URI resolved = URI.create(requestUrl).resolve(location);
                String scheme = resolved.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    return null;
                }
                return resolved.toString();
            } catch (Exception e) {
                return null;
            }
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

        private record RedirectRequestState(
                String url,
                String method,
                Map<String, String> headers,
                byte[] requestBody,
                Response response
        ) {
            private static RedirectRequestState initial(String url, String method, Map<String, String> headers, byte[] requestBody) {
                return new RedirectRequestState(url, method, new LinkedHashMap<>(headers), requestBody, null);
            }

            private RedirectRequestState withResponse(Response response) {
                return new RedirectRequestState(url, method, headers, requestBody, response);
            }

            private RedirectRequestState follow(String nextUrl, int statusCode) throws IOException {
                URI from = URI.create(url);
                URI to = URI.create(nextUrl);
                boolean sameOrigin = isSameOrigin(from, to);
                Map<String, String> nextHeaders = copyHeadersForRedirect(headers, sameOrigin);

                String nextMethod = method;
                byte[] nextBody = requestBody;
                if (statusCode == 303 || ((statusCode == 301 || statusCode == 302) && "POST".equalsIgnoreCase(method))) {
                    nextMethod = "GET";
                    nextBody = null;
                    removeEntityHeaders(nextHeaders);
                }

                return new RedirectRequestState(nextUrl, nextMethod, nextHeaders, nextBody, null);
            }

            private static Map<String, String> copyHeadersForRedirect(Map<String, String> source, boolean sameOrigin) {
                Map<String, String> copied = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : source.entrySet()) {
                    String lowerName = entry.getKey().toLowerCase(Locale.ROOT);
                    if (!sameOrigin && SENSITIVE_REDIRECT_HEADERS.contains(lowerName)) {
                        continue;
                    }
                    if (HttpHeaderNames.HOST.toString().equalsIgnoreCase(entry.getKey())) {
                        continue;
                    }
                    copied.put(entry.getKey(), entry.getValue());
                }
                return copied;
            }

            private static void removeEntityHeaders(Map<String, String> headers) {
                headers.keySet().removeIf(name ->
                        HttpHeaderNames.CONTENT_LENGTH.toString().equalsIgnoreCase(name)
                                || HttpHeaderNames.CONTENT_TYPE.toString().equalsIgnoreCase(name)
                                || HttpHeaderNames.TRANSFER_ENCODING.toString().equalsIgnoreCase(name));
            }

            private static boolean isSameOrigin(URI first, URI second) {
                if (!Objects.equals(first.getScheme(), second.getScheme())) {
                    return false;
                }
                if (!Objects.equals(first.getHost(), second.getHost())) {
                    return false;
                }
                return effectivePort(first) == effectivePort(second);
            }

            private static int effectivePort(URI uri) {
                if (uri.getPort() != -1) {
                    return uri.getPort();
                }
                return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
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
        private final CompletableFuture<Response> future;
        private final ProxyConfig proxyConfig;

        ConnectProxyHandler(String requestTarget, String hostHeader, String targetHost, int targetPort,
                           ProxyConfig proxyConfig, Map<String, String> headers,
                           byte[] requestBody, String method,
                           CompletableFuture<Response> future,
                           Duration readTimeout) {
            this.requestTarget = requestTarget;
            this.hostHeader = hostHeader;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.headers = headers;
            this.requestBody = requestBody;
            this.method = method;
            this.future = future;
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
                        SslContext sslContext = createInsecureSslContext();
                        ctx.pipeline().addFirst(sslContext.newHandler(ctx.channel().alloc(), targetHost, targetPort));
                        ctx.pipeline().addLast(new HttpClientCodec());
                        ctx.pipeline().addLast(new HttpContentDecompressor());
                        ctx.pipeline().addLast(new HttpObjectAggregator(MAX_AGGREGATE_LENGTH));
                        ctx.pipeline().addLast(new HttpChannelHandler(
                                hostHeader, requestTarget, null, headers, requestBody, method, future, true));
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
        private final CompletableFuture<Response> future;
        private final boolean waitForTlsHandshake;
        private final AtomicBoolean sent = new AtomicBoolean(false);

        HttpChannelHandler(String hostHeader, String requestTarget, ProxyConfig proxyConfig,
                          Map<String, String> headers, byte[] requestBody, String method,
                          CompletableFuture<Response> future, boolean waitForTlsHandshake) {
            this.hostHeader = hostHeader;
            this.requestTarget = requestTarget;
            this.proxyConfig = proxyConfig;
            this.headers = headers;
            this.requestBody = requestBody;
            this.method = method;
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

    // -----------------------------------------------------------------------
    // Streaming download to file
    // -----------------------------------------------------------------------

    /**
     * Progress callback for file download.
     */
    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    /**
     * Starts building a streaming file download request.
     */
    public static DownloadToFileRequest downloadToFile(String url) {
        return new DownloadToFileRequest(url);
    }

    /**
     * Fluent builder for streaming HTTP download directly to a file.
     * Bypasses the aggregate length limit by writing chunks to disk.
     */
    public static class DownloadToFileRequest {
        private final String url;
        private Path targetFile;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration readTimeout = Duration.ofSeconds(60 * 10);
        private final Map<String, String> headers = new LinkedHashMap<>();
        private ProxyConfig proxyConfig;
        private AtomicBoolean cancelled;
        private ProgressListener progressListener;

        DownloadToFileRequest(String url) {
            this.url = url;
        }

        public DownloadToFileRequest targetFile(Path targetFile) {
            this.targetFile = targetFile;
            return this;
        }

        public DownloadToFileRequest connectTimeout(int seconds) {
            this.connectTimeout = Duration.ofSeconds(seconds);
            return this;
        }

        public DownloadToFileRequest readTimeout(int seconds) {
            this.readTimeout = Duration.ofSeconds(seconds);
            return this;
        }

        public DownloadToFileRequest header(String name, String value) {
            if (name != null && value != null) {
                headers.put(name, value);
            }
            return this;
        }

        public DownloadToFileRequest proxy(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            return this;
        }

        public DownloadToFileRequest cancelled(AtomicBoolean cancelled) {
            this.cancelled = cancelled;
            return this;
        }

        public DownloadToFileRequest progressListener(ProgressListener progressListener) {
            this.progressListener = progressListener;
            return this;
        }

        /**
         * Resolves the effective proxy configuration.
         * If no proxy is explicitly set, attempts to load from global config.
         */
        private ProxyConfig resolveProxy() {
            if (proxyConfig != null) {
                return proxyConfig;
            }
            try {
                ProxyConfigData globalCfg = LlamaServer.getProxyConfig();
                if (globalCfg != null && globalCfg.isEnabled() && globalCfg.getHost() != null && !globalCfg.getHost().trim().isEmpty()) {
                    int port = globalCfg.getPort();
                    if (port > 0) {
                        String user = globalCfg.getUsername();
                        if (user != null && !user.isEmpty()) {
                            return ProxyConfig.http(globalCfg.getHost().trim(), port, user, globalCfg.getPassword() != null ? globalCfg.getPassword() : "");
                        } else {
                            return ProxyConfig.http(globalCfg.getHost().trim(), port);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[NettyHttpUtils] Failed to resolve proxy config: " + e.getMessage());
                e.printStackTrace();
            }
            return null;
        }

        public void execute() throws IOException {
            try {
                executeAsync().get(Math.max(connectTimeout.toMillis() + readTimeout.toMillis(), 600_000), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("Download failed", cause);
            } catch (TimeoutException e) {
                throw new IOException("Download timed out", e);
            }
        }

        public CompletableFuture<Void> executeAsync() {
            // Apply global proxy config if not explicitly set
            ProxyConfig effectiveProxy = resolveProxy();
            if (effectiveProxy != null && proxyConfig == null) {
                proxyConfig = effectiveProxy;
            }
            CompletableFuture<Void> future = new CompletableFuture<>();

            if (targetFile == null) {
                future.completeExceptionally(new IOException("targetFile not set"));
                return future;
            }

            EventLoopGroup group = new NioEventLoopGroup(1);
            future.whenComplete((unused, throwable) -> group.shutdownGracefully());

            try {
                URI requestUri = URI.create(url);
                String scheme = requestUri.getScheme();
                boolean isHttps = "https".equalsIgnoreCase(scheme);
                if (!isHttps && !"http".equalsIgnoreCase(scheme)) {
                    future.completeExceptionally(new IOException("Unsupported scheme: " + scheme));
                    return future;
                }

                String host = requestUri.getHost();
                if (host == null || host.isBlank()) {
                    future.completeExceptionally(new IOException("Invalid URL host: " + url));
                    return future;
                }

                int parsedPort = requestUri.getPort();
                final int port = parsedPort == -1 ? (isHttps ? 443 : 80) : parsedPort;

                String requestTarget = buildTarget(requestUri, proxyConfig != null && !isHttps);
                String hostHeader = buildHost(host, port, isHttps);
                String connectHost = proxyConfig != null ? proxyConfig.getHost() : host;
                int connectPort = proxyConfig != null ? proxyConfig.getPort() : port;

                // Ensure parent directory exists
                Path parent = targetFile.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }

                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(group)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ChannelPipeline pipeline = ch.pipeline();
                                pipeline.addLast(new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS));

                              if (proxyConfig != null && isHttps) {
                                     pipeline.addLast(new HttpClientCodec());
                                     pipeline.addLast(new HttpObjectAggregator(MAX_AGGREGATE_LENGTH));
                                     pipeline.addLast(new DownloadConnectProxyHandler(
                                             requestTarget, hostHeader, host, port,
                                             proxyConfig, headers, targetFile, cancelled, progressListener,
                                             future, readTimeout));
                                 } else {
                                     if (isHttps) {
                                         try {
                                             SslContext sslContext = createInsecureSslContext();
                                             pipeline.addLast(sslContext.newHandler(ch.alloc(), host, port));
                                         } catch (Exception e) {
                                             future.completeExceptionally(new IOException("Failed to create SSL context", e));
                                             return;
                                         }
                                     }
                                     pipeline.addLast(new HttpClientCodec());
                                     pipeline.addLast(new DownloadToFileHandler(
                                             host, hostHeader, requestTarget, proxyConfig, headers,
                                             targetFile, cancelled, progressListener, future, readTimeout, isHttps));
                                 }
                            }
                        })
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis());

                ChannelFuture connectFuture = bootstrap.connect(connectHost, connectPort);
                connectFuture.addListener((GenericFutureListener<ChannelFuture>) f -> {
                    if (!f.isSuccess()) {
                        future.completeExceptionally(new IOException("Failed to connect", f.cause()));
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
            return future;
        }

        private String buildTarget(URI uri, boolean absoluteForm) {
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
                authority = buildHost(uri.getHost(), uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort(),
                        "https".equalsIgnoreCase(uri.getScheme()));
            }
            return uri.getScheme() + "://" + authority + rawPath;
        }

        private String buildHost(String host, int port, boolean isHttps) {
            int defaultPort = isHttps ? 443 : 80;
            return port == defaultPort ? host : host + ":" + port;
        }
    }

    /**
     * Handles CONNECT tunnel for download through HTTPS proxy.
     */
    private static class DownloadConnectProxyHandler extends ChannelInboundHandlerAdapter {
        private final String requestTarget;
        private final String hostHeader;
        private final String targetHost;
        private final int targetPort;
        private final ProxyConfig proxyConfig;
        private final Map<String, String> headers;
        private final Path targetFile;
        private final AtomicBoolean cancelled;
        private final ProgressListener progressListener;
        private final CompletableFuture<Void> future;
        private final Duration readTimeout;

        DownloadConnectProxyHandler(String requestTarget, String hostHeader, String targetHost, int targetPort,
                                    ProxyConfig proxyConfig, Map<String, String> headers,
                                    Path targetFile, AtomicBoolean cancelled, ProgressListener progressListener,
                                    CompletableFuture<Void> future, Duration readTimeout) {
            this.requestTarget = requestTarget;
            this.hostHeader = hostHeader;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.proxyConfig = proxyConfig;
            this.headers = headers;
            this.targetFile = targetFile;
            this.cancelled = cancelled;
            this.progressListener = progressListener;
            this.future = future;
            this.readTimeout = readTimeout;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            FullHttpRequest connectRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.CONNECT, targetHost + ":" + targetPort);
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
                response.release();

                if (status == 200) {
                    ctx.pipeline().remove(this);
                    ctx.pipeline().remove(HttpClientCodec.class);
                    ctx.pipeline().remove(HttpObjectAggregator.class);
                    try {
                        SslContext sslContext = createInsecureSslContext();
                        ctx.pipeline().addFirst(sslContext.newHandler(ctx.channel().alloc(), targetHost, targetPort));
                        ctx.pipeline().addLast(new HttpClientCodec());
                        ctx.pipeline().addLast(new DownloadToFileHandler(
                                targetHost, hostHeader, requestTarget, null, headers,
                                targetFile, cancelled, progressListener, future, readTimeout, true));
                    } catch (Exception e) {
                        future.completeExceptionally(new IOException("Failed to establish SSL tunnel", e));
                    }
                } else {
                    future.completeExceptionally(new IOException("CONNECT failed: " + status));
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            future.completeExceptionally(cause);
            ctx.close();
        }
    }

    /**
     * Handles HTTP response and streams content directly to a file.
     * Does NOT use HttpObjectAggregator — reads HttpContent chunks and writes to disk.
     */
    private static class DownloadToFileHandler extends SimpleChannelInboundHandler<HttpObject> {
        private final String hostHeader;
        private final String requestTarget;
        private final ProxyConfig proxyConfig;
        private final Map<String, String> headers;
        private final Path targetFile;
        private final AtomicBoolean cancelled;
        private final ProgressListener progressListener;
        private final CompletableFuture<Void> future;
        private final boolean waitForTlsHandshake;

        private java.io.RandomAccessFile raf;
        private long downloaded = 0;
        private long contentLength = -1;
        private boolean responseStarted = false;
        private final AtomicBoolean sent = new AtomicBoolean(false);

        DownloadToFileHandler(String host, String hostHeader, String requestTarget, ProxyConfig proxyConfig,
                              Map<String, String> headers, Path targetFile, AtomicBoolean cancelled,
                              ProgressListener progressListener, CompletableFuture<Void> future,
                              Duration readTimeout, boolean waitForTlsHandshake) {
            this.hostHeader = hostHeader;
            this.requestTarget = requestTarget;
            this.proxyConfig = proxyConfig;
            this.headers = headers;
            this.targetFile = targetFile;
            this.cancelled = cancelled;
            this.progressListener = progressListener;
            this.future = future;
            this.waitForTlsHandshake = waitForTlsHandshake;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            if (cancelled != null && cancelled.get()) {
                cleanup();
                future.completeExceptionally(new IOException("Download cancelled"));
                ctx.close();
                return;
            }

            if (msg instanceof HttpResponse response) {
                if (!responseStarted) {
                    responseStarted = true;
                    int statusCode = response.status().code();
                    if (statusCode == 200) {
                        String cl = response.headers().get(HttpHeaderNames.CONTENT_LENGTH);
                        if (cl != null) {
                            try {
                                contentLength = Long.parseLong(cl);
                            } catch (NumberFormatException ignore) {
                            }
                        }
                        try {
                            raf = new java.io.RandomAccessFile(targetFile.toFile(), "rw");
                        } catch (IOException e) {
                            future.completeExceptionally(e);
                            ctx.close();
                            return;
                        }
                        emitProgress();
                    } else if (statusCode == 206) {
                        String cl = response.headers().get(HttpHeaderNames.CONTENT_LENGTH);
                        if (cl != null) {
                            try {
                                contentLength = Long.parseLong(cl);
                            } catch (NumberFormatException ignore) {
                            }
                        }
                        try {
                            raf = new java.io.RandomAccessFile(targetFile.toFile(), "rw");
                        } catch (IOException e) {
                            future.completeExceptionally(e);
                            ctx.close();
                            return;
                        }
                        emitProgress();
                    } else {
                        future.completeExceptionally(new IOException("HTTP " + statusCode + " for " + requestTarget));
                        ctx.close();
                        return;
                    }
                }
            } else if (msg instanceof HttpContent content) {
                ByteBuf buf = content.content();
                if (buf.readableBytes() > 0 && raf != null) {
                    try {
                        byte[] bytes = new byte[buf.readableBytes()];
                        buf.readBytes(bytes);
                        raf.write(bytes);
                        downloaded += bytes.length;
                        emitProgress();
                    } catch (IOException e) {
                        future.completeExceptionally(e);
                        cleanup();
                        ctx.close();
                        return;
                    }
                }

                if (content instanceof LastHttpContent) {
                    cleanup();
                    future.complete(null);
                    ctx.close();
                }
            }
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
            FullHttpRequest request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, requestTarget);
            request.headers().set(HttpHeaderNames.HOST, hostHeader);
            request.headers().set(HttpHeaderNames.USER_AGENT, "Mozilla/5.0 (compatible; NettyHttpUtils/1.0)");
            request.headers().set(HttpHeaderNames.ACCEPT, "*/*");
            request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "identity");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.headers().set(entry.getKey(), entry.getValue());
            }
            if (proxyConfig != null && proxyConfig.hasAuth()) {
                String credentials = proxyConfig.getUsername() + ":" + proxyConfig.getPassword();
                String encoded = java.util.Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                request.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + encoded);
            }
            ctx.writeAndFlush(request);
        }

        private void emitProgress() {
            if (progressListener != null) {
                try {
                    progressListener.onProgress(downloaded, contentLength);
                } catch (Exception ignore) {
                }
            }
        }

        private void cleanup() {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignore) {
                }
                raf = null;
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!future.isDone()) {
                cleanup();
                future.completeExceptionally(cause);
            }
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!future.isDone()) {
                cleanup();
                future.completeExceptionally(new IOException("Connection closed unexpectedly"));
            }
        }
    }
}

package org.mark.llamacpp.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.mark.llamacpp.server.LlamaServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.DefaultHttpResponse;

/** 登录页 HTML，浏览器无 API Key 时返回 */
private static final String LOGIN_PAGE_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>llama.cpp-hub</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:system-ui,-apple-system,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;background:#1a1a2e;color:#e0e0e0}
.card{background:#16213e;padding:2rem;border-radius:0.75rem;box-shadow:0 4px 24px rgba(0,0,0,.4);width:90%;max-width:360px}
.card h1{font-size:1.1rem;margin-bottom:1.2rem;text-align:center;color:#e94560}
.card input{width:100%;padding:0.65rem 0.8rem;border:1px solid #333;border-radius:0.4rem;background:#0f3460;color:#e0e0e0;font-size:0.9rem;outline:none}
.card input:focus{border-color:#e94560}
.card button{width:100%;margin-top:0.8rem;padding:0.65rem;border:none;border-radius:0.4rem;background:#e94560;color:#fff;font-size:0.9rem;cursor:pointer}
.card button:hover{background:#c81d45}
.card button:disabled{opacity:.6;cursor:wait}
.err{color:#ff6b6b;font-size:0.8rem;margin-top:0.6rem;text-align:center;min-height:1rem}
</style>
</head>
<body>
<div class="card">
<h1>llama.cpp-hub</h1>
<input type="password" id="key" placeholder="API Key" autofocus />
<button id="btn" onclick="submit()">Login</button>
<div class="err" id="err"></div>
</div>
<script>
function submit(){
var k=document.getElementById('key').value.trim();
if(!k)return;
var b=document.getElementById('btn');b.disabled=true;
var e=document.getElementById('err');e.textContent='';
fetch('/api/sys/setting',{headers:{'Authorization':'Bearer '+k}})
.then(function(r){if(!r.ok)throw 0;return r.json()})
.then(function(){localStorage.setItem('lh-api-key',k);location.reload()})
.catch(function(){e.textContent='Invalid API Key';b.disabled=false})
}
document.getElementById('key').addEventListener('keydown',function(ev){if(ev.key==='Enter')submit()});
</script>
</body>
</html>
""";

/**
 * API Key 安全验证工具。
 * 提供：
 * 1. 常量时间比较（防计时攻击）
 * 2. Bearer / x-api-key / Basic Auth 三种验证方式
 * 3. IP 级别暴力破解防护（5 次失败后封禁 15 分钟）
 */
public class ApiKeyValidator {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyValidator.class);

    /** 最大失败次数 */
    private static final int MAX_FAILURES = 5;
    /** 封禁时长（毫秒） */
    private static final long BAN_DURATION_MS = 15 * 60 * 1000;
    /** 记录清理间隔 */
    private static final long CLEANUP_INTERVAL_MS = 5 * 60 * 1000;

    private static final ConcurrentHashMap<String, AttemptTracker> failedAttempts = new ConcurrentHashMap<>();
    private static volatile long lastCleanup = System.currentTimeMillis();

    static class AttemptTracker {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long lastAttempt;
        volatile long bannedUntil;

        AttemptTracker() {
            this.lastAttempt = System.currentTimeMillis();
        }
    }

    private ApiKeyValidator() {
    }

    /**
     * 验证请求是否携带正确的 API Key。
     * 支持三种方式：
     * - Authorization: Bearer <key>
     * - x-api-key: <key>
     * - Authorization: Basic <base64(任意用户名:key)>
     *
     * @return true 如果验证通过或未启用验证
     */
    public static boolean validate(FullHttpRequest request, String clientIp) {
        if (!LlamaServer.isApiKeyValidationEnabled()) {
            return true;
        }
        String expected = LlamaServer.getApiKey();
        if (expected == null || expected.isBlank()) {
            return false;
        }

        // 检查 IP 是否被封禁
        if (isBanned(clientIp)) {
            logger.warn("IP {} 已被封禁，拒绝访问", clientIp);
            return false;
        }

        boolean ok = doValidate(request, expected);
        if (!ok) {
            recordFailure(clientIp);
        } else {
            clearFailures(clientIp);
        }
        return ok;
    }

    private static boolean doValidate(FullHttpRequest request, String expected) {
        // 1. Bearer token
        String auth = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth != null) {
            if (auth.startsWith("Bearer ")) {
                return constantTimeEquals(auth.substring(7), expected);
            }
            // 2. Basic auth: base64(username:password)，密码 = apiKey
            if (auth.startsWith("Basic ")) {
                try {
                    String decoded = new String(
                            Base64.getDecoder().decode(auth.substring(6)),
                            StandardCharsets.UTF_8);
                    int colon = decoded.indexOf(':');
                    String password = colon >= 0 ? decoded.substring(colon + 1) : decoded;
                    return constantTimeEquals(password, expected);
                } catch (IllegalArgumentException e) {
                    return false;
                }
            }
        }

        // 3. x-api-key header
        String apiKey = request.headers().get("x-api-key");
        if (apiKey != null && !apiKey.isBlank()) {
            return constantTimeEquals(apiKey, expected);
        }

        return false;
    }

    /**
     * 常量时间字符串比较，防止计时攻击。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ba, bb);
    }

    // ========== IP 限速 ==========

    private static boolean isBanned(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        AttemptTracker tracker = failedAttempts.get(ip);
        if (tracker == null) {
            return false;
        }
        if (tracker.bannedUntil > System.currentTimeMillis()) {
            return true;
        }
        // 封禁过期，重置
        if (tracker.bannedUntil > 0 && tracker.bannedUntil <= System.currentTimeMillis()) {
            failedAttempts.remove(ip);
        }
        return false;
    }

    private static void recordFailure(String ip) {
        if (ip == null || ip.isEmpty()) {
            return;
        }
        cleanupIfNeeded();
        AttemptTracker tracker = failedAttempts.computeIfAbsent(ip, k -> new AttemptTracker());
        int count = tracker.count.incrementAndGet();
        tracker.lastAttempt = System.currentTimeMillis();
        if (count >= MAX_FAILURES) {
            tracker.bannedUntil = System.currentTimeMillis() + BAN_DURATION_MS;
            logger.warn("IP {} 连续 {} 次验证失败，封禁 15 分钟", ip, count);
        }
    }

    private static void clearFailures(String ip) {
        if (ip == null || ip.isEmpty()) {
            return;
        }
        failedAttempts.remove(ip);
    }

    private static void cleanupIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanup = now;
        failedAttempts.entrySet().removeIf(entry -> {
            AttemptTracker t = entry.getValue();
            return t.bannedUntil > 0 && t.bannedUntil <= now
                    || t.bannedUntil == 0 && now - t.lastAttempt > BAN_DURATION_MS;
        });
    }

    /**
     * 发送 401 响应。
     * 浏览器请求（Accept: text/html）返回登录页 HTML；
     * API 请求返回 JSON 401。
     */
    public static void sendUnauthorized(ChannelHandlerContext ctx, FullHttpRequest request) {
        String accept = request.headers().get(HttpHeaderNames.ACCEPT);
        boolean isBrowser = accept != null && accept.contains("text/html");

        if (isBrowser) {
            String html = LOGIN_PAGE_HTML;
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            org.mark.llamacpp.server.LlamaServer.setCorsHeaders(response.headers());
            ctx.write(response);
            ctx.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(body));
        } else {
            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
            String body = "{\"error\":{\"message\":\"Unauthorized\",\"type\":\"authentication_error\"}}";
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.getBytes(StandardCharsets.UTF_8).length);
            org.mark.llamacpp.server.LlamaServer.setCorsHeaders(response.headers());
            ctx.write(response);
            ctx.writeAndFlush(io.netty.buffer.Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        }
    }

    /** 旧方法保留兼容，默认走 JSON 401 */
    public static void sendUnauthorized(ChannelHandlerContext ctx) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        String body = "{\"error\":{\"message\":\"Unauthorized\",\"type\":\"authentication_error\"}}";
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.getBytes(StandardCharsets.UTF_8).length);
        org.mark.llamacpp.server.LlamaServer.setCorsHeaders(response.headers());
        ctx.write(response);
        ctx.writeAndFlush(io.netty.buffer.Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
    }

    /**
     * 从请求中提取客户端 IP。
     */
    public static String getClientIp(ChannelHandlerContext ctx, FullHttpRequest request) {
        // 优先从代理头获取
        String forwarded = request.headers().get("x-forwarded-for");
        if (forwarded != null && !forwarded.isEmpty()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        String realIp = request.headers().get("x-real-ip");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp.trim();
        }
        // 从 channel 获取
        if (ctx.channel().remoteAddress() != null) {
            String addr = ctx.channel().remoteAddress().toString();
            // 格式: /192.168.1.1:12345
            int slash = addr.indexOf('/');
            int colon = addr.lastIndexOf(':');
            if (slash >= 0 && colon > slash) {
                return addr.substring(slash + 1, colon);
            }
        }
        return "";
    }
}

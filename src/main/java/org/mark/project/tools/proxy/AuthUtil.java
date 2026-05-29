package org.mark.project.tools.proxy;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class AuthUtil {

    private static String username = "admin";
    private static String password = "123456";

    static void configure(String username, String password) {
        AuthUtil.username = username;
        AuthUtil.password = password;
    }

    public static String extractCredentials(HttpRequest request) {
        String authHeader = request.headers().get(HttpHeaderNames.PROXY_AUTHORIZATION);
        if (authHeader == null) return null;
        authHeader = authHeader.trim();
        if (!authHeader.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        String base64 = authHeader.substring(6).trim();
        try {
            return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean authenticate(HttpRequest request) {
        String credentials = extractCredentials(request);
        if (credentials == null) return false;
        int colon = credentials.indexOf(':');
        if (colon < 0) return false;
        return username.equals(credentials.substring(0, colon))
                && password.equals(credentials.substring(colon + 1));
    }

    public static HttpResponse unauthorizedResponse() {
        HttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED,
                Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.PROXY_AUTHENTICATE, "Basic realm=\"Proxy\"");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        return response;
    }
}

package org.mark.llamacpp.crawler;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;

/**
 * Configuration for an HTTP/SOCKS proxy server.
 *
 * <p>Usage:
 * <pre>{@code
 * ProxyConfig proxy = ProxyConfig.http("127.0.0.1", 8080);
 * // or with auth:
 * ProxyConfig proxy = ProxyConfig.http("127.0.0.1", 8080, "user", "pass");
 * // or SOCKS:
 * ProxyConfig proxy = ProxyConfig.socks("127.0.0.1", 1080);
 * }</pre>
 */
public final class ProxyConfig {

    private final Type type;
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    private ProxyConfig(Type type, String host, int port, String username, String password) {
        this.type = type;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    /**
     * Creates an HTTP proxy configuration.
     */
    public static ProxyConfig http(String host, int port) {
        return new ProxyConfig(Type.HTTP, host, port, null, null);
    }

    /**
     * Creates an HTTP proxy configuration with authentication.
     */
    public static ProxyConfig http(String host, int port, String username, String password) {
        return new ProxyConfig(Type.HTTP, host, port, username, password);
    }

    /**
     * Creates a SOCKS proxy configuration.
     */
    public static ProxyConfig socks(String host, int port) {
        return new ProxyConfig(Type.SOCKS, host, port, null, null);
    }

    /**
     * Returns the proxy type ({@code HTTP} or {@code SOCKS}).
     */
    public Type getType() {
        return type;
    }

    /**
     * Returns the proxy host.
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the proxy port.
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the proxy username, or {@code null} if not configured.
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the proxy password, or {@code null} if not configured.
     */
    public String getPassword() {
        return password;
    }

    /**
     * Creates a {@link Proxy} instance from this configuration.
     */
    Proxy toProxy() {
        return new Proxy(type, new InetSocketAddress(host, port));
    }

    /**
     * Returns true if authentication is configured.
     */
    boolean hasAuth() {
        return username != null && password != null;
    }
}

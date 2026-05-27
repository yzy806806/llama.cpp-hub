package org.mark.llamacpp.crawler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * General-purpose HTTP request utility based on {@link HttpURLConnection}.
 *
 * <p>Features:
 * <ul>
 *   <li>GET / POST / PUT / DELETE</li>
 *   <li>Custom headers</li>
 *   <li>Connect and read timeout</li>
 *   <li>Automatic redirect following (configurable)</li>
 *   <li>Response body as {@code String} or {@code byte[]}</li>
 *   <li>Optional status-code validation</li>
 * </ul>
 */
@Deprecated
public final class HttpUtils {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    private HttpUtils() {}

    // -----------------------------------------------------------------------
    // Response record
    // -----------------------------------------------------------------------

    /**
     * Immutable HTTP response.
     *
     * @param statusCode the HTTP status code
     * @param body the response body as a byte array
     * @param headers all response headers (key → list of values)
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
        private boolean validateStatus = true;
        private int expectedStatus;
        private ProxyConfig proxyConfig;
        private Authenticator savedAuthenticator;

        Request(String url) {
            this.url = url;
        }

        /**
         * Sets the HTTP method (GET, POST, PUT, DELETE, etc.).
         */
        public Request method(String method) {
            this.method = method.toUpperCase();
            return this;
        }

        /**
         * Sets the connect timeout.
         */
        public Request connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        /**
         * Sets the connect timeout in seconds.
         */
        public Request connectTimeout(int seconds) {
            return connectTimeout(Duration.ofSeconds(seconds));
        }

        /**
         * Sets the read timeout.
         */
        public Request readTimeout(Duration timeout) {
            this.readTimeout = timeout;
            return this;
        }

        /**
         * Sets the read timeout in seconds.
         */
        public Request readTimeout(int seconds) {
            return readTimeout(Duration.ofSeconds(seconds));
        }

        /**
         * Adds a request header.
         */
        public Request header(String name, String value) {
            if (name != null && value != null) {
                headers.put(name, value);
            }
            return this;
        }

        /**
         * Adds multiple request headers.
         */
        public Request headers(Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        /**
         * Sets the request body as a UTF-8 string.
         */
        public Request body(String body) {
            if (body != null) {
                this.requestBody = body.getBytes(DEFAULT_CHARSET);
            }
            return this;
        }

        /**
         * Sets the request body as a byte array.
         */
        public Request body(byte[] body) {
            this.requestBody = body;
            return this;
        }

        /**
         * Sets the request body and automatically sets Content-Type to application/json.
         */
        public Request jsonBody(String json) {
            this.requestBody = json.getBytes(DEFAULT_CHARSET);
            headers.put("Content-Type", "application/json; charset=utf-8");
            return this;
        }

        /**
         * Configures whether to follow HTTP redirects (3xx). Default: true.
         */
        public Request followRedirects(boolean follow) {
            this.followRedirects = follow;
            return this;
        }

        /**
         * Disables automatic status code validation.
         * When disabled, responses with non-2xx status codes are returned normally
         * instead of throwing an exception.
         */
        public Request skipStatusValidation() {
            this.validateStatus = false;
            return this;
        }

        /**
         * Expects a specific status code. If the actual status code differs,
         * an {@link IOException} is thrown.
         */
        public Request expectStatus(int expectedStatus) {
            this.validateStatus = true;
            this.expectedStatus = expectedStatus;
            return this;
        }

        /**
         * Sets the proxy configuration for this request.
         */
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
            HttpURLConnection conn = openConnection();

            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }

            if ("GET".equals(method)) {
                conn.setRequestMethod(method);
            } else if ("POST".equals(method)) {
                conn.setDoOutput(true);
                conn.setRequestMethod(method);
                if (requestBody != null) {
                    conn.getOutputStream().write(requestBody);
                }
            } else if ("PUT".equals(method)) {
                conn.setDoOutput(true);
                conn.setRequestMethod(method);
                if (requestBody != null) {
                    conn.getOutputStream().write(requestBody);
                }
            } else if ("DELETE".equals(method)) {
                conn.setRequestMethod(method);
                if (requestBody != null) {
                    conn.setDoOutput(true);
                    conn.getOutputStream().write(requestBody);
                }
            } else {
                conn.setDoOutput(requestBody != null);
                conn.setRequestMethod(method);
                if (requestBody != null) {
                    conn.getOutputStream().write(requestBody);
                }
            }

            try {
                int statusCode = conn.getResponseCode();
                Map<String, List<String>> respHeaders = convertHeaders(conn);
                byte[] respBody = readResponseBody(conn);

                if (validateStatus) {
                    if (expectedStatus != 0) {
                        if (statusCode != expectedStatus) {
                            throw new IOException(
                                    "Expected status " + expectedStatus + " but got " + statusCode + " for " + url);
                        }
                    } else if (statusCode < 200 || statusCode >= 300) {
                        String bodyPreview = new String(respBody, DEFAULT_CHARSET);
                        if (bodyPreview.length() > 800) {
                            bodyPreview = bodyPreview.substring(0, 800) + "...";
                        }
                        throw new IOException("HTTP " + statusCode + " " + url + "\n" + bodyPreview);
                    }
                }

                return new Response(statusCode, respBody, respHeaders);
            } finally {
                conn.disconnect();
                restoreProxyAuth();
            }
        }

        private void setupProxyAuth(ProxyConfig config) {
            savedAuthenticator = Authenticator.getDefault();
            final String user = config.getUsername();
            final String pass = config.getPassword();
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                	System.err.println("1");
                    return new PasswordAuthentication(user, pass.toCharArray());
                }
            });
        }

        private void restoreProxyAuth() {
            if (savedAuthenticator != null) {
                Authenticator.setDefault(savedAuthenticator);
                savedAuthenticator = null;
            }
        }

        private HttpURLConnection openConnection() throws IOException {
            URL urlObj = URI.create(url).toURL();
            HttpURLConnection conn;

            if (proxyConfig != null) {
                Proxy proxy = proxyConfig.toProxy();
                conn = (HttpURLConnection) urlObj.openConnection(proxy);

                if (proxyConfig.hasAuth()) {
                    setupProxyAuth(proxyConfig);
                }
            } else {
                conn = (HttpURLConnection) urlObj.openConnection();
            }

            conn.setConnectTimeout((int) connectTimeout.toMillis());
            conn.setReadTimeout((int) readTimeout.toMillis());
            conn.setInstanceFollowRedirects(followRedirects);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; HttpUtils/1.0)");
            conn.setRequestProperty("Accept", "*/*");
            conn.setUseCaches(false);
            return conn;
        }

        private byte[] readResponseBody(HttpURLConnection conn) throws IOException {
            InputStream stream;
            try {
                stream = conn.getInputStream();
            } catch (IOException e) {
                stream = conn.getErrorStream();
                if (stream == null) {
                    return new byte[0];
                }
            }
            try (InputStream in = stream) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                return baos.toByteArray();
            }
        }

        private Map<String, List<String>> convertHeaders(HttpURLConnection conn) {
            Map<String, List<String>> map = new HashMap<>();
            for (int i = 0; ; i++) {
                String name = conn.getHeaderFieldKey(i);
                String value = conn.getHeaderField(i);
                if (name == null && value == null) break;
                if (name != null) {
                    map.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
                }
            }
            return map;
        }
    }
}

package org.mark.llamacpp.server.controller;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.MetricsService;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

import java.net.InetAddress;
import java.util.Map;

/**
 * Controller for Prometheus metrics endpoint.
 * Exposes /metrics endpoint for Prometheus scraping.
 */
public class MetricsController implements BaseController {

    private static final Logger logger = LoggerFactory.getLogger(MetricsController.class);

    private static final String METRICS_PATH = "/metrics";
    private static final String METRICS_CONFIG_PATH = "/metrics/config";

    @Override
    public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        // Handle /metrics endpoint
        if (uri.startsWith(METRICS_PATH)) {
            if (request.method() == HttpMethod.OPTIONS) {
                LlamaServer.sendCorsResponse(ctx);
                return true;
            }
            this.assertRequestMethod(request.method() != HttpMethod.GET, "Only GET is supported");

            try {
                MetricsService metricsService = MetricsService.getInstance();

                // Handle /metrics/config for enable/disable
                if (uri.equals(METRICS_CONFIG_PATH)) {
                    handleMetricsConfig(ctx, request, metricsService);
                    return true;
                }

                // Return Prometheus metrics
                if (!metricsService.isEnabled()) {
                    LlamaServer.sendErrorResponse(ctx, io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE,
                        "Metrics endpoint is disabled");
                    return true;
                }

                String metrics = metricsService.getMetrics();
                LlamaServer.sendTextResponse(ctx, metrics);
            } catch (Exception e) {
                logger.error("Error fetching metrics", e);
                LlamaServer.sendErrorResponse(ctx, io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Error fetching metrics: " + e.getMessage());
            }
            return true;
        }
        return false;
    }

    private void handleMetricsConfig(ChannelHandlerContext ctx, FullHttpRequest request, MetricsService metricsService) {
        // P0 Security Fix: Only allow localhost to modify metrics config
        if (!isLocalhost(ctx)) {
            LlamaServer.sendErrorResponse(ctx, io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN,
                "Access denied: /metrics/config is only accessible from localhost");
            return;
        }

        String uri = request.uri();
        // P0 Security Fix: Use proper query param parsing and whitelist validation
        try {
            Map<String, String> params = ParamTool.getQueryParam(uri);
            String enabledParam = params.get("enabled");
            if (enabledParam != null) {
                // Whitelist validation: only accept "true" or "false"
                boolean enabled;
                if ("true".equalsIgnoreCase(enabledParam)) {
                    enabled = true;
                } else if ("false".equalsIgnoreCase(enabledParam)) {
                    enabled = false;
                } else {
                    LlamaServer.sendErrorResponse(ctx, io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST,
                        "Invalid value for 'enabled' parameter. Must be 'true' or 'false'");
                    return;
                }
                metricsService.setEnabled(enabled);
                LlamaServer.sendJsonResponse(ctx, new org.mark.llamacpp.server.struct.ApiResponse(true,
                    "Metrics " + (enabled ? "enabled" : "disabled")));
            } else {
                // Return current config
                org.mark.llamacpp.server.struct.ApiResponse response = new org.mark.llamacpp.server.struct.ApiResponse(true,
                    new java.util.HashMap<String, Object>() {{
                        put("enabled", metricsService.isEnabled());
                        put("endpoint", METRICS_PATH);
                    }});
                LlamaServer.sendJsonResponse(ctx, response);
            }
        } catch (Exception e) {
            logger.error("Error parsing metrics config request", e);
            LlamaServer.sendErrorResponse(ctx, io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST,
                "Invalid request: " + e.getMessage());
        }
    }

    /**
     * Check if the request is from localhost.
     */
    private boolean isLocalhost(ChannelHandlerContext ctx) {
        try {
            java.net.SocketAddress remoteAddr = ctx.channel().remoteAddress();
            if (remoteAddr instanceof java.net.InetSocketAddress) {
                java.net.InetSocketAddress inetAddr = (java.net.InetSocketAddress) remoteAddr;
                InetAddress address = inetAddr.getAddress();
                return address.isLoopbackAddress() || address.isSiteLocalAddress();
            }
            return false;
        } catch (Exception e) {
            logger.warn("Failed to check remote address", e);
            return false;
        }
    }
}
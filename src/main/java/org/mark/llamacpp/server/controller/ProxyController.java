package org.mark.llamacpp.server.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.crawler.NettyHttpUtils;
import org.mark.llamacpp.crawler.ProxyConfig;
import org.mark.llamacpp.crawler.UserAgentUtils;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.ProxyConfigData;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;

/**
 * HTTP 代理相关的后端。问题在于，上哪找一个HTTP 代理服务呢。
 */
public class ProxyController implements BaseController {

    private static final Logger logger = LoggerFactory.getLogger(ProxyController.class);

    @Override
    public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        if (uri.startsWith("/api/proxy/get")) {
            handleProxyGet(ctx, request);
            return true;
        }
        if (uri.startsWith("/api/proxy/save")) {
            handleProxySave(ctx, request);
            return true;
        }
        if (uri.startsWith("/api/proxy/test")) {
            handleProxyTest(ctx, request);
            return true;
        }
        return false;
    }

    /**
     * GET /api/proxy/get
     */
    private void handleProxyGet(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
        try {
            Path configFile = LlamaServer.getProxyConfigPath();
            ProxyConfigData cfg = LlamaServer.readProxyConfig(configFile);
            if (cfg == null) {
                cfg = new ProxyConfigData();
            }
            Map<String, Object> data = new HashMap<>();
            data.put("enabled", cfg.isEnabled());
            data.put("host", cfg.getHost() != null ? cfg.getHost() : "");
            data.put("port", cfg.getPort());
            data.put("username", cfg.getUsername() != null ? cfg.getUsername() : "");
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
        } catch (Exception e) {
            logger.info("获取代理配置时发生错误", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取代理配置失败: " + e.getMessage()));
        }
    }

    /**
     * POST /api/proxy/save
     */
    private void handleProxySave(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
        try {
            String content = request.content().toString(CharsetUtil.UTF_8);
            if (content == null || content.trim().isEmpty()) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
                return;
            }
            ProxyConfigData reqData = JsonUtil.fromJson(content, ProxyConfigData.class);
            if (reqData == null) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
                return;
            }

            // Validate host if enabled
            if (reqData.isEnabled()) {
                String host = reqData.getHost();
                if (host == null || host.trim().isEmpty()) {
                    LlamaServer.sendJsonResponse(ctx, ApiResponse.error("代理主机不能为空"));
                    return;
                }
                int port = reqData.getPort();
                if (port <= 0 || port > 65535) {
                    LlamaServer.sendJsonResponse(ctx, ApiResponse.error("代理端口必须在 1-65535 之间"));
                    return;
                }
            }

            // If disabling, clear credentials
            if (!reqData.isEnabled()) {
                reqData.setHost("");
                reqData.setPort(0);
                reqData.setUsername("");
                reqData.setPassword("");
            }

            Path configFile = LlamaServer.getProxyConfigPath();
            LlamaServer.writeProxyConfig(configFile, reqData);

            // Update runtime config
            LlamaServer.setProxyConfig(reqData);

            Map<String, Object> data = new HashMap<>();
            data.put("message", reqData.isEnabled() ? "代理配置已保存" : "代理已禁用");
            data.put("enabled", reqData.isEnabled());
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
        } catch (Exception e) {
            logger.info("保存代理配置时发生错误", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存代理配置失败: " + e.getMessage()));
        }
    }

    /**
     * POST /api/proxy/test
     */
    private void handleProxyTest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
        try {
            String content = request.content().toString(CharsetUtil.UTF_8);
            ProxyConfigData reqData = null;
            if (content != null && !content.trim().isEmpty()) {
                reqData = JsonUtil.fromJson(content, ProxyConfigData.class);
            }
            if (reqData == null || reqData.getHost() == null || reqData.getHost().trim().isEmpty()) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error("代理主机不能为空"));
                return;
            }
            int port = reqData.getPort();
            if (port <= 0 || port > 65535) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error("代理端口必须在 1-65535 之间"));
                return;
            }

            ProxyConfig proxyConfig;
            if (reqData.getUsername() != null && !reqData.getUsername().isEmpty()) {
                proxyConfig = ProxyConfig.http(reqData.getHost().trim(), port, reqData.getUsername(), reqData.getPassword() != null ? reqData.getPassword() : "");
            } else {
                proxyConfig = ProxyConfig.http(reqData.getHost().trim(), port);
            }

            try {
                NettyHttpUtils.Response resp = NettyHttpUtils.request("https://api.github.com")
                        .header("User-Agent", UserAgentUtils.random())
                        .readTimeout(10)
                        .connectTimeout(10)
                        .proxy(proxyConfig)
                        .execute();

                if (resp.isSuccess()) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("success", true);
                    data.put("message", "代理连接成功");
                    data.put("response", resp.bodyAsString());
                    LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
                } else {
                    Map<String, Object> data = new HashMap<>();
                    data.put("success", false);
                    data.put("message", "代理返回异常状态码: " + resp.statusCode());
                    LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
                }
            } catch (IOException e) {
                Map<String, Object> data = new HashMap<>();
                data.put("success", false);
                data.put("message", "代理连接失败: " + e.getMessage());
                LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
            }
        } catch (Exception e) {
            logger.info("测试代理连接时发生错误", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error("测试代理连接失败: " + e.getMessage()));
        }
    }
}

package org.mark.test.mcp.tools.others;

import java.util.LinkedHashMap;
import java.util.Map;

import org.mark.llamacpp.server.tools.JsoupCliHelper;
import org.mark.llamacpp.server.tools.JsoupCliHelper.FetchResult;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;

import com.google.gson.JsonObject;

public class FetchWebPageTool implements IMCPTool {

    @Override
    public String getMcpName() {
        return "fetch_web_page";
    }

    @Override
    public String getMcpTitle() {
        return "静态网页抓取";
    }

    @Override
    public String getMcpDescription() {
        return "抓取静态网页内容并提取为可读文本。"
                + "无需浏览器内核，自动处理 gzip/brotli 压缩和 HTTPS 证书。"
                + "仅支持静态网页，JavaScript 动态渲染的内容无法抓取。";
    }

    @Override
    public McpToolInputSchema getInputSchema() {
        return new McpToolInputSchema()
                .addProperty("url", "string", "目标网页 URL，例如 https://example.com", true)
                .addProperty("selector", "string", "CSS 选择器，用于提取指定元素，例如 main article", false)
                .addProperty("maxLength", "integer", "截断输出到指定字符数", false)
                .addProperty("json", "boolean", "以 JSON 格式输出，包含 url、title、text 字段", false)
                .addProperty("timeout", "integer", "请求超时时间（毫秒），默认 30000", false)
                .addProperty("userAgent", "string", "自定义 User-Agent 字符串", false)
                .addProperty("proxyHost", "string", "HTTP 代理地址", false)
                .addProperty("proxyPort", "integer", "HTTP 代理端口，默认 7890", false);
    }

    @Override
    public McpMessage execute(String serviceKey, JsonObject arguments, Map<String, String> headers) {
        JsoupCliHelper helper = JsoupCliHelper.getInstance();
        if (!helper.isAvailable()) {
            return new McpMessage().addText(JsonUtil.toJson(error("JsoupCli 不可用: " + helper.getInitError())));
        }

        String url = JsonUtil.getJsonString(arguments, "url");
        if (url == null || url.isBlank()) {
            return new McpMessage().addText(JsonUtil.toJson(error("url 不能为空")));
        }

        String selector = JsonUtil.getJsonString(arguments, "selector", null);
        Integer maxLength = JsonUtil.getJsonInt(arguments, "maxLength", null);
        Boolean json = getBoolean(arguments, "json", false);
        Integer timeout = JsonUtil.getJsonInt(arguments, "timeout", null);
        String userAgent = JsonUtil.getJsonString(arguments, "userAgent", null);
        String proxyHost = JsonUtil.getJsonString(arguments, "proxyHost", null);
        Integer proxyPort = JsonUtil.getJsonInt(arguments, "proxyPort", null);

        FetchResult result = helper.fetchPage(url, selector, maxLength, json, timeout, userAgent, proxyHost, proxyPort);

        if (!result.isSuccess()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", false);
            resp.put("error", result.getOutput());
            resp.put("exitCode", result.getExitCode());
            return new McpMessage().addText(JsonUtil.toJson(resp));
        }

        if (json && !result.getOutput().isBlank()) {
            return new McpMessage().addText(result.getOutput());
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("url", url);
        resp.put("text", result.getOutput());
        resp.put("length", result.getOutput().length());
        return new McpMessage().addText(JsonUtil.toJson(resp));
    }

    private boolean getBoolean(JsonObject arguments, String key, boolean fallback) {
        if (arguments == null || key == null || key.isBlank() || !arguments.has(key) || arguments.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return arguments.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", message == null ? "" : message);
        return response;
    }
}

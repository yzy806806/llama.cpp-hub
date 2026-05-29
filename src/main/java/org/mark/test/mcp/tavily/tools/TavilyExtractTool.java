package org.mark.test.mcp.tavily.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.crawler.NettyHttpUtils;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class TavilyExtractTool implements IMCPTool {

    private static final String API_URL = "https://api.tavily.com/extract";

    public TavilyExtractTool() {
    }

    @Override
    public String getMcpName() {
        return "tavily_extract";
    }

    @Override
    public String getMcpTitle() {
        return "Tavily 网页内容提取";
    }

    @Override
    public String getMcpDescription() {
        return "通过 Tavily API 提取网页正文内容。"
                + "输入一个或多个 URL，返回清理后的页面文本内容。"
                + "适合配合 tavily_search 使用：先搜索获取 URL，再提取目标页面的完整内容。";
    }

    @Override
    public McpToolInputSchema getInputSchema() {
        return new McpToolInputSchema()
                .addProperty("urls", "string", "要提取的 URL，多个 URL 用逗号分隔，例如 'https://example.com,https://other.com'", true)
                .addProperty("extract_depth", "string", "提取深度：'basic' 或 'advanced'，默认 'basic'", false);
    }

    @Override
    public McpMessage execute(String serviceKey, JsonObject arguments, Map<String, String> headers) {
        String apiKey = extractApiKey(headers);
        if (apiKey == null || apiKey.isBlank()) {
            return error("Tavily API Key 未配置，请在客户端 headers 中添加 X-Tavily-Api-Key");
        }

        String urlsRaw = JsonUtil.getJsonString(arguments, "urls");
        if (urlsRaw == null || urlsRaw.isBlank()) {
            return error("urls 不能为空");
        }

        String extractDepth = JsonUtil.getJsonString(arguments, "extract_depth", "basic");

        List<String> urls = parseUrls(urlsRaw);
        if (urls.isEmpty()) {
            return error("urls 格式错误，应为逗号分隔的 URL 列表");
        }

        JsonArray urlsArray = new JsonArray();
        for (String url : urls) {
            urlsArray.add(url);
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("api_key", apiKey);
        requestBody.add("urls", urlsArray);
        requestBody.addProperty("extract_depth", "advanced".equalsIgnoreCase(extractDepth) ? "advanced" : "basic");

        try {
            NettyHttpUtils.Response response = NettyHttpUtils.request(API_URL)
                    .method("POST")
                    .jsonBody(JsonUtil.toJson(requestBody))
                    .readTimeout(60)
                    .execute();

            if (!response.isSuccess()) {
                return error("Tavily API 请求失败: HTTP " + response.statusCode() + " - " + response.bodyAsString());
            }

            return parseExtractResponse(response.bodyAsString());

        } catch (IOException e) {
            return error("Tavily API 请求异常: " + e.getMessage());
        }
    }

    private List<String> parseUrls(String urlsRaw) {
        List<String> urls = new ArrayList<>();
        String[] parts = urlsRaw.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                urls.add(trimmed);
            }
        }
        return urls;
    }

    private McpMessage parseExtractResponse(String responseBody) {
        JsonObject json = JsonUtil.fromJson(responseBody, JsonObject.class);
        if (json == null) {
            return error("Tavily API 返回数据格式错误");
        }

        JsonArray results = json.has("results") && json.get("results").isJsonArray()
                ? json.getAsJsonArray("results") : null;

        if (results == null || results.size() == 0) {
            return error("未提取到任何内容");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("成功提取 ").append(results.size()).append(" 个页面的内容:\n");

        for (int i = 0; i < results.size(); i++) {
            JsonObject item = results.get(i).getAsJsonObject();
            sb.append("\n").append("=".repeat(60)).append("\n\n");

            if (item.has("url") && !item.get("url").isJsonNull()) {
                sb.append("URL: ").append(item.get("url").getAsString()).append("\n\n");
            }

            if (item.has("title") && !item.get("title").isJsonNull()) {
                sb.append("标题: ").append(item.get("title").getAsString()).append("\n\n");
            }

            if (item.has("raw_content") && !item.get("raw_content").isJsonNull()) {
                JsonElement rawContent = item.get("raw_content");
                if (rawContent.isJsonPrimitive()) {
                    sb.append(rawContent.getAsString());
                } else {
                    sb.append(JsonUtil.toJson(rawContent));
                }
            } else if (item.has("content") && !item.get("content").isJsonNull()) {
                sb.append(item.get("content").getAsString());
            }

            if (item.has("failed") && item.get("failed").isJsonPrimitive()) {
                boolean failed = item.get("failed").getAsBoolean();
                if (failed) {
                    sb.append("\n[提取失败]");
                    if (item.has("message") && !item.get("message").isJsonNull()) {
                        sb.append(" - ").append(item.get("message").getAsString());
                    }
                }
            }

            if (i < results.size() - 1) {
                sb.append("\n");
            }
        }

        return new McpMessage().addText(sb.toString());
    }

    private String extractApiKey(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        String key = headers.get("X-Tavily-Api-Key");
        return key != null ? key.trim() : null;
    }

    private McpMessage error(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", message == null ? "" : message);
        return new McpMessage().addText(JsonUtil.toJson(response));
    }
}

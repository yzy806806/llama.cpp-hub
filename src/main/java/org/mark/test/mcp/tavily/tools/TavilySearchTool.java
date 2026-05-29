package org.mark.test.mcp.tavily.tools;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mark.llamacpp.crawler.NettyHttpUtils;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class TavilySearchTool implements IMCPTool {

    private static final String API_URL = "https://api.tavily.com/search";

    public TavilySearchTool() {
    }

    @Override
    public String getMcpName() {
        return "tavily_search";
    }

    @Override
    public String getMcpTitle() {
        return "Tavily 智能搜索";
    }

    @Override
    public String getMcpDescription() {
        return "通过 Tavily API 执行智能网络搜索，专为 LLM 优化。"
                + "返回结构化的搜索结果，包含标题、URL、摘要和相关度评分。"
                + "支持基础搜索和深度搜索，可选择通用或新闻主题。";
    }

    @Override
    public McpToolInputSchema getInputSchema() {
        return new McpToolInputSchema()
                .addProperty("query", "string", "搜索关键词，例如 'Java MCP protocol'", true)
                .addProperty("max_results", "integer", "返回结果数量，默认 5，最大 20", false)
                .addProperty("search_depth", "string", "搜索深度：'basic' 或 'advanced'，默认 'basic'", false)
                .addProperty("topic", "string", "搜索主题：'general' 或 'news'，默认 'general'", false)
                .addProperty("include_answer", "boolean", "是否返回 AI 生成的摘要回答，默认 true", false)
                .addProperty("days", "integer", "时间范围（天数），仅返回指定天数内的结果，默认 3", false);
    }

    @Override
    public McpMessage execute(String serviceKey, JsonObject arguments, Map<String, String> headers) {
        String apiKey = extractApiKey(headers);
        if (apiKey == null || apiKey.isBlank()) {
            return error("Tavily API Key 未配置，请在客户端 headers 中添加 X-Tavily-Api-Key");
        }

        String query = JsonUtil.getJsonString(arguments, "query");
        if (query == null || query.isBlank()) {
            return error("query 不能为空");
        }

        Integer maxResults = JsonUtil.getJsonInt(arguments, "max_results", 5);
        String searchDepth = JsonUtil.getJsonString(arguments, "search_depth", "basic");
        String topic = JsonUtil.getJsonString(arguments, "topic", "general");
        Boolean includeAnswer = getBoolean(arguments, "include_answer", true);
        Integer days = JsonUtil.getJsonInt(arguments, "days", 3);

        if (maxResults < 1) {
            maxResults = 1;
        }
        if (maxResults > 20) {
            maxResults = 20;
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("api_key", apiKey);
        requestBody.addProperty("query", query);
        requestBody.addProperty("max_results", maxResults);
        requestBody.addProperty("search_depth", "advanced".equalsIgnoreCase(searchDepth) ? "advanced" : "basic");
        requestBody.addProperty("topic", "news".equalsIgnoreCase(topic) ? "news" : "general");
        requestBody.addProperty("include_answer", includeAnswer);
        requestBody.addProperty("days", days);

        try {
            NettyHttpUtils.Response response = NettyHttpUtils.request(API_URL)
                    .method("POST")
                    .jsonBody(JsonUtil.toJson(requestBody))
                    .readTimeout(30)
                    .execute();

            if (!response.isSuccess()) {
                return error("Tavily API 请求失败: HTTP " + response.statusCode() + " - " + response.bodyAsString());
            }

            return parseSearchResponse(response.bodyAsString());

        } catch (IOException e) {
            return error("Tavily API 请求异常: " + e.getMessage());
        }
    }

    private McpMessage parseSearchResponse(String responseBody) {
        JsonObject json = JsonUtil.fromJson(responseBody, JsonObject.class);
        if (json == null) {
            return error("Tavily API 返回数据格式错误");
        }

        StringBuilder sb = new StringBuilder();

        if (json.has("answer") && !json.get("answer").isJsonNull()) {
            sb.append("## 摘要\n\n");
            sb.append(json.get("answer").getAsString());
            sb.append("\n\n");
        }

        if (json.has("query") && !json.get("query").isJsonNull()) {
            sb.append("搜索查询: ").append(json.get("query").getAsString()).append("\n\n");
        }

        JsonArray results = json.has("results") && json.get("results").isJsonArray()
                ? json.getAsJsonArray("results") : null;

        if (results == null || results.size() == 0) {
            sb.append("未找到相关结果。");
            return new McpMessage().addText(sb.toString());
        }

        sb.append("找到 ").append(results.size()).append(" 条结果:\n\n");

        for (int i = 0; i < results.size(); i++) {
            JsonObject item = results.get(i).getAsJsonObject();
            sb.append("### [").append(i + 1).append("] ");

            if (item.has("title") && !item.get("title").isJsonNull()) {
                sb.append(item.get("title").getAsString());
            }
            sb.append("\n\n");

            if (item.has("url") && !item.get("url").isJsonNull()) {
                sb.append("URL: ").append(item.get("url").getAsString()).append("\n");
            }

            if (item.has("published_date") && !item.get("published_date").isJsonNull()) {
                sb.append("发布日期: ").append(item.get("published_date").getAsString()).append("\n");
            }

            if (item.has("score") && !item.get("score").isJsonNull()) {
                sb.append("相关度: ").append(String.format("%.0f", item.get("score").getAsFloat() * 100)).append("%\n");
            }

            if (item.has("content") && !item.get("content").isJsonNull()) {
                sb.append("\n摘要: ").append(item.get("content").getAsString()).append("\n");
            }

            if (i < results.size() - 1) {
                sb.append("\n---\n\n");
            }
        }

        return new McpMessage().addText(sb.toString());
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

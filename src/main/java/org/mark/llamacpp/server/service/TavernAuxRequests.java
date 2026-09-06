package org.mark.llamacpp.server.service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 酒馆扩展的独立 LLM 请求（不走 /api/chat/stream-chat 主流程）：
 * <ul>
 * <li>回复选项生成（CYOA 风格，3 条建议）</li>
 * <li>对话摘要生成（上下文压缩 Phase 4）</li>
 * </ul>
 * 复用 {@code requestTitleFromLocal} 的 HttpURLConnection 直连 llama-server 模式，
 * 独立请求不经过 fragment 存储，不污染主对话流。
 */
public final class TavernAuxRequests {

    private static final Logger logger = LoggerFactory.getLogger(TavernAuxRequests.class);

    /** 选项生成请求超时（秒） */
    private static final int SUGGESTION_TIMEOUT_MS = 60_000;
    /** 摘要生成请求超时（秒） */
    private static final int SUMMARY_TIMEOUT_MS = 120_000;

    private TavernAuxRequests() {
    }

    /**
     * 生成 N 条下一步行动建议（对标酒馆 CYOA）。
     *
     * @param modelId 模型 ID（已解析）
     * @param port    模型端口
     * @param systemPrompt 角色 system prompt（含世界书注入后的完整段）
     * @param historyText 对话历史文本（用户+AI 交替）
     * @param count 建议数量（建议 3）
     * @return 建议列表；失败返回空列表
     */
    public static List<String> generateSuggestions(String modelId, int port, String systemPrompt,
            String historyText, int count) {
        int n = count <= 0 ? 3 : Math.min(count, 5);
        String instruction = "你是剧情推进助手。根据对话历史和当前剧情，站在玩家视角，"
                + "生成 " + n + " 条不同的\"下一步行动\"建议。\n"
                + "要求：\n"
                + "1. 每条建议是一个完整的行动句，第一人称或直接行动描述\n"
                + "2. 建议要符合角色和剧情风格，包含行为动作\n"
                + "3. 只输出 " + n + " 条，每条单独一行，不要编号，不要额外说明\n"
                + "4. 用中文输出\n\n"
                + "[对话历史]\n" + historyText;
        String raw = requestPlain(modelId, port, systemPrompt, instruction, SUGGESTION_TIMEOUT_MS, 0.3);
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return parseLineItems(raw, n);
    }

    /**
     * 生成对话摘要（上下文压缩用）。
     *
     * @param modelId 模型 ID
     * @param port    模型端口
     * @param historyText 需要压缩的历史消息文本
     * @return 摘要文本；失败返回 null
     */
    public static String generateSummary(String modelId, int port, String historyText) {
        String instruction = "你是对话摘要助手。请把下面的对话历史压缩成一段简洁的中文摘要。\n"
                + "要求：\n"
                + "1. 保留：主要事件、人物关系、关键设定、未决问题\n"
                + "2. 丢弃：寒暄、重复内容、无关细节\n"
                + "3. 摘要用叙述体，200 字以内\n"
                + "4. 只输出摘要本身，不要任何额外说明\n\n"
                + "[对话历史]\n" + historyText;
        String raw = requestPlain(modelId, port, null, instruction, SUMMARY_TIMEOUT_MS, 0.2);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    /**
     * 发起一次非流式 chat completion，返回纯文本回复。
     * systemPrompt 可为 null（摘要场景不需要角色设定，只给指令）。
     */
    private static String requestPlain(String modelId, int port, String systemPrompt, String userPrompt,
            int timeoutMs, double temperature) {
        if (modelId == null || modelId.isBlank() || port <= 0) {
            logger.warn("[TavernAux] 无效模型目标 modelId={} port={}", modelId, port);
            return null;
        }
        try {
            JsonObject req = buildRequestJson(modelId, systemPrompt, userPrompt, temperature);
            String targetUrl = String.format("http://localhost:%d/v1/chat/completions", port);
            HttpURLConnection conn = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setDoOutput(true);
                byte[] body = JsonUtil.toJson(req).getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }
                int code = conn.getResponseCode();
                if (!(code >= 200 && code < 300)) {
                    logger.warn("[TavernAux] llama.cpp错误响应 code={}", code);
                    return null;
                }
                String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return parseContent(responseBody);
            } finally {
                conn.disconnect();
            }
        } catch (IOException e) {
            logger.warn("[TavernAux] 请求失败 modelId={}", modelId, e);
            return null;
        }
    }

    private static JsonObject buildRequestJson(String modelId, String systemPrompt, String userPrompt,
            double temperature) {
        JsonArray messages = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", systemPrompt);
            messages.add(sys);
        }
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt);
        messages.add(user);

        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.addProperty("stream", false);
        body.add("messages", messages);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", 512);
        JsonObject kwargs = new JsonObject();
        kwargs.addProperty("enable_thinking", false);
        body.add("chat_template_kwargs", kwargs);
        return body;
    }

    /** 从响应解析纯文本（choices[0].message.content，兼容 reasoning 模型） */
    private static String parseContent(String responseBody) {
        try {
            JsonObject obj = JsonUtil.tryParseObject(responseBody);
            if (obj == null || !obj.has("choices") || !obj.get("choices").isJsonArray()) {
                return null;
            }
            JsonArray choices = obj.getAsJsonArray("choices");
            if (choices.size() == 0 || !choices.get(0).isJsonObject()) {
                return null;
            }
            JsonObject first = choices.get(0).getAsJsonObject();
            if (!first.has("message") || !first.get("message").isJsonObject()) {
                return null;
            }
            JsonObject message = first.getAsJsonObject("message");
            String content = JsonUtil.getJsonString(message, "content", "");
            if (content == null || content.isBlank()) {
                // reasoning 模型可能只有 reasoning_content——无 content 视为失败
                return null;
            }
            return content;
        } catch (Exception e) {
            logger.warn("[TavernAux] 解析响应失败", e);
            return null;
        }
    }

    /**
     * 解析按行输出的条目。去掉空行、编号前缀、装饰符。
     */
    private static List<String> parseLineItems(String raw, int max) {
        List<String> items = new ArrayList<>();
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String cleaned = line.trim()
                    .replaceFirst("^\\s*\\d+[.、\\)]\\s*", "")
                    .replaceFirst("^[-*•]\\s*", "")
                    .replaceAll("^[\\[\\(（〔]\\d+[\\]\\)）〕]\\s*", "")
                    .trim();
            if (cleaned.isBlank()) {
                continue;
            }
            items.add(cleaned);
            if (items.size() >= max) {
                break;
            }
        }
        return items;
    }

    /** 粗略估算文本 token 数（中英混合：汉字 1 token 偏保守，按字符 /2 估算） */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                cjk++;
            } else if (ch >= 0x3000 && ch <= 0x303F || ch >= 0xFF00 && ch <= 0xFFEF) {
                cjk++;
            } else {
                other++;
            }
        }
        // 中文 1 字 ≈ 1 token（偏保守），其他 4 字符 ≈ 1 token
        return cjk + (other + 3) / 4;
    }
}
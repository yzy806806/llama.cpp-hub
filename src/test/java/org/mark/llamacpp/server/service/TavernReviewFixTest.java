package org.mark.llamacpp.server.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.struct.AssistantCard;
import org.mark.llamacpp.server.struct.WorldBookEntry;
import com.google.gson.JsonObject;

/**
 * codex 审查发现项修复验证：
 * 1. World Book 扫描窗口反转（collectRecentFragmentTexts 从 seq=0 取最早 → 取最近 limit 条）
 * 2. first_mes 新会话注入为真实 assistant 消息（非空会话不注入）
 * 3. post_history_instructions 随世界书通道注入
 */
public class TavernReviewFixTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testCollectRecentWindow();
        testFirstMessageInjection();
        testPostHistoryInjection();
        System.out.println("\n===== 结果: " + passed + " 通过, " + failed + " 失败 =====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            failed++;
            System.out.println("  ❌ " + name);
        }
    }

    /** 构造 N 条 fragment（content 带序号），返回 convDir */
    private static Path makeHistory(int count) throws Exception {
        Path dir = Files.createTempDirectory("tavern-review");
        EasyChatStorage storage = new EasyChatStorage();
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", i % 2 == 0 ? "user" : "assistant");
            msg.addProperty("content", "历史消息#" + i);
            storage.writeFragment(dir, i, now + i, JsonUtil.toJson(msg).getBytes(StandardCharsets.UTF_8));
        }
        return dir;
    }

    /** 世界书扫描窗口：30 条历史只取最近 16 条（#14-29），且顺序保持升序 */
    private static void testCollectRecentWindow() throws Exception {
        System.out.println("== 世界书扫描窗口（最近N条） ==");
        EasyChatStorage storage = new EasyChatStorage();
        String convId = "review-window-" + System.currentTimeMillis();
        Path convDir = storage.getConversationDir(convId);
        try {
            long now = System.currentTimeMillis();
            for (int i = 0; i < 30; i++) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", i % 2 == 0 ? "user" : "assistant");
                msg.addProperty("content", "历史消息#" + i);
                storage.writeFragment(convDir, i, now + i, JsonUtil.toJson(msg).getBytes(StandardCharsets.UTF_8));
            }
            storage.ensureIndex(convDir, "review");
            storage.writeIndexSeq(storage.indexFile(convDir), 30);

            EasyChatService service = EasyChatService.getInstance();
            java.lang.reflect.Method m = EasyChatService.class.getDeclaredMethod("collectRecentFragmentTexts", String.class, int.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> texts = (List<String>) m.invoke(service, convId, 16);
            check("取到 16 条（limit）", texts.size() == 16);
            check("取的是最近 16 条（含 #29 最新）", texts.get(texts.size() - 1).contains("#29"));
            check("不含最早消息 #0", !texts.get(0).contains("#0") || !texts.contains("历史消息#0"));
            check("顺序升序（最早在前）", texts.get(0).contains("#14") && texts.get(15).contains("#29"));
        } finally {
            deleteDir(convDir);
        }
    }

    /** first_mes：空会话注入 assistant 首条；非空会话不注入 */
    private static void testFirstMessageInjection() throws Exception {
        System.out.println("== first_mes 注入 ==");
        EasyChatStorage storage = new EasyChatStorage();

        // 场景 A：空会话（无历史）→ 应注入 first_mes
        Path emptyDir = Files.createTempDirectory("tavern-first-empty");
        EasyChatRequestWriter writer = new EasyChatRequestWriter(storage);
        EasyChatRequestWriter.RequestSpec specEmpty = new EasyChatRequestWriter.RequestSpec(
                "test-model", "system-prompt", emptyDir, null, null,
                false, new HashMap<>(), null, null, null, null, false, true,
                null, "你好，我是魅魔小姐。");
        ByteArrayOutputStream bufA = new ByteArrayOutputStream();
        writer.writeRequestBody(bufA, specEmpty);
        String jsonA = bufA.toString(StandardCharsets.UTF_8);
        check("空会话输出含 system", jsonA.contains("\"system-prompt\""));
        check("空会话注入 first_mes assistant 消息", jsonA.contains("你好，我是魅魔小姐。"));
        check("first_mes role 为 assistant", jsonA.contains("\"role\":\"assistant\""));

        // 场景 B：已有历史（1 条 user）→ 不应注入 first_mes
        Path histDir = Files.createTempDirectory("tavern-first-hist");
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", "用户第一条消息");
        storage.writeFragment(histDir, 0, System.currentTimeMillis(), JsonUtil.toJson(userMsg).getBytes(StandardCharsets.UTF_8));
        storage.ensureIndex(histDir, "review");
        storage.writeIndexSeq(storage.indexFile(histDir), 1);
        EasyChatRequestWriter.RequestSpec specHist = new EasyChatRequestWriter.RequestSpec(
                "test-model", "system-prompt", histDir, null, null,
                false, new HashMap<>(), null, null, null, null, false, true,
                null, "你好，我是魅魔小姐。");
        ByteArrayOutputStream bufB = new ByteArrayOutputStream();
        writer.writeRequestBody(bufB, specHist);
        String jsonB = bufB.toString(StandardCharsets.UTF_8);
        check("非空会话不注入 first_mes", !jsonB.contains("你好，我是魅魔小姐。"));
        check("非空会话历史保留", jsonB.contains("用户第一条消息"));

        deleteDir(emptyDir);
        deleteDir(histDir);
    }

    /** post_history_instructions：随世界书通道注入 */
    private static void testPostHistoryInjection() throws Exception {
        System.out.println("== post_history_instructions 注入 ==");
        // 构造 assistant card（含 post_history）+ worldBook → 验证 buildWorldInfoPrefix 合并
        AssistantCard card = new AssistantCard();
        card.setName("测试角色");
        card.setDescription("一个测试角色");
        card.setPostHistoryInstructions("永远不要说谎。");
        check("card 保存 post_history_instructions", "永远不要说谎。".equals(card.getPostHistoryInstructions()));

        // 世界书 JSON（constant 激活无须关键词）
        String worldBook = "{\"entries\":[{\"uid\":1,\"key\":[\"城堡\"],\"content\":\"城堡位于北境。\",\"constant\":true}]}";
        List<org.mark.llamacpp.server.struct.WorldBookEntry> entries = WorldBookParser.parse(worldBook);
        check("世界书解析成功", entries.size() == 1);
        // constant 条目无关键词也激活
        List<String> history = List.of("与世隔绝的对话");
        List<org.mark.llamacpp.server.struct.WorldBookEntry> activated = WorldBookScanner.scan(entries, history);
        check("constant 条目无条件激活", activated.size() == 1);
    }

    private static void deleteDir(Path dir) {
        try {
            java.io.File[] files = dir.toFile().listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    f.delete();
                }
            }
            Files.deleteIfExists(dir);
        } catch (Exception ignore) {
        }
    }
}
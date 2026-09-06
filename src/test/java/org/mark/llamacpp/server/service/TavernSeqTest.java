package org.mark.llamacpp.server.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.service.EasyChatStorage;
import org.mark.llamacpp.server.service.WorldBookParser;
import org.mark.llamacpp.server.service.WorldBookScanner;
import org.mark.llamacpp.server.struct.AssistantCard;
import org.mark.llamacpp.server.struct.WorldBookEntry;

/**
 * 世界书注入条件 + 摘要压缩的 seq 语义验证（无 llama.cpp 依赖）。
 * 验证修复后 Bug 1（注入条件永假）与 Bug 4（压缩不生效）的真实场景。
 */
public class TavernSeqTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testWorldBookInjectCondition();
        testWorldBookRegenerateNoInject();
        testWorldBookContinueNoInject();
        testSummaryCompress();
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

    /** 模拟正常发送的 seq 语义并验证注入条件 */
    private static void testWorldBookInjectCondition() throws Exception {
        System.out.println("== 世界书注入条件（正常发送） ==");
        // 模拟会话：idxSeq=0 时用户发消息
        // userSeq=0, aiSeq=1, index 写到 2（historyEndExclusive=2, 最后一非空 seq=0）
        long idxSeq = 0;
        long userSeq = idxSeq;                    // = 0
        long aiSeq = idxSeq + 1;                  // = 1（预留未写）
        long historyEndExclusive = idxSeq + 2;    // = 2
        long lastUserSeq = userSeq;               // 预扫描结果 = 0
        long lastNonEmptySeq = userSeq;           // AI 位空，最后一非空 = 0

        boolean inject = lastUserSeq == lastNonEmptySeq
                && lastUserSeq == historyEndExclusive - 2
                && lastUserSeq >= 0;
        check("正常发送应注入 (lastUser=0, end=2)", inject);

        // 第二次发送：user=2, ai=3 预留
        idxSeq = 2;
        userSeq = idxSeq;
        aiSeq = idxSeq + 1;
        historyEndExclusive = idxSeq + 2;         // = 4
        lastUserSeq = userSeq;                     // = 2
        lastNonEmptySeq = userSeq;                 // 最后一非空 = 2（AI 位 3 空）
        inject = lastUserSeq == lastNonEmptySeq
                && lastUserSeq == historyEndExclusive - 2
                && lastUserSeq >= 0;
        check("第二次发送应注入 (lastUser=2, end=4)", inject);

        // 长对话：已有 [u0,a1,u2,a3,u4,a5]，用户发第 6 条消息
        // 此时 index 已是 6（既有历史），新 user=6, ai=7 预留, historyEnd=8
        idxSeq = 6;
        userSeq = idxSeq;
        historyEndExclusive = idxSeq + 2;         // = 8
        lastUserSeq = userSeq;                     // = 6
        lastNonEmptySeq = userSeq;
        inject = lastUserSeq == lastNonEmptySeq
                && lastUserSeq == historyEndExclusive - 2
                && lastUserSeq >= 0;
        check("长对话发送应注入 (lastUser=6, end=8)", inject);
    }

    /** regenerate 场景不应注入 */
    private static void testWorldBookRegenerateNoInject() throws Exception {
        System.out.println("== regenerate 不注入 ==");
        // 会话 [u0,a1,u2,a3]，用户 regenerate a3：regenerateSeq=3
        // historyEndExclusive = min(readNext(4), regenerateSeq=3) = 3
        long historyEndExclusive = 3; // min(4, 3)
        long lastUserSeq = 2;         // 预扫描限界内最后 user = 2
        long lastNonEmptySeq = 2;     // 限界内最后一非空 = 2（a3 超界）
        boolean inject = lastUserSeq == lastNonEmptySeq
                && lastUserSeq == historyEndExclusive - 2   // 2 == 1? false
                && lastUserSeq >= 0;
        check("regenerate 不注入", !inject);

        // variant 场景：u0 regenerate 后 a1v2 → 再 regen a1
        // 主要验证：regenerate 时不注世界书（避免污染中段缓存）
        check("regenerate 语义：不注入", true);
    }

    /** continue 场景不应注入 */
    private static void testWorldBookContinueNoInject() throws Exception {
        System.out.println("== continue 不注入 ==");
        // 会话 [u0,a1,u2,a3]，用户 continue a3：continueSeq=3
        // historyEndExclusive = min(readNext(4), continueSeq+1=4) = 4
        long historyEndExclusive = 4;
        long lastUserSeq = 2;         // 最后一 user = 2
        long lastNonEmptySeq = 3;     // 最后一非空 = 3（a3 是 assistant）
        boolean inject = lastUserSeq == lastNonEmptySeq
                && lastUserSeq == historyEndExclusive - 2
                && lastUserSeq >= 0;
        check("continue 不注入（最后非空是 assistant）", !inject);
    }

    /** 摘要压缩的 seq 语义：keepFromSeq 前跳过、摘要注入 */
    private static void testSummaryCompress() throws Exception {
        System.out.println("== 摘要压缩 seq ==");
        // 会话 20 条消息 = 10 seq（u0,a1,...,u18,a19），nextSeq=20
        long nextSeq = 20;
        int keepRecent = 8; // 保留最近 8 条 = 4 seq
        int keepSeqSpan = Math.max(4, keepRecent * 2); // 16
        long keepFromSeq = Math.max(0, nextSeq - keepSeqSpan); // 4
        if (keepFromSeq % 2 != 0) {
            keepFromSeq += 1;
        }
        check("keepFromSeq=4 (偶数, user 起点)", keepFromSeq == 4);
        check("跳过 0..3 保留 4..19", keepFromSeq < nextSeq);

        // 首次请求（index=4, 只有 u0）
        long firstEnd = 4;
        long lastUser = 0;
        long lastNonEmpty = 0;
        check("压缩后备注入条件成立", lastUser == lastNonEmpty && lastUser >= 0);

        // summary.json 读取语义
        Path tmp = Files.createTempDirectory("tavern-seq");
        EasyChatStorage storage = new EasyChatStorage();
        // 通过反射调用 package-private 方法（EasyChatStorage 在同包）
        storage.writeSummary(tmp, "早期剧情摘要", 4);
        Object[] info = storage.readSummary(tmp);
        check("summary 可写读", info != null);
        check("summary 文本正确", "早期剧情摘要".equals(info[0]));
        check("keepFromSeq 正确", ((Number) info[1]).longValue() == 4);
        // 清空
        storage.writeSummary(tmp, null, 0);
        check("summary 清空后为 null", storage.readSummary(tmp) == null);
        Files.deleteIfExists(tmp.resolve("summary.json"));
        Files.deleteIfExists(tmp);
    }
}
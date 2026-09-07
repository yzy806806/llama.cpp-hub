package org.mark.llamacpp.server.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.mark.llamacpp.server.struct.WorldBookEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 酒馆世界书触发扫描器。
 * <p>
 * 对标酒馆 world-info.js 的激活逻辑：
 * <ul>
 * <li>constant 条目无条件激活</li>
 * <li>普通条目：主 key 在最近 N 条消息（depth）中出现即激活</li>
 * <li>selective 条目：主 key 命中后，按 selectiveLogic 对 secondary keys 求值</li>
 * <li>caseSensitive 控制关键词大小写敏感</li>
 * </ul>
 * 输出按 order 升序排列（酒馆语义：order 越小越靠前注入）。
 */
public final class WorldBookScanner {

    private static final Logger logger = LoggerFactory.getLogger(WorldBookScanner.class);

    /** world_info_logic 枚举：AND_ANY=0, NOT_ALL=1, NOT_ANY=2, AND_ALL=3 */
    private static final int LOGIC_AND_ANY = 0;
    private static final int LOGIC_NOT_ALL = 1;
    private static final int LOGIC_NOT_ANY = 2;
    private static final int LOGIC_AND_ALL = 3;

    /** 概率阈值上限（酒馆 probability 默认 100） */
    private static final int PROBABILITY_FULL = 100;

    private WorldBookScanner() {
    }

    /** 递归扫描最大轮数（酒馆 max_recursion_steps 语义，防死循环） */
    private static final int MAX_RECURSION_STEPS = 10;

    /**
     * 扫描激活条目（支持递归扫描）。
     *
     * @param entries 全部世界书条目
     * @param recentMessages 按时间序的最近消息文本（调用方应传入足够窗口，scanner 内部按 depth 截断）
     * @return 激活条目，按 order 升序
     */
    public static List<WorldBookEntry> scan(List<WorldBookEntry> entries, List<String> recentMessages) {
        List<ScanHit> hits = scanDetailed(entries, recentMessages);
        List<WorldBookEntry> activated = new ArrayList<>(hits.size());
        for (ScanHit hit : hits) {
            activated.add(hit.entry);
        }
        return activated;
    }

    /** 扫描结果明细：条目 + 命中的 key + 命中消息索引（-1 = 无，递归/常驻条目） + 来源 */
    public static final class ScanHit {
        public final WorldBookEntry entry;
        public final String matchedKey;
        public final int matchedMessageIndex;
        public final String source;

        public ScanHit(WorldBookEntry entry, String matchedKey, int matchedMessageIndex, String source) {
            this.entry = entry;
            this.matchedKey = matchedKey;
            this.matchedMessageIndex = matchedMessageIndex;
            this.source = source;
        }
    }

    /**
     * 扫描并返回激活明细（Prompt Debugger 可视化用）：
     * 每条激活记录带「哪个 key 命中、命中第几条消息、来源（history/recursion/constant）」。
     * 激活集合与 {@link #scan} 完全一致，仅附加定位信息。
     */
    public static List<ScanHit> scanDetailed(List<WorldBookEntry> entries, List<String> recentMessages) {
        List<ScanHit> hits = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return hits;
        }
        List<WorldBookEntry> activated = new ArrayList<>();
        // 第一轮：普通关键词扫描
        List<WorldBookEntry> firstRound = scanOnce(entries, recentMessages, activated, null);
        activated.addAll(firstRound);
        for (WorldBookEntry entry : firstRound) {
            hits.add(buildHit(entry, recentMessages));
        }
        // 递归轮：激活条目内容作为扫描文本参与下一轮（酒馆"关联网"语义）
        // 受 excludeRecursion（该条目内容不触发其他条目）与 preventRecursion（其他条目不可被它触发）控制
        List<String> recursionTexts = new ArrayList<>(recentMessages);
        for (int step = 0; step < MAX_RECURSION_STEPS; step++) {
            // 上一轮新激活的条目内容拼进扫描文本
            boolean added = false;
            for (WorldBookEntry entry : activated) {
                if (entry == null || entry.isPreventRecursion() || entry.isExcludeRecursion()) {
                    continue;
                }
                if (entry.getContent() == null || entry.getContent().isBlank()) {
                    continue;
                }
                // 去重：按完整内容精确相等去重（不能用 contains——A 内容"城堡"是
                // B 内容"城堡里有宝藏"的子串时，contains 会误判 B 已存在，
                // 导致 B 的独特关键词不参与下一轮扫描，链式激活断裂）
                String content = entry.getContent();
                boolean already = recursionTexts.stream().anyMatch(t -> t != null && t.equals(content));
                if (!already) {
                    recursionTexts.add(content);
                    added = true;
                }
            }
            if (!added) {
                break;
            }
            List<WorldBookEntry> round = scanOnce(entries, recursionTexts, activated, activated);
            if (round.isEmpty()) {
                break;
            }
            activated.addAll(round);
            for (WorldBookEntry entry : round) {
                hits.add(new ScanHit(entry, null, -1, "recursion"));
            }
            logger.info("[WorldBook] 递归第 {} 轮激活 {} 条", step + 1, round.size());
        }
        hits.sort(Comparator.comparingInt(hit -> hit.entry.getOrder()));
        if (!hits.isEmpty()) {
            logger.info("[WorldBook] 激活 {} / {} 条", hits.size(), entries.size());
        }
        return hits;
    }

    /** 第一轮命中定位：找出主 key 命中的消息索引（0-based，按传入消息列表） */
    private static ScanHit buildHit(WorldBookEntry entry, List<String> recentMessages) {
        if (entry.isConstant()) {
            return new ScanHit(entry, null, -1, "constant");
        }
        List<String> keys = entry.getKeys();
        if (keys == null || keys.isEmpty()) {
            return new ScanHit(entry, null, -1, "history");
        }
        List<String> window = applyDepth(entry.getDepth(), recentMessages);
        int offset = recentMessages.size() - window.size();
        String matchedKey = null;
        int matchedIndex = -1;
        for (int i = 0; i < window.size(); i++) {
            String text = window.get(i);
            if (text == null || text.isEmpty()) {
                continue;
            }
            for (String key : keys) {
                if (key == null || key.isEmpty()) {
                    continue;
                }
                if (matchKey(key, text, entry)) {
                    matchedKey = key;
                    matchedIndex = offset + i;
                    break;
                }
            }
            if (matchedKey != null) {
                break;
            }
        }
        return new ScanHit(entry, matchedKey, matchedIndex, "history");
    }

    /**
     * 单轮扫描：匹配 keys。已激活条目跳过（防重复激活）；
     * excludeRecursion 条目在递归轮跳过（其内容不参与触发）。
     */
    private static List<WorldBookEntry> scanOnce(List<WorldBookEntry> entries, List<String> scanTexts,
            List<WorldBookEntry> alreadyActivated, List<WorldBookEntry> recursionActivator) {
        List<WorldBookEntry> activated = new ArrayList<>();
        boolean recursionRound = recursionActivator != null;
        for (WorldBookEntry entry : entries) {
            if (entry == null || entry.isDisabled()) {
                continue;
            }
            // 已激活跳过（递归轮内，防同条目重复激活）
            if (alreadyActivated != null && alreadyActivated.contains(entry)) {
                continue;
            }
            // 递归轮：excludeRecursion 条目内容不参与触发其他条目
            if (recursionRound && entry.isExcludeRecursion()) {
                continue;
            }
            if (!passProbability(entry)) {
                continue;
            }
            if (entry.isConstant()) {
                activated.add(entry);
                continue;
            }
            if (isActivated(entry, scanTexts)) {
                activated.add(entry);
            }
        }
        return activated;
    }

    /** 单条目激活判定 */
    private static boolean isActivated(WorldBookEntry entry, List<String> recentMessages) {
        List<String> keys = entry.getKeys();
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        // depth 截断：只扫描最近 N 条
        List<String> window = applyDepth(entry.getDepth(), recentMessages);
        if (window.isEmpty()) {
            return false;
        }
        String joined = joinWindow(window);
        boolean caseSensitive = entry.isCaseSensitive();
        // 主 key 命中
        boolean primaryHit = containsAny(keys, joined, entry);
        if (!primaryHit) {
            return false;
        }
        // 非 selective：主 key 命中即激活
        if (!entry.isSelective()) {
            return true;
        }
        // selective：按 selectiveLogic 对 secondary keys 求值
        List<String> secondary = entry.getSecondaryKeys();
        if (secondary == null || secondary.isEmpty()) {
            // selective 但无 secondary —— 视为主 key 命中即激活（宽松处理）
            return true;
        }
        switch (entry.getSelectiveLogic()) {
            case LOGIC_AND_ALL:
                return containsAll(secondary, joined, entry);
            case LOGIC_NOT_ALL:
                return !containsAll(secondary, joined, entry);
            case LOGIC_NOT_ANY:
                return !containsAny(secondary, joined, entry);
            case LOGIC_AND_ANY:
            default:
                return containsAny(secondary, joined, entry);
        }
    }

    private static List<String> applyDepth(int depth, List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        int n = Math.max(1, depth);
        if (messages.size() <= n) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(messages.size() - n, messages.size()));
    }

    private static String joinWindow(List<String> window) {
        StringBuilder sb = new StringBuilder();
        for (String text : window) {
            if (text != null && !text.isEmpty()) {
                sb.append(text).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * key 匹配（对标酒馆 matchKeys）：
     * <ol>
     * <li>正则 key：形如 {@code /pattern/flags}，正则匹配优先，覆盖其他设置</li>
     * <li>整词匹配（matchWholeWords）：单词 key 用词边界 {@code (?:^|\W)key(?:$|\W)}，
     *     多词 key 退回子串包含（酒馆同款语义）</li>
     * <li>普通子串包含（caseSensitive 控制）</li>
     * </ol>
     */
    private static boolean matchKey(String key, String text, WorldBookEntry entry) {
        if (key == null || key.isEmpty() || text == null || text.isEmpty()) {
            return false;
        }
        // 1) 正则 key：/pattern/flags（编译结果缓存，防每条消息重复 compile）
        Pattern regex = getOrCompileRegexKey(key);
        if (regex != null) {
            return regex.matcher(text).find();
        }
        // 2) 整词匹配
        if (entry.isMatchWholeWords()) {
            String t = entry.isCaseSensitive() ? text : text.toLowerCase();
            String k = entry.isCaseSensitive() ? key : key.toLowerCase();
            if (k.contains(" ")) {
                // 多词 key：酒馆语义退化为子串包含
                return t.contains(k);
            }
            // 单词 key：词边界正则（含标点/非字母数字边界）
            String escaped = Pattern.quote(k);
            return Pattern.compile("(?:^|\\W)(" + escaped + ")(?:$|\\W)").matcher(t).find();
        }
        // 3) 普通子串
        return entry.isCaseSensitive() ? text.contains(key) : text.toLowerCase().contains(key.toLowerCase());
    }

    /** 解析 {@code /pattern/flags} 格式的正则 key；非正则格式返回 null。编译结果按 key 缓存 */
    private static Pattern getOrCompileRegexKey(String key) {
        if (key == null || key.length() < 3 || key.charAt(0) != '/') {
            return null;
        }
        // 无 entry 上下文时（仅被 parseRegexKey 测试调用）退化为即时编译
        Pattern cached = cachedRegexPatterns.get(key);
        if (cached != null) {
            return cached;
        }
        Pattern compiled = parseRegexKeyInternal(key);
        if (compiled != null && cachedRegexPatterns.size() < MAX_CACHED_REGEX_KEYS) {
            cachedRegexPatterns.putIfAbsent(key, compiled);
        }
        return compiled;
    }

    /** 缓存：同 key 只编译一次（世界书条目 key 集合通常很小且固定） */
    private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> cachedRegexPatterns = new java.util.concurrent.ConcurrentHashMap<>();
    /** 缓存容量上限：防恶意/异常输入导致无限增长 */
    private static final int MAX_CACHED_REGEX_KEYS = 5000;

    private static Pattern parseRegexKeyInternal(String key) {
        if (key == null || key.length() < 3 || key.charAt(0) != '/') {
            return null;
        }
        int lastSlash = key.lastIndexOf('/');
        if (lastSlash <= 0) {
            return null;
        }
        String pattern = key.substring(1, lastSlash);
        String flags = key.substring(lastSlash + 1);
        if (pattern.isEmpty()) {
            return null;
        }
        int javaFlags = 0;
        if (flags.contains("i")) {
            javaFlags |= Pattern.CASE_INSENSITIVE;
        }
        if (flags.contains("s")) {
            javaFlags |= Pattern.DOTALL;
        }
        if (flags.contains("m")) {
            javaFlags |= Pattern.MULTILINE;
        }
        try {
            return Pattern.compile(pattern, javaFlags);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean containsAny(List<String> keys, String text, WorldBookEntry entry) {
        for (String key : keys) {
            if (matchKey(key, text, entry)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAll(List<String> keys, String text, WorldBookEntry entry) {
        for (String key : keys) {
            if (!matchKey(key, text, entry)) {
                return false;
            }
        }
        return true;
    }

    /** probability 概率判定（0-100，100 必过） */
    private static boolean passProbability(WorldBookEntry entry) {
        int p = entry.getProbability();
        if (p >= PROBABILITY_FULL) {
            return true;
        }
        if (p <= 0) {
            return false;
        }
        return Math.random() * 100 < p;
    }
}
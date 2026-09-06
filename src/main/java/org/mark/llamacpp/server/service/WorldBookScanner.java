package org.mark.llamacpp.server.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    /**
     * 扫描激活条目。
     *
     * @param entries 全部世界书条目
     * @param recentMessages 按时间序的最近消息文本（调用方应传入足够窗口，scanner 内部按 depth 截断）
     * @return 激活条目，按 order 升序
     */
    public static List<WorldBookEntry> scan(List<WorldBookEntry> entries, List<String> recentMessages) {
        List<WorldBookEntry> activated = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return activated;
        }
        for (WorldBookEntry entry : entries) {
            if (entry == null || entry.isDisabled()) {
                continue;
            }
            if (!passProbability(entry)) {
                continue;
            }
            if (entry.isConstant()) {
                activated.add(entry);
                continue;
            }
            if (isActivated(entry, recentMessages)) {
                activated.add(entry);
            }
        }
        activated.sort(Comparator.comparingInt(WorldBookEntry::getOrder));
        if (!activated.isEmpty()) {
            logger.info("[WorldBook] 激活 {} / {} 条", activated.size(), entries.size());
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
        boolean primaryHit = containsAny(keys, joined, caseSensitive);
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
                return containsAll(secondary, joined, caseSensitive);
            case LOGIC_NOT_ALL:
                return !containsAll(secondary, joined, caseSensitive);
            case LOGIC_NOT_ANY:
                return !containsAny(secondary, joined, caseSensitive);
            case LOGIC_AND_ANY:
            default:
                return containsAny(secondary, joined, caseSensitive);
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

    private static boolean containsAny(List<String> keys, String text, boolean caseSensitive) {
        for (String key : keys) {
            if (key == null || key.isEmpty()) {
                continue;
            }
            if (caseSensitive ? text.contains(key) : text.toLowerCase().contains(key.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAll(List<String> keys, String text, boolean caseSensitive) {
        for (String key : keys) {
            if (key == null || key.isEmpty()) {
                continue;
            }
            if (!(caseSensitive ? text.contains(key) : text.toLowerCase().contains(key.toLowerCase()))) {
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
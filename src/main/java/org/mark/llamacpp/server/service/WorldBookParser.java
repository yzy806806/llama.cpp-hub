package org.mark.llamacpp.server.service;

import java.util.ArrayList;
import java.util.List;

import org.mark.llamacpp.server.struct.WorldBookEntry;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 酒馆世界书 JSON 解析。
 * <p>
 * 酒馆世界书格式：{ "entries": { "&lt;uid&gt;": &lt;Entry&gt;, ... } }
 * 或新版数组格式：{ "entries": [ &lt;Entry&gt;, ... ] }。
 * 兼容两种。
 */
public final class WorldBookParser {

    private static final Logger logger = LoggerFactory.getLogger(WorldBookParser.class);

    private WorldBookParser() {
    }

    /**
     * 从世界书 JSON 字符串解析条目列表。
     *
     * @param json 酒馆世界书 JSON（可含 entries 容器，也容忍裸 Entry 或裸 Entry 数组）
     * @return 有效条目列表（空绝不返回 null）
     */
    public static List<WorldBookEntry> parse(String json) {
        List<WorldBookEntry> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        JsonObject root = JsonUtil.tryParseObject(json);
        if (root == null) {
            logger.warn("[WorldBook] 解析世界书 JSON 失败（非对象）");
            return result;
        }
        if (root.has("entries") && root.get("entries").isJsonObject()) {
            parseEntriesObject(root.getAsJsonObject("entries"), result);
        } else if (root.has("entries") && root.get("entries").isJsonArray()) {
            parseEntriesArray(root.getAsJsonArray("entries"), result);
        } else if (root.has("key") || root.has("keys") || root.has("content")) {
            // 裸单个条目
            WorldBookEntry entry = parseSingle(root);
            if (entry != null) {
                result.add(entry);
            }
        } else {
            logger.warn("[WorldBook] 世界书 JSON 无 entries 结构，忽略");
        }
        logger.info("[WorldBook] 解析完成，共 {} 条", result.size());
        return result;
    }

    /** 旧版对象格式 entries: { "0": {...}, "1": {...} } */
    private static void parseEntriesObject(JsonObject entriesObj, List<WorldBookEntry> out) {
        for (String key : entriesObj.keySet()) {
            JsonElement el = entriesObj.get(key);
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            WorldBookEntry entry = parseSingle(el.getAsJsonObject());
            if (entry == null) {
                continue;
            }
            if (entry.getUid() == null || entry.getUid().isBlank()) {
                entry.setUid(key);
            }
            out.add(entry);
        }
    }

    /** 新版数组格式 entries: [ {...}, {...} ] */
    private static void parseEntriesArray(JsonArray entriesArr, List<WorldBookEntry> out) {
        for (int i = 0; i < entriesArr.size(); i++) {
            JsonElement el = entriesArr.get(i);
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            WorldBookEntry entry = parseSingle(el.getAsJsonObject());
            if (entry != null) {
                out.add(entry);
            }
        }
    }

    /** 解析单个条目对象（兼容酒馆字段名与常见别名） */
    private static WorldBookEntry parseSingle(JsonObject obj) {
        WorldBookEntry entry = new WorldBookEntry();
        entry.setUid(JsonUtil.getJsonString(obj, "uid", JsonUtil.getJsonString(obj, "id", "")));

        // keys: string[] 或单字符串
        entry.setKeys(readStringList(obj, "key", "keys"));
        // keysecondary: string[] 或单字符串
        entry.setSecondaryKeys(readStringList(obj, "keysecondary", "keySecondary"));

        entry.setContent(JsonUtil.getJsonString(obj, "content", ""));
        entry.setComment(JsonUtil.getJsonString(obj, "comment", ""));
        entry.setConstant(readBoolean(obj, "constant", false));
        entry.setSelective(readBoolean(obj, "selective", false));
        entry.setSelectiveLogic(readInt(obj, "selectiveLogic", 0));
        entry.setPosition(readInt(obj, "position", 0));
        entry.setDepth(readInt(obj, "depth", 4));
        entry.setOrder(readInt(obj, "order", 100));
        entry.setProbability(readInt(obj, "probability", 100));
        entry.setCaseSensitive(readBoolean(obj, "caseSensitive", false));
        entry.setMatchWholeWords(readBoolean(obj, "matchWholeWords", readBoolean(obj, "match_whole_words", false)));
        entry.setDisabled(readBoolean(obj, "disable", false));
        entry.setExcludeRecursion(readBoolean(obj, "excludeRecursion", false));
        entry.setPreventRecursion(readBoolean(obj, "preventRecursion", false));
        return entry;
    }

    private static List<String> readStringList(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (!obj.has(key) || obj.get(key).isJsonNull()) {
                continue;
            }
            JsonElement el = obj.get(key);
            if (el.isJsonArray()) {
                List<String> list = JsonUtil.getJsonStringList(el);
                if (list != null && !list.isEmpty()) {
                    return list;
                }
            } else if (el.isJsonPrimitive()) {
                String s = el.getAsString();
                if (s != null && !s.isBlank()) {
                    List<String> list = new ArrayList<>();
                    list.add(s);
                    return list;
                }
            }
        }
        return new ArrayList<>();
    }

    private static boolean readBoolean(JsonObject obj, String key, boolean fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int readInt(JsonObject obj, String key, int fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }
}
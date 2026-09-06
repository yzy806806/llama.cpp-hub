package org.mark.llamacpp.server.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 酒馆模板宏解析器（Tavern Template Resolver）。
 * <p>
 * 对标 SillyTavern 的 substituteParams：
 * <ul>
 * <li>{@code {{char}}} / {@code {{charName}}} —— 角色名</li>
 * <li>{@code {{user}}} / {@code {{userName}}} —— 用户（玩家）名</li>
 * <li>{@code {{persona}}} —— 用户人设描述</li>
 * <li>{@code {{original}}} —— 原文（无替换，占位保留）</li>
 * </ul>
 * 替换发生在<b>出站组装时</b>（system prompt / 世界书内容 / 开场白），
 * 存储层保持原始 {{macro}} 不变——与酒馆一致（卡里存宏，注入时替换）。
 */
public final class TavernTemplateResolver {

    /** 大小写不敏感宏：{{char}} {{charName}} {{user}} {{userName}} {{persona}} {{original}} */
    private static final Pattern MACRO_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z_]+)\\s*\\}\\}");

    private TavernTemplateResolver() {
    }

    /**
     * 替换文本中的所有已知宏。
     *
     * @param text 含宏的原始文本
     * @param charName 角色名（assistant name）
     * @param userName 用户名（默认 "用户"）
     * @param persona 用户人设（可空）
     * @return 替换后的文本
     */
    public static String resolve(String text, String charName, String userName, String persona) {
        if (text == null || text.isEmpty() || !text.contains("{{")) {
            return text;
        }
        String safeChar = blankToDefault(charName, "角色");
        String safeUser = blankToDefault(userName, "用户");
        String safePersona = persona == null ? "" : persona;

        Matcher matcher = MACRO_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 32);
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase();
            String replacement;
            switch (name) {
                case "char":
                case "charname":
                    replacement = safeChar;
                    break;
                case "user":
                case "username":
                    replacement = safeUser;
                    break;
                case "persona":
                    replacement = safePersona;
                    break;
                default:
                    // 未知宏原样保留（酒馆行为：未知宏不替换）
                    replacement = matcher.group(0);
                    break;
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 便捷重载：默认用户名 */
    public static String resolve(String text, String charName) {
        return resolve(text, charName, "用户", null);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 构建宏上下文（供调试/预览用） */
    public static Map<String, String> buildContext(String charName, String userName, String persona) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("char", blankToDefault(charName, "角色"));
        ctx.put("user", blankToDefault(userName, "用户"));
        ctx.put("persona", persona == null ? "" : persona);
        return ctx;
    }
}

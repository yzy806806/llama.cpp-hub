package org.mark.llamacpp.server.struct;

import java.util.ArrayList;
import java.util.List;

/**
 * 酒馆（SillyTavern）世界书条目。
 * <p>
 * 字段映射自酒馆 world-info.js 权威定义。世界书 JSON 格式：
 * <pre>{ "entries": { "&lt;uid&gt;": &lt;Entry&gt;, ... } }</pre>
 */
public class WorldBookEntry {

    private String uid;
    private List<String> keys = new ArrayList<>();
    private List<String> secondaryKeys = new ArrayList<>();
    private String content = "";
    private String comment = "";
    private boolean constant;
    private boolean selective;
    /** world_info_logic: AND_ANY=0, NOT_ALL=1, NOT_ANY=2, AND_ALL=3 */
    private int selectiveLogic;
    /** world_info_position: before=0, after=1, ...（本实现统一降级为新消息段注入） */
    private int position;
    /** 关键词扫描深度（最近 N 条消息），默认 4 */
    private int depth = 4;
    private int order;
    private int probability = 100;
    private boolean caseSensitive;
    /** 整词匹配（酒馆 match_whole_words）：单词 key 只在词边界处命中，防 "he" 误命中 "the" */
    private boolean matchWholeWords;
    private boolean disabled;
    private boolean excludeRecursion;
    private boolean preventRecursion;

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public List<String> getKeys() {
        return keys;
    }

    public void setKeys(List<String> keys) {
        this.keys = keys == null ? new ArrayList<>() : keys;
    }

    public List<String> getSecondaryKeys() {
        return secondaryKeys;
    }

    public void setSecondaryKeys(List<String> secondaryKeys) {
        this.secondaryKeys = secondaryKeys == null ? new ArrayList<>() : secondaryKeys;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content == null ? "" : content;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment == null ? "" : comment;
    }

    public boolean isConstant() {
        return constant;
    }

    public void setConstant(boolean constant) {
        this.constant = constant;
    }

    public boolean isSelective() {
        return selective;
    }

    public void setSelective(boolean selective) {
        this.selective = selective;
    }

    public int getSelectiveLogic() {
        return selectiveLogic;
    }

    public void setSelectiveLogic(int selectiveLogic) {
        this.selectiveLogic = selectiveLogic;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public int getProbability() {
        return probability;
    }

    public void setProbability(int probability) {
        this.probability = probability;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    public boolean isMatchWholeWords() {
        return matchWholeWords;
    }

    public void setMatchWholeWords(boolean matchWholeWords) {
        this.matchWholeWords = matchWholeWords;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isExcludeRecursion() {
        return excludeRecursion;
    }

    public void setExcludeRecursion(boolean excludeRecursion) {
        this.excludeRecursion = excludeRecursion;
    }

    public boolean isPreventRecursion() {
        return preventRecursion;
    }

    public void setPreventRecursion(boolean preventRecursion) {
        this.preventRecursion = preventRecursion;
    }

    /** 组装注入文本（对标酒馆 [World Info] 段） */
    public String formatContent() {
        if (comment != null && !comment.isBlank()) {
            return "[" + comment + "]\n" + content;
        }
        return content;
    }
}
package org.mark.llamacpp.server.struct;

import java.util.ArrayList;
import java.util.List;

/**
 * 酒馆（SillyTavern）角色卡数据模型。
 * <p>
 * 兼容 chara_card_v2 / chara_card_v3，字段映射自酒馆规范
 * （name/description/personality/scenario/first_mes/mes_example/system_prompt...）。
 * store 在 assistant 的 state.json 的 card 字段里。
 */
public class AssistantCard {

    /** 角色名 —— 覆盖 assistant.name（若酒馆卡有 name） */
    private String name;

    /** 角色描述（who the character is） */
    private String description;

    /** 性格 */
    private String personality;

    /** 场景设定 */
    private String scenario;

    /** 开场白（新对话首条 assistant 消息） */
    private String firstMes;

    /** 示例对话 */
    private String mesExample;

    /** 角色卡显式 system prompt（若存在，追加到组装结果） */
    private String systemPrompt;

    /** 世界书（角色卡内嵌 character_book 的原始 JSON），独立存 assistant.worldBook */
    private String characterBook;

    /** 对话后指令（post_history_instructions）：注入到历史之后的指令段（世界书同通道） */
    private String postHistoryInstructions;

    public AssistantCard() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPersonality() {
        return personality;
    }

    public void setPersonality(String personality) {
        this.personality = personality;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public String getFirstMes() {
        return firstMes;
    }

    public void setFirstMes(String firstMes) {
        this.firstMes = firstMes;
    }

    public String getMesExample() {
        return mesExample;
    }

    public void setMesExample(String mesExample) {
        this.mesExample = mesExample;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getCharacterBook() {
        return characterBook;
    }

    public void setCharacterBook(String characterBook) {
        this.characterBook = characterBook;
    }

    public String getPostHistoryInstructions() {
        return postHistoryInstructions;
    }

    public void setPostHistoryInstructions(String postHistoryInstructions) {
        this.postHistoryInstructions = postHistoryInstructions;
    }

    /** 是否携带任何有效内容（用于判断旧 assistant 是否有 card） */
    public boolean isEmpty() {
        return (name == null || name.isBlank())
                && (description == null || description.isBlank())
                && (personality == null || personality.isBlank())
                && (scenario == null || scenario.isBlank())
                && (firstMes == null || firstMes.isBlank())
                && (mesExample == null || mesExample.isBlank())
                && (systemPrompt == null || systemPrompt.isBlank());
    }

    /** 组装为 system prompt（对标酒馆角色定义 → 单条 system 消息） */
    public String buildSystemPrompt() {
        List<String> parts = new ArrayList<>();
        if (description != null && !description.isBlank()) {
            parts.add("[Character Description]\n" + description);
        }
        if (personality != null && !personality.isBlank()) {
            parts.add("[Personality]\n" + personality);
        }
        if (scenario != null && !scenario.isBlank()) {
            parts.add("[Scenario]\n" + scenario);
        }
        if (mesExample != null && !mesExample.isBlank()) {
            parts.add("[Example Dialogue]\n" + mesExample);
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            parts.add(systemPrompt);
        }
        if (parts.isEmpty()) {
            return null;
        }
        return String.join("\n\n", parts);
    }
}
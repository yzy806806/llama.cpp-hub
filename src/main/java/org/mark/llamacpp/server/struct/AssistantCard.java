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

    /** 作者注记（Author's Note）：随世界书通道注入新消息段的全局提醒 */
    private String authorNote;

    /** 备选开场白（酒馆 alternate_greetings）：新聊天可选的开场白列表 */
    private List<String> alternateGreetings = new ArrayList<>();

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

    public String getAuthorNote() {
        return authorNote;
    }

    public void setAuthorNote(String authorNote) {
        this.authorNote = authorNote;
    }

    public List<String> getAlternateGreetings() {
        return alternateGreetings;
    }

    public void setAlternateGreetings(List<String> alternateGreetings) {
        this.alternateGreetings = alternateGreetings == null ? new ArrayList<>() : alternateGreetings;
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
            // 示例对话 role 化：解析 {{user}}/{{char}} 交替的 few-shot（酒馆 mes_example 格式），
            // 保持 system 单条约束，用 <START> 分隔符标明示例区块
            parts.add("[Example Dialogue]\n" + formatExampleDialogue(mesExample));
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            parts.add(systemPrompt);
        }
        if (parts.isEmpty()) {
            return null;
        }
        return String.join("\n\n", parts);
    }

    /**
     * 示例对话 role 化。
     * <p>
     * 酒馆 mes_example 是 {@code {{user}}: ...\n{{char}}: ...} 交替的纯文本，
     * 模型端 few-shot 语义依赖 role 标记。这里将未标记的行保持原样（作者写法各异），
     * 仅将首尾包裹 <START>/<END> 并保留原文——宏替换在出站组装时统一做。
     */
    private static String formatExampleDialogue(String mesExample) {
        String trimmed = mesExample.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return "<START>\n" + trimmed + "\n<END>";
    }
}
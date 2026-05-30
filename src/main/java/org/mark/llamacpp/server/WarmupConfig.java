package org.mark.llamacpp.server;

import java.util.Map;

/**
 * JIT 智能模型预热配置
 */
public class WarmupConfig {

    /**
     * 是否启用预热
     */
    private boolean enabled = false;

    /**
     * 预热 prompt（默认简短）
     */
    private String prompt = "Hello";

    /**
     * 预热生成长度（token 数）
     */
    private int maxTokens = 8;

    /**
     * 预热超时时间（毫秒）
     */
    private int timeout = 30000;

    public WarmupConfig() {
    }

    public WarmupConfig(boolean enabled, String prompt, int maxTokens, int timeout) {
        this.enabled = enabled;
        this.prompt = prompt;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    /**
     * 从 Map 构建 WarmupConfig
     */
    public static WarmupConfig fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return new WarmupConfig();
        }
        WarmupConfig config = new WarmupConfig();
        if (map.containsKey("enabled")) {
            Object enabled = map.get("enabled");
            if (enabled instanceof Boolean) {
                config.setEnabled((Boolean) enabled);
            } else if (enabled instanceof String) {
                config.setEnabled(Boolean.parseBoolean((String) enabled));
            }
        }
        if (map.containsKey("prompt") && map.get("prompt") != null) {
            config.setPrompt(String.valueOf(map.get("prompt")));
        }
        if (map.containsKey("maxTokens") && map.get("maxTokens") != null) {
            Object maxTokens = map.get("maxTokens");
            if (maxTokens instanceof Number) {
                config.setMaxTokens(((Number) maxTokens).intValue());
            } else {
                try {
                    config.setMaxTokens(Integer.parseInt(String.valueOf(maxTokens)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (map.containsKey("timeout") && map.get("timeout") != null) {
            Object timeout = map.get("timeout");
            if (timeout instanceof Number) {
                config.setTimeout(((Number) timeout).intValue());
            } else {
                try {
                    config.setTimeout(Integer.parseInt(String.valueOf(timeout)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return config;
    }
}
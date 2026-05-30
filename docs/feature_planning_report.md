# llama.cpp-hub 新功能规划报告

> 日期: 2026-05-30
> 任务: t_53243c9a - 构思新功能：增强 JIT 自动加载或其他特性

---

## 背景分析

llama.cpp-hub 是一个基于 llama.cpp 的模型管理和服务框架，核心价值在于：

1. **JIT 自动加载** - 收到 API 请求时自动加载模型，无需手动预加载
2. **多协议兼容** - 同时支持 OpenAI、Anthropic、Ollama、LM Studio 协议
3. **远程聚合** - 多节点服务统一管理
4. **资源管理** - TTL 空闲卸载、并发限制、LRU/FIFO 策略

当前 JIT 功能已实现基础能力，但仍存在可增强空间。

---

## 建议的新功能

### 功能 1：智能模型预热 (Smart Model Warmup)

#### 功能描述

在 JIT 自动加载模型后，自动执行一次轻量级推理预热，确保：
- GPU kernels 完全编译并缓存
- 首次真实请求无需等待冷启动延迟
- 模型状态真正"就绪"后才标记为可用

#### 价值
- 消除首次推理的冷启动延迟（通常 3-10 秒）
- 提升用户体验，减少"第一请求超时"问题
- 提前发现模型加载异常

#### 实现思路

```
配置项 (application.json):
{
  "jit": {
    "warmup": {
      "enabled": true,           // 是否启用预热
      "prompt": "Hello",         // 预热 prompt（默认简短）
      "maxTokens": 8,            // 预热生成长度
      "timeout": 30000           // 预热超时（毫秒）
    }
  }
}
```

**实现位置**: `ModelManager` 或 `JITLoader` 类

**流程**:
1. JIT 加载模型进程启动
2. 等待 llama-server 就绪（健康检查）
3. 发送预热请求（短 prompt，少量 token）
4. 预热成功后标记模型为 "ready"
5. 预热失败则标记加载失败，通知客户端

---

### 功能 2：Webhook 事件通知

#### 功能描述

当模型生命周期事件发生时，向配置的 Webhook URL 发送 HTTP POST 通知，实现：
- 外部系统感知模型状态变化
- 与 CI/CD、监控告警系统集成
- 自动化运维工作流触发

#### 价值
- 与外部系统集成，支持自动化运维
- 实时告警模型异常
- 审计追踪模型使用情况

#### 事件类型

| 事件 | 说明 | 负载 |
|------|------|------|
| `model.loaded` | 模型加载成功 | `{modelId, nodeId, timestamp, config}` |
| `model.unloaded` | 模型卸载（空闲超时/手动） | `{modelId, nodeId, timestamp, reason}` |
| `model.error` | 模型加载/推理错误 | `{modelId, nodeId, timestamp, error}` |
| `model.request` | 收到推理请求 | `{modelId, nodeId, timestamp, endpoint}` |
| `model.request.complete` | 推理请求完成 | `{modelId, nodeId, timestamp, duration, tokens}` |

#### 配置

```json
{
  "webhook": {
    "enabled": true,
    "url": "https://your-server.com/webhook",
    "secret": "optional-signing-secret",
    "events": ["model.loaded", "model.unloaded", "model.error"],
    "retry": {
      "maxAttempts": 3,
      "interval": 1000
    }
  }
}
```

#### 实现思路

**新增模块**: `WebhookNotifier`

```java
public class WebhookEvent {
    private String event;
    private String modelId;
    private String nodeId;
    private long timestamp;
    private Map<String, Object> payload;
}
```

**事件发布**: 在 `ModelManager` 的加载/卸载/错误处理逻辑中发布事件

**消息队列** (可选): 使用阻塞队列避免阻塞主请求线程

---

### 功能 3：模型标签与智能路由

#### 功能描述

为模型添加标签（tag），实现：
- 按标签分组显示和管理模型
- 请求路由时支持按标签选择模型
- 简化复杂环境下的模型选择

#### 价值
- 大规模部署时便于模型分类管理
- 支持 A/B 测试、灰度发布场景
- 简化客户端请求（指定标签而非具体模型名）

#### 配置与数据模型

**模型元数据扩展** (models.json):

```json
{
  "models": [
    {
      "id": "qwen3-8b",
      "path": "models/Qwen3-8B-Q8_0",
      "tags": ["qwen", "8b", "fast", "chat"],
      "capabilities": ["chat", "function-call"],
      "priority": 10
    },
    {
      "id": "qwen3-72b",
      "path": "models/Qwen3-72B-Q8_0",
      "tags": ["qwen", "72b", "high-quality"],
      "capabilities": ["chat", "function-call", "thinking"],
      "priority": 5
    }
  ]
}
```

**API 扩展**:

```bash
# 按标签查询模型
GET /v1/models?tags=qwen,fast

# 请求时指定标签（而非模型名）
POST /v1/chat/completions
{
  "model": "tag:fast",  # 或 "model": "tag:qwen&high-quality"
  "messages": [...]
}
```

#### 实现思路

**1. 数据层**:
- 扩展 `ModelInfo` 类，添加 `tags: List<String>` 字段
- 解析 models.json 时加载标签

**2. API 层**:
- 修改 `OpenAIService` 的模型解析逻辑，支持 `tag:xxx` 前缀
- 实现标签匹配算法（AND/OR 逻辑）

**3. 前端**:
- 模型列表增加标签筛选器
- 模型卡片显示标签 badge

---

## 优先级建议

| 功能 | 优先级 | 复杂度 | 依赖 |
|------|--------|--------|------|
| 智能模型预热 | P1 | 低 | 现有 JIT 模块 |
| Webhook 事件通知 | P2 | 中 | 事件系统（可选） |
| 模型标签与智能路由 | P2 | 中 | models.json 解析 |

---

## 风险与注意事项

1. **预热超时**: 预热请求可能超时，需合理设置 TTL 和超时时间
2. **Webhook 可靠性**: 需考虑失败重试和异步发送，避免阻塞主流程
3. **标签冲突**: 多节点环境下标签需保证唯一性
4. **向后兼容**: 新配置项应有默认值，不影响现有用户

---

## 总结

以上三个功能从不同维度增强 llama.cpp-hub 的核心价值：

1. **预热** 解决 JIT 首次请求延迟问题，直接提升用户体验
2. **Webhook** 打通外部系统，支持自动化运维
3. **标签路由** 简化大规模模型管理，支持复杂部署场景

建议优先实现 **智能模型预热**，投入产出比最高。
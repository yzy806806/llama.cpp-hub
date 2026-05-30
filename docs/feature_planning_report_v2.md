# llama.cpp-hub JIT 增强功能规划报告 (第二轮)

> 日期: 2026-05-30
> 任务: t_31e8db5d - 思考增强 JIT 自动加载或其他特性

---

## 背景分析

llama.cpp-hub 是一个基于 llama.cpp 的模型管理和服务框架，核心价值在于：

1. **JIT 自动加载** - 收到 API 请求时自动加载模型，无需手动预加载
2. **多协议兼容** - 同时支持 OpenAI、Anthropic、Ollama、LM Studio 协议
3. **资源管理** - TTL 空闲卸载、并发限制、LRU/FIFO 策略
4. **模型能力检测** - 自动识别 embedding、rerank、tools、thinking 等能力

### 已规划功能（来自前两次规划）

| 功能 | 优先级 | 状态 |
|------|--------|------|
| 智能模型预热 | P1 | 待实现 |
| Webhook 事件通知 | P2 | 待实现 |
| 模型标签与智能路由 | P2 | 待实现 |

### 用户需求（来自 GitHub Issues）

- **Issue #28**: 便携性功能 - 模型不存在时加载，需严格设计避免拖垮机器 (JIT 核心)
- **Issue #30**: Web 端体验 - 为简易聊天页面添加可关闭插拔的工具
- **Issue #29**: Web 端体验 - 简易聊天自动生成聊天名称
- **Issue #16**: Console 日志显示 UTC 时间

---

## 新增功能建议

基于以上分析，提出以下新的增强方向：

---

### 功能 4：请求队列与优先级系统 (Request Queuing & Priority)

#### 功能描述

当模型正在加载时，将请求加入队列而非立即失败（500），等待模型就绪后处理。实现请求优先级机制，高优先级请求可以"插队"。

#### 价值

- 提升首次请求成功率，JIT 加载期间请求不立即失败
- 支持企业场景的优先级需求
- 改善用户体验，减少重复请求

#### 实现思路

```
配置项 (application.json):
{
  "jit": {
    "queue": {
      "enabled": true,
      "maxQueueSize": 100,
      "defaultTimeout": 60000,
      "priority": {
        "enabled": true,
        "header": "X-Request-Priority"  // high, normal, low
      }
    }
  }
}
```

**流程:**
1. 收到 API 请求时，检查模型是否已加载
2. 如果模型正在加载：
   - 判断队列是否已满，满则返回 503
   - 将请求加入优先级队列
   - 等待模型就绪后按优先级处理
3. 如果模型未加载（第一次请求）：
   - 启动 JIT 加载流程
   - 将请求加入队列等待
4. 超时的请求返回 504 Gateway Timeout

**实现位置**: `OpenAIService` 或新增 `RequestQueueManager`

#### 相关文件

- `src/main/java/org/mark/llamacpp/server/service/OpenAIService.java`
- 新增 `src/main/java/org/mark/llamacpp/server/RequestQueueManager.java`

---

### 功能 5：模型健康监控与自动恢复

#### 功能描述

对已加载的模型进程进行定期健康检查，检测到异常时自动重启。提供模型进程状态的可观测性。

#### 价值

- 提高系统可靠性，模型崩溃后自动恢复
- 减少人工干预，提升 SLA
- 为运维提供进程状态数据

#### 实现思路

```
配置项:
{
  "jit": {
    "healthCheck": {
      "enabled": true,
      "intervalSeconds": 30,
      "timeoutMs": 5000,
      "maxRetries": 3,
      "autoRestart": true
    }
  }
}
```

**健康检查项:**
1. 进程是否存在
2. HTTP 端点响应是否正常
3. 进程 CPU/内存使用情况

**事件类型:**

| 事件 | 说明 |
|------|------|
| `model.healthy` | 模型健康检查通过 |
| `model.unhealthy` | 模型健康检查失败 |
| `model.restarting` | 模型正在重启 |
| `model.restarted` | 模型重启成功 |
| `model.restart_failed` | 模型重启失败 |

**实现位置**: `LlamaServerManager` 或新增 `ModelHealthMonitor`

---

### 功能 6：Prometheus 指标导出

#### 功能描述

暴露 Prometheus 指标端点，导出系统运行时指标，供 Prometheus 采集和 Grafana 可视化。

#### 价值

- 与现有监控体系集成
- 便于容量规划和性能分析
- 支持告警规则配置

#### 指标列表

| 指标名 | 类型 | 描述 |
|--------|------|------|
| `llama_hub_loaded_models` | Gauge | 当前加载的模型数量 |
| `llama_hub_model_load_duration_seconds` | Histogram | 模型加载耗时 |
| `llama_hub_request_duration_seconds` | Histogram | 请求耗时 |
| `llama_hub_requests_total` | Counter | 请求总数 |
| `llama_hub_requests_errors_total` | Counter | 请求错误数 |
| `llama_hub_tokens_total` | Counter | 生成的 token 总数 |
| `llama_hub_queue_size` | Gauge | 当前队列大小 |
| `llama_hub_vram_used_bytes` | Gauge | GPU 显存使用量 |

#### 实现思路

**端点**: `/metrics` (OpenMetrics 格式)

**实现方式**:
1. 添加 Micrometer 依赖或自行实现
2. 在关键路径埋点（请求开始/结束、模型加载/卸载）
3. 暴露 `/metrics` 端点

#### 配置

```json
{
  "metrics": {
    "enabled": true,
    "port": 9090,
    "path": "/metrics"
  }
}
```

---

### 功能 7：请求去重与结果缓存

#### 功能描述

对相同 prompt 的请求进行去重，短时间内返回缓存结果。减少重复计算，提升吞吐量。

#### 价值

- 减少重复计算，降低 GPU 使用
- 提升相同问题重复提问的响应速度
- 适用于知识库问答等重复场景

#### 实现思路

```
配置项:
{
  "jit": {
    "dedup": {
      "enabled": false,
      "ttlSeconds": 300,
      "maxCacheSize": 1000
    }
  }
}
```

**去重逻辑:**
1. 对请求内容计算 MD5/SHA256 哈希
2. 检查缓存是否存在且未过期
3. 如果命中，返回缓存结果
4. 如果未命中，执行推理并缓存结果

**限制:**
- 仅对 `temperature=0` 或低 temperature 请求生效
- 不缓存包含 `stream=true` 的请求
- 不缓存 tool call 请求

**实现位置**: 新增 `RequestDeduplicator` 类

---

### 功能 8：GPU 显存管理增强

#### 功能描述

在模型加载前预估显存需求，提供告警机制。防止因显存不足导致加载失败。

#### 价值

- 提前预警显存不足
- 优化模型卸载策略（优先卸载大模型）
- 提供显存使用的可观测性

#### 实现思路

**显存估算:**
1. 从 GGUF 头信息读取 `llama.context.embedding` 和 `llama.block.count`
2. 根据量化位数估算模型大小

```java
// 估算公式（简化版）
long estimatedVRAM = 
    (params.n_embd() * params.n_layer() * 4 +   // MLP weights
     params.n_vocab() * params.n_embd() * 2)    // embedding
    * quantizationBytes() / 1024 / 1024;        // MB
```

**配置:**

```json
{
  "jit": {
    "vram": {
      "warningThreshold": 0.9,
      "criticalThreshold": 0.95,
      "autoEvictOnWarning": true
    }
  }
}
```

**策略:**
1. 加载前计算预估显存
2. 如果当前使用 + 预估 > 警告阈值，优先卸载其他模型
3. 如果超过临界阈值，拒绝加载

---

## 优先级建议 (第二轮)

| 功能 | 优先级 | 复杂度 | 依赖 |
|------|--------|--------|------|
| 请求队列与优先级系统 | P1 | 中 | 现有 JIT 模块 |
| Prometheus 指标导出 | P2 | 低 | - |
| 模型健康监控 | P2 | 中 | 请求队列 |
| 请求去重与缓存 | P3 | 中 | - |
| GPU 显存管理 | P3 | 中 | - |

---

## 风险与注意事项

1. **队列内存**: 请求队列会占用内存，需限制队列大小防止 OOM
2. **缓存一致性**: 请求去重可能导致返回过期结果，需合理设置 TTL
3. **指标性能**: 指标导出不应影响请求性能，建议异步处理
4. **显存估算精度**: 简化估算可能不准确，需持续调优

---

## 总结

本次规划在之前三个功能（预热、Webhook、标签）的基础上，新增了五个 JIT 增强方向：

1. **请求队列** - 解决 JIT 加载期间请求失败问题
2. **健康监控** - 提高系统可靠性
3. **指标导出** - 可观测性支持
4. **请求去重** - 性能优化
5. **显存管理** - 资源保护

建议优先实现 **请求队列与 Prometheus 指标导出**，两者投入产出比高，且互不依赖。
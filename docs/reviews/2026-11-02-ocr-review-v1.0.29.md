# OCR 审查报告 — v1.0.29（就绪检测兼容 b11960）

- 日期：2026-11-02
- 范围：`5ae3719`（v1.0.28）→ `db1234c`
- 变更：1 文件 `src/main/java/org/mark/llamacpp/server/LlamaServerManager.java`（+4/−1）
- 工具：`ocr delegate preview` / `ocr delegate rule`（open-code-review v1.13.3）

## 变更内容

```diff
- if (line.contains("llama_server: model loaded") || line.contains("update_slots: all slots are idle")) {
+ if (line.contains("model loaded") || line.contains("all slots are idle")) {
```

## 背景

llama.cpp b11960 把就绪日志的前缀从 `llama_server:` 改成 `server_run_i:`，hub 的
固定字符串匹配失效：子进程已 `model loaded` 并监听，hub 的 `/api/models/loaded`
仍返回空，WebUI 卡在"正在加载"。

## 审查关注点与结论

| 关注点 | 结论 |
|--------|------|
| 放宽匹配是否会误判就绪 | 不会。handler 只看子进程 stdout；`model loaded` 是 llama.cpp 的就绪行特有文案，加载失败路径走 `isModelErrorLine` 分支，文案不同 |
| 是否影响旧版二进制 | 兼容。b11756 及更早的行是 `llama_server: model loaded`，同样包含 `model loaded` |
| 是否漏掉另一条就绪判据 | 没有。`all slots are idle` 同步放宽到消息体，兼容 `update_slots:` 与将来换前缀 |
| 线程安全 / 性能 | 无变化，仍在单条 stdout 回调里做两次 `String.contains` |
| 是否有其它调用方依赖原字符串 | 全仓仅此一处匹配（`grep -rn "model loaded"`） |

发现数：0。无需要修改的项。

## 验证

- `javac-linux.sh` 编译通过（exit 0）。
- `SecurityFixTest`：14 通过 / 0 失败。
- 7840H 实机（Windows，llama.cpp b11960）：替换 `LlamaServerManager.class` 后经
  `/api/models/load` 加载 `Qwen3.8-27B`，12 秒后 `/api/models/loaded` 返回
  `status=running port=8081`（修复前恒为空）。

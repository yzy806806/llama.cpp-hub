# EasyChat v1.0.23 代码审查报告（opencode review）

> 审查对象：commit `e3d3d41`（v1.0.23-security）· 5 文件 +313/-23
> 审查方式：opencode review（OCR）选文件定规则 + 人工逐行精读 diff
> 审查日期：2026-09-07
> 涉及功能：Prompt Debugger 对齐实发 + 世界书激活可视化

---

## 结论

**无 High/Critical 问题。** 1 个 Medium（预算超限语义变化，需知情），2 个 Low（记录不修）。整体实现质量良好，测试覆盖到位（98/98 全绿）。

---

## 🟡 Medium — 1 个

### Bug 1：世界书预算超限语义变化（break → continue，未声明）

**位置**：`EasyChatService.buildWorldInfoPrefixDetail()` 预算循环

- **原版**（v1.0.22）：`if (used + tokens > budget) break;` —— order 靠前的大条目超限后，**后续条目全部不注入**
- **新版**（v1.0.23，为可视化逐条标记 injected 引入）：`continue` —— 跳过超限条目，**后续小条目仍注入**并标 `injected=false`

**真实影响**：同一世界书在预算超限时，v1.0.22 → v1.0.23 注入内容会不同。新版不会被单个大条目阻断，预算利用更充分——方向合理，但 commit 未声明该行为变化。

**处置**：接受保留（更合理的预算语义）。前端 `⚠️未注入(budget)` 标签依赖此 continue 语义（否则无法区分"跳过"与"未注入"）。

---

## 🟢 Low — 2 个（记录不修）

### Bug 2：跨消息拼接命中时明细无 key

**位置**：`WorldBookScanner.buildHit()`

`isActivated` 用 `joinWindow`（所有消息拼成一句）匹配；`buildHit` 逐条消息匹配。极端场景：key="dragon" 恰好拆在两条消息边界（msg1 尾 "dra" + msg2 头 "gon"），拼接命中 → 条目激活，但逐条定位不到 → `matchedKey=null, index=-1`。前端显示无 key 无位置，但仍显示激活。

**处置**：罕见，不修。

### Bug 3：递归轮激活条目无 key 定位

**位置**：`WorldBookScanner.scanDetailed()` 递归轮

递归命中的条目（被其他条目内容触发）`matchedKey=null`，前端只显示 `source=recursion`。语义正确（本来就是间接触发）。

**处置**：不修。

---

## ✅ 验证通过的关键点

| 检查项 | 结果 |
|---|---|
| `scan` 委托 `scanDetailed` 激活集合一致 | ✅ 单测验证 + 87 项回归 |
| `buildHit` 索引映射 `offset + i` | ✅ 正确 |
| 前端 XSS | ✅ `textContent` 渲染（非 innerHTML） |
| 侧栏刷新时机（onMessageComplete in finally） | ✅ 正确——后端先写 user fragment 再响应，刷新时历史窗口已含最新消息 |
| 宏替换保留 | ✅ `resolveTavernText` 在 detail 版保留 |
| `hitToDetail` 类型覆盖 | ✅ String/Number/Boolean/null 全覆盖 |
| 空 uid / 空 key 边界 | ✅ 前端有 fallback |

---

## 测试与验证

- 全量测试 **98/98 绿**（TavernLogicTest 59 + TavernSeqTest 13 + TavernReviewFixTest 12 + SecurityFixTest 14）
- API 实测：带草稿 `"the dragon flies"` → 激活 `{uid:e1, matchedKey:"dragon", matchedMessageIndex:0, injected:true}`
- headless Chrome：`status-dot ready` + 3 个新 UI 元素在 DOM + 零 console 错误
- 产物级：zip 内确认 `ScanHit.class` + 前端标记 + 完整加载实测

---

## 相关文件

- `src/main/java/org/mark/llamacpp/server/service/WorldBookScanner.java`（scanDetailed + ScanHit）
- `src/main/java/org/mark/llamacpp/server/service/EasyChatService.java`（buildWorldInfoPrefixDetail + handlePromptPreview 增强）
- `src/main/resources/web/chat/tavern.js`（fetchPromptPreview/renderActivatedEntries/refreshActivationBox）
- `src/main/resources/web/chat/index.html`（侧栏激活框 + 面板激活区 + onMessageComplete）
- `src/test/java/org/mark/llamacpp/server/TavernLogicTest.java`（+11 scanDetailed 单测）

# FORK.md — yzy806806 fork 维护手册

本文件记录这个 fork 与上游的全部差异、每处差异的原因，以及同步上游时的检查点。
**改动这里记录的安全代码前必读。** 最近更新：2026-09-07（v1.0.23 后）。

---

## 与上游的关系

- 上游：[IIIIIllllIIIIIlllll/llama.cpp-hub](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub)
- 分叉方式：只保留一个 `master` 分支持续 merge 上游；不提 PR（曾提交 PR #51 安全增强被拒，双方设计思路不同）
- 版本线：上游用 `v0.9.x.x`，我们用 `v1.0.x-security`
- 同步节奏：上游有更新就合并；发 release 只在我们想发的时候打 tag
- **已同步上游 v0.9.8.3**（2026-09-07，commit 1e14a92）：
  - `c36a01b` StreamingForwarder JSON 跨 chunk key 截断修复（pendingKeyTail 暂存跨块字节）
  - `a9cf128` 默认监听地址 127.0.0.1 → **0.0.0.0**（上游自己改了，我们跟随）

## fork 的三大工作线

```
① 安全架构（API Key 鉴权体系 + MCP 加固）—— 见下节
② 酒馆兼容（EasyChat Tavern Runtime）—— fork 当前最大主体，v1.0.15 起
③ 上游同步（v0.9.8.3 已合并）
```

---

# ① 安全架构（fork 核心价值）

上游是局域网工具、无鉴权设计。我们在其上加了一层完整的 API Key 鉴权体系：

```
外部客户端 ──> newapi（额外的鉴权/限流层）──> hub 8080 ──(localhost)──> llama-server 8081+
                                                │
                                    全路径 ApiKeyValidator 鉴权
```

## 改动清单

| # | 文件 | 内容 | 原因 |
|---|------|------|------|
| 1 | `server/security/ApiKeyValidator.java` | 新增。集中鉴权：常量时间比较防计时攻击、Bearer/x-api-key/Cookie 三种验证、IP 暴力破解防护（5 次失败封 15 分钟，failedAttempts 容量上限 50k + TTL 清理）、TCP 直取真实 IP 不信任 XFF | 鉴权核心 |
| 2 | `server/security/WebSocketAuthHandler.java` | 新增。WebSocket 握手阶段验证 API Key | WS 是独立通道，不过 HTTP pipeline |
| 3 | `server/channel/*RouterHandler.java` 等 | 各 handler 入口调用 `ApiKeyValidator.validate` | 全路由覆盖：流式/EasyChat/上传下载/WebUI |
| 4 | **`LlamaServerManager.java` 两处 `--host 127.0.0.1`** ⭐ | llama-server 子进程只监听回环；`stripHubOwnedFlags` 剥离用户传入的 `--host/--port/--alias/--timeout/--metrics/--model/-m` | **最高优先级守卫点**，见下节 |
| 5 | `update/GitHubTagFetcherNative.java` + `web/js/settings.js` | 自动更新指向本 fork | 上游 release 与我们无关 |
| 6 | `web/js/api-auth.js` + 各页面引入 | 前端从 Cookie 读 Key 自动注入 Bearer | WebUI 免登录态丢失 |
| 7 | `SystemController.java` | `/api/sys/setting` 不返回明文 apiKey；新增 `/api/auth/verify`；目录浏览白名单 | 敏感信息脱敏 + 路径穿越防护 |
| 8 | `mcp/channel/NettySseMcpServer.java` + `McpRouterHandler.java` | **MCP 强制 bind `127.0.0.1`**（非 wildcard）；新增 Bearer token 校验（constant-time compare），缺失/错误返回 401 | MCP 暴露面收口（v1.0.18） |
| 9 | `mcp` 工具 | `write_text_file`：1MB 大小上限 + symlink 路径检查；`McpServer.getMcpServerToken()` 提供 token | MCP 文件工具 sandbox（v1.0.18） |
| 10 | `pom.xml` + `lib/` | **Netty 4.1.35 → 4.1.137**（模块化升级：netty-all 4.1.137 是空壳，改用 11 个 module jar） | 依赖安全（v1.0.18） |
| 11 | `launcher` classpath | CI 构建的 launcher 用 Netty 4.1.137 module jar | 与 #10 配套 |

## 守卫点详解

### ⭐ 子进程端口绑定（历史上回归过两次）

**位置**：`LlamaServerManager.java`
- `buildCommandStrFromForm()` 中 `sb.append(" --host 127.0.0.1")`
- `buildCommandStrFromCmd()` 中 `--host 127.0.0.1`

**为什么必须 127.0.0.1**：hub 对外暴露模型靠的是自己（8080）的鉴权层转发。子进程本身无任何认证，若绑定 `0.0.0.0`，知道端口的任何人可直连 8081+ 绕过全部鉴权。在 IPv6 环境下同理——没有任何进程监听 v6 地址即拒绝。

**历史**：PR #51 引入修复 → 合并上游时被上游的 `0.0.0.0` 覆盖 → 在生产环境发现（v1.0.12 期间）→ v1.0.13 重新修复并加 CI 守卫 → v1.0.18 codex 审查后改为 `stripHubOwnedFlags` 统一剥离（含 `--host=0.0.0.0` 等号写法）。

**CI 已守卫**：`build-and-release.yml` 的「安全回归守卫」步骤会检查 0.0.0.0 禁止出现、127.0.0.1 必须两处都在。

### MCP 三重锁（v1.0.18 codex P0）

1. **bind 127.0.0.1**（NettySseMcpServer：`bootstrap.bind("127.0.0.1", port)`）
2. **Bearer token**（McpRouterHandler：缺失/错误 → 401，constant-time compare）
3. **工具 sandbox**（write_text_file 1MB + symlink 检查）

> 设计取舍：write_text_file 保留绝对路径能力（工具设计用途），靠 MCP localhost + token 封远程面兜底，不靠路径限制（避免工具变废）。

### 其他 CI 守卫项

| 检查 | 文件 |
|------|------|
| WebSocketAuthHandler 必须在 pipeline 中 | `HttpHttpsUnificationHandler.java` |
| ApiKeyValidator 存在且被 BasicRouterHandler 调用 | security/ + channel/ |
| 自动更新不得指向上游 IIIIIllllIIIIIlllll | update/ + settings.js |
| 三个前端页面必须引入 api-auth.js | web/*.html |

**同步上游后如果 CI 红**：先看 FORK.md 本节，恢复对应代码再构建。

---

# ② 酒馆兼容（EasyChat Tavern Runtime）—— fork 最大主体

> 定位：**Tavern-compatible Prompt Runtime + EasyChat UI**（不是 UI Clone）。目标是"导入酒馆现成角色卡/世界书就能跑"，数据模型对齐 SillyTavern，但 prompt 组装按我们自己的铁律。

## 版本历史（v1.0.15 → v1.0.23）

> tag 名即 release 版本；下表 commit 列为该版本**主要功能 commit**（tag 实际指向 release 收尾 commit，`git log --oneline v1.0.23-security` 可看全链）

| 版本 | 主要内容 |
|------|---------|
| v1.0.15 | 酒馆 5 阶段：角色卡导入、世界书扫描注入、回复选项（CYOA）、上下文压缩、前端整合 |
| v1.0.16 | codex SillyTavern 审查 3 个 P0 修复（历史窗口倒序 bug、first_mes 丢失、post_history/alt greetings 保留） |
| v1.0.18 | codex 安全 P0/P1：--host 剥离、MCP localhost+token、write_text_file sandbox、Netty 4.1.137、failedAttempts 容量 |
| v1.0.19 | **EasyChat 页面红点修复**（tavern accessor 初始化顺序：syncSettingsInputs 在 setStateAccessor 前调用 → getState() 抛异常 → loadState 失败；修复=未初始化时安全返回 null） |
| v1.0.20 | **对齐酒馆补全 10 项**（宏替换/整词匹配/正则 key/递归扫描/Author's Note/全局世界书/开场白切换/Prompt Debugger/token budget/mes_example role 化） |
| v1.0.21 | opencode review 4 项修复（Author's Note input 事件/递归 equals 去重/全局书 mtime 缓存/正则编译缓存） |
| v1.0.22 | 同步上游 v0.9.8.3（JSON 截断修复 + 默认监听 0.0.0.0） |
| v1.0.23 | Prompt Debugger 对齐实发 + 世界书激活可视化（scanDetailed + 草稿 message + 侧栏激活框） |

## 核心文件（fork 独有，上游没有）

**后端（`src/main/java/org/mark/llamacpp/server/`）**

| 文件 | 职责 | 同步上游时注意 |
|------|------|----------------|
| `service/EasyChatService.java` | 主服务：角色卡解析、世界书注入、suggestions/summarize/compress/prompt-preview | ⚠️ **完全 fork 独有逻辑，上游合并必冲突，优先 ours** |
| `service/EasyChatRequestWriter.java` | **手写字节流** writer（防 OOM，多 MB 附件直接流式转发） | ⚠️ 同上前两行原则；**不许改成完整 JsonObject 序列化**（OOM 坑会回来） |
| `service/EasyChatStorage.java` | 对话 fragments 存储（seq 步进 2：user/assistant 各占 1，index 文件） | |
| `service/WorldBookScanner.java` | 世界书扫描器：constant/key/secondary/selective/递归/整词/正则 key；`scanDetailed` 返回 ScanHit{entry, matchedKey, matchedMessageIndex, source} | |
| `service/WorldBookParser.java` | 世界书 JSON 解析（entries map，uid → entry） | |
| `service/TavernTemplateResolver.java` | 宏替换 `{{char}}/{{user}}/{{persona}}`（出站时替换，存储保持原样；未知宏原样保留、空格容忍） | |
| `service/TavernAuxRequests.java` | 辅助请求（suggestions/summarize/compress 端点实现 + token 估算） | |
| `struct/AssistantCard.java` | 角色卡模型（V1/V2/V3 归一化：description/personality/scenario/first_mes/mes_example/post_history_instructions/alternate_greetings/authorNote/raw 保留） | |
| `struct/WorldBookEntry.java` | 世界书条目模型（含 matchWholeWords/recursion 控制字段） | |

**前端（`src/main/resources/web/chat/`）—— 整个目录是 fork 独有**

| 文件 | 职责 |
|------|------|
| `index.html` | EasyChat 主页面（约 11.6k 行内联 JS），含酒馆 UI 区块（导入卡/世界书/Author's Note/开场白/Prompt 预览按钮） |
| `tavern.js` | 酒馆模块（window.Tavern）：导入/解析/同步/预览/激活可视化；stateAccessor 模式 |
| `index.css` | 样式 |
| `chat-mcp.js` | MCP 客户端（fork 安全改动：Bearer token） |
| `audio-recorder.js` | 录音（上游原有） |

## 三条铁律（任何架构建议必须对照）

1. **Qwen3.6 单条 system 最前**：Jinja 模板硬约束。任何"拆多 PromptBlock/中段插入 system/Impersonate 走 system"的建议一律不采纳
2. **prefix cache 稳定**：动态内容（世界书/Author's Note/post_history）只进**最新 user 消息前缀**，不碰旧历史字节
3. **项目定位**：Tavern-compatible Prompt Runtime + EasyChat UI，不是 UI Clone

> 已核实不采纳的架构建议（历轮 codex/审查报告提过）：Tavern Prompt Runtime 六阶段重构（PromptPlan/PromptBlock/PlacementEngine）、世界书 position 多桶、mes_example 转多 Message[]、HttpOnly Session 改造（认证统一在 ApiKeyValidator 层，改认证链路有回归风险）、MCP Scope/SSRF（P2 记录在案）。

## 关键机制速查

- **开场白**：first_mes 作为空会话首条 assistant 消息（writer 注入，不写盘）；alternate_greetings 前端下拉选择 → `X-Tavern-Greeting` header
- **世界书注入**：`seq == lastUserSeq && lastUserSeq == lastNonEmptySeq && seq == historyEndExclusive - 2`（正常发送成立——index 是 +2 步进，`readNextSeq` 返回 index 值而非消息计数）；regenerate/continue 最后非空是 assistant，不注入
- **注入通道**：worldInfoPrefix 拼进最新 user 消息 content 前（保留 images/audios/videos 附件字段）
- **token budget**：世界书注入 2048 token 上限；超限条目标 injected=false，后续小条目仍注入（v1.0.23 continue 语义）
- **递归扫描**：激活条目内容参与下一轮，MAX 10 轮防环；excludeRecursion/preventRecursion 控制
- **全局世界书**：state.json 顶层 globalWorldBook（mtime 缓存，避免每请求全量读）
- **Prompt Debugger**：`/api/chat/prompt-preview` 返回分段+tokens+激活明细；body 可带 `message`（草稿）对齐实发
- **宏替换**：出站时 `TavernTemplateResolver.resolve(text, charName, "用户", null)`

---

# 测试体系（v1.0.16 起建立）

无 llama.cpp 依赖的纯逻辑测试，全部可本地跑：

| 测试类 | 覆盖 | 数量 |
|--------|------|------|
| `TavernLogicTest` | 角色卡组装/PNG tEXt/世界书四值逻辑/宏替换/整词/正则/递归/scanDetailed | 59 |
| `TavernSeqTest` | 世界书注入 seq 语义 + 摘要压缩 | 13 |
| `TavernReviewFixTest` | 历轮 review 修复回归 | 12 |
| `SecurityFixTest` | stripHubOwnedFlags/MCP 鉴权/路径 sandbox | 14 |
| **合计** | | **98** |

运行方式：

```bash
export JAVA_HOME=/opt/jdk-21.0.12.1+1
bash javac-linux.sh   # 先编译主代码
javac -cp "build/classes:lib/*" -d /tmp/tt <测试文件...>
java -cp "/tmp/tt:build/classes:lib/*" org.mark.llamacpp.server.TavernLogicTest
```

**前端验证铁律**：curl 200 ≠ WebUI 能跑。前端改动必须 headless Chrome 实测：
`status-dot ready` + 目标 UI 元素在 DOM + 零 console 错误，才是判据。
（v1.0.19 红点 bug 就是 curl 全 200 但浏览器必现的初始化时序问题。）

**产物级验证铁律**：每次发版下载 zip → 确认 fix class 在包内 → 完整加载实测。只看源码不算通过。

---

# 同步上游的操作规程

```bash
git fetch upstream
git log HEAD..upstream/master --oneline   # 先看改了什么
git merge upstream/master -m "merge: sync upstream vX.X.X.X"

# 冲突处理原则：
# - .github/workflows/build-and-release.yml → git checkout --ours（我们的精简版+守卫）
# - README.md / README-EN.md → git checkout --ours（我们的文档含安全章节）
# - LlamaServerManager.java 出现冲突时 → 合并后手动确认 --host 仍是 127.0.0.1
# - EasyChatService.java / EasyChatRequestWriter.java / EasyChatStorage.java
#   → 这些是 fork 独有逻辑（上游没有），merge 冲突时逐块核对，保留我们的语义
#   （writer 手写字节流、注入铁律、seq 步进 2）
# - NettySseMcpServer.java / McpRouterHandler.java / ApiKeyValidator.java
#   → 安全加固文件，上游没有，冲突时优先 ours
# - WebSocketAuthHandler / HttpHttpsUnificationHandler → 确认 WS 鉴权仍在 pipeline
# - 其余按语义合并

git push origin master
```

发 release（可选）：

```bash
git tag -a v1.0.(N+1)-security -m "..."
git push origin v1.0.(N+1)-security   # 触发自动构建发布
```

注意：workflow 的 release 步骤只在 tag push 时触发。若 tag push 未触发构建，用
`gh workflow run build-and-release.yml --ref <tag>` 手动触发（此方式产物需手动附到 release）。

**同步上游后必须确认**：
1. `--host 127.0.0.1` 两处仍在（CI 会查，但别只依赖 CI）
2. 酒馆文件语义没被上游覆盖（EasyChatService/writer/scanner）
3. 测试全量跑一遍（98 项）
4. headless Chrome 打开 EasyChat 页面（如果动了前端）

---

# 已知问题 / 待观察

- **v1.0.23 世界书 budget 语义**：超限从 break 改为 continue（order 靠前大条目不再阻断后续小条目）。这是有意的合理演进（前端 ⚠️未注入 标签依赖它），但 v1.0.22 → v1.0.23 同一世界书注入内容可能不同，对比时注意。
- **跨消息拼接命中时明细无 key**：key 恰好拆在两条消息边界时，条目激活但 scanDetailed 定位不到 matchedKey（罕见，记录不修）。
- **递归轮条目无 key 定位**：递归激活条目 matchedKey=null，前端只显示 source=recursion（语义正确，记录不修）。
- **上游 listenAddress 的 IPv6 校验有 bug**（v0.9.8.1）：`isValidListenAddress` 用字符串比较 IP，Java 会把 `::1` 展开成 `0:0:0:0:0:0:0:1` 导致合法 IPv6 被拒。目前 hub 只能配 IPv4 监听地址。
- **httpOnlyPort 默认 8081 与模型端口段冲突**：加载模型时会有一边绑定失败。若启用该功能记得改端口。
- **上游新版前端（index-new）仍在演进**：转正时需专门做一次安全层适配审查（api-auth.js 是否全引入、WS 路径匹配、Cookie 登录页兼容）。
- **ProxyConfig.java 默认密码 "123456"**：上游命令行代理工具的默认值（--password 可覆盖），非泄露，未处理。

---

# 配置要点（部署）

```json
{
  "security": { "apiKeyEnabled": true, "apiKey": "<强随机密钥>" },
  "server": { "webPort": 8080 }
}
```

- **v1.0.22 起默认监听地址就是 `0.0.0.0`**（上游 a9cf128 改了默认值）——不配 listenAddress 也监听所有网卡，局域网开箱可访问
- 需要仅回环时显式配 `"listenAddress": "127.0.0.1"`（会覆盖默认）
- 旧 config 里显式 `127.0.0.1` 会覆盖新默认——从旧版本升级想用局域网，记得删掉那行或改成 0.0.0.0
- ⚠️ 默认 0.0.0.0 = WebUI 开箱暴露局域网；对外暴露靠 newapi 等前置层承担
- 子进程端口与 listenAddress 无关，永远 127.0.0.1（代码写死，勿改）
- 升级版本后需要卸载重载所有运行中的模型，旧的 `0.0.0.0` 子进程才会退出

---

# 审查记录

历轮审查结论已入库 `docs/reviews/`：

| 日期 | 文件 | 对象 |
|------|------|------|
| 2026-09-07 | `docs/reviews/2026-09-07-opencode-review-v1.0.23.md` | v1.0.23（0 High/1 Medium 知情/2 Low 记录） |

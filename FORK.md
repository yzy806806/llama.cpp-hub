# FORK.md — yzy806806 fork 维护手册

本文件记录这个 fork 与上游的全部差异、每处差异的原因，以及同步上游时的检查点。
**改动这里记录的安全代码前必读。** 最近更新：2026-09-10（酒馆改造已回退）。

---

## 与上游的关系

- 上游：[IIIIIllllIIIIIlllll/llama.cpp-hub](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub)
- 分叉方式：只保留一个 `master` 分支持续 merge 上游；不提 PR（曾提交 PR #51 安全增强被拒，双方设计思路不同）
- 版本线：上游用 `v0.9.x.x`，我们用 `v1.0.x-security`
- 同步节奏：上游有更新就合并；发 release 只在我们想发的时候打 tag
- **已同步上游 v0.9.8.3**（2026-09-07，commit 1e14a92）：
  - `c36a01b` StreamingForwarder JSON 跨 chunk key 截断修复（pendingKeyTail 暂存跨块字节）
  - `a9cf128` 默认监听地址 127.0.0.1 → **0.0.0.0**（上游自己改了，我们跟随）

## 工作线（回退后）

```
① 安全架构（API Key 鉴权体系 + MCP 加固）—— fork 核心价值
② 上游同步（v0.9.8.3 已合并）
③ EasyChat 酒馆改造（2026-09-06 起）→ 已回退（2026-09-10），见下
```

### 酒馆改造已回退（commit `ee9c96f`）

2026-09-06 ~ 09-10 期间我们在 EasyChat 上实现了 SillyTavern 兼容（角色卡/世界书/
回复选项/上下文压缩/Prompt Debugger，v1.0.15~v1.0.24）。经用户实测后**放弃该方向**
（改用其他 agent + 提示词做角色扮演），2026-09-10 回退：

- **EasyChat 回到 v1.0.14 基线**（无酒馆功能，仅上游原生聊天）
- 文件级回退依据：7 个文件恢复到 `d97b105` 版本（EasyChatController/
  RequestWriter/Service/Storage + index.html/css + .gitignore），9 个酒馆新增文件
  删除（TavernAuxRequests/TavernTemplateResolver/WorldBookParser/WorldBookScanner/
  AssistantCard/WorldBookEntry/tavern.js + 3 个酒馆测试类）
- **保留的全部是安全/上游改动**（25 个文件，见下节）
- 验证：编译过、SecurityFixTest 14/14、headless Chrome ready、路由与 v1.0.14 逐行一致

**教训（避免重蹈）**：如果有朝一日想重新加酒馆功能，不要从对话记忆重建——回退
commit `ee9c96f` 的父链里完整保留了 4177 行酒馆代码（`git log` 可找回），且当时
的核心设计沉淀在 `.hermes/plans/` 设计文档与 ReMe 记忆库。

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

# 测试体系（回退后）

无 llama.cpp 依赖的纯逻辑测试，全部可本地跑：

| 测试类 | 覆盖 | 数量 |
|--------|------|------|
| `SecurityFixTest` | stripHubOwnedFlags/MCP 鉴权/路径 sandbox | 14 |
| **合计** | | **14** |

> 酒馆测试（TavernLogicTest/TavernSeqTest/TavernReviewFixTest）随酒馆改造一起回退删除。

运行方式：

```bash
export JAVA_HOME=/opt/jdk-21.0.12.1+1
bash javac-linux.sh   # 先编译主代码
javac -cp "build/classes:lib/*" -d /tmp/tt <测试文件...>
java -cp "/tmp/tt:build/classes:lib/*" org.mark.llamacpp.server.SecurityFixTest
```

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
# - NettySseMcpServer.java / McpRouterHandler.java / ApiKeyValidator.java
#   → 安全加固文件，上游没有，冲突时优先 ours
# - WebSocketAuthHandler / HttpHttpsUnificationHandler → 确认 WS 鉴权仍在 pipeline
# - LlamaServer.java 有我们的 MCP token 改动 → 合并时确认 MCP 部分保留
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
2. MCP bind 127.0.0.1 + Bearer token 未丢
3. 安全测试跑一遍（14 项）

---

# 已知问题 / 待观察

- **上游 listenAddress 的 IPv6 校验有 bug**（v0.9.8.1）：`isValidListenAddress` 用字符串比较 IP，Java 会把 `::1` 展开成 `0:0:0:0:0:0:0:1` 导致合法 IPv6 被拒。目前 hub 只能配 IPv4 监听地址。
- **httpOnlyPort 默认 8081 与模型端口段冲突**：加载模型时会有一边绑定失败。若启用该功能记得改端口。
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

| 日期 | 文件 | 对象 |
|------|------|------|
| 2026-09-07 | `docs/reviews/2026-09-07-opencode-review-v1.0.23.md` | v1.0.23 酒馆调试闭环（**功能已回退**，文档留存供参考） |
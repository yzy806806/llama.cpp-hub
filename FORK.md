# FORK.md — yzy806806 fork 维护手册

本文件记录这个 fork 与上游的全部差异、每处差异的原因，以及同步上游时的检查点。
**改动这里记录的安全代码前必读。**

---

## 与上游的关系

- 上游：[IIIIIllllIIIIIlllll/llama.cpp-hub](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub)
- 分叉方式：只保留一个 `master` 分支持续 merge 上游；不提 PR（曾提交 PR #51 安全增强被拒，双方设计思路不同）
- 版本线：上游用 `v0.9.x.x`，我们用 `v1.0.x-security`
- 同步节奏：上游有更新就合并；发 release 只在我们想发的时候打 tag

## 安全架构（fork 核心价值）

上游是局域网工具、无鉴权设计。我们在其上加了一层完整的 API Key 鉴权体系：

```
外部客户端 ──> newapi（额外的鉴权/限流层）──> hub 8080 ──(localhost)──> llama-server 8081+
                                                │
                                    全路径 ApiKeyValidator 鉴权
```

### 改动清单

| # | 文件 | 内容 | 原因 |
|---|------|------|------|
| 1 | `server/security/ApiKeyValidator.java` | 新增。集中鉴权：常量时间比较防计时攻击、Bearer/x-api-key/Cookie 三种验证、IP 暴力破解防护（5 次失败封 15 分钟）、TCP 直取真实 IP 不信任 XFF | 鉴权核心 |
| 2 | `server/security/WebSocketAuthHandler.java` | 新增。WebSocket 握手阶段验证 API Key | WS 是独立通道，不过 HTTP pipeline |
| 3 | `server/channel/*RouterHandler.java` 等 | 各 handler 入口调用 `ApiKeyValidator.validate` | 全路由覆盖：流式/EasyChat/上传下载/WebUI |
| 4 | **`LlamaServerManager.java` 两处 `--host 127.0.0.1`** ⭐ | llama-server 子进程只监听回环 | **最高优先级守卫点**，见下节 |
| 5 | `update/GitHubTagFetcherNative.java` + `web/js/settings.js` | 自动更新指向本 fork | 上游 release 与我们无关 |
| 6 | `web/js/api-auth.js` + 各页面引入 | 前端从 Cookie 读 Key 自动注入 Bearer | WebUI 免登录态丢失 |
| 7 | `SystemController.java` | `/api/sys/setting` 不返回明文 apiKey；新增 `/api/auth/verify`；目录浏览白名单 | 敏感信息脱敏 + 路径穿越防护 |

## 守卫点详解

### ⭐ 子进程端口绑定（历史上回归过两次）

**位置**：`LlamaServerManager.java`
- `buildCommandStrFromForm()` 中 `sb.append(" --host 127.0.0.1")`
- `buildCommandStrFromCmd()` 中 `--host 127.0.0.1`

**为什么必须 127.0.0.1**：hub 对外暴露模型靠的是自己（8080）的鉴权层转发。子进程本身无任何认证，若绑定 `0.0.0.0`，知道端口的任何人可直连 8081+ 绕过全部鉴权。在 IPv6 环境下同理——没有任何进程监听 v6 地址即拒绝。

**历史**：PR #51 引入修复 → 合并上游时被上游的 `0.0.0.0` 覆盖 → 在生产环境发现（v1.0.12 期间）→ v1.0.13 重新修复并加 CI 守卫。

**CI 已守卫**：`build-and-release.yml` 的「安全回归守卫」步骤会检查 0.0.0.0 禁止出现、127.0.0.1 必须两处都在。

### 其他 CI 守卫项

| 检查 | 文件 |
|------|------|
| WebSocketAuthHandler 必须在 pipeline 中 | `HttpHttpsUnificationHandler.java` |
| ApiKeyValidator 存在且被 BasicRouterHandler 调用 | security/ + channel/ |
| 自动更新不得指向上游 IIIIIllllIIIIIlllll | update/ + settings.js |
| 三个前端页面必须引入 api-auth.js | web/*.html |

**同步上游后如果 CI 红**：先看 FORK.md 本节，恢复对应代码再构建。

## 同步上游的操作规程

```bash
git fetch upstream
git log HEAD..upstream/master --oneline   # 先看改了什么
git merge upstream/master -m "merge: sync upstream vX.X.X.X"

# 冲突处理原则：
# - .github/workflows/build-and-release.yml → git checkout --ours（我们的精简版+守卫）
# - README.md / README-EN.md → git checkout --ours（我们的文档含安全章节）
# - LlamaServerManager.java 出现冲突时 → 合并后手动确认 --host 仍是 127.0.0.1
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

## 已知问题 / 待观察

- **上游 listenAddress 的 IPv6 校验有 bug**（v0.9.8.1）：`isValidListenAddress` 用字符串比较 IP，
  Java 会把 `::1` 展开成 `0:0:0:0:0:0:0:1` 导致合法 IPv6 被拒。目前 hub 只能配 IPv4 监听地址。
  若未来需要 IPv6 监听，需要修上游这段或在自己侧覆盖。
- **httpOnlyPort 默认 8081 与模型端口段冲突**：上游把独立 HTTP 端口默认值设为 8081，
  加载模型时会有一边绑定失败。若启用该功能记得改端口。
- **上游新版前端（index-new）仍在演进**：转正时需专门做一次安全层适配审查
 （api-auth.js 是否全引入、WS 路径匹配、Cookie 登录页兼容）。
- **每次同步上游后必须确认**：`--host 127.0.0.1` 两处仍在（CI 会查，但别只依赖 CI）。

## 配置要点（部署）

```json
{
  "security": { "apiKeyEnabled": true, "apiKey": "<强随机密钥>" },
  "server": { "webPort": 18500, "listenAddress": "0.0.0.0" }
}
```

- `listenAddress: 0.0.0.0` 用于让 newapi 从其他机器转发进来；对外安全由 newapi 承担
- 子进程端口与 listenAddress 无关，永远 127.0.0.1（代码写死，勿改）
- 升级版本后需要卸载重载所有运行中的模型，旧的 `0.0.0.0` 子进程才会退出

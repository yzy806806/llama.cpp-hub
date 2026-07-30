# llama.cpp-hub (yzy806806 fork)

> 给 llama.cpp 套个 Web 壳 — 用图形化的 Web 页面来操作模型和 llama.cpp。
>
> 原项目：[IIIIIllllIIIIIlllll/llama.cpp-hub](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub)
>
> 💡 **PWA 支持**：本应用是渐进式 Web 应用（PWA），可直接在浏览器中安装到桌面/任务栏，获得类似原生应用的体验。

---

## 🔒 安全增强（本 Fork 独有）

相比上游，本 Fork 进行了全面的安全加固，使应用在启用 API Key 后可以安全地暴露在公网：

### 1. 全路由统一鉴权

新增 `ApiKeyValidator`（`src/main/java/org/mark/llamacpp/server/security/ApiKeyValidator.java`）集中管理 API Key 验证：

| 特性 | 说明 |
|------|------|
| **常量时间比较** | 使用 `MessageDigest.isEqual`，防止计时攻击 |
| **三种验证方式** | `Authorization: Bearer <key>` / `x-api-key: <key>` / Cookie `lh-api-key` |
| **IP 暴力破解防护** | 连续 5 次失败后封禁 15 分钟（上限 10000 条记录，防内存 DoS） |
| **客户端 IP 直取** | 从 TCP 连接提取真实 IP，不信任 `X-Forwarded-For` / `X-Real-IP` 等可伪造代理头 |

### 2. Pipeline 全覆盖

所有请求路径均走统一鉴权，不留死角：

- **流式 API**（`/v1/chat/completions`）— 之前完全绕过验证
- **Easy Chat** — 之前在验证前就写临时文件
- **文件上传/下载** — 之前未验证就接受上传
- **WebSocket** — 新增 `WebSocketAuthHandler`，握手阶段验证
- **WebUI + `/api/*`** — 浏览器请求返回登录页，API 请求返回 JSON 401

### 3. 子进程端口隔离

llama.cpp 子进程默认绑定 `--host 127.0.0.1`，仅 Hub 通过 localhost 转发访问，模型端口（8081、8082…）不会直接暴露在公网。

### 4. 节点间通信认证

主从节点通信在启用 API Key 后也走认证：
- `RemoteWebSocketClient`：连接时带 `Authorization: Bearer <node.apiKey>`
- HTTP 远程转发时带节点 apiKey
- 未配置 apiKey 的节点行为不变（完全兼容）

### 5. 敏感信息保护

- `/api/sys/setting` 不再返回明文 `apiKey` / `keystorePassword`
- 前端显示 `******` 而非明文密钥
- 新增 `/api/auth/verify` 端点（只返回 200/401）

### 6. 网络加固

| 项目 | 措施 |
|------|------|
| CORS | `Allow-Headers` 从 `*` 收紧为显式列表 |
| 请求头过滤 | 转发时过滤 `Authorization` / `Cookie` / `X-Forwarded-*` |
| 安全响应头 | `X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy` |
| Cookie | `SameSite=Strict`，HTTPS 启用时自动加 `Secure` |
| 路径穿越 | 下载和目录列举限制白名单 |
| Service Worker | 只缓存静态资源 |

---

## ⚙️ 配置安全特性

配置文件位于 `config/application.json`（首次运行后自动生成）：

```json
{
  "security": {
    "apiKeyEnabled": true,
    "apiKey": "your-strong-random-key-here"
  }
}
```

| 配置项 | 类型 | 说明 |
|--------|------|------|
| `security.apiKeyEnabled` | Boolean | 设为 `true` 启用 API Key 验证 |
| `security.apiKey` | String | 你的密钥（建议 32 位以上随机字符串） |

**启用后**:
- 浏览器首次访问 WebUI 显示登录页，输入 Key 后存 Cookie（7天有效）
- API 客户端使用 `Authorization: Bearer <key>` 或 `x-api-key: <key>`
- 远程节点在面板中添加时填入对应节点的 apiKey

> **也可以直接在 Web 界面配置**：系统设置 → 安全 tab，打开开关并输入密钥后保存即生效。

---

## 功能概览

### 模型操作
- 加载 / 卸载 GGUF 模型
- 每个模型可保存多套启动配置和采样信息，随时切换
- 自动识别同目录下的 mmproj 文件，可手动指定草稿模型
- 可配置多种不同版本的 llama.cpp，选择指定版本加载模型
- 设置聊天模板，自定义 kwargs

### 多协议 API
一个后端同时暴露 OpenAI / Anthropic 兼容 API，已接入对应 SDK 的工具直接换地址即可使用。

### Web 管理面板
- 模型列表 + 实时状态 / 参数调节 / WebSocket 实时日志
- 用量统计（Token 消耗、推理速度）
- 简易性能基准测试 / llama-bench
- 模型克隆、搜索、系统信息

### 远程节点（服务聚合）
将多台服务器上部署的 llama.cpp-hub 实例聚合到一起，从一个入口统一管理。

### 内置 MCP 🧪
用于测试模型 tool call，监听端口 8075（需在设置中启用）。

### 下载管理器
支持 HTTP 断点续传，适合局域网传模型。

### 在线更新
自动检查 GitHub Release → 下载更新包 → 解压替换。

---

## 快速开始

1. 下载 [Release](https://github.com/yzy806806/llama.cpp-hub/releases) 程序包，解压
2. 自行下载 llama.cpp 放入 `llamacpp/` 目录
3. 每个 GGUF 模型放在独立文件夹，如：`models/Qwen3.5-27B-Q8_0/Qwen3.5-27B.gguf`
4. 运行 `run.bat`（Windows）
5. 浏览器打开 `http://localhost:8080`
6. 选择模型 → 点击加载 → 开始使用

> **端口**：默认 8080，确保该端口可用。

---

## 技术栈

- 后端：Java 21 + Netty 4.1
- 前端：Vanilla JS（无框架、无打包器）
- 模型：llama.cpp 子进程（每个模型独立进程）
- 安全：常量时间比较 + IP 限速 + 子进程端口隔离

---

## 端口布局

| 端口 | 用途 |
|------|------|
| 8080 | WebUI + OpenAI/Anthropic API + WebSocket |
| 8081+ | 每个已加载模型的推理进程（自动分配，绑定 127.0.0.1） |
| 8075 | MCP 服务器（可选） |

---

## ⚠️ 安全声明

**本应用是面向局域网的个人工具，不具备互联网级别的安全防护能力。**

虽然本 Fork 进行了安全加固，但如果你将服务暴露到公网，请注意：

- 务必**启用 API Key**（`security.apiKeyEnabled: true`）
- 使用强密钥（32 位以上随机字符串）
- 推荐使用反向代理（如 Nginx / Cloudflare Tunnel）配置 HTTPS + 访问控制 + 速率限制
- 不要将 8080 或其他服务端口直接暴露到公网

**任何因端口映射/公网暴露导致的损失，后果自负。**

---

## 与原项目的差异

本 Fork 在 [原项目](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub) 基础上：

- ✅ 新增全路由 API Key 鉴权 + 暴力破解防护
- ✅ 子进程端口绑定 127.0.0.1
- ✅ WebSocket 握手认证
- ✅ 节点间通信认证
- ✅ 敏感信息脱敏
- ✅ 网络加固（CORS/SameSite/安全响应头/路径穿越防护）
- ❌ 移除了 Ollama / LM Studio 兼容 API（上游已删除）
- ❌ 移除了 ACME Let's Encrypt 证书功能（上游已删除）
- 🔗 自动更新指向本 Fork
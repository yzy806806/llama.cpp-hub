# llama.cpp-hub (JIT 分支)

[🇬🇧 English](./README-EN.md) | [🇨🇳 中文](./README.md) | **[JIT 功能说明](#jit-自动加载模型)**

> ⚠️ **本分支为 yzy806806 的功能增强分支**，基于主项目 [IIIIIllllIIIIIlllll/llama.cpp-hub](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub) 开发。
>
> 主要新增功能：**JIT 自动加载模型**、**代理支持**、**请求级 TTL**、**JIT REST API**。

---

![image](./screenshot/laika.jpg)

---

## 功能

### 模型操作

- 加载 / 卸载 GGUF 模型
- 每个模型可保存多套启动配置和采样信息，随时切换
- 自动识别同目录下的mmproj文件，允许用户自行选择草稿模型
- 可以配置多种不同版本的llama.cpp，选择指定版本加载模型
- 设置聊天模板，自定义kwargs
- 嵌入模型和重排序模型需在加载页面手动开启对应功能

### ⚡ JIT 自动加载模型（本分支新增）

类似 LM Studio 的 JIT 功能，**收到 API 请求时自动加载模型**，无需手动预先加载。

**功能特点：**
- 🤖 **自动加载** - 收到推理请求时自动加载模型，第一个请求会等待加载完成
- ⏱️ **TTL 空闲超时** - 模型空闲一段时间后自动卸载，节省显存
- 🔢 **并发限制** - 最多同时加载 N 个模型，防止显存爆满
- 📋 **LRU/FIFO 卸载策略** - 达到上限时按策略卸载空闲模型

**支持的 API 端点：**
- `/v1/chat/completions` 
- `/v1/completions`
- `/v1/embeddings`
- `/v1/rerank`
- `/v1/responses`

**配置项（`config/application.json`）：**
```json
{
  "jit": {
    "enabled": true,           // 开启 JIT 功能
    "defaultTtl": 3600,        // 空闲超时（秒），默认 60 分钟
    "maxLoadedModels": 2,      // 最多同时加载模型数
    "loadStrategy": "lru",     // 卸载策略：lru / fifo
    "allowQueue": true         // 是否允许排队等待
  }
}
```

**REST API：**

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/config/jit` | 读取当前 JIT 配置 |
| `PUT` | `/api/config/jit` | 更新 JIT 配置（JSON body，部分更新） |

**请求级 TTL：**

在 API 请求中通过 `ttl` 字段指定本次加载的空闲超时（秒），覆盖全局 `defaultTtl`：

```json
{
  "model": "Qwen3.6-27B",
  "ttl": 1200,
  "messages": [...]
}
```

**前端配置：**

WebUI 设置页 → JIT 配置，支持在线开关 JIT、调整 TTL、加载策略等。

**使用方式：**
1. 在 WebUI 中配置好模型的启动参数，并**设置为默认配置**
2. 发送 API 请求时指定模型名称
3. 如果模型未加载，会自动使用默认配置加载
4. 空闲超时后自动卸载

**硬件建议：**
- 双 3090（24GB x2）→ `maxLoadedModels` 设为 2

> ⚠️ 注意：模型必须先在 WebUI 中配置好启动参数并设置为默认配置，JIT 才能自动加载。

### 便宜聊天

- 一个非常简陋的聊天前端，可以拿来便捷聊天
- 支持MCP，但是应该没人会在这里用它吧，应该吧应该吧应该吧应该吧应该吧应该吧
- 它甚至还没有英文版本
- 不过可以显示llama.cpp的性能指标

### 多协议 API

一个后端同时暴露四种兼容 API，已接入对应 SDK 的工具直接换地址即可使用：

| 协议 | 端口 | 状态 |
|------|------|------|
| OpenAI | 8080 | ✅ |
| Anthropic | 8080 | ✅ |
| Ollama | 11434 | ⚠️ 默认关闭，需手动启用 |
| LM Studio | 1234 | ⚠️ 默认关闭，需手动启用 |

### Web 管理面板

桌面端主力维护，功能完整。移动端 ⚠️ 长期缺维护，可能存在 UI 问题。

- 模型列表 + 实时状态
- 参数调节（采样预设、聊天模板、Slots 状态）
- WebSocket 实时日志
- 用量统计（Token 消耗、推理速度）
- 简易性能基准测试，简单粗暴地塞一堆上下文进去看它什么时候能搞定
- llama-bench性能基准测试，这东西你得会用熬

### 远程节点（服务聚合）

支持将多个 llama.cpp-hub 服务实例聚合到一起统一管理，通过主从节点架构将多台服务器的模型列表、运行状态聚合到主节点前端。

**节点角色：**
- **主节点（master）**：管理所有从节点的 WebSocket 连接，运行 30 秒健康检查，中继日志和事件
- **从节点（slave）**：接受主节点管理，默认 `nodeRole` 为 null 即视为从节点

**远程路由（3 层查找）：**
1. 请求体中显式指定 `nodeId` → 直接路由到该节点
2. 本地已加载模型 → 本地处理
3. 全节点回退 → 遍历所有已启用的远程节点查询 `/v1/models`

**WebSocket 事件中继：**
- 远程节点的控制台日志、模型加载/停止事件、模型繁忙状态等自动中继到主节点前端
- 远程日志行显示 `[nodeId/modelId]` 前缀
- 前端模型列表支持节点筛选下拉框（全部/本地/远程）

**支持的远程操作：**
- 查看模型列表、加载/停止模型
- 聊天补全（OpenAI、Anthropic、Ollama 格式，流式+非流式）
- 嵌入向量、重排序
- 基准测试
- VRAM 估算、设备列表查询

**配置方式：**
- 主节点：`config/application.json` 中设置 `"nodeRole": "master"`，重启生效
- 添加节点：WebUI 系统设置 → 节点管理，或调用 `/api/node/add` API
- 节点信息：需配置 `nodeId`、`name`、`baseUrl`、可选的 `apiKey`
- 支持的协议：HTTP 和 HTTPS（自动信任自签名证书）

> **注意：** 请避免在多个节点上使用相同的模型名称/ID。当外部客户端通过主节点的 `/v1/*` API 调用时（如 `/v1/chat/completions`），如果多个节点存在同名模型，系统无法确定应该路由到哪个节点，会导致调用错误。建议在不同节点上使用不同的模型名称，或者在请求体中显式指定 `nodeId`。

---

### 内置 MCP 🧪

用于测试模型能否正确执行 tool call。MCP 服务端监听端口 **8075**（需在设置中启用）。

#### HTTP 路由

| 方法 | URL | 说明 |
|------|-----|------|
| `GET` | `/mcp/{serviceKey}` | Streamable HTTP SSE |
| `POST` | `/mcp/{serviceKey}` | Streamable HTTP 请求 |
| `DELETE` | `/mcp/{serviceKey}` | 删除会话 |
| `GET` | `/mcp/{serviceKey}/sse` | SSE 连接 |
| `POST` | `/mcp/{serviceKey}/message?sessionId=` | SSE 消息 |

#### Service Key

| Key | 工具数 |
|-----|--------|
| `llama_hub_info` | 7 |
| `llama_hub_image` | 1 |
| `llama_hub_file` | 1 |

这些工具用处不大，意义不明，一般来说直接无视。

#### MCP 客户端配置示例

```json
{
  "mcpServers": {
    "llama_hub_info": {
      "url": "http://localhost:8075/mcp/llama_hub_info",
      "transportType": "streamable-http"
    }
  }
}
```

---

### 下载管理器 ⚠️

- 支持 HTTP 断点续传，后端实现较基础。大批量下载建议用 aria2、IDM 等专用工具。
- 这玩意我主要要来局域网传模型，偶尔才会拿去下载在线的模型。

---

### 在线更新

自动检查 GitHub Release → 下载更新包 → 解压替换。由于使用GitHub的API，因此存在无法访问和403的风险。

---

### 代理支持（本分支新增）

支持通过 HTTP 代理访问 HuggingFace 和 GitHub，适用于国内网络环境。

**配置方式：** WebUI 设置页 → 代理配置，填写代理地址（如 `http://127.0.0.1:7890`）。

支持范围：
- HuggingFace 模型搜索与下载
- GitHub Release 下载
- 在线更新检查

---

## 适用人群

- 远程服务器操作太麻烦了，想要省事
- 有多台机器跑 llama.cpp，想通过一个入口统一管理
- 喜欢自己编译 llama.cpp，又嫌不同版本堆积在一起很混乱的
- 记不住 llama.cpp 那成堆的参数，但是又想用

---

## 快速开始

1. 下载包含 llama.cpp 的 Release 程序包，解压
2. 每个 GGUF 模型需放置在一个独立文件夹中，例如：`models/Qwen3.5-27B-Q8_0/Qwen3.5-27B.gguf`，文件夹名称可自定义
3. 运行启动脚本：Windows 执行 `.bat`，Linux 执行 `.sh`
4. 浏览器打开 `http://localhost:8080`
5. 在页面选择模型 → 点击加载 → 开始使用
6. OK，如果没有启动成功，可能是端口被占用了，默认需要使用8080端口，请确保该端口可用再运行

---

## 注意事项

> **重要**：本应用需要读写文件的权限，无读写权限会导致无法进入网页、无法正常使用功能。如 Windows 11 的 C 盘根目录会导致无法读取文件。

> **提醒**：每个模型需单独放在一个文件夹内，文件夹存放该模型的 GGUF 文件（分片、mmproj 等），不能将不同模型的 GGUF 文件混放。模型只有加载后才会在 `/v1/models` 中显示。

> **提醒**：界面支持英中双语，会根据浏览器语言设置自动切换，也可在 URL 中通过 `?lang=en` 参数手动指定。首页左下角也有个开关方便切换。

> **注意**：/v1/models API端点只会返回 **运行中的模型** ，我个人的理解是：如果你的模型没有加载就显示在模型列表里，客户端还以为这些模型都能用呢，结果发现全是用不了的，那太逗了。
---

### 多模态说明

默认情况下，应用会自动检索 GGUF 文件所在目录下的 mmproj 文件（例如 `Qwen3.6-27B/Qwen3.6-27B.gguf`，会在同目录查找 mmproj）。找到后前端会标记为多模态模型，具备视觉能力。

但如果你有多个同架构的微调模型（例如 `Qwen3.6-27B-威力加强版.gguf`），它们共用同一个 mmproj 文件。按「同目录」检索就需要为每个变体都复制一份 mmproj，很麻烦。因此加载参数中额外提供了一个 mmproj 文件选择项，允许手动指定任意位置的 mmproj 文件。

### MTP 说明

llama.cpp 支持两种 MTP 使用方式：

1. **自带 MTP 层的模型** — 直接加载使用，无需额外配置
2. **额外加载 MTP 层** — 通过加载参数「草稿模型」指定一个独立的 GGUF 文件（仅含 MTP 层权重）来启用

第二种方式的用途：很多变体模型的作者并没有量化带有 MTP 层的版本，此时想启用 MTP，只能从原始模型中提取 MTP 层，单独配合变体模型加载使用（至于变体模型 + 原版 MTP 层是否影响生成质量，优质回答：我不知道）。

### 说明：关于 FastFetch

本人过于懒惰，不想自己实现系统信息读取的功能，于是从 GitHub 上下载了 fastfetch 来辅助。你可以在 `classes/tools/` 目录下找到可执行文件。

这玩意可能会被安全软件拦截（比如超级无敌我爱你的 360！）。没事，一般来说它是安全的，除非 GitHub 上提供的是恶意程序。不相信我 or 担心安全问题？那就删了它。

---

## 说明：模型路径

1. 默认情况下，程序会自动扫描根目录下的 models 文件夹来查找模型，不需要再添加这个路径了；
2. 不同的模型不要放在一个目录里，单独给他们设置一个文件夹，多模态模型的话，mmproj文件也放在一起；
3. **模型文件夹名称唯一**：该程序使用模型所在的文件夹名称作为模型的唯一标识（modelId），请避免在不同路径下出现同名文件夹。例如 `D:\Models\Qwen3.6-27B\model.gguf` 和 `D:\llama.cpp-hub\models\Qwen3.6-27B\aaa.gguf` 会导致 modelId 冲突，影响外部客户端调用。请确保每个模型文件夹名称在整个搜索路径中唯一。
4. 暂时没了。

## 说明：用量统计

1. 这玩意只能统计 **正常完成响应的用量** ，如果某次请求被中断了，那就统计不到token用量了；
2. 远程节点的用量也是统计不到的，如果你在节点A上用了100万token，然后把节点A聚合到了节点B，通过B去访问节点A，那么在B看来，用量是0；
3. 对于 embedding 模型和 rerank 模型，截止 2026年5月22日，无任何统计功能；
4. 暂时没了。

---

## 端口布局

| 端口 | 用途 |
|------|------|
| 8080 | WebUI + OpenAI/Anthropic API + WebSocket |
| 8081+ | 每个已加载模型的推理进程（自动分配） |
| 11434 | Ollama 兼容 API（可选） |
| 1234 | LM Studio 兼容 API（可选） |
| 8075 | MCP 服务器（可选） |

---

## 技术栈

- 后端：Java 21 + Netty 4.1
- 前端：Vanilla JS（无框架、无打包器）
- 模型：llama.cpp 子进程（每个模型独立进程）

---

## AI 工具的使用声明

作为一个非互联网行业的个人开发者，工作之余很难有精力坚持纯手工开发。AI 很好地解决了这个问题——我只需要用尽可能简单的技术方案，配合大量的人工 review 就行。

这个项目的技术栈十分简单，用 AI 开发其实问题不大。尤其是我不考虑更深层次的功能，作为一个启动 llama.cpp 的壳就足够了。

开发中大量使用了 **Qwen3.6-27B-FP8** 来制定计划和执行代码编写任务，其次是 **DeepSeek V4 Flash**。早期也用过 GPT 5.2 到 GPT 5.4，但做这种简单的项目太浪费了，就不再使用。

Qwen3.6-27B-FP8 是我的救星，帮我做了大量大量大量大量的工作！

无敌的 Qwen3.6-27B 和它没用的主人。

---

## 编译说明

### 手动编译
- 注意修改脚本中的 `JAVA_HOME` 路径为实际路径
- Windows：运行 `javac-win.bat`
- Linux：运行 `javac-linux.sh`

### 注意

- 请确保 `JAVA_HOME` 指向 JDK 21 或更高版本
- Windows 使用 CRLF（`\r\n`）作为换行符，Linux 使用 LF（`\n`），修改脚本时请留意
- 如果编译脚本存在问题，也可作为 Maven 项目导入 IDE 自行操作，或直接下载 Release 程序包

---

## 配置文件

位于 `config/` 目录，首次启动自动生成 `application.json`。

| 文件 | 说明 |
|------|------|
| `application.json` | 服务器端口、安全、兼容服务开关 |
| `launch_config.json` | 每个模型的启动参数配置 |
| `llamacpp.json` | llama.cpp 二进制文件路径 |
| `nodes.json` | 远程节点定义 |
| `model-sampling.json` | 采样预设 |
| `modelpaths.json` | 模型搜索目录 |
| `models.json` | 已发现模型的元数据（别名、收藏） |
| `model-sampling-settings.json` | 模型→采样预设绑定 |
| `model-chat-template-kwargs.json` | 每模型聊天模板参数 |
| `mcp-tools.json` | MCP 客户端工具注册 |

---

## 截图预览
![image](./screenshot/1.png)
![image](./screenshot/2.png)
![image](./screenshot/3.png)
![image](./screenshot/4.png)
![image](./screenshot/5.png)
![image](./screenshot/6.png)
![image](./screenshot/7.png)
![image](./screenshot/8.png)
![image](./screenshot/9.png)
![image](./screenshot/10.png)
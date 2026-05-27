# llama.cpp-hub - JIT 分支

> ⚠️ **本分支为功能增强分支**，基于主项目 [IIIIIllllIIIIIlllll/llama.cpp-hub](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub) 开发。
>
> GitHub：https://github.com/yzy806806/llama.cpp-hub

---

## 功能增强

### ⚡ JIT 自动加载模型

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

### 🌐 全局代理配置

添加了 HTTP 代理支持，解决国内用户访问 HuggingFace 模型仓库和 GitHub 更新时的网络问题。

**支持的功能：**
- 模型下载代理（HTTP/HTTPS）
- HuggingFace 模型搜索代理
- GitHub 更新检查代理
- llama.cpp 更新下载代理

**使用方式：**
- WebUI → 设置页面 → 代理标签页 → 启用并配置代理地址

---

## 配置说明

### JIT 配置（`config/application.json`）

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

### 使用方式

1. 在 WebUI 中配置好模型的启动参数，并**设置为默认配置**
2. 发送 API 请求时指定模型名称
3. 如果模型未加载，会自动使用默认配置加载
4. 空闲超时后自动卸载



## 下载

从 [Release 页面](https://github.com/yzy806806/llama.cpp-hub/releases) 下载最新版本。

**推荐版本：**
- Windows + NVIDIA GPU → `windows-cuda13` 或 `windows-cuda12`
- Linux + AMD GPU → `linux-rocm-7.2`

---

## 反馈与交流

- GitHub Issues：https://github.com/yzy806806/llama.cpp-hub/issues

---

## 鸣谢

- 感谢 [IIIIIllllIIIIIlllll](https://github.com/IIIIIllllIIIIIlllll) 的原始项目
- 感谢 [llama.cpp](https://github.com/ggerganov/llama.cpp) 的核心推理引擎

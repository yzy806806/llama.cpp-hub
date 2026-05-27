# llama.cpp-hub

[🇬🇧 English](./README-EN.md) | [🇨🇳 中文](./README.md)

Slapping a web UI on llama.cpp — a graphical interface to wrangle models and llama.cpp. Packed with way too many bells and whistles.

---

![image](./screenshot/laika.jpg)

---

## Features

### Model Management

- Load / unload GGUF models
- Multiple launch configs and sampling presets per model, swap on the fly
- Auto-detects mmproj files in the same directory, lets you pick your own draft model
- Configure multiple llama.cpp versions, pick which one to load with
- Set chat templates, customize kwargs
- Embedding and reranking models need to be manually enabled on the load page

### The Janky Chat

- A very crude chat frontend. Gets the job done. Barely.
- MCP support is there. Nobody's actually gonna use it though... right? Right?? **RIGHT?!**
- The chat UI doesn't even have an English version yet (LAZY!!!)
- At least it shows llama.cpp performance stats

### Multi-Protocol API

One backend, four compatible APIs. Point your favorite SDK at it and you're off:

| Protocol | Port | Status |
|----------|------|--------|
| OpenAI | 8080 | ✅ |
| Anthropic | 8080 | ✅ |
| Ollama | 11434 | ⚠️ Off by default, enable manually |
| LM Studio | 1234 | ⚠️ Off by default, enable manually |

### Web Admin Panel

Desktop is the main squeeze — fully featured. Mobile ⚠️ chronically under-maintained, expect UI jank.

- Model list + realtime status
- Parameter tuning (sampling presets, chat templates, slot states)
- WebSocket live logs
- Usage stats (token consumption, inference speed)
- Quick & dirty benchmark — brute-force shove a ton of context in and see when it finally surrenders
- llama-bench benchmark — you'll actually need to know what you're doing for this one. Good luck.

### Remote Nodes (Service Aggregation)

Aggregate multiple llama.cpp-hub instances into one unified management view. Master-slave architecture merges model lists and running status from multiple servers into the master node's frontend.

**Node Roles:**
- **Master**: Manages all slave nodes' WebSocket connections, runs 30s health checks, relays logs and events
- **Slave**: Takes orders from the master. Default `nodeRole` of `null` = slave

**Remote Routing (3-layer lookup):**
1. Explicit `nodeId` in request body → direct route
2. Locally loaded model → handle locally
3. Full-node fallback → iterate all enabled remote nodes, query `/v1/models`

**WebSocket Event Relaying:**
- Console logs, model load/stop events, model busy state from remote nodes are automatically relayed to the master frontend
- Remote log lines show `[nodeId/modelId]` prefix
- Frontend model list supports node filter dropdown (All / Local / Remote)

**Supported Remote Operations:**
- View model list, load/stop models
- Chat completions (OpenAI, Anthropic, Ollama formats, streaming + non-streaming)
- Embeddings, reranking
- Benchmarking
- VRAM estimation, device list queries

**Configuration:**
- Master: set `"nodeRole": "master"` in `config/application.json`, restart to apply
- Add nodes: WebUI System Settings → Node Management, or call `/api/node/add` API
- Node info: needs `nodeId`, `name`, `baseUrl`, optional `apiKey`
- Protocols: HTTP and HTTPS (auto-trusts self-signed certs)

> **Note:** Avoid using identical model names/IDs across different nodes. When external clients call the master node's `/v1/*` APIs (e.g., `/v1/chat/completions`), if multiple nodes have models with the same name, the system cannot determine which node to route to, resulting in call errors. Use distinct model names across nodes, or explicitly specify `nodeId` in the request body.

---

### Built-in MCP 🧪

Use it to test whether your model can actually execute tool calls. The MCP server listens on port **8075** (enable it in settings).

#### HTTP Routes

| Method | URL | Description |
|--------|-----|-------------|
| `GET` | `/mcp/{serviceKey}` | Streamable HTTP SSE |
| `POST` | `/mcp/{serviceKey}` | Streamable HTTP request |
| `DELETE` | `/mcp/{serviceKey}` | Delete session |
| `GET` | `/mcp/{serviceKey}/sse` | SSE connection |
| `POST` | `/mcp/{serviceKey}/message?sessionId=` | SSE message |

#### Service Keys

| Key | Tools |
|-----|-------|
| `llama_hub_info` | 7 |
| `llama_hub_image` | 1 |
| `llama_hub_file` | 1 |

These tools aren't very useful. Their purpose is questionable. Just pretend they don't exist.

#### MCP Client Config Example

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

### Download Manager ⚠️

- HTTP download with resume support. The backend implementation is pretty basic. For bulk downloading, use aria2, IDM, or something that wasn't cobbled together in a weekend.
- I mainly use this thing to shuttle models around the LAN. Occasional online model downloads are just a bonus.

---

### Online Updates

Auto-check GitHub Release → download the update package → unzip and replace. Since it pokes GitHub's API, expect random connectivity issues and 403s for... *reasons*.

---

## Target Audience

- SSHing into remote servers is a pain and you want an easier way
- You have multiple machines running llama.cpp and want one unified dashboard
- You enjoy compiling llama.cpp yourself but hate the chaos of managing multiple versions lying around
- You can never remember llama.cpp's mountain of parameters but still want to use them

---

## Quick Start

1. Download a Release package that includes llama.cpp, unzip
2. Each GGUF model goes in its own folder, e.g.: `models/Qwen3.5-27B-Q8_0/Qwen3.5-27B.gguf`. Folder names are up to you.
3. Run the startup script: `.bat` on Windows, `.sh` on Linux
4. Open `http://localhost:8080` in your browser
5. Pick a model on the page → click Load → go nuts
6. If it doesn't start, port 8080 might be in use. Make sure it's available before running.

---

## Notes

> **Important**: This app needs file read/write permissions. Without them, the web UI won't load and nothing will work. For example, Windows 11's C: drive root will lock you out.

> **Heads up**: Each model needs its own folder. Keep GGUF files (shards, mmproj, etc.) for one model in one folder — don't mix different models. Models only show up in `/v1/models` after they're loaded.

> **PS**: The UI supports Chinese and English. It auto-switches based on your browser language. You can also force it with `?lang=en` in the URL.

> **Note**: The `/v1/models` API endpoint only returns **running models**. If unloaded models showed up in the list, clients would think they're all available, only to find none of them work — and that's just silly.

---

### Multimodal

By default, the app auto-detects mmproj files in the same directory as the GGUF file (e.g., `Qwen3.6-27B/Qwen3.6-27B.gguf` will look for mmproj in the same directory). If found, the frontend marks the model as multimodal with vision capabilities.

However, if you have multiple fine-tuned variants sharing the same architecture (e.g., `Qwen3.6-27B-PowerEdition.gguf`), they can reuse the same mmproj file. The "same directory" approach would require copying the mmproj for each variant. That's why there's an additional mmproj picker in the load parameters — you can point to any mmproj file manually.

### MTP: A Quick Explainer

llama.cpp lets you do Multi-Token Prediction in two ways:

1. **Models with MTP layers baked in** — load and go, zero fuss
2. **Loading MTP layers separately** — point the "draft model" parameter at a standalone GGUF file containing only MTP layer weights

Why bother with option 2? Because most fine-tune authors don't bother quantizing MTP-enabled versions. So if you want MTP on your favorite shiny variant, you'll have to extract the layers from the original and load them alongside. Whether a variant + original MTP layers actually plays nice together? High-quality answer: beats me.

### A Note on FastFetch

I'm too lazy to implement system info collection myself, so I grabbed fastfetch from GitHub to help out. You can find the binary in the `classes/tools/` directory.

It might get flagged by antivirus software (hello there, 360!). Don't worry — it's generally safe, unless GitHub itself is serving malware. Don't trust me or concerned about security? Go ahead and delete it.

---

## Guide: Model Paths

1. By default, the program auto-scans the `models/` directory at the root — no need to add it manually
2. Don't put different models in the same folder. Give each model its own folder. For multimodal models, keep the mmproj file in the same folder too
3. **Unique model folder names**: The program uses each model's folder name as its unique identifier (modelId). Avoid having identical folder names under different paths. For example, `D:\Models\Qwen3.6-27B\model.gguf` and `D:\llama.cpp-hub\models\Qwen3.6-27B\aaa.gguf` will cause modelId conflicts and break external client calls. Ensure every model folder name is unique across all search paths.
4. That's it for now.

## Guide: Usage Statistics

1. Only **successful responses** are counted. If a request is interrupted, those tokens won't be recorded
2. Remote node usage is NOT tracked. If you burn through 1M tokens on node A, then aggregate node A under node B, node B will show 0 usage for those tokens
3. As of May 22, 2026, embedding and reranking models have zero usage tracking
4. That's it for now.

---

## Port Layout

| Port | Purpose |
|------|---------|
| 8080 | WebUI + OpenAI/Anthropic API + WebSocket |
| 8081+ | Inference process for each loaded model (auto-assigned) |
| 11434 | Ollama compatible API (optional) |
| 1234 | LM Studio compatible API (optional) |
| 8075 | MCP server (optional) |

---

## Tech Stack

- Backend: Java 21 + Netty 4.1
- Frontend: Vanilla JS (no frameworks, no bundlers — we like it raw)
- Models: llama.cpp subprocesses (one process per model — they don't share rooms)

---

## AI Tool Usage Acknowledgement

As an individual developer outside the internet industry, I don't have much energy for pure manual development in my spare time. AI solves this problem well — I just need to use the simplest technical solutions with plenty of manual review.

The tech stack here is very simple, so AI-assisted development works just fine. Especially since I'm not aiming for deep functionality — being a shell for launching llama.cpp is good enough.

I've heavily used **Qwen3.6-27B-FP8** for planning and writing code, followed by **DeepSeek V4 Flash**. Early on I also used GPT 5.2 through GPT 5.4, but for a simple project like this, that felt like overkill.

Qwen3.6-27B-FP8 is my savior. It has done a tremendous, tremendous, tremendous, tremendous amount of work!

The invincible Qwen3.6-27B and its useless master.

---

## Build Instructions

### Manual Build
- Make sure to update the `JAVA_HOME` path in the scripts to your actual path
- Windows: run `javac-win.bat`
- Linux: run `javac-linux.sh`

### Notes

- Make sure `JAVA_HOME` points to JDK 21 or later
- Windows uses CRLF (`\r\n`) line endings, Linux uses LF (`\n`). Watch out when editing scripts across platforms.
- If the build scripts give you trouble, you can also import it as a Maven project in your IDE, or just grab a Release package.

---

## Configuration Files

All in the `config/` directory. `application.json` is auto-generated on first launch.

| File | Description |
|------|-------------|
| `application.json` | Server port, security, compat service toggles |
| `launch_config.json` | Per-model launch parameters |
| `llamacpp.json` | llama.cpp binary paths |
| `nodes.json` | Remote node definitions |
| `model-sampling.json` | Sampling presets |
| `modelpaths.json` | Model search directories |
| `models.json` | Discovered model metadata (aliases, favourites) |
| `model-sampling-settings.json` | Model → sampling preset bindings |
| `model-chat-template-kwargs.json` | Per-model chat template kwargs |
| `mcp-tools.json` | MCP client tool registrations |

---

## Screenshots
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

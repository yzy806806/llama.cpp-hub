# llama.cpp-hub (yzy806806 fork)

> Slapping a web UI on llama.cpp — a graphical interface to manage models and llama.cpp.
>
> Upstream: [IIIIIllllIIIIIlllll/llama.cpp-hub](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub)
>
> 💡 **PWA Supported**: Install directly from your browser to desktop/taskbar for a native-like experience.

---

## 🔒 Security Hardening (Fork-specific)

This fork adds comprehensive security hardening, making it safe to expose on the public internet when API Key is enabled:

### 1. Unified Authentication

`ApiKeyValidator` (`src/main/java/org/mark/llamacpp/server/security/ApiKeyValidator.java`):

| Feature | Detail |
|---------|--------|
| **Constant-time comparison** | `MessageDigest.isEqual` — prevents timing attacks |
| **Three auth methods** | `Authorization: Bearer <key>` / `x-api-key: <key>` / Cookie `lh-api-key` |
| **IP brute force protection** | 5 consecutive failures → 15-minute ban (cap: 10000 entries) |
| **Client IP from TCP** | Reads real IP from TCP connection, ignores `X-Forwarded-For` |

### 2. Pipeline Coverage

All paths go through auth, no blind spots:
- Streaming API (`/v1/chat/completions`) — previously bypassed entirely
- Easy Chat — previously wrote temp files before auth
- File upload/download — previously accepted uploads unauthenticated
- WebSocket — new `WebSocketAuthHandler` authenticates during handshake
- WebUI + `/api/*` — returns login page for browsers, JSON 401 for API

### 3. Subprocess Isolation

llama.cpp subprocesses bind `--host 127.0.0.1`, only Hub accesses them via localhost forwarding. Model ports (8081, 8082…) never exposed directly.

### 4. Inter-Node Auth

Master-slave communication is authenticated when API Key is enabled:
- `RemoteWebSocketClient`: sends `Authorization: Bearer <node.apiKey>` on connect
- HTTP forwarding includes node apiKey
- Nodes without apiKey work as before (fully compatible)

### 5. Sensitivity Redaction

- `/api/sys/setting` no longer returns plaintext `apiKey` / `keystorePassword`
- `/api/auth/verify` endpoint (returns 200/401 only)
- Frontend displays `******` instead of plaintext keys

### 6. Network Hardening

| Area | Measure |
|------|---------|
| CORS | `Allow-Headers` tightened from `*` to explicit list |
| Header filtering | Strips `Authorization` / `Cookie` / `X-Forwarded-*` when forwarding |
| Security headers | `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` |
| Cookie | `SameSite=Strict`, `Secure` when HTTPS |
| Path traversal | Download and directory listing restricted to whitelist |
| Service Worker | Caches only static resources |

---

## ⚙️ Configuration

Config file `config/application.json` (auto-generated on first run):

```json
{
  "security": {
    "apiKeyEnabled": true,
    "apiKey": "your-strong-random-key-here"
  }
}
```

| Setting | Type | Description |
|---------|------|-------------|
| `security.apiKeyEnabled` | Boolean | Set `true` to enable |
| `security.apiKey` | String | Your secret key (32+ random chars recommended) |

**After enabling:**
- Browser visits show a login page, key stored in Cookie (7-day expiry)
- API clients use `Authorization: Bearer <key>` or `x-api-key: <key>`
- Remote nodes: fill in the node's apiKey when adding in the panel

> You can also configure this in the Web UI: System Settings → Security tab.

---

## Features

### Model Management
- Load / unload GGUF models
- Multiple launch configs and sampling presets per model
- Auto-detects mmproj files, manual draft model selection
- Multiple llama.cpp versions support

### Multi-Protocol API
OpenAI / Anthropic compatible. Point your favorite SDK at it.

### Web Admin Panel
- Model list + realtime status / parameters / WebSocket logs
- Usage stats (tokens, inference speed)
- Benchmarks / model cloning / search / system info

### Remote Nodes
Aggregate multiple instances into one unified management view.

### Built-in MCP 🧪
Test model tool calls. MCP server on port 8075 (enable in settings).

### Download Manager
HTTP resume support. Great for LAN model transfers.

### Online Updates
Auto-check GitHub Release → download → unzip → replace.

---

## Quick Start

1. Download a [Release](https://github.com/yzy806806/llama.cpp-hub/releases) package, unzip
2. Download llama.cpp and place under `llamacpp/` directory
3. Each GGUF model in its own folder, e.g.: `models/Qwen3.5-27B-Q8_0/Qwen3.5-27B.gguf`
4. Run `run.bat` (Windows)
5. Open `http://localhost:8080`
6. Select model → Load → Use

> Default port: 8080. Make sure it's free.

---

## Tech Stack

- Backend: Java 21 + Netty 4.1
- Frontend: Vanilla JS
- Models: llama.cpp subprocesses
- Security: constant-time compare + IP rate limiting + subprocess isolation

---

## Port Layout

| Port | Purpose |
|------|---------|
| 8080 | WebUI + OpenAI/Anthropic API + WebSocket |
| 8081+ | Model inference processes (bind 127.0.0.1) |
| 8075 | MCP server (optional) |

---

## ⚠️ Security Disclaimer

**This is a LAN tool — no internet-grade security.**

Even with security hardening, if you expose it publicly:
- **Always enable API Key** (`security.apiKeyEnabled: true`)
- Use a strong key (32+ random characters)
- Use a reverse proxy (Nginx / Cloudflare Tunnel) with HTTPS + access control + rate limiting
- Never expose port 8080 directly

**You assume all responsibility for any loss from public exposure.**

---

## Differences from Upstream

Compared to [upstream](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub):

- ✅ Unified API Key auth + brute force protection
- ✅ Subprocess binds 127.0.0.1
- ✅ WebSocket handshake auth
- ✅ Inter-node auth
- ✅ Sensitivity redaction
- ✅ Network hardening
- ❌ Ollama / LM Studio compat removed (by upstream)
- ❌ ACME Let's Encrypt removed (by upstream)
- 🔗 Auto-update points to this fork
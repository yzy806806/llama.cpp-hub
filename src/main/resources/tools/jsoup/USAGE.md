# JsoupCli 使用说明

## 简介

JsoupCli 是一个轻量级的命令行网页文本提取工具，基于 Netty + Jsoup 构建。

- **无需浏览器内核**，不依赖 Playwright/Selenium
- 自动处理 gzip/brotli 压缩响应
- 信任所有 HTTPS 证书（支持自签名）
- 输出格式化的 Markdown 风格纯文本或 JSON

## 环境要求

- JDK 21+
- Netty 4.1.35.Final

## 运行方式

```bash
java -cp "release.jar;lib/netty-all-4.1.35.Final.jar" org.jsoup.app.JsoupCli [选项]
```

## 参数说明

### 必需参数

| 参数 | 说明 |
|---|---|
| `--url <url>` | 目标 URL |

### 内容选项

| 参数 | 说明 |
|---|---|
| `--selector`, `-s <css>` | CSS 选择器，提取指定元素 |
| `--no-links` | 移除输出中的链接 |
| `--max-length <n>` | 截断输出到 n 个字符 |
| `--json` | 以 JSON 格式输出（包含 url、title、text） |

### HTTP 选项

| 参数 | 说明 |
|---|---|
| `--timeout <ms>` | 请求超时时间，默认 30000ms |
| `--ua`, `--user-agent <ua>` | 自定义 User-Agent |

### 代理选项

| 参数 | 说明 |
|---|---|
| `--proxy-host <host>` | HTTP 代理地址 |
| `--proxy-port <port>` | HTTP 代理端口，默认 7890 |
| `--proxy-user <user>` | 代理用户名 |
| `--proxy-pass <pass>` | 代理密码 |

### 其他

| 参数 | 说明 |
|---|---|
| `--help`, `-h` | 打印帮助信息 |

## 使用示例

### 基本抓取

```bash
java -cp "release.jar;lib/netty-all-4.1.35.Final.jar" org.jsoup.app.JsoupCli --url https://github.com/ggml-org/llama.cpp
```

### 指定 CSS 选择器

```bash
java -cp "release.jar;lib/netty-all-4.1.35.Final.jar" org.jsoup.app.JsoupCli --url https://example.com -s "main article"
```

### JSON 输出

```bash
java -cp "release.jar;lib/netty-all-4.1.35.Final.jar" org.jsoup.app.JsoupCli --url https://example.com --json
```

输出格式：

```json
{
  "url": "https://example.com",
  "title": "Example Domain",
  "length": 1234,
  "text": "..."
}
```

### 通过代理访问

```bash
java -cp "release.jar;lib/netty-all-4.1.35.Final.jar" org.jsoup.app.JsoupCli --url https://example.com --proxy-host 127.0.0.1 --proxy-port 7890
```

### 截断输出

```bash
java -cp "release.jar;lib/netty-all-4.1.35.Final.jar" org.jsoup.app.JsoupCli --url https://example.com --max-length 500
```

### 访问自签名 HTTPS 站点

无需额外配置，工具默认信任所有 SSL 证书：

```bash
java -cp "release.jar;lib/netty-all-4.1.35.Final.jar" org.jsoup.app.JsoupCli --url https://127.0.0.1:8080/v1/models
```

## 文本提取规则

工具会自动处理以下 HTML 元素并转换为可读文本：

| HTML 元素 | 输出格式 |
|---|---|
| `h1` ~ `h6` | Markdown 标题（`#` ~ `######`） |
| `p` | 段落，末尾双换行 |
| `a` | 链接文本 (URL) |
| `img` | `[image: alt] (src)` |
| `li` | `- 列表项` |
| `table/tr/td` | 管道分隔的表格 |
| `pre` | 保留原始格式 |
| `blockquote` | `> 引用内容` |
| `br` | 换行 |
| `hr` | `---` 分隔线 |

以下元素会被自动移除：`script`、`style`、`nav`、`footer`、`header`、`aside`、`noscript`，以及含 `.sidebar`、`.menu`、`.ad`、`.ads`、`.advertisement` 类名的元素。

## 局限性

- **仅支持静态网页**。JavaScript 动态渲染的内容无法抓取。
- 字符集检测依赖 HTTP `Content-Type` header，部分页面可能因编码声明不一致导致乱码。

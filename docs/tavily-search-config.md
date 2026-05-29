# Tavily Search MCP 服务配置说明

## 前置条件

1. 前往 https://tavily.com 注册账号
2. 在 Dashboard 获取 API Key（格式：`tvly-xxxxx`）
3. 免费额度：每月 1000 次调用

## 客户端配置

### Streamable HTTP 方式（推荐）

将以下 JSON 配置添加到你的 MCP 客户端：

```json
{
  "transport": "streamable_http",
  "url": "http://localhost:8075/mcp/tavily_search",
  "headers": {
    "X-Tavily-Api-Key": "tvly-你的API密钥"
  },
  "timeout": 5,
  "sse_read_timeout": 300
}
```

### SSE 方式

```json
{
  "transport": "sse",
  "url": "http://localhost:8075/mcp/tavily_search/sse",
  "headers": {
    "X-Tavily-Api-Key": "tvly-你的API密钥"
  },
  "timeout": 5,
  "sse_read_timeout": 300
}
```

## 可用工具

### 1. tavily_search — 智能搜索

搜索网络内容，返回结构化的结果列表。

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string | 是 | 搜索关键词 |
| `max_results` | integer | 否 | 返回数量，默认5，最大20 |
| `search_depth` | string | 否 | `"basic"` 或 `"advanced"`，默认 `"basic"` |
| `topic` | string | 否 | `"general"` 或 `"news"`，默认 `"general"` |
| `include_answer` | boolean | 否 | 是否返回AI摘要，默认 `true` |
| `days` | integer | 否 | 时间范围（天数），默认3 |

**调用示例：**

```json
{
  "query": "Java MCP protocol",
  "max_results": 5,
  "search_depth": "basic",
  "topic": "general"
}
```

### 2. tavily_extract — 网页内容提取

提取一个或多个网页的正文内容。

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `urls` | string | 是 | URL 列表，逗号分隔 |
| `extract_depth` | string | 否 | `"basic"` 或 `"advanced"`，默认 `"basic"` |

**调用示例：**

```json
{
  "urls": "https://example.com,https://other.com",
  "extract_depth": "basic"
}
```

## 典型工作流

1. 调用 `tavily_search` 搜索关键词
2. 从搜索结果中选取感兴趣的 URL
3. 调用 `tavily_extract` 提取目标页面的完整正文
4. 将提取的内容用于后续分析或问答

## 注意事项

- API Key 通过 HTTP Header `X-Tavily-Api-Key` 传递，不会出现在日志中
- 搜索结果的 `content` 字段为摘要，如需完整内容请用 `tavily_extract`
- `advanced` 深度模式耗时更长但结果更精准
- 免费账户有调用频率限制，如遇 429 错误请稍后重试

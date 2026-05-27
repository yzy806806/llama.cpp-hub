package org.mark.llamacpp.server.service;

import org.mark.llamacpp.server.mcp.McpClientService;
import org.mark.llamacpp.server.tools.JsonUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;


/**
 * 	调用工具的服务。
 */
public class ToolExecutionService {

	/**
	 * 	空构造器。
	 */
	public ToolExecutionService() {

	}

	/**
	 * 	执行工具并生成文字内容。
	 * @param toolName
	 * @param toolArguments
	 * @param preparedQuery
	 * @return
	 */
	public String executeToText(String toolName, String toolArguments, String preparedQuery) {
		if (toolName == null || toolName.isBlank()) {
			return "检测到 tool_calls，但无法解析工具名称";
		}
		try {
			JsonObject resp = McpClientService.getInstance().callTool(toolName.trim(), toolArguments);
			return formatMcpToolResult(resp);
		} catch (Exception e) {
			return "MCP工具调用失败：" + e.getMessage();
		}
	}

	private String formatMcpToolResult(JsonObject resp) {
		if (resp == null) {
			return "";
		}
		if (resp.has("error") && resp.get("error") != null && resp.get("error").isJsonObject()) {
			JsonObject err = resp.getAsJsonObject("error");
			String msg = safeString(err, "message");
			return msg == null ? "MCP工具调用失败" : ("MCP工具调用失败：" + msg);
		}
		if (!resp.has("result") || resp.get("result") == null || !resp.get("result").isJsonObject()) {
			return resp.toString();
		}
		JsonObject result = resp.getAsJsonObject("result");
		if (!result.has("content") || result.get("content") == null || !result.get("content").isJsonArray()) {
			return result.toString();
		}
		JsonArray contentArr = result.getAsJsonArray("content");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < contentArr.size(); i++) {
			JsonElement el = contentArr.get(i);
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			JsonObject item = el.getAsJsonObject();
			String type = safeString(item, "type");
			if (type != null && type.equals("text")) {
				String text = safeString(item, "text");
				if (text != null && !text.isBlank()) {
					if (sb.length() > 0) {
						sb.append("\n");
					}
					sb.append(text.trim());
				}
			} else {
				if (sb.length() > 0) {
					sb.append("\n");
				}
				sb.append(item.toString());
			}
		}
		return sb.toString().trim();
	}

	private String safeString(JsonObject obj, String key) {
		return JsonUtil.getJsonString(obj, key, null);
	}
}

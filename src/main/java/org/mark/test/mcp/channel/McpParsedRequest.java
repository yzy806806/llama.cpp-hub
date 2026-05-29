package org.mark.test.mcp.channel;

import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class McpParsedRequest {

	private final JsonObject body;
	private final String method;
	private final JsonElement id;
	private final Map<String, String> headers;

	public McpParsedRequest(JsonObject body, String method, JsonElement id) {
		this(body, method, id, null);
	}

	public McpParsedRequest(JsonObject body, String method, JsonElement id, Map<String, String> headers) {
		this.body = body;
		this.method = method;
		this.id = id;
		this.headers = headers;
	}

	public JsonObject getBody() {
		return body;
	}

	public String getMethod() {
		return method;
	}

	public JsonElement getId() {
		return id;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}
}

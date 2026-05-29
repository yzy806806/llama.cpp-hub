package org.mark.test.mcp.channel;

import java.util.Map;

import com.google.gson.JsonObject;

public class McpProtocolResult {

	private final JsonObject response;
	private final Map<String, String> sessionHeaders;

	private McpProtocolResult(JsonObject response) {
		this(response, null);
	}

	private McpProtocolResult(JsonObject response, Map<String, String> sessionHeaders) {
		this.response = response;
		this.sessionHeaders = sessionHeaders;
	}

	public JsonObject getResponse() {
		return response;
	}

	public boolean hasResponse() {
		return response != null;
	}

	public Map<String, String> getSessionHeaders() {
		return sessionHeaders;
	}

	public static McpProtocolResult respond(JsonObject response) {
		return new McpProtocolResult(response);
	}

	public static McpProtocolResult respondWithHeaders(JsonObject response, Map<String, String> sessionHeaders) {
		return new McpProtocolResult(response, sessionHeaders);
	}

	public static McpProtocolResult accept() {
		return new McpProtocolResult(null);
	}
}

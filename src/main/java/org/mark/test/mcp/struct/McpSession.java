package org.mark.test.mcp.struct;

import java.util.Map;

import io.netty.channel.ChannelHandlerContext;

public class McpSession {

	private final String id;
	private final String serviceKey;
	private final boolean sse;
	private volatile ChannelHandlerContext ctx;
	private volatile Map<String, String> headers;

	public McpSession(String id, String serviceKey, ChannelHandlerContext ctx, boolean sse) {
		this.id = id;
		this.serviceKey = serviceKey;
		this.ctx = ctx;
		this.sse = sse;
	}

	public String getId() {
		return id;
	}

	public String getServiceKey() {
		return serviceKey;
	}

	public boolean isSse() {
		return sse;
	}

	public ChannelHandlerContext getCtx() {
		return ctx;
	}

	public void bindCtx(ChannelHandlerContext ctx) {
		this.ctx = ctx;
	}

	public void clearCtx() {
		this.ctx = null;
	}

	public void clearCtxIfMatches(ChannelHandlerContext ctx) {
		if (this.ctx == ctx) {
			this.ctx = null;
		}
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public void setHeaders(Map<String, String> headers) {
		this.headers = headers;
	}

	public String getHeader(String name) {
		if (headers == null || name == null) {
			return null;
		}
		String lowerName = name.toLowerCase(java.util.Locale.ROOT);
		for (Map.Entry<String, String> e : headers.entrySet()) {
			if (e.getKey().toLowerCase(java.util.Locale.ROOT).equals(lowerName)) {
				return e.getValue();
			}
		}
		return null;
	}
}

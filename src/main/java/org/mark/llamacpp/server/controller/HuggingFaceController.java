package org.mark.llamacpp.server.controller;

import java.util.Map;

import org.mark.llamacpp.crawler.HuggingFaceSearcher;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.ParamTool;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

/**
 * 	
 */
public class HuggingFaceController implements BaseController {
	
	
	
	
	public HuggingFaceController() {
		
	}
	
	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (uri.startsWith("/api/hf/search")) {
			this.handleHFSearchRequest(ctx, request);
			return true;
		}
		if (uri.startsWith("/api/hf/gguf")) {
			this.handleHFGGUFRequest(ctx, request);
			return true;
		}
		if (uri.startsWith("/api/hf/readme")) {
			this.handleHFReadmeRequest(ctx, request);
			return true;
		}
		
		return false;
	}
	
	
	/**
	 * 	处理HF搜索请求。
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleHFSearchRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String query = params.get("query");
			if (query == null || query.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的query参数"));
				return;
			}
			int limit = parseIntOrDefault(params.get("limit"), 30);
			int timeoutSeconds = parseIntOrDefault(params.get("timeoutSeconds"), 20);
			int startPage = parseIntOrDefault(params.get("startPage"), 0);
			int maxPages = parseIntOrDefault(params.get("maxPages"), 0);
			String base = firstNonBlank(params.get("base"), params.get("baseUrl"), params.get("host"));

			var result = HuggingFaceSearcher.search(query.trim(), limit, timeoutSeconds, startPage, maxPages, base);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(result));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("搜索失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	处理HF模型信息请求
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleHFGGUFRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String input = firstNonBlank(params.get("model"), params.get("repoId"), params.get("modelUrl"), params.get("url"),
					params.get("input"));
			if (input == null || input.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的model参数"));
				return;
			}
			int timeoutSeconds = parseIntOrDefault(params.get("timeoutSeconds"), 20);
			String base = firstNonBlank(params.get("base"), params.get("baseUrl"), params.get("host"));
			var result = HuggingFaceSearcher.listGgufFiles(input.trim(), timeoutSeconds, base);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(result));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("解析GGUF失败: " + e.getMessage()));
		}
	}

	private void handleHFReadmeRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String input = firstNonBlank(params.get("model"), params.get("repoId"), params.get("modelUrl"), params.get("url"),
					params.get("input"));
			if (input == null || input.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的model参数"));
				return;
			}
			int timeoutSeconds = parseIntOrDefault(params.get("timeoutSeconds"), 20);
			String base = firstNonBlank(params.get("base"), params.get("baseUrl"), params.get("host"));
			var result = HuggingFaceSearcher.fetchReadme(input.trim(), timeoutSeconds, base);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(result));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取README失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 	
	 * @param value
	 * @param fallback
	 * @return
	 */
	private static int parseIntOrDefault(String value, int fallback) {
		if (value == null)
			return fallback;
		String s = value.trim();
		if (s.isEmpty())
			return fallback;
		try {
			return Integer.parseInt(s);
		} catch (Exception e) {
			return fallback;
		}
	}
	
	/**
	 * 	
	 * @param values
	 * @return
	 */
	private static String firstNonBlank(String... values) {
		if (values == null)
			return null;
		for (String v : values) {
			if (v == null)
				continue;
			String s = v.trim();
			if (!s.isEmpty())
				return s;
		}
		return null;
	}
}

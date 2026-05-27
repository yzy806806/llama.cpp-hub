package org.mark.llamacpp.server.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;

/**
 * JIT 自动加载配置 REST API
 * 
 * GET  /api/config/jit — 返回当前 JIT 配置
 * PUT  /api/config/jit — 更新 JIT 配置（带校验）
 */
public class JitConfigController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(JitConfigController.class);
	
	private static final Set<String> VALID_LOAD_STRATEGIES = Set.of("lru", "fifo");

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		
		if (uri.startsWith("/api/config/jit") && request.method() == HttpMethod.GET) {
			this.handleGetJitConfig(ctx, request);
			return true;
		}
		if (uri.startsWith("/api/config/jit") && request.method() == HttpMethod.PUT) {
			this.handlePutJitConfig(ctx, request);
			return true;
		}
		
		return false;
	}

	/**
	 * GET /api/config/jit
	 * 返回当前 JIT 配置 JSON
	 */
	private void handleGetJitConfig(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			Map<String, Object> jit = new HashMap<>();
			jit.put("enabled", LlamaServer.isJitEnabled());
			jit.put("defaultTtl", LlamaServer.getJitDefaultTtl());
			jit.put("maxLoadedModels", LlamaServer.getJitMaxLoadedModels());
			jit.put("loadStrategy", LlamaServer.getJitLoadStrategy());
			jit.put("allowQueue", LlamaServer.isJitAllowQueue());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(jit));
		} catch (Exception e) {
			logger.error("获取 JIT 配置失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取 JIT 配置失败: " + e.getMessage()));
		}
	}

	/**
	 * PUT /api/config/jit
	 * 接收配置 JSON 并持久化
	 * 
	 * 校验规则：
	 * - enabled: boolean
	 * - defaultTtl: int (>= 0)
	 * - maxLoadedModels: int (>= 1)
	 * - loadStrategy: "lru" | "fifo"
	 * - allowQueue: boolean
	 */
	private void handlePutJitConfig(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			String body = request.content().toString(CharsetUtil.UTF_8);
			if (body == null || body.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			
			JsonObject obj;
			try {
				obj = JsonUtil.fromJson(body, JsonObject.class);
			} catch (Exception e) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("JSON 格式错误"));
				return;
			}
			
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("JSON 格式错误"));
				return;
			}
			
			// 解析并校验各字段
			Boolean enabled = null;
			Integer defaultTtl = null;
			Integer maxLoadedModels = null;
			String loadStrategy = null;
			Boolean allowQueue = null;
			
			if (obj.has("enabled")) {
				try {
					enabled = obj.get("enabled").getAsBoolean();
				} catch (Exception e) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("enabled 必须是 boolean 类型"));
					return;
				}
			}
			
			if (obj.has("defaultTtl")) {
				try {
					defaultTtl = obj.get("defaultTtl").getAsInt();
					if (defaultTtl < 0) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error("defaultTtl 不能为负数"));
						return;
					}
				} catch (Exception e) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("defaultTtl 必须是整数"));
					return;
				}
			}
			
			if (obj.has("maxLoadedModels")) {
				try {
					maxLoadedModels = obj.get("maxLoadedModels").getAsInt();
					if (maxLoadedModels < 1) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error("maxLoadedModels 不能小于 1"));
						return;
					}
				} catch (Exception e) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("maxLoadedModels 必须是整数"));
					return;
				}
			}
			
			if (obj.has("loadStrategy")) {
				try {
					loadStrategy = obj.get("loadStrategy").getAsString();
					if (!VALID_LOAD_STRATEGIES.contains(loadStrategy)) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error("loadStrategy 必须是 lru 或 fifo"));
						return;
					}
				} catch (Exception e) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("loadStrategy 必须是字符串"));
					return;
				}
			}
			
			if (obj.has("allowQueue")) {
				try {
					allowQueue = obj.get("allowQueue").getAsBoolean();
				} catch (Exception e) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("allowQueue 必须是 boolean 类型"));
					return;
				}
			}
			
			// 调用 LlamaServer 更新配置
			LlamaServer.updateJitConfig(enabled, defaultTtl, maxLoadedModels, loadStrategy, allowQueue);
			
			// 返回更新后的完整配置
			Map<String, Object> result = new HashMap<>();
			result.put("enabled", LlamaServer.isJitEnabled());
			result.put("defaultTtl", LlamaServer.getJitDefaultTtl());
			result.put("maxLoadedModels", LlamaServer.getJitMaxLoadedModels());
			result.put("loadStrategy", LlamaServer.getJitLoadStrategy());
			result.put("allowQueue", LlamaServer.isJitAllowQueue());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(result));
			
		} catch (Exception e) {
			logger.error("更新 JIT 配置失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("更新 JIT 配置失败: " + e.getMessage()));
		}
	}
}

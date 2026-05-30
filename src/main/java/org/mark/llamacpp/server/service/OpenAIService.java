package org.mark.llamacpp.server.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.CharsetUtil;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.ConfigManager;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaHubNode;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.struct.ActiveRequest;
import org.mark.llamacpp.server.struct.Timing;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.mark.llamacpp.server.service.RequestQueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 	处理openai api请求的服务。
 */
public class OpenAIService {
	
	private static final Logger logger = LoggerFactory.getLogger(OpenAIService.class);
	
	/**
	 * 	存储当前通道正在处理的模型链接，用于在连接关闭时停止对应的模型进程
	 */
	private final Map<ChannelHandlerContext, HttpURLConnection> channelConnectionMap = new HashMap<>();
	
	/**
	 * 	线程池。
	 */
	private static final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
	
	/**
	 * 	给响应头做时间转换（DateTimeFormatter是线程安全的）
	 */
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
				.ofPattern("EEE, d MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
				.withZone(ZoneId.of("GMT"));

		/**
		 * 请求队列管理器
		 */
		private static final RequestQueueManager queueManager = RequestQueueManager.getInstance();

		/**
		 * 集霸矛！
		 */
		public OpenAIService() {
			// P0 Fix: Register this instance with RequestQueueManager for failAll to send HTTP error responses
			RequestQueueManager.setOpenAIService(this);
		}
	
	/**
	 * 	处理模型列表请求
	 * 	/api/models
	 * 	
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIModelsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only GET method is supported", "method");
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			Map<String, LlamaCppProcess> loaded = manager.getLoadedProcesses();

			Map<String, JsonObject> modelsByKey = new LinkedHashMap<>();
			Map<String, JsonObject> dataById = new LinkedHashMap<>();

			for (Map.Entry<String, LlamaCppProcess> e : loaded.entrySet()) {
				String modelId = e.getKey();
				if (modelId == null || modelId.isBlank()) {
					continue;
				}
				JsonObject capabilities = manager.getModelCapabilities(modelId);
				int runtimeCtx = e.getValue().getCtxSize();

				JsonObject info = manager.getLoadedModelInfo(modelId);
				if (info == null) {
					try {
						info = manager.handleModelInfo(modelId);
					} catch (Exception ignore) {
						info = null;
					}
				}
				if (info == null) {
					continue;
				}

				if (!info.has("items") || !info.get("items").isJsonArray()) {
					continue;
				}
				JsonArray items = info.getAsJsonArray("items");
				for (JsonElement itemEl : items) {
					if (itemEl == null || itemEl.isJsonNull() || !itemEl.isJsonObject()) {
						continue;
					}
					JsonObject item = itemEl.getAsJsonObject();

					if (item.has("model") && item.get("model").isJsonObject()) {
						JsonObject m = item.getAsJsonObject("model");
						String key = JsonUtil.getJsonString(m, "model");
						if (key.isEmpty()) {
							key = JsonUtil.getJsonString(m, "name");
						}
						if (!key.isEmpty() && !modelsByKey.containsKey(key)) {
							JsonObject mCopy = m.deepCopy();
							mCopy.addProperty("runtimeCtx", runtimeCtx);
							mCopy.add("my_capabilities", capabilities);
							modelsByKey.put(key, mCopy);
						}
					}

					if (item.has("data") && item.get("data").isJsonObject()) {
						JsonObject d = item.getAsJsonObject("data");
						String id = JsonUtil.getJsonString(d, "id");
						if (!id.isEmpty() && !dataById.containsKey(id)) {
							JsonObject dCopy = d.deepCopy();
							dCopy.addProperty("runtimeCtx", runtimeCtx);
							dCopy.add("my_capabilities", capabilities);
							dataById.put(id, dCopy);
						}
					}
				}
			}

			for (org.mark.llamacpp.server.LlamaHubNode node : NodeManager.getInstance().listEnabledNodes()) {
				this.mergeRemoteModels(node.getNodeId(), node.getName(), modelsByKey, dataById);
			}

			JsonArray models = new JsonArray();
			for (JsonObject m : modelsByKey.values()) {
				models.add(m);
			}
			JsonArray data = new JsonArray();
			for (JsonObject d : dataById.values()) {
				data.add(d);
			}

			JsonObject response = new JsonObject();
			response.addProperty("object", "list");
			response.add("models", models);
			response.add("data", data);
			sendOpenAIJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取模型列表时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 合并远程节点的模型列表到本地结果中
	 */
	private void mergeRemoteModels(String nodeId, String nodeName,
	                               Map<String, JsonObject> modelsByKey,
	                               Map<String, JsonObject> dataById) {
		try {
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
					nodeId, "GET", "/v1/models", null);
			if (!result.isSuccess()) {
				logger.warn("获取远程节点模型列表失败: nodeId={}, code={}, body={}", nodeId, result.getStatusCode(), result.getBody());
				return;
			}

			JsonObject root = JsonUtil.fromJson(result.getBody(), JsonObject.class);
			if (root == null) return;

			if (root.has("models") && root.get("models").isJsonArray()) {
				JsonArray remoteModels = root.getAsJsonArray("models");
				for (JsonElement el : remoteModels) {
					if (!el.isJsonObject()) continue;
					JsonObject m = el.getAsJsonObject();
					String key = JsonUtil.getJsonString(m, "model");
					if (key.isEmpty()) key = JsonUtil.getJsonString(m, "name");
					if (key.isEmpty()) continue;
					key = nodeId + ":" + key;
					if (!modelsByKey.containsKey(key)) {
						JsonObject copy = m.deepCopy();
						copy.addProperty("nodeId", nodeId);
						copy.addProperty("nodeName", nodeName);
						modelsByKey.put(key, copy);
					}
				}
			}
			if (root.has("data") && root.get("data").isJsonArray()) {
				JsonArray remoteData = root.getAsJsonArray("data");
				for (JsonElement el : remoteData) {
					if (!el.isJsonObject()) continue;
					JsonObject d = el.getAsJsonObject();
					String id = JsonUtil.getJsonString(d, "id");
					if (id.isEmpty()) continue;
					id = nodeId + ":" + id;
					if (!dataById.containsKey(id)) {
						JsonObject copy = d.deepCopy();
						copy.addProperty("nodeId", nodeId);
						copy.addProperty("nodeName", nodeName);
						dataById.put(id, copy);
					}
				}
			}
		} catch (Exception e) {
			logger.warn("合并远程节点模型列表失败: nodeId={}, error={}", nodeId, e.getMessage());
		}
	}

	/**
	 * 	处理 OpenAI 聊天补全请求，/v1/chat/completions。考虑到现在有了LlamaServer.isChatStreamingEnabled()，应该不会在进入这里了。
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIChatCompletionsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 只支持POST请求
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}

			// 读取请求体
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "messages");
				return;
			}

			// 解析JSON请求体
			JsonObject requestJson = JsonUtil.fromJson(content, JsonObject.class);
			if (requestJson == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is not a valid JSON object", null);
				return;
			}
			
			// 获取模型名称
			if (!requestJson.has("model") || requestJson.get("model") == null || requestJson.get("model").isJsonNull()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Missing required parameter: model", "model");
				return;
			}
			String modelName = null;
			try {
				modelName = requestJson.get("model").getAsString();
			} catch (Exception ignore) {
			}
			if (modelName == null || modelName.isBlank()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Invalid parameter: model", "model");
				return;
			}
			
			// 检查是否为流式请求
			boolean isStream = false;
			if (requestJson.has("stream")) {
				try {
					isStream = requestJson.get("stream").getAsBoolean();
				} catch (Exception ignore) {
				}
			}
//			// 这个东西暂时用于控制enable_thinking，实际上是不完善的，临时解决吧。
//			if (requestJson.has("enable_thinking") && !requestJson.has("chat_template_kwargs")) {
//				// 拼接一个chat_template_kwargs进去： "chat_template_kwargs" : {"enable_thinking": false},
//				JsonObject chatTemplateKwargs = new JsonObject();
//				chatTemplateKwargs.addProperty("enable_thinking", requestJson.get("enable_thinking").getAsBoolean());
//				// 添加到主 JsonObject
//				requestJson.add("chat_template_kwargs", chatTemplateKwargs);
//			}
			
			// 统一处理 enable_thinking / thinking.type 等兼容字段，保持与流式链路一致。
			this.applyThinkingInjection(requestJson);
			this.applyChatTemplateKwargsInjection(requestJson);
			// 这里做采样代理，针对llamacpp中的请求，注入采样参数。
			ModelSamplingService service = ModelSamplingService.getInstance();
			service.handleOpenAI(requestJson);
			
			// 获取LlamaServerManager实例
			LlamaServerManager manager = LlamaServerManager.getInstance();

			String body = JsonUtil.toJson(requestJson);
			String bodyNodeId = JsonUtil.getJsonString(requestJson, "nodeId", "");
			if (bodyNodeId != null && !bodyNodeId.isBlank()) {
				requestJson.remove("nodeId");
				body = JsonUtil.toJson(requestJson);
				NodeProxyService.getInstance().proxyStreamRequest(ctx, request, bodyNodeId, "v1/chat/completions", requestJson);
			} else {
				if (!manager.getLoadedProcesses().containsKey(modelName)) {
					String resolved = manager.findModelIdByAlias(modelName);
					if (resolved != null) {
						modelName = resolved;
					}
				}
				if (manager.getLoadedProcesses().containsKey(modelName)) {
					Integer modelPort = manager.getModelPort(modelName);
					if (modelPort == null) {
						this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
						return;
					}
					// 更新 JIT 活动时间
					manager.updateModelActiveTime(modelName);
					this.forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, "/v1/chat/completions", isStream, body);
				} else {
					// 模型未加载，检查是否启用 JIT 自动加载
					if (LlamaServer.isJitEnabled()) {
						boolean loaded = this.jitAutoLoadModel(ctx, request, modelName, requestJson, manager, body, isStream, "/v1/chat/completions");
						if (loaded) {
							return;
						}
					}
					this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + modelName, "model");
				}
			}
		} catch (Exception e) {
			logger.info("处理OpenAI聊天补全请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 统一委托公共工具处理 thinking 兼容字段，避免普通链路与流式链路出现行为漂移。
	 * @param requestJson
	 */
	private void applyThinkingInjection(JsonObject requestJson) {
		ParamTool.handleOpenAIChatThinking(requestJson);
	}
	
	/**
	 * 	注入 chat-template-kwargs
	 * @param requestJson
	 */
	private void applyChatTemplateKwargsInjection(JsonObject requestJson) {
		ChatTemplateKwargsService.getInstance().handleOpenAI(requestJson);
	}
	
	
	/**
	 * 	处理 OpenAI 文本补全请求：/v1/completions
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAICompletionsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 只支持POST请求
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}

			// 读取请求体
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "messages");
				return;
			}

			// 解析JSON请求体
			JsonObject requestJson = JsonUtil.fromJson(content, JsonObject.class);

			// 获取LlamaServerManager实例
			LlamaServerManager manager = LlamaServerManager.getInstance();

			String modelName = null;

			// 搜索模型的名字，如果没有这个字段，则直接取用第一个模型。
			if (!requestJson.has("model")) {
				modelName = manager.getFirstModelName();
				if (modelName == null) {
					this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "No models are currently loaded", null);
					return;
				}
				requestJson.addProperty("model", modelName);
			} else {
				modelName = requestJson.get("model").getAsString();
			}

			// 检查是否为流式请求
			boolean isStream = false;
			if (requestJson.has("stream")) {
				isStream = requestJson.get("stream").getAsBoolean();
			}

			// 检查模型是否已加载
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				String resolved = manager.findModelIdByAlias(modelName);
				if (resolved != null) {
					modelName = resolved;
				}
			}
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				// JIT 自动加载
				if (LlamaServer.isJitEnabled()) {
					String body = JsonUtil.toJson(requestJson);
					boolean loaded = this.jitAutoLoadModel(ctx, request, modelName, requestJson, manager, body, isStream, "/v1/completions");
					if (loaded) {
						return;
					}
				}
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + modelName, "model");
				return;
			}
			ModelSamplingService service = ModelSamplingService.getInstance();
			service.handleOpenAI(requestJson);
			
			// 在这加入特殊处理，判断是否存在特殊字符。
			//String body = LlamaCommandParser.filterCompletion(ctx, modelName, requestJson);
			//if(body == null)
				//return;
			// 获取模型端口
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			// 更新 JIT 活动时间
			manager.updateModelActiveTime(modelName);
			// 转发请求到对应的llama.cpp进程
			this.forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, "/v1/completions", isStream, JsonUtil.toJson(requestJson));
		} catch (Exception e) {
			logger.info("处理OpenAI文本补全请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 	处理 OpenAI 嵌入请求
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIEmbeddingsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "messages");
				return;
			}
			JsonObject requestJson = JsonUtil.fromJson(content, JsonObject.class);
			LlamaServerManager manager = LlamaServerManager.getInstance();
			String modelName = null;
			if (!requestJson.has("model")) {
				modelName = manager.getFirstModelName();
				if (modelName == null) {
					this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "No models are currently loaded", null);
					return;
				}
			} else {
				modelName = requestJson.get("model").getAsString();
			}
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				String resolved = manager.findModelIdByAlias(modelName);
				if (resolved != null) {
					modelName = resolved;
				}
			}
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				// JIT 自动加载
				if (LlamaServer.isJitEnabled()) {
					boolean loaded = this.jitAutoLoadModel(ctx, request, modelName, requestJson, manager, content, false, "/v1/embeddings");
					if (loaded) {
						return;
					}
				}
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + modelName, "model");
				return;
			}
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			// 更新 JIT 活动时间
			manager.updateModelActiveTime(modelName);
			this.forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, "/v1/embeddings", false, request.content().toString(StandardCharsets.UTF_8));
		} catch (Exception e) {
			logger.info("处理OpenAI嵌入请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 	转发rerank请求，重排序用。
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIRerankRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "query");
				return;
			}
			JsonObject requestJson = JsonUtil.fromJson(content, JsonObject.class);
			LlamaServerManager manager = LlamaServerManager.getInstance();
			String modelName;
			if (!requestJson.has("model")) {
				modelName = manager.getFirstModelName();
				if (modelName == null) {
					this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "No models are currently loaded", null);
					return;
				}
			} else {
				modelName = requestJson.get("model").getAsString();
			}
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				String resolved = manager.findModelIdByAlias(modelName);
				if (resolved != null) {
					modelName = resolved;
				}
			}
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				// JIT 自动加载
				if (LlamaServer.isJitEnabled()) {
					boolean loaded = this.jitAutoLoadModel(ctx, request, modelName, requestJson, manager, content, false, "/v1/rerank");
					if (loaded) {
						return;
					}
				}
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + modelName, "model");
				return;
			}
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			// 更新 JIT 活动时间
			manager.updateModelActiveTime(modelName);
			String endpoint = request.uri();
			if (endpoint != null && endpoint.startsWith("/rerank")) {
				endpoint = "/v1" + endpoint;
			}
			if (endpoint == null || endpoint.isBlank()) {
				endpoint = "/v1/rerank";
			}
			this.forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, endpoint, false, content);
		} catch (Exception e) {
			logger.info("处理OpenAI rerank 请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 	对应端点：/v1/responses
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIResponsesRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "input");
				return;
			}

			JsonObject requestJson = JsonUtil.fromJson(content, JsonObject.class);
			LlamaServerManager manager = LlamaServerManager.getInstance();

			String modelName;
			if (!requestJson.has("model")) {
				modelName = manager.getFirstModelName();
				if (modelName == null) {
					this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "No models are currently loaded", null);
					return;
				}
			} else {
				modelName = requestJson.get("model").getAsString();
			}

			boolean isStream = false;
			if (requestJson.has("stream")) {
				isStream = requestJson.get("stream").getAsBoolean();
			}

			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				String resolved = manager.findModelIdByAlias(modelName);
				if (resolved != null) {
					modelName = resolved;
				}
			}
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				// JIT 自动加载
				if (LlamaServer.isJitEnabled()) {
					boolean loaded = this.jitAutoLoadModel(ctx, request, modelName, requestJson, manager, content, isStream, "/v1/responses");
					if (loaded) {
						return;
					}
				}
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + modelName, "model");
				return;
			}
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			// 更新 JIT 活动时间
			manager.updateModelActiveTime(modelName);
			String endpoint = request.uri();
			if (endpoint != null && endpoint.startsWith("/responses")) {
				endpoint = "/v1" + endpoint;
			}
			if (endpoint == null || endpoint.isBlank()) {
				endpoint = "/v1/responses";
			}
			this.forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, endpoint, isStream, content);
		} catch (Exception e) {
			logger.info("处理OpenAI responses 请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	
	/**
	 * 	转发请求到对应的llama.cpp进程
	 * @param ctx
	 * @param request
	 * @param modelName
	 * @param port
	 * @param endpoint
	 * @param isStream
	 * @param requestBody
	 */
	private void forwardRequestToLlamaCpp(ChannelHandlerContext ctx, FullHttpRequest request, String modelName, int port, String endpoint, boolean isStream, String requestBody) {
		byte[] requestBodyBytes = requestBody == null ? new byte[0] : requestBody.getBytes(StandardCharsets.UTF_8);
		// 从 FullHttpRequest 提取 method 和 headers
		HttpMethod method = request.method();
		Map<String, String> headersMap = new HashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			headersMap.put(entry.getKey(), entry.getValue());
		}
		this.forwardRequestToLlamaCpp(ctx, method, headersMap, port, endpoint, isStream, requestBodyBytes, modelName);
	}

	/**
	 * 转发请求到 llama.cpp 进程（使用 FullHttpRequest + byte[]）
	 */
	private void forwardRequestToLlamaCpp(ChannelHandlerContext ctx, FullHttpRequest request, String modelName, int port, String endpoint, boolean isStream, byte[] requestBodyBytes) {
		// 从 FullHttpRequest 提取 method 和 headers
		HttpMethod method = request.method();
		Map<String, String> headersMap = new HashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			headersMap.put(entry.getKey(), entry.getValue());
		}
		this.forwardRequestToLlamaCpp(ctx, method, headersMap, port, endpoint, isStream, requestBodyBytes, modelName);
	}

	/**
	 * 转发请求到 llama.cpp 进程（使用预复制的 headers，适用于队列中的请求）
	 * P0 Fix: Now accepts explicit httpMethod parameter to avoid :method pseudo-header issue in HTTP/1.1
	 *
	 * @deprecated 此重载仅用于已知 POST 的场景。建议使用带显式 httpMethod 参数的新重载。
	 * @deprecated 无调用者，已被带 httpMethod 参数的新方法替代。
	 * @deprecated 请使用 {@link #forwardRequestToLlamaCpp(ChannelHandlerContext, String, Map, String, int, String, boolean, String)}
	 */
	@Deprecated
	private void forwardRequestToLlamaCpp(ChannelHandlerContext ctx, Map<String, String> headers, String modelName, int port, String endpoint, boolean isStream, String requestBody) {
		// 默认使用 POST 方法（大多数 API 是 POST），当没有显式指定 httpMethod 时
		this.forwardRequestToLlamaCpp(ctx, HttpMethod.POST, headers, port, endpoint, isStream, 
			requestBody == null ? new byte[0] : requestBody.getBytes(StandardCharsets.UTF_8), modelName);
	}

	/**
	 * 转发请求到 llama.cpp 进程（使用预复制的 headers 和显式 HTTP 方法，适用于队列中的请求）
	 * P0 Fix: New overload method that accepts explicit httpMethod to avoid :method pseudo-header issue
	 */
	private void forwardRequestToLlamaCpp(ChannelHandlerContext ctx, String httpMethod, Map<String, String> headers, String modelName, int port, String endpoint, boolean isStream, String requestBody) {
		byte[] requestBodyBytes = requestBody == null ? new byte[0] : requestBody.getBytes(StandardCharsets.UTF_8);
		HttpMethod method = HttpMethod.POST;  // 默认 POST
		if (httpMethod != null && !httpMethod.isBlank()) {
			try {
				method = HttpMethod.valueOf(httpMethod.toUpperCase());
			} catch (IllegalArgumentException e) {
				logger.warn("[Queue] Invalid HTTP method: {}, using POST", httpMethod);
			}
		}
		this.forwardRequestToLlamaCpp(ctx, method, headers, port, endpoint, isStream, requestBodyBytes, modelName);
	}

	/**
	 * 核心转发方法（直接使用 headers Map 和 HttpMethod）
	 */
	private void forwardRequestToLlamaCpp(ChannelHandlerContext ctx, HttpMethod method, Map<String, String> headers, int port, String endpoint, boolean isStream, byte[] requestBodyBytes, String modelName) {
		int requestBodyLength = requestBodyBytes == null ? 0 : requestBodyBytes.length;
		logger.info("转发请求到llama.cpp进程: {} {} 端口: {} 请求体长度: {}", method.name(), endpoint, port, requestBodyLength);
		
		worker.execute(() -> {
			String requestId = ModelRequestTracker.getInstance().createRequest(modelName, endpoint);
			HttpURLConnection connection = null;
			try {
				String targetUrl = String.format("http://localhost:%d%s", port, endpoint);
				logger.info("连接到llama.cpp进程: {}", targetUrl);
				connection = this.openTrackedConnection(ctx, targetUrl, method, headers, false);
				
				if (method == HttpMethod.POST && requestBodyBytes != null && requestBodyBytes.length > 0) {
					try (OutputStream os = connection.getOutputStream()) {
						os.write(requestBodyBytes, 0, requestBodyBytes.length);
						logger.info("已发送请求体到llama.cpp进程，大小: {} 字节", requestBodyBytes.length);
					}
				}
				long t = System.currentTimeMillis();
				int responseCode = connection.getResponseCode();
				logger.info("llama.cpp进程响应码: {}，等待时间：{}", responseCode, System.currentTimeMillis() - t);
				ModelRequestTracker.getInstance().updatePhase(requestId, ActiveRequest.Phase.GENERATION);
				this.handleProxyResponse(ctx, connection, responseCode, isStream, modelName, requestId);
			} catch (Exception e) {
				logger.info("转发请求到llama.cpp进程时发生错误", e);
				if (e.getMessage() != null && e.getMessage().contains("Connection reset by peer")) {
					
				}
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
			} catch (Throwable t) {
				logger.error("虚拟线程异常已兜底: {}", t.getMessage(), t);
			} finally {
				ModelRequestTracker.getInstance().removeRequest(requestId);
				this.cleanupTrackedConnection(ctx, connection);
			}
		});
	}
	
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIAudioTranscriptionsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			
			ByteBuf requestContent = request.content();
			if (requestContent == null || !requestContent.isReadable()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "file");
				return;
			}
			
			LlamaServerManager manager = LlamaServerManager.getInstance();
			String modelName = this.resolveAudioTranscriptionModel(request);
			if (modelName == null || modelName.isBlank()) {
				modelName = manager.getFirstModelName();
			}
			if (modelName == null || modelName.isBlank()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "No models are currently loaded", "model");
				return;
			}
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				String resolved = manager.findModelIdByAlias(modelName);
				if (resolved != null) {
					modelName = resolved;
				}
			}
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				// JIT 自动加载（音频转录场景下直接返回 404，不自动加载）
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + modelName, "model");
				return;
			}
			
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			
			byte[] bodyBytes = new byte[requestContent.readableBytes()];
			requestContent.getBytes(requestContent.readerIndex(), bodyBytes);
			
			String endpoint = request.uri();
			if (endpoint != null && endpoint.startsWith("/audio/transcriptions")) {
				endpoint = "/v1" + endpoint;
			}
			if (endpoint == null || endpoint.isBlank()) {
				endpoint = "/v1/audio/transcriptions";
			}
			this.forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, endpoint, false, bodyBytes);
		} catch (Exception e) {
			logger.info("处理OpenAI音频转录请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	private String resolveAudioTranscriptionModel(FullHttpRequest request) {
		String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
		if (contentType == null) {
			return null;
		}
		String lowered = contentType.toLowerCase(Locale.ROOT);
		if (!lowered.startsWith("multipart/form-data")) {
			return null;
		}
		
		HttpPostRequestDecoder decoder = null;
		try {
			decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), request);
			for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
				if (data == null || data.getHttpDataType() != InterfaceHttpData.HttpDataType.Attribute) {
					continue;
				}
				Attribute attribute = (Attribute) data;
				if (!"model".equals(attribute.getName())) {
					continue;
				}
				String model = attribute.getValue();
				if (model != null) {
					model = model.trim();
				}
				if (model != null && !model.isBlank()) {
					return model;
				}
			}
		} catch (Exception e) {
			logger.info("解析audio/transcriptions表单模型参数失败，尝试回退默认模型", e);
		} finally {
			if (decoder != null) {
				try {
					decoder.destroy();
				} catch (Exception ignore) {
				}
			}
		}
		return null;
	}
	
	
	

	public HttpURLConnection openTrackedConnection(ChannelHandlerContext ctx, String targetUrl, HttpMethod method, Map<String, String> headers, boolean chunkedStreaming) throws IOException {
		URL url = URI.create(targetUrl).toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();

		if (connection instanceof javax.net.ssl.HttpsURLConnection) {
			try {
				NodeManager.trustAllCerts((javax.net.ssl.HttpsURLConnection) connection);
			} catch (Exception e) {
				logger.warn("配置HTTPS证书信任失败: {}", e.getMessage());
			}
		}

		synchronized (this.channelConnectionMap) {
			this.channelConnectionMap.put(ctx, connection);
		}

		connection.setRequestMethod(method.name());
		for (Map.Entry<String, String> entry : headers.entrySet()) {
			if (!entry.getKey().equalsIgnoreCase("Connection") &&
				!entry.getKey().equalsIgnoreCase("Content-Length") &&
				!entry.getKey().equalsIgnoreCase("Transfer-Encoding")) {
				connection.setRequestProperty(entry.getKey(), entry.getValue());
			}
		}
		connection.setConnectTimeout(36000 * 1000);
		connection.setReadTimeout(36000 * 1000);
		if (method == HttpMethod.POST) {
			connection.setDoOutput(true);
			if (chunkedStreaming) {
				connection.setChunkedStreamingMode(8192);
			}
		}
		return connection;
	}

	public void handleProxyResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, boolean isStream, String modelName) throws IOException {
		this.handleProxyResponse(ctx, connection, responseCode, isStream, modelName, null, null);
	}

	public void handleProxyResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, boolean isStream, String modelName, String requestId) throws IOException {
		this.handleProxyResponse(ctx, connection, responseCode, isStream, modelName, requestId, null);
	}

	public void handleProxyResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, boolean isStream, String modelName, String requestId, String nodeId) throws IOException {
		if (isStream) {
			this.handleStreamResponse(ctx, connection, responseCode, modelName, requestId, nodeId);
			return;
		}
		this.handleNonStreamResponse(ctx, connection, responseCode, modelName, requestId, nodeId);
	}

	public void cleanupTrackedConnection(ChannelHandlerContext ctx, HttpURLConnection connection) {
		if (connection != null) {
			connection.disconnect();
		}
		synchronized (this.channelConnectionMap) {
			HttpURLConnection mapped = this.channelConnectionMap.remove(ctx);
			if (mapped != null && mapped != connection) {
				try {
					mapped.disconnect();
				} catch (Exception e) {
				}
			}
		}
	}

	private void handleNonStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName, String requestId, String nodeId) throws IOException {
		// 读取响应
		String responseBody;
		if (responseCode >= 200 && responseCode < 300) {
			try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
				StringBuilder response = new StringBuilder();
				String responseLine;
				while ((responseLine = br.readLine()) != null) {
					response.append(responseLine.trim());
				}
				responseBody = response.toString();
			}
		} else {
			// 读取错误响应
			try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
				StringBuilder response = new StringBuilder();
				String responseLine;
				while ((responseLine = br.readLine()) != null) {
					response.append(responseLine.trim());
				}
				responseBody = response.toString();
			}
		}

		if (responseCode >= 200 && responseCode < 300) {
			JsonObject parsed = JsonUtil.tryParseObject(responseBody);
			if (parsed != null) {
				boolean changed = JsonUtil.ensureToolCallIds(parsed, null);
				if (changed) {
					responseBody = JsonUtil.toJson(parsed);
				}
			}
		}
		
		// 创建响应
		FullHttpResponse response = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1,
			HttpResponseStatus.valueOf(responseCode)
		);
		
		// 设置响应头
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseBytes.length);
		response.headers().set(HttpHeaderNames.ETAG, buildEtag(responseBytes));
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
		
		// 设置响应体
		response.content().writeBytes(responseBytes);
		
		// 发送响应
		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
		// 缓存生成信息。
		Timing timing = LlamaRecordService.getInstance().handleStream(modelName, responseBody, requestId);
		if (requestId != null && timing != null) {
			ModelRequestTracker.getInstance().updateTiming(requestId, timing);
		}
	}

	private void handleStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName, String requestId, String nodeId) throws IOException {
		// 创建响应头
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.ETAG, buildEtag((modelName + ":" + responseCode + ":" + System.nanoTime()).getBytes(StandardCharsets.UTF_8)));
		
		// 发送响应头
		ctx.write(response);
		ctx.flush();
		
		logger.info("开始处理流式响应，响应码: {}", responseCode);
		
		// 读取流式响应
		try (BufferedReader br = new BufferedReader(
			new InputStreamReader(
				responseCode >= 200 && responseCode < 300 ?
					connection.getInputStream() : connection.getErrorStream(),
				StandardCharsets.UTF_8
			)
		)) {
			String line;
			int chunkCount = 0;
			Map<Integer, String> toolCallIds = new HashMap<>();
			while ((line = br.readLine()) != null) {
				// 检查客户端连接是否仍然活跃
				if (!ctx.channel().isActive() || !ctx.channel().isWritable()) {
					logger.info("检测到客户端连接已断开，停止流式响应处理");
					if (connection != null) {
						connection.disconnect();
					}
					break;
				}
				// 处理SSE格式的数据行
				if (line.startsWith("data: ")) {
					String data = line.substring(6); // 去掉 "data: " 前缀
					// 检查是否为结束标记
					if (data.equals("[DONE]")) {
						logger.info("收到流式响应结束标记");
						break;
					}
					else 
					// 统计生成信息 — timings 只在最后一个 chunk 出现，天然作为结束标记
					if(data.contains("\"timings\"")) {
						Timing timing = LlamaRecordService.getInstance().handleStream(modelName, data, requestId);
						if (requestId != null && timing != null) {
							ModelRequestTracker.getInstance().updateTiming(requestId, timing);
						}
					}
					
					String outLine = line;
					JsonObject parsed = JsonUtil.tryParseObject(data);
					if (parsed != null) {
						boolean changed = JsonUtil.ensureToolCallIds(parsed, toolCallIds);
						if (changed) {
							outLine = "data: " + JsonUtil.toJson(parsed);
						}
					}
					
					// 创建数据块
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes(outLine.getBytes(StandardCharsets.UTF_8));
					content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
					
					// 创建HTTP内容块
					HttpContent httpContent = new DefaultHttpContent(content);
					
					// 发送数据块，并添加监听器检查写入是否成功
					ChannelFuture future = ctx.writeAndFlush(httpContent);
					
					// 检查写入是否失败，如果失败可能是客户端断开连接
					future.addListener((ChannelFutureListener) channelFuture -> {
						if (!channelFuture.isSuccess()) {
							logger.info("写入流式数据失败，可能是客户端断开连接: {}", channelFuture.cause().getMessage());
							ctx.close();
						}
					});
					
					chunkCount++;
					
					// 每发送10个数据块记录一次日志
					if (chunkCount % 10 == 0) {
						//logger.info("已发送 {} 个流式数据块", chunkCount);
					}
				} else if (line.startsWith("event: ")) {
					// 处理事件行
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
					content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
					
					HttpContent httpContent = new DefaultHttpContent(content);
					ctx.writeAndFlush(httpContent);
				} else if (line.isEmpty()) {
					// 发送空行作为分隔符
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
					
					HttpContent httpContent = new DefaultHttpContent(content);
					ctx.writeAndFlush(httpContent);
				}
			}
			
			logger.info("流式响应处理完成，共发送 {} 个数据块", chunkCount);
		} catch (Exception e) {
			String nodeCtx = this.resolveNodeName(nodeId);
			logger.info("处理流式响应时发生错误 [{}]", nodeCtx, e);
			// 检查是否是客户端断开连接导致的异常
			if (e.getMessage() != null &&
				(e.getMessage().contains("Connection reset by peer") ||
				 e.getMessage().contains("Broken pipe") ||
				 e.getMessage().contains("Connection closed"))) {
				logger.info("检测到客户端断开连接，尝试断开与llama.cpp的连接");
				if (connection != null) {
					connection.disconnect();
				}
			}
		}
		
		// 发送结束标记
		LastHttpContent lastContent = LastHttpContent.EMPTY_LAST_CONTENT;
		ctx.writeAndFlush(lastContent).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

//	private static String safeString(JsonObject obj, String key) {
//		try {
//			if (obj == null || key == null) {
//				return null;
//			}
//			JsonElement el = obj.get(key);
//			if (el == null || el.isJsonNull()) {
//				return null;
//			}
//			return el.getAsString();
//		} catch (Exception e) {
//			return null;
//		}
//	}

	private static String buildEtag(byte[] content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(content == null ? new byte[0] : content);
			StringBuilder sb = new StringBuilder(hash.length * 2 + 2);
			sb.append('"');
			for (byte b : hash) {
				sb.append(String.format("%02x", b));
			}
			sb.append('"');
			return sb.toString();
		} catch (Exception e) {
			return "\"" + UUID.randomUUID().toString().replace("-", "") + "\"";
		}
	}

	/**
	 * 	发送OpenAI格式的JSON响应
	 * @param ctx
	 * @param data
	 */
	private void sendOpenAIJsonResponse(ChannelHandlerContext ctx, Object data) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.ETAG, buildEtag(content));
		response.headers().set("X-Powered-By", "Express");
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		//response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.CONNECTION, "alive");
		response.headers().set(HttpHeaderNames.DATE, DATE_FORMATTER.format(java.time.Instant.now()));
		
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	/**
	 * 	发送OpenAI格式的错误响应并清理资源
	 * @param ctx
	 * @param httpStatus
	 * @param openAiErrorCode
	 * @param message
	 * @param param
	 */
	public void sendOpenAIErrorResponseWithCleanup(ChannelHandlerContext ctx, int httpStatus, String openAiErrorCode, String message, String param) {
		String type = "invalid_request_error";
		// 通过code判断错误类型
		if(httpStatus == 401) {
			type = "authentication_error";
		}
		if(httpStatus == 403) {
			type = "permission_error";
		}
		if(httpStatus == 404 || httpStatus == 400) {
			type = "invalid_request_error";
		}
		if(httpStatus == 429) {
			type = "rate_limit_error";
		}
		if(httpStatus == 500 || httpStatus == 502 || httpStatus == 503 || httpStatus == 504) {
			type = "server_error";
		}
		
		Map<String, Object> error = new HashMap<>();
		error.put("message", message);
		error.put("type", type);
		error.put("code", openAiErrorCode);
		error.put("param", param);
		
		Map<String, Object> response = new HashMap<>();
		response.put("error", error);
		sendOpenAIJsonResponseWithCleanup(ctx, response, HttpResponseStatus.valueOf(httpStatus));
	}
	
	
	/**
	 * 	发送OpenAI格式的JSON响应并清理资源
	 * @param ctx
	 * @param data
	 * @param httpStatus
	 */
	private void sendOpenAIJsonResponseWithCleanup(ChannelHandlerContext ctx, Object data, HttpResponseStatus httpStatus) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, httpStatus);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.ETAG, buildEtag(content));
		response.headers().set("X-Powered-By", "Express");
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.CONNECTION, "alive");
		response.headers().set(HttpHeaderNames.DATE, DATE_FORMATTER.format(java.time.Instant.now()));
		
		
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	/**
	 * 解析节点名称
	 */
	private String resolveNodeName(String nodeId) {
		if (nodeId == null || nodeId.isBlank()) {
			return "本机";
		}
		try {
			LlamaHubNode node = NodeManager.getInstance().getNode(nodeId);
			if (node != null && node.getName() != null && !node.getName().isBlank()) {
				return node.getName();
			}
		} catch (Exception e) {
		}
		return nodeId;
	}

	/**
	 * 	当连接断开时调用，用于清理{@link #channelConnectionMap}
	 * 
	 * @param ctx
	 * @throws Exception
	 */
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		// 关闭正在进行的链接
		synchronized (this.channelConnectionMap) {
			HttpURLConnection conn = this.channelConnectionMap.remove(ctx);
			if (conn != null) {
				try {
					conn.disconnect();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	// ==================== JIT 自动加载方法 ====================
	
	/**
	 * JIT 自动加载模型
	 * @return true 表示成功加载并转发请求，false 表示失败
	 */
	@SuppressWarnings("unchecked")
	private boolean jitAutoLoadModel(ChannelHandlerContext ctx, FullHttpRequest request, 
			String modelName, JsonObject requestJson, LlamaServerManager manager,
			String body, boolean isStream, String apiPath) {
		try {
			// 0. 从请求体解析请求级 TTL（可选，覆盖全局 defaultTtl）
			int requestTtl = 0;
			if (requestJson != null && requestJson.has("ttl")) {
				try {
					requestTtl = requestJson.get("ttl").getAsInt();
				} catch (Exception ignored) {
					// ttl 格式不合法，忽略，使用全局默认值
				}
			}
			
			// 1. 查找模型配置
			GGUFModel model = manager.findModelById(modelName);
			if (model == null) {
				// 尝试通过别名查找
				String resolvedModelId = manager.findModelIdByAlias(modelName);
				if (resolvedModelId != null) {
					model = manager.findModelById(resolvedModelId);
					modelName = resolvedModelId;
				}
			}
			if (model == null) {
				logger.info("JIT: 未找到模型 {}", modelName);
				return false;
			}
			
			// 1.5 检查模型是否已经在加载中，如果是则加入队列等待
			if (manager.isLoading(modelName)) {
				logger.info("JIT: 模型 {} 正在加载中，加入请求队列等待", modelName);
				if (queueManager.isQueueEnabled()) {
					boolean enqueued = queueManager.enqueueRequest(ctx, request, modelName, apiPath, isStream, body);
					if (enqueued) {
						// 已在队列中等待，模型就绪后会自动处理
						return true;
					}
					// 队列满，返回 503
					this.sendOpenAIErrorResponseWithCleanup(ctx, 503, null, 
						"Queue full, please try again later", null);
					return true;
				}
				// 队列未启用，失败
				return false;
			}
			
			// 2. 获取模型启动配置
			ConfigManager configManager = ConfigManager.getInstance();
			Map<String, Object> configBundle = configManager.getModelLaunchConfigBundle(modelName);
			if (configBundle == null || configBundle.isEmpty()) {
				logger.info("JIT: 模型 {} 没有启动配置", modelName);
				return false;
			}
			
			// 获取默认配置
			String selectedConfig = (String) configBundle.getOrDefault("selectedConfig", "default");
			Object configsObj = configBundle.get("configs");
			if (configsObj == null || !(configsObj instanceof Map)) {
				logger.info("JIT: 模型 {} 没有可用配置", modelName);
				return false;
			}
			Map<String, Object> configs = (Map<String, Object>) configsObj;
			Map<String, Object> selectedConfigData = (Map<String, Object>) configs.get(selectedConfig);
			if (selectedConfigData == null) {
				logger.info("JIT: 模型 {} 的配置 {} 不存在", modelName, selectedConfig);
				return false;
			}
			
			// 提取启动参数
			String llamaBinPath = (String) selectedConfigData.get("llamaBinPathSelect");
			if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
				llamaBinPath = (String) selectedConfigData.get("llamaBinPath");
			}
			List<String> device = (List<String>) selectedConfigData.get("device");
			Integer mg = (Integer) selectedConfigData.get("mg");
			Boolean enableVision = (Boolean) selectedConfigData.get("enableVision");
			if (enableVision == null) {
				enableVision = true;
			}
			String cmd = (String) selectedConfigData.get("cmd");
			String extraParams = (String) selectedConfigData.get("extraParams");
			String chatTemplateFilePath = null;
			if (selectedConfigData.containsKey("chatTemplateFile")) {
				chatTemplateFilePath = (String) selectedConfigData.get("chatTemplateFile");
			}
			
			if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
				logger.info("JIT: 模型 {} 未配置 llamaBinPath", modelName);
				return false;
			}
			
			// 3. 检查并发数量限制
			int maxModels = LlamaServer.getJitMaxLoadedModels();
			int currentCount = manager.getLoadedModelCount();
			
			if (currentCount >= maxModels) {
				// 已达上限，需要卸载一个模型
				String strategy = LlamaServer.getJitLoadStrategy();
				String evicted = manager.evictModel(strategy);
				if (evicted == null) {
					logger.info("JIT: 未能卸载模型，尝试加入队列等待");
					// 如果队列启用，加入队列等待
					if (queueManager.isQueueEnabled()) {
						// 移除旧队列（如果有）
						queueManager.removeQueue(modelName);
						// 尝试加载模型并加入队列
						boolean enqueued = queueManager.enqueueRequest(ctx, request, modelName, apiPath, isStream, body);
						if (enqueued) {
							// 启动后台加载
							manager.loadModelAsyncFromCmd(modelName, llamaBinPath, device, mg, 
								enableVision, cmd, extraParams, chatTemplateFilePath);
							return true;
						}
						// 队列满，返回 503
						this.sendOpenAIErrorResponseWithCleanup(ctx, 503, null, 
							"Queue full, please try again later", null);
						return true;
					}
					if (LlamaServer.isJitAllowQueue()) {
						// 暂时不支持排队，返回繁忙
						this.sendOpenAIErrorResponseWithCleanup(ctx, 503, null, 
							"Too many models loaded, please try again later", null);
						return true;
					}
					return false;
				}
				logger.info("JIT: 已卸载模型 {}，准备加载 {}", evicted, modelName);
			}
			
			// 4. 自动加载模型
			logger.info("JIT: 开始自动加载模型 {}", modelName);
			boolean started = manager.loadModelAsyncFromCmd(modelName, llamaBinPath, device, mg, 
				enableVision, cmd, extraParams, chatTemplateFilePath);
			if (!started) {
				logger.info("JIT: 模型 {} 加载提交失败", modelName);
				return false;
			}
			
			// 5. 等待加载完成（最多等待 120 秒）
			boolean loadSuccess = manager.waitForModelLoad(modelName, 120000);
			if (!loadSuccess) {
				logger.info("JIT: 模型 {} 加载超时", modelName);
				// 通知队列加载失败
				if (queueManager.isQueueEnabled()) {
					queueManager.onModelLoadFailed(modelName);
				}
				this.sendOpenAIErrorResponseWithCleanup(ctx, 504, null, 
					"Model loading timeout: " + modelName, null);
				return true;
			}
			
			// 6. 获取端口并转发请求
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				logger.info("JIT: 模型 {} 加载成功但无法获取端口", modelName);
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, 
					"Model loaded but port not found: " + modelName, null);
				return true;
			}
			
			// 更新活动时间（带请求级 TTL 覆盖）
			manager.updateModelActiveTime(modelName, requestTtl);
			
			// 通知队列模型已加载，并处理排队的请求
			if (queueManager.isQueueEnabled()) {
				queueManager.onModelLoaded(modelName);
				// 获取并处理所有排队的请求
				java.util.List<RequestQueueManager.QueuedRequest> queuedRequests = queueManager.drainQueue(modelName);
				for (RequestQueueManager.QueuedRequest queuedReq : queuedRequests) {
					try {
						// P0-2: 检查连接是否仍然活跃，避免向已关闭的 channel 写入数据
						if (!queuedReq.ctx.channel().isActive()) {
							logger.warn("[Queue] 客户端连接已关闭，跳过转发请求: model={}", modelName);
							try {
								this.sendOpenAIErrorResponseWithCleanup(queuedReq.ctx, 502, null,
									"Bad Gateway: Client connection closed", null);
							} catch (Exception ignore) {
							}
							continue;
						}
						// P0-1: 转发排队的请求到模型 (使用 stored httpMethod and headers)
						this.forwardRequestToLlamaCpp(queuedReq.ctx, queuedReq.httpMethod, queuedReq.headers, modelName,
								modelPort, queuedReq.apiPath, queuedReq.isStream, queuedReq.requestBody);
					} catch (Exception e) {
						logger.error("[Queue] 转发排队请求失败: model={}", modelName, e);
						try {
							this.sendOpenAIErrorResponseWithCleanup(queuedReq.ctx, 500, null,
								"Failed to forward queued request: " + e.getMessage(), null);
						} catch (Exception ignore) {
						}
					}
				}
			}
			
			// 转发请求
			this.forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, apiPath, isStream, body);
			return true;
			
		} catch (Exception e) {
			logger.info("JIT 自动加载模型时发生错误: {}", e.getMessage(), e);
			return false;
		}
	}
}

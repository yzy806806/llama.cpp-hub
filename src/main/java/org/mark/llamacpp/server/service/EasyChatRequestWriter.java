package org.mark.llamacpp.server.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class EasyChatRequestWriter {

	private static final byte[] REQUEST_PREFIX = "{\"model\":\"".getBytes(StandardCharsets.UTF_8);
	private static final byte[] ARRAY_END = "]".getBytes(StandardCharsets.UTF_8);
	private static final byte[] OBJECT_END = "}".getBytes(StandardCharsets.UTF_8);
	private static final byte[] COMMA = ",".getBytes(StandardCharsets.UTF_8);
	private static final byte[] QUOTE_COLON = "\":".getBytes(StandardCharsets.UTF_8);

	private final EasyChatStorage storage;

	EasyChatRequestWriter(EasyChatStorage storage) {
		this.storage = storage;
	}

	void writeRequestBody(OutputStream output, RequestSpec spec) throws IOException {
		this.writeAscii(output, REQUEST_PREFIX);
		this.writeString(output, spec.modelId);
		this.writeAscii(output, "\",\"stream\":".getBytes(StandardCharsets.UTF_8));
		this.writeString(output, Boolean.toString(spec.stream));
		if (spec.stream) {
			this.writeAscii(output, ",\"timings_per_token\":true,\"return_progress\":true,\"verbose\":true".getBytes(StandardCharsets.UTF_8));
		}
		this.writeAscii(output, ",\"messages\":[".getBytes(StandardCharsets.UTF_8));

		boolean wroteAnyMessage = false;
		if (spec.systemPrompt != null && !spec.systemPrompt.isBlank()) {
			JsonObject systemMessage = new JsonObject();
			systemMessage.addProperty("role", "system");
			systemMessage.addProperty("content", spec.systemPrompt);
			this.writeString(output, JsonUtil.toJson(systemMessage));
			wroteAnyMessage = true;
		}

		boolean isContinue = spec.continueSeq != null;
		// 会话摘要（上下文压缩）：有 summary.json 时在 system 后注入摘要消息，
		// 历史回放跳过 seq < keepFromSeq 的旧消息（它们已被摘要替代）。
		Object[] summaryInfo = spec.conversationDir == null ? null : this.storage.readSummary(spec.conversationDir);
		String conversationSummary = summaryInfo == null ? null : (String) summaryInfo[0];
		long summaryKeepFromSeq = summaryInfo == null ? 0 : (Long) summaryInfo[1];
		// 预扫描：历史范围内最后一条 user fragment 的 seq + 最后一条非空 fragment 的 seq。
		// 世界书只在「最后一条非空消息恰好是 user 消息」（正常发送场景）时注入到它之前，
		// 保持 system + 旧历史段字节不变（prefix cache 命中），动态内容收口到新消息段。
		// regenerate/continue 场景最后一条非空是 assistant，不注入（避免污染中段）。
		long[] scanResult = findLastSeqAndUserSeq(spec);
		long lastUserSeq = scanResult[0];
		long lastNonEmptySeq = scanResult[1];
		// 摘要消息注入（在 system 之后、历史段之前）
		if (conversationSummary != null && !conversationSummary.isBlank()) {
			JsonObject summaryMsg = new JsonObject();
			summaryMsg.addProperty("role", "user");
			summaryMsg.addProperty("content", "[Conversation Summary - 之前的故事]\n" + conversationSummary);
			if (wroteAnyMessage) {
				this.writeAscii(output, COMMA);
			}
			this.writeString(output, JsonUtil.toJson(summaryMsg));
			wroteAnyMessage = true;
		}
		// 开场白注入：会话历史为空（新聊天）时，first_mes 作为首条 assistant 消息
		// （酒馆语义：新聊天 = 角色先开口，用户在下面回复）
		if (lastNonEmptySeq < 0 && spec.firstMessage != null && !spec.firstMessage.isBlank()) {
			JsonObject firstMsg = new JsonObject();
			firstMsg.addProperty("role", "assistant");
			firstMsg.addProperty("content", spec.firstMessage);
			if (wroteAnyMessage) {
				this.writeAscii(output, COMMA);
			}
			this.writeString(output, JsonUtil.toJson(firstMsg));
			wroteAnyMessage = true;
		}
		if (!spec.skipHistory && spec.conversationDir != null) {
			long historyEndExclusive = storage.readNextSeq(spec.conversationDir);
			if (spec.regenerateSeq != null) {
				historyEndExclusive = Math.min(historyEndExclusive, spec.regenerateSeq.longValue());
			}
			if (isContinue) {
				// Include the target assistant fragment as the last message.
				historyEndExclusive = Math.min(historyEndExclusive, spec.continueSeq.longValue() + 1);
			}
			long startSeq = Math.max(0, summaryKeepFromSeq);
			for (long seq = startSeq; seq < historyEndExclusive; seq++) {
				EasyChatStorage.FragmentHeader header = this.storage.readFragmentHeader(spec.conversationDir, seq);
				if (header == null) {
					continue;
				}
				if (this.storage.isDeleted(header)) {
					continue;
				}
				Integer preferredVariant = spec.variants == null ? null : spec.variants.get(seq);
				int resolvedVariant = this.storage.resolveVariantIndex(header, preferredVariant);
				if (resolvedVariant < 0) {
					continue;
				}
				// Stream the fragment payload verbatim from disk. llama.cpp ignores
				// unsupported top-level fields (timings / finish_reason), so we no
				// longer parse the message into a JsonObject tree just to strip
				// those keys — that path OOMs on multi-MB attachments.
				EasyChatStorage.FragmentSlice slice = this.storage.getVariantSlice(spec.conversationDir, seq, resolvedVariant);
				if (slice == null || slice.length <= 0) {
					continue;
				}
				if (wroteAnyMessage) {
					this.writeAscii(output, COMMA);
				}
				// 世界书注入：仅当最后一条非空消息是 user（正常发送场景）才把它拿出来，
				// 在 content 前拼 worldInfo 前缀后整体写出（保留 images/audios/videos 等附件字段）。
				// regenerate/continue 最后一条非空是 assistant，不注入。
				boolean injectWorldInfo = seq == lastUserSeq
						&& lastUserSeq == lastNonEmptySeq
						&& seq == historyEndExclusive - 2
						&& spec.worldInfoPrefix != null && !spec.worldInfoPrefix.isBlank();
				if (injectWorldInfo) {
					this.writeString(output, JsonUtil.toJson(this.injectWorldInfoIntoFragment(spec.conversationDir, seq, resolvedVariant, spec.worldInfoPrefix)));
				} else {
					this.storage.streamSlice(slice, output);
				}
				wroteAnyMessage = true;
			}
		}
		if (!isContinue) {
			boolean hasBytes = spec.transientUserMessageBytes != null && spec.transientUserMessageBytes.length > 0;
			boolean hasFile = false;
			if (spec.transientUserMessageFile != null) {
				try {
					hasFile = Files.size(spec.transientUserMessageFile) > 0;
				} catch (IOException | SecurityException ignore) {
					hasFile = false;
				}
			}
			if (hasBytes || hasFile) {
				if (wroteAnyMessage) {
					this.writeAscii(output, COMMA);
				}
				if (hasBytes) {
					output.write(spec.transientUserMessageBytes);
				} else {
					// Stream the ephemeral user body straight from disk — never
					// materialize multi-MB attachments in JVM heap.
					Files.copy(spec.transientUserMessageFile, output);
				}
				wroteAnyMessage = true;
			}
		}

		this.writeAscii(output, ARRAY_END);
		this.writeExtraFields(output, spec);
		this.writeAscii(output, OBJECT_END);
		output.flush();
	}

	private void writeExtraFields(OutputStream output, RequestSpec spec) throws IOException {
		if (spec.toolsBytes != null && spec.toolsBytes.length > 0) {
			JsonObject toolsObj = JsonUtil.tryParseObject(new String(spec.toolsBytes, StandardCharsets.UTF_8));
			if (toolsObj != null) {
				this.writeObjectFields(output, toolsObj);
			}
		}

		JsonObject requestOptions = new JsonObject();
		if (!spec.skipSamplingInjection && spec.samplingParams != null) {
			for (String key : spec.samplingParams.keySet()) {
				// For continue requests these fields are managed explicitly.
				if (spec.continueSeq != null && ("continue_final_message".equals(key) || "add_generation_prompt".equals(key))) {
					continue;
				}
				requestOptions.add(key, spec.samplingParams.get(key));
			}
		}
		if (spec.continueSeq != null) {
			requestOptions.addProperty("continue_final_message", true);
			requestOptions.addProperty("add_generation_prompt", false);
		}
		Boolean clientEnableThinking = this.readClientEnableThinking(requestOptions);
		ParamTool.handleOpenAIChatThinking(requestOptions);
		String resolvedModelId = SamplingInjectionBuilder.resolveModelName(spec.modelId);
		requestOptions.addProperty("model", resolvedModelId == null || resolvedModelId.isBlank() ? spec.modelId : resolvedModelId);
		this.applyMergedChatTemplateKwargs(requestOptions, resolvedModelId, clientEnableThinking);
		if (!spec.skipSamplingInjection) {
			ModelSamplingService.getInstance().handleOpenAI(requestOptions);
		}
		this.writeObjectFields(output, requestOptions, "model", "messages", "stream");
	}

	private void applyMergedChatTemplateKwargs(JsonObject requestOptions, String modelId, Boolean clientEnableThinking) {
		if (requestOptions == null || modelId == null || modelId.isBlank()) {
			return;
		}
		JsonObject finalKwargs = this.readJsonObjectCopy(requestOptions.get("chat_template_kwargs"));
		if (finalKwargs == null) {
			finalKwargs = new JsonObject();
		}
		if (clientEnableThinking != null && !finalKwargs.has("enable_thinking")) {
			finalKwargs.addProperty("enable_thinking", clientEnableThinking);
		}
		JsonObject serverKwargs = ChatTemplateKwargsService.getInstance().getOpenAIChatTemplateKwargs(modelId);
		if (serverKwargs != null) {
			for (Map.Entry<String, JsonElement> entry : serverKwargs.entrySet()) {
				String key = entry.getKey();
				JsonElement value = entry.getValue();
				if (key == null || value == null || value.isJsonNull()) {
					continue;
				}
				finalKwargs.add(key, value.deepCopy());
			}
		}
		if (finalKwargs.entrySet().isEmpty()) {
			return;
		}
		requestOptions.add("chat_template_kwargs", finalKwargs);
	}

	private JsonObject readJsonObjectCopy(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (element.isJsonObject()) {
			return element.getAsJsonObject().deepCopy();
		}
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			JsonObject parsed = JsonUtil.tryParseObject(element.getAsString());
			return parsed == null ? null : parsed.deepCopy();
		}
		return null;
	}

	private Boolean readClientEnableThinking(JsonObject requestOptions) {
		if (requestOptions == null) {
			return null;
		}
		Boolean directValue = readBooleanLenient(requestOptions.get("enable_thinking"));
		if (directValue != null) {
			return directValue;
		}
		JsonElement thinking = requestOptions.get("thinking");
		if (thinking != null && thinking.isJsonObject()) {
			JsonElement type = thinking.getAsJsonObject().get("type");
			if (type != null && type.isJsonPrimitive()) {
				try {
					String value = type.getAsString();
					if (value != null && "disabled".equalsIgnoreCase(value.trim())) {
						return Boolean.FALSE;
					}
				} catch (Exception ignore) {
				}
			}
		}
		JsonElement thinkingBudget = requestOptions.get("thinking_budget_tokens");
		if (thinkingBudget != null && !thinkingBudget.isJsonNull() && thinkingBudget.isJsonPrimitive()) {
			try {
				return thinkingBudget.getAsInt() > 0;
			} catch (Exception ignore) {
			}
		}
		return null;
	}

	private Boolean readBooleanLenient(JsonElement element) {
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return null;
		}
		try {
			if (element.getAsJsonPrimitive().isBoolean()) {
				return element.getAsBoolean();
			}
			if (element.getAsJsonPrimitive().isString()) {
				return Boolean.parseBoolean(element.getAsString().trim());
			}
		} catch (Exception ignore) {
			return null;
		}
		return null;
	}

	private void writeObjectFields(OutputStream output, JsonObject obj, String... ignoredKeys) throws IOException {
		if (obj == null) {
			return;
		}
		for (String key : obj.keySet()) {
			if (shouldIgnore(key, ignoredKeys)) {
				continue;
			}
			this.writeAscii(output, COMMA);
			this.writeAscii(output, "\"".getBytes(StandardCharsets.UTF_8));
			this.writeString(output, key);
			this.writeAscii(output, QUOTE_COLON);
			this.writeString(output, JsonUtil.toJson(obj.get(key)));
		}
	}

	private boolean shouldIgnore(String key, String... ignoredKeys) {
		if (ignoredKeys == null) {
			return false;
		}
		for (String ignored : ignoredKeys) {
			if (ignored != null && ignored.equals(key)) {
				return true;
			}
		}
		return false;
	}

	private void writeString(OutputStream output, String text) throws IOException {
		output.write(text.getBytes(StandardCharsets.UTF_8));
	}

	/** 判断 fragment 是否为 user 消息（读 payload 顶层 role，容忍缺失/损坏） */
	private boolean isUserFragment(Path dir, long seq, int variantIndex) {
		try {
			byte[] payload = this.storage.readPayload(dir, seq, variantIndex);
			if (payload == null || payload.length == 0) {
				return false;
			}
			JsonObject parsed = JsonUtil.tryParseObject(payload);
			if (parsed == null) {
				return false;
			}
			String role = JsonUtil.getJsonString(parsed, "role", "");
			return "user".equals(role);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * 预扫描历史，返回 [最后一条 user fragment 的 seq, 最后一条非空 fragment 的 seq]。
	 * 不存在返回 -1。-1 表示无匹配。
	 * 只读头部 + 极小 payload 判断 role，不流式输出。
	 */
	private long[] findLastSeqAndUserSeq(RequestSpec spec) {
		if (spec.skipHistory || spec.conversationDir == null) {
			return new long[]{-1, -1};
		}
		try {
			long historyEndExclusive = this.storage.readNextSeq(spec.conversationDir);
			if (spec.regenerateSeq != null) {
				historyEndExclusive = Math.min(historyEndExclusive, spec.regenerateSeq.longValue());
			}
			if (spec.continueSeq != null) {
				historyEndExclusive = Math.min(historyEndExclusive, spec.continueSeq.longValue() + 1);
			}
			long lastUser = -1;
			long lastNonEmpty = -1;
			for (long seq = 0; seq < historyEndExclusive; seq++) {
				EasyChatStorage.FragmentHeader header = this.storage.readFragmentHeader(spec.conversationDir, seq);
				if (header == null || this.storage.isDeleted(header)) {
					continue;
				}
				Integer preferredVariant = spec.variants == null ? null : spec.variants.get(seq);
				int resolvedVariant = this.storage.resolveVariantIndex(header, preferredVariant);
				if (resolvedVariant < 0) {
					continue;
				}
				EasyChatStorage.FragmentSlice slice = this.storage.getVariantSlice(spec.conversationDir, seq, resolvedVariant);
				if (slice != null && slice.length > 0) {
					lastNonEmpty = seq;
					if (this.isUserFragment(spec.conversationDir, seq, resolvedVariant)) {
						lastUser = seq;
					}
				}
			}
			return new long[]{lastUser, lastNonEmpty};
		} catch (Exception e) {
			return new long[]{-1, -1};
		}
	}

	/**
	 * 世界书注入：读取 fragment 原始 payload，把 worldInfo 前缀拼接到 content 之前，
	 * 其余字段（images/audios/videos/tool_calls 等附件）原样保留。
	 * 单条 fragment 读入内存无 OOM 风险（原注释警告的是回放全历史逐条 parse）。
	 */
	private JsonObject injectWorldInfoIntoFragment(Path dir, long seq, int variantIndex, String worldInfoPrefix) {
		JsonObject parsed = null;
		try {
			byte[] payload = this.storage.readPayload(dir, seq, variantIndex);
			if (payload != null && payload.length > 0) {
				parsed = JsonUtil.tryParseObject(payload);
			}
		} catch (Exception ignore) {
			parsed = null;
		}
		if (parsed == null) {
			parsed = new JsonObject();
			parsed.addProperty("role", "user");
		}
		String original = JsonUtil.getJsonString(parsed, "content", "");
		String merged = (worldInfoPrefix == null || worldInfoPrefix.isBlank())
				? original
				: worldInfoPrefix + "\n" + (original == null ? "" : original);
		parsed.addProperty("content", merged);
		return parsed;
	}

	/** 读取 fragment 的 content 字段（用于世界书注入时拼接内容） */
	private String readFragmentContent(Path dir, long seq, int variantIndex) {
		try {
			byte[] payload = this.storage.readPayload(dir, seq, variantIndex);
			if (payload == null || payload.length == 0) {
				return "";
			}
			JsonObject parsed = JsonUtil.tryParseObject(payload);
			if (parsed == null) {
				return "";
			}
			String content = JsonUtil.getJsonString(parsed, "content", "");
			return content == null ? "" : content;
		} catch (IOException | RuntimeException e) {
			return "";
		}
	}

	private void writeAscii(OutputStream output, byte[] bytes) throws IOException {
		output.write(bytes);
	}

	static final class RequestSpec {
			final String modelId;
			final String systemPrompt;
			final Path conversationDir;
			final byte[] toolsBytes;
			final JsonObject samplingParams;
			final boolean skipSamplingInjection;
			final Map<Long, Integer> variants;
			final Long regenerateSeq;
			final Long continueSeq;
			final byte[] transientUserMessageBytes;
			final Path transientUserMessageFile;
			final boolean skipHistory;
			final boolean stream;
			/** 世界书激活条目文本，注入到最新一条 user 消息之前（新消息段，缓存友好） */
			final String worldInfoPrefix;
			/** 开场白（first_mes）：仅在会话历史为空（新聊天）时作为首条 assistant 消息注入 */
			final String firstMessage;

			RequestSpec(String modelId, String systemPrompt, Path conversationDir, byte[] toolsBytes,
					JsonObject samplingParams, boolean skipSamplingInjection, Map<Long, Integer> variants, Long regenerateSeq,
					Long continueSeq, byte[] transientUserMessageBytes, Path transientUserMessageFile,
					boolean skipHistory, boolean stream) {
				this(modelId, systemPrompt, conversationDir, toolsBytes, samplingParams, skipSamplingInjection, variants,
						regenerateSeq, continueSeq, transientUserMessageBytes, transientUserMessageFile, skipHistory, stream, null, null);
			}

			RequestSpec(String modelId, String systemPrompt, Path conversationDir, byte[] toolsBytes,
					JsonObject samplingParams, boolean skipSamplingInjection, Map<Long, Integer> variants, Long regenerateSeq,
					Long continueSeq, byte[] transientUserMessageBytes, Path transientUserMessageFile,
					boolean skipHistory, boolean stream, String worldInfoPrefix) {
				this(modelId, systemPrompt, conversationDir, toolsBytes, samplingParams, skipSamplingInjection, variants,
						regenerateSeq, continueSeq, transientUserMessageBytes, transientUserMessageFile, skipHistory, stream, worldInfoPrefix, null);
			}

			RequestSpec(String modelId, String systemPrompt, Path conversationDir, byte[] toolsBytes,
					JsonObject samplingParams, boolean skipSamplingInjection, Map<Long, Integer> variants, Long regenerateSeq,
					Long continueSeq, byte[] transientUserMessageBytes, Path transientUserMessageFile,
					boolean skipHistory, boolean stream, String worldInfoPrefix, String firstMessage) {
				this.modelId = modelId;
				this.systemPrompt = systemPrompt;
				this.conversationDir = conversationDir;
				this.toolsBytes = toolsBytes;
				this.samplingParams = samplingParams;
				this.skipSamplingInjection = skipSamplingInjection;
				this.variants = variants;
				this.regenerateSeq = regenerateSeq;
				this.continueSeq = continueSeq;
				this.transientUserMessageBytes = transientUserMessageBytes;
				this.transientUserMessageFile = transientUserMessageFile;
				this.skipHistory = skipHistory;
				this.stream = stream;
				this.worldInfoPrefix = worldInfoPrefix;
				this.firstMessage = firstMessage;
			}
		}
}
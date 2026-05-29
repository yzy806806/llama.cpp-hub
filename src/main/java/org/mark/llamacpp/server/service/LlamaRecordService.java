package org.mark.llamacpp.server.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mark.llamacpp.record.BinaryRequestLog;
import org.mark.llamacpp.record.RequestLogRecord;
import org.mark.llamacpp.server.struct.ActiveRequest;
import org.mark.llamacpp.server.struct.Timing;
import org.mark.llamacpp.server.struct.TokenSummaryEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 处理 llama.cpp 响应中的 timings 性能参数，并持久化累计记录。
 */
public class LlamaRecordService {
	
 private static final LlamaRecordService INSTANCE = new LlamaRecordService();
	private final Gson gson = new Gson();
	private static final String RECORD_DIR = "cache/record/";
	private final Map<String, BinaryRequestLog> logMap = new ConcurrentHashMap<>();
	private final AtomicLong totalRecordCount = new AtomicLong(0);
	private final Map<String, TokenSummaryEntry> tokenSummaryCache = new ConcurrentHashMap<>();

	public static LlamaRecordService getInstance() {
		return INSTANCE;
	}

	public LlamaRecordService() {
		try {
			Files.createDirectories(Paths.get(RECORD_DIR));
			this.migrateOldLogs();
			this.loadTotalRecordCount();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

    private void loadTotalRecordCount() {
		Path dir = Paths.get(RECORD_DIR);
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> paths = Files.list(dir)) {
			List<Path> binFiles = paths
				.filter(p -> p.toString().endsWith(".requests.bin"))
				.collect(java.util.stream.Collectors.toList());
			for (Path binPath : binFiles) {
				try (BinaryRequestLog log = new BinaryRequestLog(binPath)) {
					String modelId = binPath.getFileName().toString().replace(".requests.bin", "");
					this.totalRecordCount.addAndGet(log.getRecordCount());
					TokenSummaryEntry entry = new TokenSummaryEntry();
					entry.setModelId(modelId);
					entry.setTotalCacheTokens(log.getTotalCacheTokens());
					entry.setTotalPromptTokens(log.getTotalPromptTokens());
					entry.setTotalPredictedTokens(log.getTotalPredictedTokens());
					entry.setTotalTokens(log.getTotalPromptTokens() + log.getTotalPredictedTokens());
					entry.setTotalPromptMs(log.getTotalPromptMs());
					entry.setTotalPredictedMs(log.getTotalPredictedMs());
					entry.setTotalDraftTokens(log.getTotalDraftTokens());
					entry.setTotalDraftAccepted(log.getTotalDraftAccepted());
					this.tokenSummaryCache.put(modelId, entry);
				} catch (Exception ignore) {
				}
			}
		} catch (IOException ignore) {
		}
    }

    public long getTotalRecordCount() {
		return this.totalRecordCount.get();
	}

    public List<TokenSummaryEntry> getTokenSummary() {
		return new ArrayList<>(this.tokenSummaryCache.values());
	}

	private void updateTokenSummary(String modelId, RequestLogRecord record) {
		TokenSummaryEntry entry = this.tokenSummaryCache.computeIfAbsent(modelId, id -> {
			TokenSummaryEntry e = new TokenSummaryEntry();
			e.setModelId(id);
			return e;
		});
		entry.setTotalCacheTokens(entry.getTotalCacheTokens() + record.cacheN);
		entry.setTotalPromptTokens(entry.getTotalPromptTokens() + record.promptN);
		entry.setTotalPredictedTokens(entry.getTotalPredictedTokens() + record.predictedN);
		entry.setTotalTokens(entry.getTotalPromptTokens() + entry.getTotalPredictedTokens());
		entry.setTotalPromptMs(entry.getTotalPromptMs() + record.promptMs);
		entry.setTotalPredictedMs(entry.getTotalPredictedMs() + record.predictedMs);
		entry.setTotalDraftTokens(entry.getTotalDraftTokens() + record.draftN);
		entry.setTotalDraftAccepted(entry.getTotalDraftAccepted() + record.draftNAccepted);
	}

    private BinaryRequestLog getLog(String modelId) throws IOException {
        return this.logMap.computeIfAbsent(modelId, id -> {
            try {
                return new BinaryRequestLog(Paths.get(RECORD_DIR + id + ".requests.bin"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static byte toEndpointByte(String endpoint) {
        if (endpoint == null) return 0;
        if (endpoint.contains("chat/completions")) return 0;
        if (endpoint.contains("completions")) return 1;
        if (endpoint.contains("embed")) return 2;
        if (endpoint.contains("messages")) return 3;
        if (endpoint.contains("/api/chat")) return 4;
        if (endpoint.contains("/api/embed")) return 5;
        if (endpoint.contains("generate")) return 6;
        return 0;
    }

    private static byte toStatusByte(ActiveRequest.RequestStatus status) {
        if (status == null) return 1;
        switch (status) {
            case CREATED: return 0;
            case COMPLETED: return 1;
            case FAILED: return 2;
            case PROXYING: return 4;
            default: return 1;
        }
    }

    private static byte toPhaseByte(ActiveRequest.Phase phase) {
        if (phase == null) return 1;
        switch (phase) {
            case PREFILL: return 0;
            case GENERATION: return 1;
            default: return 1;
        }
    }

    /**
     * 启动时自动迁移旧的 .requests.log 和 .json 文件到 .requests.bin 二进制格式。
     * 迁移成功后，将旧文件移动到 bak/ 目录。
     */
	private void migrateOldLogs() {
		Path dir = Paths.get(RECORD_DIR);
		if (!Files.exists(dir)) {
			return;
		}
		Path bakDir = dir.resolve("bak");
		try {
			Files.createDirectories(bakDir);
		} catch (IOException ignore) {
		}

		try (Stream<Path> paths = Files.list(dir)) {
			List<Path> files = paths
				.filter(p -> p.toString().endsWith(".requests.log") || p.toString().endsWith(".json"))
				.collect(java.util.stream.Collectors.toList());

			for (Path filePath : files) {
				migrateOneFile(filePath, bakDir);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	/**
	 * 	兼容旧的文本日志内容，转为二进制文件。
	 */
	private void migrateOneFile(Path filePath, Path bakDir) {
		String fileName = filePath.getFileName().toString();

		if (fileName.endsWith(".requests.log")) {
			String modelId = fileName.replace(".requests.log", "");
			Path binPath = filePath.resolveSibling(modelId + ".requests.bin");
			try {
				List<String> lines = Files.readAllLines(filePath);
				try (BinaryRequestLog log = new BinaryRequestLog(binPath)) {
					for (String line : lines) {
						if (line == null || line.trim().isEmpty()) continue;
						try {
							log.appendFromJson(line);
						} catch (Exception ignore) {
						}
					}
				}
				Files.move(filePath, bakDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else if (fileName.endsWith(".json")) {
			try {
				Files.move(filePath, bakDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 处理流式响应中的 timings 数据，将其累加到对应模型的记录中并持久化。
	 * 
	 * @param modelId 模型唯一标识
	 * @param json    包含 timings 数据的 JSON 字符串
	 * @return 解析出的本次 Timing 数据
	 */
	public Timing handleStream(String modelId, String json) {
		return this.handleStream(modelId, json, null);
	}

	/**
	 * 处理流式响应中的 timings 数据，将其累加到对应模型的记录中并持久化。
	 * 若无 timings 但有 usage，则从 usage 提取 token 数据写入 .requests.log。
	 *
	 * @param modelId   模型唯一标识
	 * @param json      包含 timings 或 usage 的 JSON 字符串
	 * @param requestId 请求 ID（用于写入 .requests.log）
	 * @return 解析出的本次 Timing 数据
	 */
	public Timing handleStream(String modelId, String json, String requestId) {
		Timing data = null;
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			if (root.has("timings")) {
				data = this.gson.fromJson(root.get("timings"), Timing.class);
				this.recordTiming(modelId, data);
			} else if (requestId != null && root.has("usage")) {
				this.recordUsage(requestId, modelId, root.getAsJsonObject("usage"));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return data;
	}

	/**
	 * 从 OpenAI 格式的 usage 字段提取 token 数据，写入二进制日志。
	 * 用于远程节点代理场景（无 timings，只有 usage）。
	 */
	public void recordUsage(String requestId, String modelId, JsonObject usage) {
		if (requestId == null || modelId == null || usage == null) return;
		try {
			RequestLogRecord record = new RequestLogRecord();
			record.startTime = System.currentTimeMillis();
			record.endpoint = toEndpointByte("");
			record.status = 1;
			record.phase = 1;
			record.cacheN = getJsonInt(usage, "prompt_cache_hit_tokens", 0);
			record.promptN = getJsonInt(usage, "prompt_tokens", 0);
            record.predictedN = getJsonInt(usage, "completion_tokens", 0);
			this.getLog(modelId).append(record);
			this.totalRecordCount.incrementAndGet();
			this.updateTokenSummary(modelId, record);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private int getJsonInt(JsonObject obj, String key, int fallback) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return obj.get(key).getAsInt();
		} catch (Exception e) {
			return fallback;
		}
	}

	/**
	 * 根据模型ID获取累计性能记录（从二进制日志 header 读取）。
	 *
	 * @param modelId 模型ID
	 * @return 累计的 Timing 记录，不存在则返回 null
	 */
	public Timing getRecord(String modelId) {
		Path binPath = Paths.get(RECORD_DIR + modelId + ".requests.bin");
		if (!Files.exists(binPath)) {
			return null;
		}
		try (BinaryRequestLog log = new BinaryRequestLog(binPath)) {
			Timing timing = new Timing();
			timing.setCache_n((int) log.getTotalCacheTokens());
			timing.setPrompt_n((int) log.getTotalPromptTokens());
			timing.setPrompt_ms(log.getTotalPromptMs());
			timing.setPredicted_n((int) log.getTotalPredictedTokens());
			timing.setPredicted_ms(log.getTotalPredictedMs());
			timing.setDraft_n((int) log.getTotalDraftTokens());
			timing.setDraft_n_accepted((int) log.getTotalDraftAccepted());
			return timing;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * 记录一次完整的请求记录，包含包裹了 Timing 的 ActiveRequest。
	 * 追加写入 cache/record/{modelId}.requests.bin。
	 */
	public void recordRequest(ActiveRequest request) {
		if (request == null || request.getModelId() == null) return;
		try {
			RequestLogRecord record = new RequestLogRecord();
			record.startTime = request.getStartTime();
			record.endpoint = toEndpointByte(request.getEndpoint());
			record.status = toStatusByte(request.getStatus());
			record.phase = toPhaseByte(request.getPhase());
			Timing timing = request.getTiming();
			if (timing != null) {
				record.cacheN = timing.getCache_n();
				record.promptN = timing.getPrompt_n();
				record.promptMs = (float) timing.getPrompt_ms();
				record.promptPerTokenMs = (float) timing.getPrompt_per_token_ms();
				record.promptPerSecond = (float) timing.getPrompt_per_second();
				record.predictedN = timing.getPredicted_n();
				record.predictedMs = (float) timing.getPredicted_ms();
				record.predictedPerTokenMs = (float) timing.getPredicted_per_token_ms();
				record.predictedPerSecond = (float) timing.getPredicted_per_second();
				record.draftN = timing.getDraft_n();
				record.draftNAccepted = timing.getDraft_n_accepted();
			}
      getLog(request.getModelId()).append(record);
			this.totalRecordCount.incrementAndGet();
			this.updateTokenSummary(request.getModelId(), record);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * 此方法已废弃，数据由 recordRequest 统一写入。
	 */
	private void recordTiming(String modelId, Timing requestTiming) {
		// No-op: recordRequest 在请求结束时统一写入，避免重复计数
	}
}

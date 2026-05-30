package org.mark.llamacpp.server.service;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

/**
 * 请求队列管理器。
 * 当模型正在加载时，将请求加入队列等待而不是立即返回失败。
 * 支持优先级队列（high/normal/low）。
 */
public class RequestQueueManager {

	private static final Logger logger = LoggerFactory.getLogger(RequestQueueManager.class);

	// 单例
	private static final RequestQueueManager INSTANCE = new RequestQueueManager();

	// P0 Fix: Static reference to OpenAIService for sending error responses in failAll
	private static volatile OpenAIService openAIService;

	// 每个模型的队列（modelId -> 队列）
	private final java.util.concurrent.ConcurrentHashMap<String, ModelRequestQueue> modelQueues = new java.util.concurrent.ConcurrentHashMap<>();

	private RequestQueueManager() {
	}

	public static RequestQueueManager getInstance() {
		return INSTANCE;
	}

	/**
	 * P0 Fix: Set OpenAIService instance for sending error responses in failAll
	 */
	public static void setOpenAIService(OpenAIService service) {
		openAIService = service;
	}

	/**
	 * 检查队列是否启用
	 */
	public boolean isQueueEnabled() {
		return LlamaServer.isJitQueueEnabled();
	}

	/**
	 * 获取请求优先级
	 */
	public RequestPriority getPriority(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (!LlamaServer.isJitQueuePriorityEnabled()) {
			return RequestPriority.NORMAL;
		}
		String headerName = LlamaServer.getJitQueuePriorityHeader();
		String priorityValue = request.headers().get(headerName);
		if (priorityValue == null || priorityValue.isBlank()) {
			return RequestPriority.NORMAL;
		}
		String p = priorityValue.trim().toLowerCase();
		if ("high".equals(p) || "1".equals(p) || "urgent".equals(p)) {
			return RequestPriority.HIGH;
		} else if ("low".equals(p) || "-1".equals(p) || "background".equals(p)) {
			return RequestPriority.LOW;
		}
		return RequestPriority.NORMAL;
	}

	/**
	 * 将请求加入队列等待模型加载完成
	 * 
	 * @param ctx         Netty 上下文
	 * @param request     HTTP 请求
	 * @param modelName   模型名称
	 * @param apiPath     API 路径
	 * @param isStream    是否流式
	 * @param requestBody 请求体 JSON
	 * @return true 表示已加入队列并等待处理，false 表示队列已满
	 */
	public boolean enqueueRequest(ChannelHandlerContext ctx, FullHttpRequest request, String modelName,
			String apiPath, boolean isStream, String requestBody) {

		if (!isQueueEnabled()) {
			return false;
		}

		// 获取或创建模型队列
		ModelRequestQueue queue = modelQueues.computeIfAbsent(modelName, k -> new ModelRequestQueue(modelName));

		// 检查队列是否已满
		if (queue.isFull()) {
			logger.warn("[Queue] 模型 {} 的请求队列已满，拒绝新请求", modelName);
			return false;
		}

		// 获取优先级
		RequestPriority priority = getPriority(ctx, request);

		// 获取超时时间
		int timeoutMs = LlamaServer.getJitQueueDefaultTimeout();

		// 创建排队的请求
		QueuedRequest queuedRequest = new QueuedRequest(ctx, request, modelName, apiPath, isStream,
				requestBody, priority, timeoutMs);

		// 加入队列
		boolean offered = queue.offer(queuedRequest);
		if (!offered) {
			logger.warn("[Queue] 模型 {} 的请求队列已满（并发）", modelName);
			return false;
		}

		logger.info("[Queue] 请求已加入队列等待模型 {} 加载，优先级: {}, 队列大小: {}", modelName, priority,
				queue.size());
		return true;
	}

	/**
	 * 模型加载完成后处理队列中的请求
	 * 
	 * @param modelName 模型名称
	 * @return 排队的请求列表，用于后续处理
	 */
	public void onModelLoaded(String modelName) {
		ModelRequestQueue queue = modelQueues.get(modelName);
		if (queue != null) {
			queue.signalReady();
		}
	}
	
	/**
	 * 获取并移除所有排队的请求（模型加载后调用）
	 * @param modelName 模型名称
	 * @return 排队的请求列表
	 */
	public java.util.List<QueuedRequest> drainQueue(String modelName) {
		ModelRequestQueue queue = modelQueues.remove(modelName);
		if (queue == null) {
			return new java.util.ArrayList<>();
		}
		return queue.drainAll();
	}

	/**
	 * 模型加载失败时清理队列
	 * 
	 * @param modelName 模型名称
	 */
	public void onModelLoadFailed(String modelName) {
		ModelRequestQueue queue = modelQueues.remove(modelName);
		if (queue != null) {
			queue.failAll(504, "Model loading failed: " + modelName);
		}
	}

	/**
	 * 移除模型队列（模型卸载时调用）
	 * 改为 failAll 以通知客户端请求失败，而不是静默丢弃
	 */
	public void removeQueue(String modelName) {
		ModelRequestQueue queue = modelQueues.remove(modelName);
		if (queue != null) {
			queue.failAll(503, "Model queue removed: model was unloaded or replaced");
		}
	}

	/**
	 * 获取队列大小
	 */
	public int getQueueSize(String modelName) {
		ModelRequestQueue queue = modelQueues.get(modelName);
		return queue != null ? queue.size() : 0;
	}

	/**
	 * 请求优先级
	 */
	public enum RequestPriority {
		HIGH(0), NORMAL(1), LOW(2);

		private final int order;

		RequestPriority(int order) {
			this.order = order;
		}

		public int getOrder() {
			return order;
		}
	}

	/**
	 * 排队的请求
	 */
	public static class QueuedRequest implements Comparable<QueuedRequest> {
		public final ChannelHandlerContext ctx;
		public final Map<String, String> headers;
		public final String httpMethod;  // P0 Fix: Store HTTP method from request.method().name()
		public final String modelName;
		public final String apiPath;
		public final boolean isStream;
		public final String requestBody;
		public final RequestPriority priority;
		public final long enqueueTime;
		public final long timeoutMs;
		public final ReentrantLock lock = new ReentrantLock();
		public final Condition completed = lock.newCondition();
		public volatile boolean processed = false;
		public volatile boolean failed = false;
		public volatile int errorCode = 0;
		public volatile String errorMessage = null;

		public QueuedRequest(ChannelHandlerContext ctx, FullHttpRequest request, String modelName,
				String apiPath, boolean isStream, String requestBody, RequestPriority priority, long timeoutMs) {
			this.ctx = ctx;
			this.modelName = modelName;
			this.apiPath = apiPath;
			this.isStream = isStream;
			this.requestBody = requestBody;
			this.priority = priority;
			this.enqueueTime = System.currentTimeMillis();
			this.timeoutMs = timeoutMs;
			// P0 Fix: Store HTTP method from request.method().name() to avoid :method pseudo-header issue in HTTP/1.1
			this.httpMethod = request.method().name();
			// 复制请求头，避免在异步任务中访问已释放的请求对象
			this.headers = new java.util.HashMap<>();
			for (Map.Entry<String, String> entry : request.headers()) {
				this.headers.put(entry.getKey(), entry.getValue());
			}
		}

		@Override
		public int compareTo(QueuedRequest other) {
			// 优先级高的在前，同优先级按时间顺序
			int cmp = this.priority.getOrder() - other.priority.getOrder();
			if (cmp != 0) {
				return cmp;
			}
			return Long.compare(this.enqueueTime, other.enqueueTime);
		}

		/**
		 * 等待请求被处理
		 * 
		 * @return true 表示成功处理，false 表示超时或失败
		 */
		public boolean await() {
			lock.lock();
			try {
				while (!processed && !failed) {
					if (!completed.await(timeoutMs, TimeUnit.MILLISECONDS)) {
						// 超时
						logger.warn("[Queue] 请求等待超时: model={}, priority={}", modelName, priority);
						failed = true;
						errorCode = 504;
						errorMessage = "Gateway Timeout: Request timed out while waiting in queue";
						return false;
					}
				}
				return !failed;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				failed = true;
				errorCode = 500;
				errorMessage = "Request interrupted";
				return false;
			} finally {
				lock.unlock();
			}
		}

		/**
		 * 标记请求处理完成
		 */
		public void markProcessed() {
			lock.lock();
			try {
				this.processed = true;
				this.completed.signalAll();
			} finally {
				lock.unlock();
			}
		}

		/**
		 * 标记请求失败
		 */
		public void markFailed(int errorCode, String errorMessage) {
			lock.lock();
			try {
				this.failed = true;
				this.errorCode = errorCode;
				this.errorMessage = errorMessage;
				this.completed.signalAll();
			} finally {
				lock.unlock();
			}
		}
	}

	/**
	 * 单个模型的请求队列
	 */
	private static class ModelRequestQueue {
		private final String modelName;
		private final BlockingQueue<QueuedRequest> queue;
		private final AtomicInteger activeCount = new AtomicInteger(0);
		private final ReentrantLock readyLock = new ReentrantLock();
		private final Condition ready = readyLock.newCondition();
		private volatile boolean modelReady = false;
		private volatile boolean failed = false;

		public ModelRequestQueue(String modelName) {
			int maxSize = LlamaServer.getJitQueueMaxSize();
			this.queue = new PriorityBlockingQueue<>(maxSize);
			this.modelName = modelName;
		}

		public boolean offer(QueuedRequest request) {
			return queue.offer(request);
		}

		public int size() {
			return queue.size() + activeCount.get();
		}

		public boolean isFull() {
			return queue.size() >= LlamaServer.getJitQueueMaxSize();
		}

		public void signalReady() {
			readyLock.lock();
			try {
				this.modelReady = true;
				ready.signalAll();
			} finally {
				readyLock.unlock();
			}
		}

		public void failAll(int errorCode, String errorMessage) {
			readyLock.lock();
			try {
				this.failed = true;
				QueuedRequest req;
				while ((req = queue.poll()) != null) {
					req.markFailed(errorCode, errorMessage);
					// P0 Fix: Send HTTP error response to active channel
					if (req.ctx.channel().isActive()) {
						try {
							if (openAIService != null) {
								openAIService.sendOpenAIErrorResponseWithCleanup(req.ctx, errorCode, null, errorMessage, null);
							} else {
								logger.warn("[Queue] failAll: OpenAIService not set, cannot send error response to client");
							}
						} catch (Exception e) {
							logger.error("[Queue] failAll: Failed to send error response to client", e);
						}
					}
				}
			} finally {
				readyLock.unlock();
			}
		}
		
		/**
		 * 取出并移除所有排队的请求
		 * P0 Fix: Added readyLock to prevent race condition
		 */
		public java.util.List<QueuedRequest> drainAll() {
			readyLock.lock();
			try {
				java.util.List<QueuedRequest> list = new java.util.ArrayList<>();
				QueuedRequest req;
				while ((req = queue.poll()) != null) {
					list.add(req);
				}
				return list;
			} finally {
				readyLock.unlock();
			}
		}
	}
}
package org.mark.test.mcp.channel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mark.llamacpp.server.LlamaServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;



/**
 * 	Netty的请求处理器。
 */
public class McpRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(McpRouterHandler.class);
	private static final Pattern STREAMABLE_PATH = Pattern.compile("^/mcp/([^/]+)$");
	private static final Pattern SSE_PATH = Pattern.compile("^/mcp/([^/]+)/sse$");
	private static final Pattern MESSAGE_PATH = Pattern.compile("^/mcp/([^/]+)/message$");

	private final NettySseMcpServer server;

	public McpRouterHandler(NettySseMcpServer server) {
		this.server = server;
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		logger.info("MCP路由收到请求: method={}, uri={}, remote={}", request.method().name(), request.uri(), ctx.channel().remoteAddress());
		if (!request.decoderResult().isSuccess()) {
			logger.info("MCP路由请求解析失败: uri={}", request.uri());
			this.server.handleBadRequest(ctx, "请求解析失败");
			return;
		}
		if (request.method() == HttpMethod.OPTIONS) {
			logger.info("MCP路由分发OPTIONS: uri={}", request.uri());
			this.server.handleOptions(ctx);
			return;
		}
		// 安全红线：MCP Bearer token 校验（除 OPTIONS 预检外的所有请求）
		// 配置了 token 时，所有请求必须携带 Authorization: Bearer <token>
		// （常量时间比较，防 timing attack）。未配置 token 时依赖 127.0.0.1 绑定兜底。
		String expectedToken = LlamaServer.getMcpServerToken();
		if (expectedToken != null && !expectedToken.isBlank()) {
			String auth = request.headers().get("Authorization");
			String provided = null;
			if (auth != null && auth.startsWith("Bearer ")) {
				provided = auth.substring("Bearer ".length()).trim();
			}
			if (!constantTimeEquals(provided, expectedToken)) {
				logger.info("MCP未授权请求被拒绝: remote={}, uri={}", ctx.channel().remoteAddress(), request.uri());
				this.server.sendJsonHttp(ctx, HttpResponseStatus.UNAUTHORIZED,
						this.server.newErrorBody("2.0", null, -32001, "unauthorized: missing or invalid Bearer token"));
				return;
			}
		}
		String path = this.server.cleanPath(request.uri());
		Matcher streamableMatcher = STREAMABLE_PATH.matcher(path);
		if (streamableMatcher.matches()) {
			String serviceKey = streamableMatcher.group(1);
			if (request.method() == HttpMethod.GET) {
				String sessionId = this.server.readSessionIdHeader(request);
				logger.info("MCP路由分发Streamable GET: serviceKey={}, sessionId={}", serviceKey, sessionId);
				this.server.handleStreamableGet(ctx, serviceKey, sessionId);
				return;
			}
			if (request.method() == HttpMethod.POST) {
				logger.info("MCP路由分发Streamable POST: serviceKey={}", serviceKey);
				this.server.handleStreamablePost(ctx, request, serviceKey);
				return;
			}
			if (request.method() == HttpMethod.DELETE) {
				logger.info("MCP路由分发Streamable DELETE: serviceKey={}", serviceKey);
				this.server.handleStreamableDelete(ctx, request, serviceKey);
				return;
			}
		}
		Matcher sseMatcher = SSE_PATH.matcher(path);
		if (request.method() == HttpMethod.GET && sseMatcher.matches()) {
			logger.info("MCP路由分发SSE连接: serviceKey={}", sseMatcher.group(1));
			this.server.handleSseConnect(ctx, sseMatcher.group(1));
			return;
		}
		Matcher msgMatcher = MESSAGE_PATH.matcher(path);
		if (request.method() == HttpMethod.POST && msgMatcher.matches()) {
			logger.info("MCP路由分发SSE消息请求: serviceKey={}", msgMatcher.group(1));
			this.server.handleSseMessagePost(ctx, request, msgMatcher.group(1));
			return;
		}
		logger.info("MCP路由未命中: method={}, path={}", request.method().name(), path);
		this.server.handleNotFound(ctx);
	}

	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		if (a.length() != b.length()) {
			return false;
		}
		int diff = 0;
		for (int i = 0; i < a.length(); i++) {
			diff |= a.charAt(i) ^ b.charAt(i);
		}
		return diff == 0;
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		logger.info("MCP路由检测到连接关闭: remote={}", ctx.channel().remoteAddress());
		this.server.cleanupByContext(ctx);
		super.channelInactive(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		logger.info("MCP测试服务连接异常: {}", cause.getMessage());
		ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
	}
}

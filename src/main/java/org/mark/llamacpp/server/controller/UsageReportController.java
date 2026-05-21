package org.mark.llamacpp.server.controller;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.UsageReportService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

public class UsageReportController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(UsageReportController.class);

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (uri.startsWith("/api/report/token-summary")) {
			this.handleTokenSummary(ctx, request);
			return true;
		}
		if (uri.startsWith("/api/report/daily-tokens")) {
			this.handleDailyTokens(ctx, request);
			return true;
		}
		if (uri.startsWith("/api/report/request-logs")) {
			this.handleRequestLogs(ctx, request);
			return true;
		}
		return false;
	}

	private void handleTokenSummary(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Object data = UsageReportService.getInstance().getTokenSummary();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取Token用量概览时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取Token用量概览失败: " + e.getMessage()));
		}
	}

	private void handleDailyTokens(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Object data = UsageReportService.getInstance().getDailyTokenUsage();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取每日Token用量时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取每日Token用量失败: " + e.getMessage()));
		}
	}

	private void handleRequestLogs(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Object data = UsageReportService.getInstance().getRequestLogs();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取请求记录时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取请求记录失败: " + e.getMessage()));
		}
	}
}

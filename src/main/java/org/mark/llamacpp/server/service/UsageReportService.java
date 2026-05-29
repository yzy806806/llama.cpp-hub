package org.mark.llamacpp.server.service;

import org.mark.llamacpp.record.BinaryRequestLog;
import org.mark.llamacpp.record.RequestLogRecord;
import org.mark.llamacpp.server.struct.DailyTokenEntry;
import org.mark.llamacpp.server.struct.RequestLogEntry;
import org.mark.llamacpp.server.struct.TokenSummaryEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

public class UsageReportService {

	private static final UsageReportService INSTANCE = new UsageReportService();
	private static final String RECORD_DIR = "cache/record/";

	public static UsageReportService getInstance() {
		return INSTANCE;
	}

	/**
	 * 从内存缓存获取所有有记录的模型概览。
	 */
	public List<TokenSummaryEntry> getTokenSummary() {
		return LlamaRecordService.getInstance().getTokenSummary();
	}

	/**
	 * 读取 cache/record/{modelId}.requests.bin 中的请求记录。
	 * 支持按模型过滤和分页。
	 *
	 * @param modelId  模型ID，null 表示查询所有模型
	 * @param page     页码（从1开始）
	 * @param pageSize 每页大小
	 * @return Map 包含 total、totalPages、page、pageSize、records
	 */
	public Map<String, Object> getRequestLogs(String modelId, int page, int pageSize) {
		Map<String, Object> response = new LinkedHashMap<>();
		List<RequestLogEntry> records;
		long total;

		try {
			if (modelId != null && !modelId.isEmpty()) {
				Path logPath = Paths.get(RECORD_DIR + modelId + ".requests.bin");
				if (Files.exists(logPath)) {
					try (BinaryRequestLog log = new BinaryRequestLog(logPath)) {
						total = log.getRecordCount();
					} catch (Exception e) {
						total = 0;
					}
				} else {
					total = 0;
				}
				records = readModelLogs(modelId, page, pageSize);
			} else {
				total = LlamaRecordService.getInstance().getTotalRecordCount();
				records = readAllModelLogs(page, pageSize);
			}
		} catch (Exception e) {
			e.printStackTrace();
			total = 0;
			records = new ArrayList<>();
		}

		response.put("total", total);
		response.put("totalPages", total > 0 ? (total + pageSize - 1) / pageSize : 0);
		response.put("page", page);
		response.put("pageSize", pageSize);
		response.put("records", records);
		return response;
	}

	private List<RequestLogEntry> readModelLogs(String modelId, int page, int pageSize) throws IOException {
		List<RequestLogEntry> result = new ArrayList<>();
		Path logPath = Paths.get(RECORD_DIR + modelId + ".requests.bin");
		if (!Files.exists(logPath)) {
			return result;
		}
		try (BinaryRequestLog log = new BinaryRequestLog(logPath)) {
			long totalRecords = log.getRecordCount();
			if (totalRecords <= 0) {
				return result;
			}
			long needed = (long) page * pageSize;
			long toRead = Math.min(totalRecords, needed);
			long startIndex = totalRecords - toRead;
			RequestLogRecord[] records = log.readRecords(startIndex, (int) toRead);
			for (RequestLogRecord r : records) {
				result.add(toEntry(r, modelId));
			}
		}
		return result;
	}

	private List<RequestLogEntry> readAllModelLogs(int page, int pageSize) throws IOException {
		List<RequestLogEntry> result = new ArrayList<>();
		long needed = (long) page * pageSize;
		try (Stream<Path> paths = Files.list(Paths.get(RECORD_DIR))) {
			List<Path> logFiles = paths.filter(p -> p.toString().endsWith(".requests.bin")).collect(java.util.stream.Collectors.toList());
			for (Path logPath : logFiles) {
				String modelId = logPath.getFileName().toString().replace(".requests.bin", "");
				try (BinaryRequestLog log = new BinaryRequestLog(logPath)) {
					long totalRecords = log.getRecordCount();
					if (totalRecords <= 0) {
						continue;
					}
					long toRead = (int) Math.min(totalRecords, needed);
					long startIndex = totalRecords - toRead;
					RequestLogRecord[] records = log.readRecords(startIndex, (int) toRead);
					for (RequestLogRecord r : records) {
						result.add(toEntry(r, modelId));
					}
				}
			}
		}
		result.sort((a, b) -> Long.compare(b.getStartTime(), a.getStartTime()));
		int fromIndex = (page - 1) * pageSize;
		int toIndex = Math.min(fromIndex + pageSize, result.size());
		if (fromIndex >= result.size()) {
			return new ArrayList<>();
		}
		return result.subList(fromIndex, toIndex);
	}

	private List<RequestLogEntry> readModelLogsByTimeRange(String modelId, long startEpoch, long endEpoch) throws IOException {
		List<RequestLogEntry> result = new ArrayList<>();
		if (modelId != null && !modelId.isEmpty()) {
			result.addAll(readSingleModelLogsByTimeRange(modelId, startEpoch, endEpoch));
		} else {
			try (Stream<Path> paths = Files.list(Paths.get(RECORD_DIR))) {
				List<Path> logFiles = paths.filter(p -> p.toString().endsWith(".requests.bin")).collect(java.util.stream.Collectors.toList());
				for (Path logPath : logFiles) {
					String mid = logPath.getFileName().toString().replace(".requests.bin", "");
					result.addAll(readSingleModelLogsByTimeRange(mid, startEpoch, endEpoch));
				}
			}
		}
		return result;
	}

	private List<RequestLogEntry> readSingleModelLogsByTimeRange(String modelId, long startEpoch, long endEpoch) throws IOException {
		List<RequestLogEntry> result = new ArrayList<>();
		Path logPath = Paths.get(RECORD_DIR + modelId + ".requests.bin");
		if (!Files.exists(logPath)) return result;
		try (BinaryRequestLog log = new BinaryRequestLog(logPath)) {
			long firstIdx = log.findFirstIndex(startEpoch);
			if (firstIdx >= log.getRecordCount()) return result;
			long lastIdx = log.findLastIndex(endEpoch);
			if (lastIdx < 0) return result;
			int count = (int) (lastIdx - firstIdx + 1);
			RequestLogRecord[] records = log.readRecords(firstIdx, count);
			for (RequestLogRecord r : records) {
				result.add(toEntry(r, modelId));
			}
		}
		return result;
	}

	private RequestLogEntry toEntry(RequestLogRecord r, String modelId) {
		RequestLogEntry entry = new RequestLogEntry();
		entry.setStartTime(r.startTime);
		entry.setModelId(modelId);
		entry.setEndpoint(r.endpointName());
		entry.setElapsedMs(r.elapsedMs());
		entry.setCacheTokens(r.cacheN);
		entry.setPromptTokens(r.promptN);
		entry.setPredictedTokens(r.predictedN);
		entry.setTotalTokens(r.totalTokens());
		entry.setPromptPerSecond(r.promptPerSecond);
		entry.setPredictedPerSecond(r.predictedPerSecond);
		entry.setDraftTokens(r.draftN);
		entry.setDraftAccepted(r.draftNAccepted);
		return entry;
	}

	/**
	 * 基于请求日志，聚合指定月份的每日 Token 用量。
	 */
	public List<DailyTokenEntry> getDailyTokenUsage(int year, int month) {
		return getDailyTokenUsage(year, month, null);
	}

	/**
	 * 基于请求日志，聚合指定月份、指定模型的每日 Token 用量。
	 */
	public List<DailyTokenEntry> getDailyTokenUsage(int year, int month, String modelId) {
		LocalDate firstDay = LocalDate.of(year, month, 1);
		LocalDate lastDay = firstDay.withDayOfMonth(firstDay.lengthOfMonth());

		ZoneId zone = ZoneId.systemDefault();
		long startEpoch = firstDay.atStartOfDay(zone).toInstant().toEpochMilli();
		long endEpoch = lastDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;

		List<RequestLogEntry> logs;
		try {
			logs = readModelLogsByTimeRange(modelId, startEpoch, endEpoch);
		} catch (IOException e) {
			e.printStackTrace();
			logs = new ArrayList<>();
		}
		if (logs.isEmpty()) {
			return buildEmptyMonthlyEntries(firstDay, lastDay);
		}

		Map<LocalDate, DailyTokenEntry> dayMap = new LinkedHashMap<>();
		for (RequestLogEntry log : logs) {
			if (log.getStartTime() <= 0) continue;
			if (modelId != null && !modelId.isEmpty() && !modelId.equals(log.getModelId())) continue;
			LocalDate day = Instant.ofEpochMilli(log.getStartTime()).atZone(ZoneId.systemDefault()).toLocalDate();
			if (day.isBefore(firstDay) || day.isAfter(lastDay)) continue;
			DailyTokenEntry entry = dayMap.get(day);
			if (entry == null) {
				entry = new DailyTokenEntry();
				entry.setDate(day.toString());
				dayMap.put(day, entry);
			}
			entry.setPromptTokens(entry.getPromptTokens() + log.getPromptTokens());
			entry.setPredictedTokens(entry.getPredictedTokens() + log.getPredictedTokens());
			entry.setCacheTokens(entry.getCacheTokens() + log.getCacheTokens());
		}

		List<DailyTokenEntry> result = new ArrayList<>();
		for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
			if (dayMap.containsKey(d)) {
				result.add(dayMap.get(d));
			} else {
				DailyTokenEntry empty = new DailyTokenEntry();
				empty.setDate(d.toString());
				result.add(empty);
			}
		}
		return result;
	}

	/**
	 * 获取有数据的所有年份（去重，升序）。
	 */
	public List<Integer> getAvailableYears() {
		Set<Integer> years = new TreeSet<>();
		try (Stream<Path> paths = Files.list(Paths.get(RECORD_DIR))) {
			List<Path> logFiles = paths.filter(p -> p.toString().endsWith(".requests.bin")).collect(java.util.stream.Collectors.toList());
			for (Path logPath : logFiles) {
				try (BinaryRequestLog log = new BinaryRequestLog(logPath)) {
					long count = log.getRecordCount();
					if (count == 0) continue;
					RequestLogRecord first = log.readRecord(0);
					RequestLogRecord last = log.readRecord(count - 1);
					int firstYear = Instant.ofEpochMilli(first.startTime).atZone(ZoneId.systemDefault()).toLocalDate().getYear();
					int lastYear = Instant.ofEpochMilli(last.startTime).atZone(ZoneId.systemDefault()).toLocalDate().getYear();
					for (int y = firstYear; y <= lastYear; y++) {
						years.add(y);
					}
				} catch (Exception e) {
					// skip
				}
			}
		} catch (IOException e) {
			// skip
		}
		// 确保包含当前年份
		years.add(LocalDate.now().getYear());
		return new ArrayList<>(years);
	}

	private List<DailyTokenEntry> buildEmptyMonthlyEntries(LocalDate firstDay, LocalDate lastDay) {
		List<DailyTokenEntry> result = new ArrayList<>();
		for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
			DailyTokenEntry entry = new DailyTokenEntry();
			entry.setDate(d.toString());
			result.add(entry);
		}
		return result;
	}
}

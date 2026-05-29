package org.mark.llamacpp.record;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;


/**
 *  嘿嘿嘿嘿。这玩意用于写入日志，用byte写入。
 */
public class BinaryRequestLog implements Closeable {

    public static final int MAGIC = 0x524C4F47;
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 80;
    public static final int RECORD_SIZE = 55;

    private FileChannel channel;

    private long recordCount;
    private long totalPromptTokens;
    private long totalPredictedTokens;
    private long totalCacheTokens;
    private long totalDraftTokens;
    private long totalDraftAccepted;
    private long lastRecordTime;
    private double totalPromptMs;
    private double totalPredictedMs;

    public BinaryRequestLog(Path filePath) throws IOException {
        this.channel = FileChannel.open(filePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        if (this.channel.size() == 0) {
            initHeader();
        } else {
            readHeader();
        }
    }

    private void initHeader() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(MAGIC);
        buf.putInt(VERSION);
        buf.putLong(0);
        buf.putLong(0);
        buf.putLong(0);
        buf.putLong(0);
        buf.putLong(0);
        buf.putLong(0);
        buf.putLong(0);
        buf.putDouble(0);
        buf.putDouble(0);
        synchronized (this.channel) {
            this.channel.position(0);
            buf.flip();
            this.channel.write(buf);
        }
    }

    private void readHeader() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        synchronized (this.channel) {
            this.channel.position(0);
            this.channel.read(buf);
        }
        buf.flip();
        int magic = buf.getInt();
        int version = buf.getInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid magic number: 0x" + Integer.toHexString(magic));
        }
        if (version != VERSION) {
            throw new IOException("Unsupported version: " + version);
        }
        this.recordCount = buf.getLong();
        this.totalPromptTokens = buf.getLong();
        this.totalPredictedTokens = buf.getLong();
        this.totalCacheTokens = buf.getLong();
        this.totalDraftTokens = buf.getLong();
        this.totalDraftAccepted = buf.getLong();
        this.lastRecordTime = buf.getLong();
        this.totalPromptMs = buf.getDouble();
        this.totalPredictedMs = buf.getDouble();
    }

    private void writeHeader() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(MAGIC);
        buf.putInt(VERSION);
        buf.putLong(this.recordCount);
        buf.putLong(this.totalPromptTokens);
        buf.putLong(this.totalPredictedTokens);
        buf.putLong(this.totalCacheTokens);
        buf.putLong(this.totalDraftTokens);
        buf.putLong(this.totalDraftAccepted);
        buf.putLong(this.lastRecordTime);
        buf.putDouble(this.totalPromptMs);
        buf.putDouble(this.totalPredictedMs);
        synchronized (this.channel) {
            this.channel.position(0);
            buf.flip();
            this.channel.write(buf);
        }
    }

    public synchronized void appendFromJson(String json) throws IOException {
        RequestLogRecord record = parseJson(json);
        append(record);
    }

    public static RequestLogRecord parseJson(String json) {
        RequestLogRecord record = new RequestLogRecord();
        if (json == null || json.trim().isEmpty()) {
            return record;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            record.startTime = getLong(root, "startTime", 0);
            record.endpoint = parseEndpoint(getString(root, "endpoint", ""));
            record.status = parseStatus(getString(root, "status", ""));
            record.phase = parsePhase(getString(root, "phase", ""));

            JsonObject timing = root.getAsJsonObject("timing");
            if (timing != null) {
                record.cacheN = getInt(timing, "cache_n", 0);
                record.promptN = getInt(timing, "prompt_n", 0);
                record.promptMs = (float) getDouble(timing, "prompt_ms", 0);
                record.promptPerTokenMs = (float) getDouble(timing, "prompt_per_token_ms", 0);
                record.promptPerSecond = (float) getDouble(timing, "prompt_per_second", 0);
                record.predictedN = getInt(timing, "predicted_n", 0);
                record.predictedMs = (float) getDouble(timing, "predicted_ms", 0);
                record.predictedPerTokenMs = (float) getDouble(timing, "predicted_per_token_ms", 0);
                record.predictedPerSecond = (float) getDouble(timing, "predicted_per_second", 0);
                record.draftN = getInt(timing, "draft_n", 0);
                record.draftNAccepted = getInt(timing, "draft_n_accepted", 0);
            }
        } catch (Exception ignore) {
        }
        return record;
    }

    private static byte parseEndpoint(String endpoint) {
        if ("/v1/chat/completions".equals(endpoint)) return 0;
        if ("/v1/completions".equals(endpoint)) return 1;
        if ("/v1/embeddings".equals(endpoint)) return 2;
        if ("/v1/messages".equals(endpoint)) return 3;
        if ("/api/chat".equals(endpoint)) return 4;
        if ("/api/embed".equals(endpoint)) return 5;
        if ("/v1/generate".equals(endpoint)) return 6;
        return 0;
    }

    private static byte parseStatus(String status) {
        if ("CREATED".equals(status)) return 0;
        if ("COMPLETED".equals(status)) return 1;
        if ("FAILED".equals(status)) return 2;
        if ("CANCELLED".equals(status)) return 3;
        if ("PROXYING".equals(status)) return 4;
        return 1;
    }

    private static byte parsePhase(String phase) {
        if ("PREFILL".equals(phase)) return 0;
        if ("PROMPT".equals(phase)) return 0;
        if ("GENERATION".equals(phase)) return 1;
        return 1;
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsString();
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsInt();
    }

    private static long getLong(JsonObject obj, String key, long fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsLong();
    }

    private static double getDouble(JsonObject obj, String key, double fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsDouble();
    }

    public synchronized void append(RequestLogRecord record) throws IOException {
        append(record.startTime, record.endpoint, record.status, record.phase,
               record.cacheN, record.promptN, record.promptMs, record.promptPerTokenMs,
               record.promptPerSecond, record.predictedN, record.predictedMs,
               record.predictedPerTokenMs, record.predictedPerSecond,
               record.draftN, record.draftNAccepted);
    }

    public synchronized void append(long startTime, byte endpoint, byte status, byte phase,
                                    int cacheN, int promptN, float promptMs, float promptPerTokenMs,
                                    float promptPerSecond, int predictedN, float predictedMs,
                                    float predictedPerTokenMs, float predictedPerSecond,
                                    int draftN, int draftNAccepted) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(RECORD_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(startTime);
        buf.put(endpoint);
        buf.put(status);
        buf.put(phase);
        buf.putInt(cacheN);
        buf.putInt(promptN);
        buf.putFloat(promptMs);
        buf.putFloat(promptPerTokenMs);
        buf.putFloat(promptPerSecond);
        buf.putInt(predictedN);
        buf.putFloat(predictedMs);
        buf.putFloat(predictedPerTokenMs);
        buf.putFloat(predictedPerSecond);
        buf.putInt(draftN);
        buf.putInt(draftNAccepted);

        synchronized (this.channel) {
            this.channel.position(this.channel.size());
            buf.flip();
            this.channel.write(buf);
        }

        this.recordCount++;
        this.totalPromptTokens += promptN;
        this.totalPredictedTokens += predictedN;
        this.totalCacheTokens += cacheN;
        this.totalDraftTokens += draftN;
        this.totalDraftAccepted += draftNAccepted;
        this.lastRecordTime = startTime;
        this.totalPromptMs += promptMs;
        this.totalPredictedMs += predictedMs;
        writeHeader();
    }

    public synchronized RequestLogRecord readRecord(long index) throws IOException {
        if (index < 0 || index >= this.recordCount) {
            throw new IndexOutOfBoundsException("Record index: " + index + ", total: " + this.recordCount);
        }
        long offset = HEADER_SIZE + (index * RECORD_SIZE);
        ByteBuffer buf = ByteBuffer.allocate(RECORD_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        synchronized (this.channel) {
            this.channel.position(offset);
            this.channel.read(buf);
        }
        buf.flip();
        return decodeRecord(buf);
    }

    public synchronized RequestLogRecord[] readRecords(long startIndex, int count) throws IOException {
        if (startIndex < 0 || startIndex >= this.recordCount) {
            throw new IndexOutOfBoundsException("Start index: " + startIndex + ", total: " + this.recordCount);
        }
        int actualCount = (int) Math.min(count, this.recordCount - startIndex);
        RequestLogRecord[] records = new RequestLogRecord[actualCount];
        long offset = HEADER_SIZE + (startIndex * RECORD_SIZE);
        ByteBuffer buf = ByteBuffer.allocate(RECORD_SIZE * actualCount);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        synchronized (this.channel) {
            this.channel.position(offset);
            this.channel.read(buf);
        }
        buf.flip();
        for (int i = 0; i < actualCount; i++) {
            ByteBuffer recordBuf = ByteBuffer.allocate(RECORD_SIZE);
            recordBuf.order(ByteOrder.LITTLE_ENDIAN);
            buf.get(recordBuf.array());
            records[i] = decodeRecord(recordBuf);
        }
        return records;
    }

	public long findFirstIndex(long targetEpochMillis) throws IOException {
		long low = 0, high = this.recordCount;
		while (low < high) {
			long mid = (low + high) >>> 1;
			long midTime = readRecord(mid).startTime;
			if (midTime < targetEpochMillis) low = mid + 1;
			else high = mid;
		}
		return low;
	}

	public long findLastIndex(long targetEpochMillis) throws IOException {
		if (this.recordCount == 0) return -1;
		long low = -1, high = this.recordCount - 1;
		while (low < high) {
			long mid = (low + high + 1) >>> 1;
			long midTime = readRecord(mid).startTime;
			if (midTime > targetEpochMillis) high = mid - 1;
			else low = mid;
		}
		return low;
	}

	private RequestLogRecord decodeRecord(ByteBuffer buf) {
        RequestLogRecord record = new RequestLogRecord();
        record.startTime = buf.getLong();
        record.endpoint = buf.get();
        record.status = buf.get();
        record.phase = buf.get();
        record.cacheN = buf.getInt();
        record.promptN = buf.getInt();
        record.promptMs = buf.getFloat();
        record.promptPerTokenMs = buf.getFloat();
        record.promptPerSecond = buf.getFloat();
        record.predictedN = buf.getInt();
        record.predictedMs = buf.getFloat();
        record.predictedPerTokenMs = buf.getFloat();
        record.predictedPerSecond = buf.getFloat();
        record.draftN = buf.getInt();
        record.draftNAccepted = buf.getInt();
        return record;
    }

    public long getRecordCount() {
        return this.recordCount;
    }

    public long getTotalPromptTokens() {
        return this.totalPromptTokens;
    }

    public long getTotalPredictedTokens() {
        return this.totalPredictedTokens;
    }

    public long getTotalCacheTokens() {
        return this.totalCacheTokens;
    }

    public long getTotalDraftTokens() {
        return this.totalDraftTokens;
    }

    public long getTotalDraftAccepted() {
        return this.totalDraftAccepted;
    }

    public long getLastRecordTime() {
        return this.lastRecordTime;
    }

    public double getTotalPromptMs() {
        return this.totalPromptMs;
    }

    public double getTotalPredictedMs() {
        return this.totalPredictedMs;
    }

    @Override
    public void close() throws IOException {
        if (this.channel != null) {
            this.channel.close();
        }
    }
}

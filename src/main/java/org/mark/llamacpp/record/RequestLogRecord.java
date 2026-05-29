package org.mark.llamacpp.record;

public class RequestLogRecord {

    public long startTime;
    public byte endpoint;
    public byte status;
    public byte phase;
    public int cacheN;
    public int promptN;
    public float promptMs;
    public float promptPerTokenMs;
    public float promptPerSecond;
    public int predictedN;
    public float predictedMs;
    public float predictedPerTokenMs;
    public float predictedPerSecond;
    public int draftN;
    public int draftNAccepted;

    public long elapsedMs() {
        return Math.round(this.promptMs + this.predictedMs);
    }

    public int totalTokens() {
        return this.promptN + this.predictedN;
    }

    public String endpointName() {
        switch (this.endpoint) {
            case 0: return "/v1/chat/completions";
            case 1: return "/v1/completions";
            case 2: return "/v1/embeddings";
            case 3: return "/v1/messages";
            case 4: return "/api/chat";
            case 5: return "/api/embed";
            case 6: return "/v1/generate";
            default: return "UNKNOWN(" + this.endpoint + ")";
        }
    }

    public String statusName() {
        switch (this.status) {
            case 0: return "CREATED";
            case 1: return "COMPLETED";
            case 2: return "FAILED";
            case 3: return "CANCELLED";
            case 4: return "PROXYING";
            default: return "UNKNOWN(" + this.status + ")";
        }
    }

    public String phaseName() {
        switch (this.phase) {
            case 0: return "PREFILL";
            case 1: return "GENERATION";
            default: return "UNKNOWN(" + this.phase + ")";
        }
    }
}

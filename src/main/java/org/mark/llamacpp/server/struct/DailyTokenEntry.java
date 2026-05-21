package org.mark.llamacpp.server.struct;

public class DailyTokenEntry {
	private String date;
	private long promptTokens;
	private long predictedTokens;
	private long cacheTokens;

	public String getDate() {
		return date;
	}

	public void setDate(String date) {
		this.date = date;
	}

	public long getPromptTokens() {
		return promptTokens;
	}

	public void setPromptTokens(long promptTokens) {
		this.promptTokens = promptTokens;
	}

	public long getPredictedTokens() {
		return predictedTokens;
	}

	public void setPredictedTokens(long predictedTokens) {
		this.predictedTokens = predictedTokens;
	}

	public long getCacheTokens() {
		return cacheTokens;
	}

	public void setCacheTokens(long cacheTokens) {
		this.cacheTokens = cacheTokens;
	}
}

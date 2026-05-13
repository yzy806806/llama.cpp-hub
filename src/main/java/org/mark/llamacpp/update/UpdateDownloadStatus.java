package org.mark.llamacpp.update;

public enum UpdateDownloadStatus {
    IDLE("idle"),
    DOWNLOADING("downloading"),
    READY("ready"),
    FAILED("failed");

    private final String label;
    UpdateDownloadStatus(String label) { this.label = label; }
    public String getLabel() { return label; }
}

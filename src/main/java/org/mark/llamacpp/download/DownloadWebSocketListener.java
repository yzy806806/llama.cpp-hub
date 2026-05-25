package org.mark.llamacpp.download;

import org.mark.llamacpp.download.struct.DownloadProgress;
import org.mark.llamacpp.download.struct.DownloadState;
import org.mark.llamacpp.server.websocket.WebSocketManager;

/**
 * 下载进度WebSocket监听器，用于将下载状态变化通过WebSocket广播给客户端
 */
public class DownloadWebSocketListener implements DownloadProgressListener {
    
    private final WebSocketManager webSocketManager;
    
    public DownloadWebSocketListener() {
        this.webSocketManager = WebSocketManager.getInstance();
    }
    
    @Override
    public void onStateChanged(DownloadTask task, DownloadState oldState, DownloadState newState) {
        // 广播状态变化事件
        webSocketManager.sendDownloadStatusEvent(
            task.getTaskId(),
            newState.toString(),
            task.getDownloadedBytes(),
            task.getTotalBytes(),
            task.getPartsCompleted(),
            task.getPartsTotal(),
            task.getFileName(),
            task.getErrorMessage(),
            task.getUrl(),
            resolveTargetPath(task)
        );
    }
    
    @Override
    public void onProgressUpdated(DownloadTask task, DownloadProgress progress) {
        // 广播所有任务的进度，包括正在下载和暂停的任务
    	/*
        if (task.getState() == DownloadState.DOWNLOADING ||
            task.getState() == DownloadState.IDLE) {
            
        }
        */
        this.webSocketManager.sendDownloadProgressEvent(
                task.getTaskId(),
                progress.getDownloadedBytes(),
                progress.getTotalBytes(),
                progress.getPartsCompleted(),
                progress.getPartsTotal(),
                task.getProgressRatio(),
                task.getFileName(),
                task.getUrl(),
                resolveTargetPath(task)
            );
    }
    
    @Override
    public void onTaskCompleted(DownloadTask task) {
        // 广播任务完成事件
        webSocketManager.sendDownloadStatusEvent(
            task.getTaskId(),
            DownloadState.COMPLETED.toString(),
            task.getDownloadedBytes(),
            task.getTotalBytes(),
            task.getPartsCompleted(),
            task.getPartsTotal(),
            task.getFileName(),
            task.getErrorMessage(),
            task.getUrl(),
            resolveTargetPath(task)
        );
    }
    
    @Override
    public void onTaskFailed(DownloadTask task, String error) {
        // 广播任务失败事件
        webSocketManager.sendDownloadStatusEvent(
            task.getTaskId(),
            DownloadState.FAILED.toString(),
            task.getDownloadedBytes(),
            task.getTotalBytes(),
            task.getPartsCompleted(),
            task.getPartsTotal(),
            task.getFileName(),
            error,
            task.getUrl(),
            resolveTargetPath(task)
        );
    }
    
    @Override
    public void onTaskPaused(DownloadTask task) {
        // 广播任务暂停事件
        webSocketManager.sendDownloadStatusEvent(
            task.getTaskId(),
            DownloadState.IDLE.toString(),
            task.getDownloadedBytes(),
            task.getTotalBytes(),
            task.getPartsCompleted(),
            task.getPartsTotal(),
            task.getFileName(),
            task.getErrorMessage(),
            task.getUrl(),
            resolveTargetPath(task)
        );
    }
    
    @Override
    public void onTaskResumed(DownloadTask task) {
        // 广播任务恢复事件
        webSocketManager.sendDownloadStatusEvent(
            task.getTaskId(),
            DownloadState.DOWNLOADING.toString(),
            task.getDownloadedBytes(),
            task.getTotalBytes(),
            task.getPartsCompleted(),
            task.getPartsTotal(),
            task.getFileName(),
            task.getErrorMessage(),
            task.getUrl(),
            resolveTargetPath(task)
        );
    }

    private String resolveTargetPath(DownloadTask task) {
        try {
            return task.getTargetPath() == null ? "" : task.getTargetPath().toString();
        } catch (Exception e) {
            return "";
        }
    }
}

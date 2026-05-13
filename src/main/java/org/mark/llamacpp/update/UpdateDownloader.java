package org.mark.llamacpp.update;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.mark.llamacpp.server.websocket.WebSocketManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateDownloader {

    private static final Logger logger = LoggerFactory.getLogger(UpdateDownloader.class);

    private static final UpdateDownloader INSTANCE = new UpdateDownloader();

    private static final String CACHE_DIR = "cache";
    private static final String UPDATE_ZIP = CACHE_DIR + File.separator + "update.zip";
    private static final String UPDATE_PENDING_VERSION = CACHE_DIR + File.separator + "update-pending-version";

    private final AtomicReference<UpdateDownloadStatus> status = new AtomicReference<>(UpdateDownloadStatus.IDLE);
    private final AtomicLong downloadedBytes = new AtomicLong(0);
    private final AtomicLong totalBytes = new AtomicLong(-1);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Thread downloadThread;
    private volatile String currentVersion;
    private volatile String errorMessage;

    // i18n keys — sent via WebSocket for frontend translation
    private static final String I18N_HTTP_ERROR = "update.download.failed.http";
    private static final String I18N_CANCELLED = "update.download.cancelled";
    private static final String I18N_FAILED = "update.download.failed";

    public static UpdateDownloader getInstance() {
        return INSTANCE;
    }

    private UpdateDownloader() {
    }

    public boolean downloadAsync(String url, String version) {
        UpdateDownloadStatus current;
        while (true) {
            current = status.get();
            if (current == UpdateDownloadStatus.DOWNLOADING || current == UpdateDownloadStatus.READY) {
                return false;
            }
            if (status.compareAndSet(current, UpdateDownloadStatus.DOWNLOADING)) {
                break;
            }
        }
        currentVersion = version;
        errorMessage = null;
        downloadedBytes.set(0);
        totalBytes.set(-1);
        cancelled.set(false);

        Path userDir = Paths.get(System.getProperty("user.dir"));
        Path oldVersionFile = userDir.resolve(UPDATE_PENDING_VERSION).normalize();
        deleteQuietly(oldVersionFile);

        Thread t = Thread.ofVirtual().start(() -> downloadZip(url, userDir));
        downloadThread = t;
        return true;
    }

    public void cancel() {
        cancelled.set(true);
        Thread t = downloadThread;
        if (t != null) {
            t.interrupt();
        }
    }

    public UpdateDownloadStatus getStatus() {
        return status.get();
    }

    public long getDownloadedBytes() {
        return downloadedBytes.get();
    }

    public long getTotalBytes() {
        return totalBytes.get();
    }

    public double getProgressRatio() {
        long total = totalBytes.get();
        if (total <= 0) {
            return -1;
        }
        return (double) downloadedBytes.get() / total;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    private void downloadZip(String url, Path userDir) {
        Path target = userDir.resolve(UPDATE_ZIP).normalize();
        try {
            Path parent = target.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            HttpURLConnection conn = connect(url);
            int respCode = conn.getResponseCode();
            if (respCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                fail(I18N_HTTP_ERROR);
                return;
            }

            long contentLength = conn.getContentLengthLong();
            if (contentLength > 0) {
                totalBytes.set(contentLength);
            }

            broadcastProgress();

            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(target.toFile())) {
                byte[] buf = new byte[8192];
                int len;
                long lastBroadcast = System.currentTimeMillis();

                while ((len = in.read(buf)) != -1) {
                    if (cancelled.get()) {
                        conn.disconnect();
                        cleanup(target);
                        status.set(UpdateDownloadStatus.IDLE);
                        WebSocketManager.getInstance().sendAppUpdateEvent("failed", 0, totalBytes.get(), -1, currentVersion, I18N_CANCELLED);
                        return;
                    }
                    out.write(buf, 0, len);
                    downloadedBytes.addAndGet(len);

                    long now = System.currentTimeMillis();
                    if (now - lastBroadcast >= 500) {
                        broadcastProgress();
                        lastBroadcast = now;
                    }
                }
                out.flush();
            }
            conn.disconnect();

            savePendingVersion(userDir, currentVersion);
            status.set(UpdateDownloadStatus.READY);
            WebSocketManager.getInstance().sendAppUpdateEvent("completed", downloadedBytes.get(), totalBytes.get(), 1.0, currentVersion, "");

        } catch (IOException e) {
            if (cancelled.get()) {
                cleanup(target);
                status.set(UpdateDownloadStatus.IDLE);
                WebSocketManager.getInstance().sendAppUpdateEvent("failed", 0, totalBytes.get(), -1, currentVersion, I18N_CANCELLED);
                return;
            }
            logger.error("下载更新包时发生错误", e);
            fail(I18N_FAILED);
            cleanup(target);
        }
    }

    private HttpURLConnection connect(String downloadUrl) throws IOException {
        URL url = URI.create(downloadUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "llama.cpp-hub-updater");
        conn.setRequestProperty("Accept", "*/*");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        return conn;
    }

    private void broadcastProgress() {
        double ratio = getProgressRatio();
        WebSocketManager.getInstance().sendAppUpdateEvent("downloading", downloadedBytes.get(), totalBytes.get(), ratio, currentVersion, "");
    }

    private void fail(String errorMsg) {
        errorMessage = errorMsg;
        status.set(UpdateDownloadStatus.FAILED);
        WebSocketManager.getInstance().sendAppUpdateEvent("failed", downloadedBytes.get(), totalBytes.get(), -1, currentVersion, errorMsg);
    }

    private void savePendingVersion(Path userDir, String version) {
        try {
            Path versionFile = userDir.resolve(UPDATE_PENDING_VERSION).normalize();
            Path parent = versionFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(versionFile, version != null ? version : "");
        } catch (IOException e) {
            logger.warn("保存待更新版本号失败: {}", e.getMessage());
        }
    }

    private void cleanup(Path path) {
        deleteQuietly(path);
    }

    private void deleteQuietly(Path path) {
        if (path != null && Files.exists(path)) {
            try {
                Files.delete(path);
            } catch (IOException e) {
                logger.warn("删除文件失败: {}", path, e);
            }
        }
    }
}

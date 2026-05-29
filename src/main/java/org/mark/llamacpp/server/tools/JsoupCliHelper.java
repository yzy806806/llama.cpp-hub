package org.mark.llamacpp.server.tools;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsoupCliHelper {

    private static final Logger logger = LoggerFactory.getLogger(JsoupCliHelper.class);

    private static final JsoupCliHelper INSTANCE = new JsoupCliHelper();
    private static final String CACHE_DIR_NAME = "cache/tools/jsoup";
    private static final String RESOURCE_PATH = "/tools/jsoup/release.jar";
    private static final String NETTY_JAR_NAME = "netty-all-4.1.35.Final.jar";

    private volatile boolean initialized = false;
    private String releaseJarPath;
    private String nettyJarPath;
    private String javaExecutable;
    private boolean available = false;
    private String initError;

    private JsoupCliHelper() {}

    public static JsoupCliHelper getInstance() {
        return INSTANCE;
    }

    public synchronized String init() {
        if (initialized)
            return initError;
        initialized = true;

        try {
            Path cacheDir = Paths.get(CACHE_DIR_NAME);
            Files.createDirectories(cacheDir);
            logger.info("JsoupCli init: cacheDir={}", cacheDir.toAbsolutePath());

            Path releaseJarDest = cacheDir.resolve("release.jar");
            copyFromClasspath(RESOURCE_PATH, releaseJarDest);
            if (!Files.exists(releaseJarDest)) {
                throw new FileNotFoundException("JsoupCli release.jar not extracted: " + releaseJarDest);
            }
            releaseJarPath = releaseJarDest.toAbsolutePath().toString();
            logger.info("JsoupCli init: releaseJar={}", releaseJarPath);

            nettyJarPath = findNettyJar();
            if (nettyJarPath == null) {
                throw new FileNotFoundException("netty-all JAR not found. Expected in lib/ directory.");
            }
            logger.info("JsoupCli init: nettyJar={}", nettyJarPath);

            javaExecutable = findJavaExecutable();
            if (javaExecutable == null) {
                throw new FileNotFoundException("Java executable not found. Checked java.home and PATH.");
            }
            logger.info("JsoupCli init: java={}", javaExecutable);

            available = true;
            return null;
        } catch (Exception e) {
            available = false;
            String msg = e.getMessage();
            initError = (msg != null && !msg.isBlank()) ? msg : e.getClass().getName() + ": " + e.toString();
            logger.error("JsoupCli init failed: {}", initError, e);
            return initError;
        }
    }

    public boolean isAvailable() {
        if (!initialized)
            init();
        return available;
    }

    public String getInitError() {
        return initError;
    }

    public String getReleaseJarPath() {
        return releaseJarPath;
    }

    public String getNettyJarPath() {
        return nettyJarPath;
    }

    public String getJavaExecutable() {
        return javaExecutable;
    }

    private String findJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        String candidate = javaHome + File.separator + "bin" + File.separator + "java";
        if (new File(candidate).exists()) {
            return candidate;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("where", "java");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank() && new File(line.trim()).exists()) {
                        return line.trim();
                    }
                }
            }
        } catch (Exception ignored) {}

        return "java";
    }

    private String findNettyJar() {
        String userDir = System.getProperty("user.dir");
        Path libPath = Paths.get(userDir, "lib", NETTY_JAR_NAME);
        if (Files.exists(libPath)) {
            return libPath.toAbsolutePath().toString();
        }

        try {
            String jarPath = JsoupCliHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            Path jarFile = Paths.get(jarPath).normalize();
            Path jarParent = jarFile.getParent();
            if (jarParent != null) {
                Path libNextToJar = jarParent.resolve("lib").resolve(NETTY_JAR_NAME);
                if (Files.exists(libNextToJar)) {
                    return libNextToJar.toAbsolutePath().toString();
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    public FetchResult fetchPage(String url, String selector, Integer maxLength, Boolean json,
                                  Integer timeout, String userAgent, String proxyHost, Integer proxyPort) {
        if (!isAvailable()) {
            return new FetchResult(false, "JsoupCli not available: " + initError, -1);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable);
        cmd.add("-cp");
        String cp = releaseJarPath;
        if (nettyJarPath != null) {
            cp += File.pathSeparatorChar + nettyJarPath;
        }
        cmd.add(cp);
        cmd.add("org.jsoup.app.JsoupCli");
        cmd.add("--url");
        cmd.add(url);

        if (selector != null && !selector.isBlank()) {
            cmd.add("--selector");
            cmd.add(selector);
        }
        if (maxLength != null && maxLength > 0) {
            cmd.add("--max-length");
            cmd.add(String.valueOf(maxLength));
        }
        if (json != null && json) {
            cmd.add("--json");
        }
        if (timeout != null && timeout > 0) {
            cmd.add("--timeout");
            cmd.add(String.valueOf(timeout));
        }
        if (userAgent != null && !userAgent.isBlank()) {
            cmd.add("--user-agent");
            cmd.add(userAgent);
        }
        if (proxyHost != null && !proxyHost.isBlank()) {
            cmd.add("--proxy-host");
            cmd.add(proxyHost);
            if (proxyPort != null && proxyPort > 0) {
                cmd.add("--proxy-port");
                cmd.add(String.valueOf(proxyPort));
            }
        }

        return runProcess(cmd, timeout != null ? timeout : 30000);
    }

    static FetchResult runProcess(List<String> cmd, int timeoutMs) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("jsoupcli_", ".txt");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(tempFile.toFile());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new FetchResult(false, "JsoupCli timed out after " + timeoutMs + "ms", -1);
            }

            int exitCode = process.exitValue();
            byte[] raw = Files.readAllBytes(tempFile);
            String text = new String(raw, detectOutputCharset(raw)).trim();
            return new FetchResult(exitCode == 0, text, exitCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchResult(false, "JsoupCli execution interrupted", -1);
        } catch (Exception e) {
            return new FetchResult(false, "JsoupCli execution failed: " + e.getMessage(), -1);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {}
            }
        }
    }

    private static Charset detectOutputCharset(byte[] data) {
        if (data.length == 0) {
            return StandardCharsets.UTF_8;
        }
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            return Charset.forName("GBK");
        }
        return StandardCharsets.UTF_8;
    }

    private static void copyFromClasspath(String resource, Path dest) {
        if (Files.exists(dest))
            return;
        try (InputStream is = JsoupCliHelper.class.getResourceAsStream(resource)) {
            if (is == null)
                return;
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // non-fatal
        }
    }

    public static class FetchResult {
        private final boolean success;
        private final String output;
        private final int exitCode;

        public FetchResult(boolean success, String output, int exitCode) {
            this.success = success;
            this.output = output;
            this.exitCode = exitCode;
        }

        public boolean isSuccess() { return success; }
        public String getOutput() { return output; }
        public int getExitCode() { return exitCode; }
    }
}

package org.mark.llamacpp.crawler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Searches HuggingFace for GGUF models by name.
 * Based on the official HF OpenAPI spec (docs/openapi.md).
 *
 * API endpoints used:
 *   GET /api/models?search={q}&filter=gguf&full=true
 *   GET /api/models/{namespace}/{repo}
 *   GET /api/models/{namespace}/{repo}/tree/{rev}/{path}?recursive=true&limit={n}&cursor={c}
 *   GET /{namespace}/{repo}/resolve/{rev}/{path}
 */
public final class HuggingFaceSearcher {

    private static final String HF_BASE = "https://huggingface.co";
    private static final String HF_MIRROR_BASE = "https://hf-mirror.com";

    // ---------------------------------------------------------------------------
    // Records
    // ---------------------------------------------------------------------------

    public record ModelHit(String repoId, String modelUrl, String pipelineTag,
                           String lastModified, String libraryName,
                           Long downloads, Long likes, String parameters) {}

    public record PageResult(String query, List<ModelHit> hits) {}

    public record GgufFile(String path, Long size, String lfsOid, Long lfsSize, String downloadUrl) {}

    public record GgufCrawlResult(String repoId, String revision,
                                  List<GgufFile> ggufFiles, String treeError) {}

    public record ReadmeResult(String repoId, String revision, String path,
                               String modelUrl, String readmeUrl, String markdown) {}

    // ---------------------------------------------------------------------------
    // Public – search
    // ---------------------------------------------------------------------------

    public static PageResult search(String query) throws IOException {
        return search(query, 30, 20, 0, 0, null);
    }

   public static PageResult search(String query, int limit, int timeoutSeconds,
                                      int startPage, int maxPages, String baseUrl)
            throws IOException {
        if (query == null || query.isBlank())
            throw new IllegalArgumentException("query cannot be empty");

        int safeLimit = Math.max(1, Math.min(200, limit));
        int safeTimeout = Math.max(1, timeoutSeconds);
        String base = resolveBaseUrl(baseUrl);

        int estimatedPages = Math.min(30, Math.max(1, (safeLimit + 29) / 30) + 3);
        int safeStartPage = Math.max(0, startPage);
        int safeMaxPages = maxPages > 0 ? maxPages : estimatedPages;

        Set<String> seen = new LinkedHashSet<>();
        List<ModelHit> hits = new ArrayList<>();

        String cursor = null;
        for (int i = 0; i < safeStartPage; i++) {
            InternalPage page = fetchSearchPage(base, query, cursor, safeTimeout);
            cursor = page.nextCursor;
            if (cursor == null || cursor.isBlank())
                return new PageResult(query, hits);
        }

        int consecutiveEmpty = 0;
        for (int fetched = 0; hits.size() < safeLimit && fetched < safeMaxPages && consecutiveEmpty < 2; fetched++) {
            int before = seen.size();
            InternalPage page = fetchSearchPage(base, query, cursor, safeTimeout);
            collectHits(page.entries, safeLimit, seen, hits, base);
            cursor = page.nextCursor;
            consecutiveEmpty = seen.size() == before ? consecutiveEmpty + 1 : 0;
            if (cursor == null || cursor.isBlank())
                break;
        }

        return new PageResult(query, hits);
    }

    // ---------------------------------------------------------------------------
    // Public – list GGUF files in a repo
    // ---------------------------------------------------------------------------

    public static GgufCrawlResult listGgufFiles(String repoId)
            throws IOException {
        return listGgufFiles(repoId, 20, null);
    }

    public static GgufCrawlResult listGgufFiles(String repoId, int timeoutSeconds, String baseUrl)
            throws IOException {
        if (repoId == null || repoId.isBlank())
            throw new IllegalArgumentException("repoId cannot be empty");

        String base = resolveBaseUrl(baseUrl);
        int safeTimeout = Math.max(1, timeoutSeconds);
        String normalizedId = normalizeRepoId(repoId);
        if (normalizedId == null)
            throw new IllegalArgumentException("Invalid repoId: " + repoId);

        String revision = null;
        String treeError = null;
        List<GgufFile> files;

        try {
            JsonObject modelInfo = fetchModelInfo(base, normalizedId, safeTimeout);
            revision = getString(modelInfo, "sha");
            if (revision == null || revision.isBlank())
                revision = "main";
        } catch (Exception e) {
            revision = "main";
        }

        try {
            files = crawlGgufTargeted(base, normalizedId, revision, safeTimeout);
        } catch (Exception e) {
            String fallbackRev = findFallbackRevision(revision);
            if (fallbackRev == null) fallbackRev = "main";
            try {
                revision = fallbackRev;
                files = crawlGgufTargeted(base, normalizedId, revision, safeTimeout);
            } catch (Exception e2) {
                files = new ArrayList<>();
                treeError = e2.getMessage();
            }
        }

        return new GgufCrawlResult(normalizedId, revision, files, treeError);
    }

    public static ReadmeResult fetchReadme(String repoId)
            throws IOException {
        return fetchReadme(repoId, 20, null);
    }

    public static ReadmeResult fetchReadme(String repoId, int timeoutSeconds, String baseUrl)
            throws IOException {
        if (repoId == null || repoId.isBlank())
            throw new IllegalArgumentException("repoId cannot be empty");

        String base = resolveBaseUrl(baseUrl);
        int safeTimeout = Math.max(1, timeoutSeconds);
        String normalizedId = normalizeRepoId(repoId);
        if (normalizedId == null)
            throw new IllegalArgumentException("Invalid repoId: " + repoId);

        String revision = "main";
        JsonObject modelInfo = null;
        try {
            modelInfo = fetchModelInfo(base, normalizedId, safeTimeout);
            String fetchedRevision = getString(modelInfo, "sha");
            if (fetchedRevision != null && !fetchedRevision.isBlank()) {
                revision = fetchedRevision;
            }
        } catch (Exception ignored) {
        }

        String readmePath = detectReadmePath(modelInfo);
        if (readmePath == null) {
            readmePath = fetchReadmeByCandidates(base, normalizedId, revision, safeTimeout);
            if (readmePath == null) {
                String fallbackRev = findFallbackRevision(revision);
                if (fallbackRev != null && !fallbackRev.equalsIgnoreCase(revision)) {
                    revision = fallbackRev;
                    if (modelInfo != null) {
                        readmePath = detectReadmePath(modelInfo);
                    }
                    if (readmePath == null) {
                        readmePath = fetchReadmeByCandidates(base, normalizedId, revision, safeTimeout);
                    }
                }
            }
        }

        if (readmePath == null || readmePath.isBlank()) {
            throw new IOException("未找到 README 文件");
        }

        String readmeUrl = buildResolveUrl(base, normalizedId, revision, readmePath);
        String markdown = sendTextGet(readmeUrl, safeTimeout,
                "text/markdown, text/plain; charset=utf-8, */*");
        markdown = stripReadmeFrontMatter(markdown);
        return new ReadmeResult(normalizedId, revision, readmePath, base + "/" + normalizedId, readmeUrl, markdown);
    }

    // ---------------------------------------------------------------------------
    // Internal – model info
    // ---------------------------------------------------------------------------

    private static JsonObject fetchModelInfo(String base, String repoId, int timeout)
            throws IOException {
        NettyHttpUtils.Response resp = sendGet(base + "/api/models/" + repoId, timeout, "application/json");
        JsonElement root = JsonParser.parseString(resp.bodyAsString());
        if (!root.isJsonObject())
            throw new IOException("Response is not a JSON object: " + resp.bodyAsString());
        return root.getAsJsonObject();
    }

    // ---------------------------------------------------------------------------
    // Internal – search page
    // ---------------------------------------------------------------------------

    private record InternalPage(JsonArray entries, String nextCursor) {}

    private static InternalPage fetchSearchPage(String base, String query, String cursor, int timeout)
            throws IOException {
        String q = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        StringBuilder url = new StringBuilder();
        url.append(base).append("/api/models?search=").append(q)
           .append("&filter=gguf&full=true&limit=30");
        if (cursor != null && !cursor.isBlank())
            url.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8).replace("+", "%20"));

        NettyHttpUtils.Response resp = sendGet(url.toString(), timeout, "application/json");

        JsonElement root = JsonParser.parseString(resp.bodyAsString());
        JsonArray entries = root != null && root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();

        String nextCursor = resp.header("X-Next-Cursor");
        if (nextCursor == null || nextCursor.isBlank())
            nextCursor = parseCursorFromLinkHeader(resp.header("Link"));
        return new InternalPage(entries, nextCursor);
    }

    private static void collectHits(JsonArray entries, int limit, Set<String> seen,
                                     List<ModelHit> hits, String base) {
        if (entries == null || entries.isEmpty()) return;
        for (JsonElement el : entries) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();

            // "id" is the official HF API field; "modelId" is a fallback
            String repoId = getString(obj, "id");
            if (repoId == null || repoId.isBlank())
                repoId = getString(obj, "modelId");
            if (repoId == null || repoId.isBlank()) continue;
            if (!seen.add(repoId)) continue;

            String modelUrl = base + "/" + repoId;
            String pipelineTag = getString(obj, "pipeline_tag");
            String lastModified = getString(obj, "lastModified");
            String libraryName = getString(obj, "library_name");
            Long downloads = parseLongOrNull(getNumberString(obj, "downloads"));
            Long likes = parseLongOrNull(getNumberString(obj, "likes"));
            String parameters = null;
            JsonObject cardData = getObject(obj, "cardData");
            if (cardData != null) {
                parameters = firstNonBlank(
                    getString(cardData, "parameters"),
                    getString(cardData, "parameter_count"),
                    getString(cardData, "param_count"),
                    getString(cardData, "model_parameters"));
            }

            hits.add(new ModelHit(repoId, modelUrl, pipelineTag, lastModified,
                    libraryName, downloads, likes, parameters));
            if (hits.size() >= limit) break;
        }
    }

   // ---------------------------------------------------------------------------
    // Internal – repo tree (paginated)
    // ---------------------------------------------------------------------------

   private static List<GgufFile> crawlGgufTargeted(String base, String repoId, String revision,
                                                        int timeout)
            throws IOException {
        List<GgufFile> files = new ArrayList<>();
        String cursor = null;
        while (true) {
            InternalPage page = fetchTreeRecursive(base, repoId, revision, cursor, timeout);
            for (JsonElement el : page.entries) {
                if (el == null || !el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String type = getString(obj, "type");
                String path = getString(obj, "path");
                if (type == null || path == null) continue;

                if ("file".equalsIgnoreCase(type) && path.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
                    Long size = parseLongOrNull(getNumberString(obj, "size"));
                    JsonObject lfs = getObject(obj, "lfs");
                    String lfsOid = lfs != null ? getString(lfs, "oid") : null;
                    Long lfsSize = lfs != null ? parseLongOrNull(getNumberString(lfs, "size")) : null;
                    String downloadUrl = buildResolveUrl(base, repoId, revision, path);
                    files.add(new GgufFile(path, size, lfsOid, lfsSize, downloadUrl));
                }
            }
            cursor = page.nextCursor;
            if (cursor == null || cursor.isBlank()) break;
        }
        return files;
    }

    private static InternalPage fetchTreeRecursive(String base, String repoId, String revision,
                                                    String cursor, int timeout)
            throws IOException {
        return fetchTreePageImpl(base, repoId, revision, "", cursor, true, timeout);
    }

    private static InternalPage fetchTreePageImpl(String base, String repoId, String revision,
                                                   String path, String cursor, boolean recursive, int timeout)
            throws IOException {
        StringBuilder url = new StringBuilder();
        url.append(base).append("/api/models/").append(repoId)
           .append("/tree/").append(revision).append("/")
           .append(encodePathSegments(path))
           .append("?recursive=").append(recursive).append("&limit=1000");
        if (cursor != null && !cursor.isBlank())
            url.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8).replace("+", "%20"));

        NettyHttpUtils.Response resp = sendGet(url.toString(), timeout, "application/json");

        JsonElement root = JsonParser.parseString(resp.bodyAsString());
        JsonArray entries = root != null && root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();

        String nextCursor = resp.header("X-Next-Cursor");
        if (nextCursor == null || nextCursor.isBlank())
            nextCursor = parseCursorFromLinkHeader(resp.header("Link"));
        return new InternalPage(entries, nextCursor);
    }

    private static String detectReadmePath(JsonObject modelInfo) {
        JsonArray siblings = getArray(modelInfo, "siblings");
        if (siblings == null || siblings.isEmpty()) {
            return null;
        }
        String bestRoot = null;
        String bestNested = null;
        for (JsonElement el : siblings) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            String path = firstNonBlank(getString(obj, "rfilename"), getString(obj, "path"));
            if (path == null || path.isBlank()) continue;
            if (!looksLikeReadme(path)) continue;
            if (!path.contains("/")) {
                if (bestRoot == null || compareReadmePriority(path, bestRoot) < 0) {
                    bestRoot = path;
                }
            } else if (bestNested == null || compareReadmePriority(path, bestNested) < 0) {
                bestNested = path;
            }
        }
        return bestRoot != null ? bestRoot : bestNested;
    }

    private static String fetchReadmeByCandidates(String base, String repoId, String revision,
                                                  int timeout)
            throws IOException {
        for (String candidate : readmeCandidates()) {
            String readmeUrl = buildResolveUrl(base, repoId, revision, candidate);
            try {
                sendTextGet(readmeUrl, timeout,
                        "text/markdown, text/plain; charset=utf-8, */*");
                return candidate;
            } catch (IOException e) {
                if (!looksLikeNotFound(e)) {
                    throw e;
                }
            }
        }
        return null;
    }

    private static List<String> readmeCandidates() {
        return List.of(
            "README.md",
            "README.MD",
            "Readme.md",
            "readme.md",
            "README.markdown",
            "README.mdown",
            "README.mdx",
            "README.txt",
            "README"
        );
    }

    private static boolean looksLikeReadme(String path) {
        if (path == null || path.isBlank()) return false;
        String fileName = path;
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < fileName.length()) {
            fileName = fileName.substring(slash + 1);
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.equals("readme")
                || lower.equals("readme.md")
                || lower.equals("readme.markdown")
                || lower.equals("readme.mdown")
                || lower.equals("readme.mdx")
                || lower.equals("readme.txt");
    }

    private static int compareReadmePriority(String candidate, String baseline) {
        return Integer.compare(readmePriority(candidate), readmePriority(baseline));
    }

    private static int readmePriority(String path) {
        if (path == null) return Integer.MAX_VALUE;
        String fileName = path;
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < fileName.length()) {
            fileName = fileName.substring(slash + 1);
        }
        return switch (fileName.toLowerCase(Locale.ROOT)) {
        case "readme.md" -> 0;
        case "readme.markdown" -> 1;
        case "readme.mdown" -> 2;
        case "readme.mdx" -> 3;
        case "readme.txt" -> 4;
        case "readme" -> 5;
        default -> 10;
        };
    }

    private static String stripReadmeFrontMatter(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }
        String normalized = markdown.replace("\r\n", "\n");
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith("---\n")) {
            return markdown;
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            end = normalized.indexOf("\n...\n", 4);
        }
        if (end < 0) {
            return markdown;
        }
        String stripped = normalized.substring(end + 5);
        while (stripped.startsWith("\n")) {
            stripped = stripped.substring(1);
        }
        return stripped;
    }


    // ---------------------------------------------------------------------------
    // URL builders
    // ---------------------------------------------------------------------------

    private static String buildResolveUrl(String base, String repoId, String revision, String path) {
        // Per OpenAPI spec L808-831: GET /{namespace}/{repo}/resolve/{rev}/{path}
        String p = path;
        while (p.startsWith("/")) p = p.substring(1);
        return base + "/" + repoId + "/resolve/" + revision + "/" + encodePathSegments(p);
    }

    private static String encodePathSegments(String path) {
        if (path == null || path.isBlank()) return "";
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("/");
            sb.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------------

    private static NettyHttpUtils.Response sendGet(String urlStr, int timeout, String accept) throws IOException {
        NettyHttpUtils.Request req = NettyHttpUtils.request(urlStr)
                .header("User-Agent", UserAgentUtils.random())
                .header("Accept", accept == null || accept.isBlank() ? "*/*" : accept)
                .readTimeout(timeout);

        String token = System.getenv("HF_TOKEN");
        if (token != null && !token.isBlank())
            req.header("Authorization", "Bearer " + token.trim());

        return req.execute();
    }

    private static String sendTextGet(String urlStr, int timeout, String accept) throws IOException {
        return sendGet(urlStr, timeout, accept).bodyAsString();
    }

    // ---------------------------------------------------------------------------
    // Fallback revision logic
    // ---------------------------------------------------------------------------

    private static String findFallbackRevision(String revision) {
        if (revision == null) return null;
        if (looksLikeSha(revision)) return "main";
        if ("main".equalsIgnoreCase(revision)) return "master";
        if ("master".equalsIgnoreCase(revision)) return "main";
        return null;
    }

    private static boolean looksLikeSha(String s) {
        if (s == null || s.length() != 40) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))
                return false;
    }
        return true;
    }

    private static boolean looksLikeNotFound(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage();
        return msg != null && msg.contains("HTTP 404");
    }

    // ---------------------------------------------------------------------------
    // Link header cursor parser
    // ---------------------------------------------------------------------------

    private static String parseCursorFromLinkHeader(String link) {
        if (link == null || link.isBlank()) return null;
        for (String part : link.split(",")) {
            if (part == null) continue;
            String p = part.trim();
            if (!p.contains("rel=\"next\"")) continue;
            int start = p.indexOf('<');
            int end = p.indexOf('>');
            if (start < 0 || end <= start) continue;
            String url = p.substring(start + 1, end);
            try {
                String query = URI.create(url).getQuery();
                if (query == null) continue;
                for (String kv : query.split("&")) {
                    if (kv == null) continue;
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    if ("cursor".equals(kv.substring(0, eq))) {
                        String v = kv.substring(eq + 1);
                        return v.isBlank() ? null : v;
                    }
                }
            } catch (Exception ignored) {
                int idx = url.indexOf("cursor=");
                if (idx >= 0) {
                    String v = url.substring(idx + "cursor=".length());
                    int amp = v.indexOf('&');
                    if (amp >= 0) v = v.substring(0, amp);
                    return v.isBlank() ? null : v;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // Base URL resolution
    // ---------------------------------------------------------------------------

    public static String resolveBaseUrl(String baseUrlOrChoice) {
        if (baseUrlOrChoice != null) {
            String v = baseUrlOrChoice.trim();
            if (!v.isEmpty()) {
                String lower = v.toLowerCase(Locale.ROOT);
                if (lower.equals("mirror") || lower.equals("hf-mirror") || lower.equals("hf-mirror.com"))
                    return HF_MIRROR_BASE;
                if (lower.equals("official") || lower.equals("huggingface") || lower.equals("huggingface.co"))
                    return HF_BASE;
                if (lower.equals(HF_MIRROR_BASE) || lower.equals(HF_BASE))
                    return lower;
                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    try {
                        String host = URI.create(v).getHost();
                        if (host != null) {
                            String h = host.trim().toLowerCase(Locale.ROOT);
                            if (h.equals("hf-mirror.com")) return HF_MIRROR_BASE;
                            if (h.endsWith("huggingface.co")) return HF_BASE;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        String env = System.getenv("HF_BASE_URL");
        if (env != null && !env.isBlank()) {
            String e = env.trim();
            if (e.endsWith("/")) e = e.substring(0, e.length() - 1);
            if (e.equalsIgnoreCase(HF_BASE) || e.equalsIgnoreCase(HF_MIRROR_BASE))
                return e;
        }
        return HF_MIRROR_BASE;
    }

    // ---------------------------------------------------------------------------
    // Repo ID normalization
    // ---------------------------------------------------------------------------

    private static String normalizeRepoId(String input) {
        if (input == null || input.isBlank()) return null;
        String s = input.trim();
        while (s.startsWith("/")) s = s.substring(1);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int f = s.indexOf('#');
        if (f >= 0) s = s.substring(0, f);

        if (s.startsWith("http://") || s.startsWith("https://")) {
            try {
                URI uri = URI.create(s);
                String host = uri.getHost();
                if (host == null) return null;
                String h = host.toLowerCase(Locale.ROOT);
                if (!h.equals("hf-mirror.com") && !h.endsWith("huggingface.co"))
                    return null;
                String path = uri.getPath();
                if (path == null) return null;
                while (path.startsWith("/")) path = path.substring(1);
                String[] seg = path.split("/");
                if (seg.length < 2 || seg[0].isBlank() || seg[1].isBlank())
                    return null;
                return seg[0] + "/" + seg[1];
            } catch (Exception e) {
                return null;
            }
        }

        String[] parts = s.split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank())
            return null;
        return parts[0] + "/" + parts[1];
    }

    // ---------------------------------------------------------------------------
    // JSON helpers
    // ---------------------------------------------------------------------------

    private static String getString(JsonObject obj, String key) {
        if (obj == null) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        try { return el.getAsString(); } catch (Exception e) { return null; }
    }

    private static String getNumberString(JsonObject obj, String key) {
        if (obj == null) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        try { return Objects.toString(el.getAsNumber()); } catch (Exception e) { return null; }
    }

    private static JsonObject getObject(JsonObject obj, String key) {
        if (obj == null) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonObject()) return null;
        return el.getAsJsonObject();
    }

    private static JsonArray getArray(JsonObject obj, String key) {
        if (obj == null) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonArray()) return null;
        return el.getAsJsonArray();
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v == null) continue;
            String s = v.trim();
            if (!s.isBlank()) return s;
        }
        return null;
    }

    private HuggingFaceSearcher() {}
}

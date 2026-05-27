package org.mark.llamacpp.crawler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


/**
 * 	针对huggingface的爬虫。
 */
public final class HuggingFaceModelCrawler {
	
	/**
	 * 两个域名
	 */
	private static final String HF_BASE = "https://huggingface.co";
	private static final String HF_MIRROR_BASE = "https://hf-mirror.com";
	
	/**
	 * 	默认分页数量
	 */
	private static final int HF_SEARCH_PAGE_SIZE = 30;

	public record GGUFFileInfo(String path, Long size, String lfsOid, Long lfsSize, String downloadUrl) {
	}

	public record GGUFCrawlResult(String repoId, String revision, List<GGUFFileInfo> ggufFiles, String treeError) {
	}

	public record ModelSearchHit(String repoId, String modelUrl, String pipelineTag, String lastModified,
			String libraryName, Long downloads, Long likes, String parameters) {
	}

	public record ModelSearchResult(String query, List<ModelSearchHit> hits) {
	}
	
	/**
	 * 创建支持代理的 HttpClient
	 */
	private static HttpClient createHttpClient(int timeoutSeconds) {
		HttpClient.Builder builder = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(timeoutSeconds))
				.followRedirects(HttpClient.Redirect.NORMAL);
		
		// 添加代理支持
		java.net.Proxy proxy = org.mark.llamacpp.server.LlamaServer.getProxy();
		if (proxy != null) {
			builder.proxy(java.net.ProxySelector.of(proxy.address()));
		}
		
		return builder.build();
	}

	public static GGUFCrawlResult crawlGGUFFiles(String modelUrlOrRepoId) throws IOException, InterruptedException {
		return crawlGGUFFiles(modelUrlOrRepoId, 20);
	}

	public static GGUFCrawlResult crawlGGUFFiles(String modelUrlOrRepoId, int timeoutSeconds)
			throws IOException, InterruptedException {
		return crawlGGUFFiles(modelUrlOrRepoId, timeoutSeconds, null);
	}

	public static GGUFCrawlResult crawlGGUFFiles(String modelUrlOrRepoId, int timeoutSeconds, String baseUrlOrChoice)
			throws IOException, InterruptedException {
		if (modelUrlOrRepoId == null || modelUrlOrRepoId.isBlank())
			throw new IllegalArgumentException("modelUrlOrRepoId 不能为空");
		String baseUrl = pickBaseUrl(modelUrlOrRepoId, baseUrlOrChoice);
		String repoId = normalizeToRepoId(modelUrlOrRepoId);
		if (repoId == null)
			throw new IllegalArgumentException("无法从输入解析出 repoId： " + modelUrlOrRepoId);

		JsonObject model = fetchModelInfo(baseUrl, repoId, Math.max(1, timeoutSeconds));
		String revision = getString(model, "sha");
		if (revision == null || revision.isBlank())
			revision = "main";

		JsonArray tree;
		String treeError = null;
		try {
			tree = fetchRepoTree(baseUrl, repoId, revision, Math.max(1, timeoutSeconds));
		} catch (Exception e) {
			boolean fetched = false;
			JsonArray fallbackTree = null;
			String fallbackRevision = null;
			List<String> candidates = new ArrayList<>();
			if (looksLikeSha(revision)) {
				candidates.add("main");
				candidates.add("master");
			} else if ("main".equalsIgnoreCase(revision)) {
				candidates.add("master");
			} else if ("master".equalsIgnoreCase(revision)) {
				candidates.add("main");
			}
			for (String candidate : candidates) {
				try {
					fallbackTree = fetchRepoTree(baseUrl, repoId, candidate, Math.max(1, timeoutSeconds));
					fallbackRevision = candidate;
					fetched = true;
					break;
				} catch (Exception ignored) {
				}
			}
			if (fetched && fallbackTree != null) {
				tree = fallbackTree;
				revision = fallbackRevision;
			} else {
				tree = new JsonArray();
				treeError = e.getMessage();
			}
		}

		List<GGUFFileInfo> ggufFiles = extractGGUFFiles(baseUrl, repoId, revision, tree);
		return new GGUFCrawlResult(repoId, revision, ggufFiles, treeError);
	}

	public static ModelSearchResult searchModels(String query) throws IOException, InterruptedException {
		return searchModels(query, 30, 20);
	}

	public static ModelSearchResult searchModels(String query, int limit, int timeoutSeconds)
			throws IOException, InterruptedException {
		return searchModels(query, limit, timeoutSeconds, 0, 0);
	}

	public static ModelSearchResult searchModels(String query, int limit, int timeoutSeconds, int startPage,
			int maxPages) throws IOException, InterruptedException {
		return searchModels(query, limit, timeoutSeconds, startPage, maxPages, null);
	}

	public static ModelSearchResult searchModels(String query, int limit, int timeoutSeconds, int startPage, int maxPages,
			String baseUrlOrChoice) throws IOException, InterruptedException {
		if (query == null || query.isBlank())
			throw new IllegalArgumentException("query 不能为空");
		int safeLimit = Math.max(1, Math.min(200, limit));
		int safeTimeout = Math.max(1, timeoutSeconds);

		String baseUrl = pickBaseUrl(null, baseUrlOrChoice);

		int estimatedPages = Math.min(30, Math.max(1, (safeLimit + 29) / HF_SEARCH_PAGE_SIZE) + 3);
		int safeStartPage = Math.max(0, startPage);
		int safeMaxPages = maxPages > 0 ? maxPages : estimatedPages;

		Set<String> seen = new LinkedHashSet<>();
		List<ModelSearchHit> hits = new ArrayList<>();

		String cursor = null;
		for (int i = 0; i < safeStartPage; i++) {
			SearchApiPage page = fetchModelsSearchApiPage(baseUrl, query, cursor, HF_SEARCH_PAGE_SIZE, safeTimeout);
			cursor = page.nextCursor();
			if (cursor == null || cursor.isBlank()) {
				return new ModelSearchResult(query, hits);
			}
		}

		int consecutiveNoNew = 0;
		for (int fetchedPages = 0; hits.size() < safeLimit && fetchedPages < safeMaxPages
				&& consecutiveNoNew < 2; fetchedPages++) {
			int before = seen.size();

			SearchApiPage page = fetchModelsSearchApiPage(baseUrl, query, cursor, HF_SEARCH_PAGE_SIZE, safeTimeout);
			collectModelSearchHits(page.models(), safeLimit, seen, hits, baseUrl);

			cursor = page.nextCursor();
			if (seen.size() == before) {
				consecutiveNoNew++;
			} else {
				consecutiveNoNew = 0;
			}
			if (cursor == null || cursor.isBlank())
				break;
		}

		return new ModelSearchResult(query, hits);
	}

	private static JsonObject fetchModelInfo(String baseUrl, String repoId, int timeoutSeconds)
			throws IOException, InterruptedException {
		HttpClient client = createHttpClient(timeoutSeconds);

		URI uri = URI.create(baseUrl + "/api/models/" + repoId);
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(uri)
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("User-Agent", "hf-model-crawler/0.0.1 (+https://huggingface.co)")
				.header("Accept", "application/json");

		String token = System.getenv("HF_TOKEN");
		if (token != null && !token.isBlank()) {
			requestBuilder.header("Authorization", "Bearer " + token.trim());
		}

		HttpResponse<String> response = client.send(requestBuilder.GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			String bodyPreview = response.body() == null ? "" : response.body();
			if (bodyPreview.length() > 800)
				bodyPreview = bodyPreview.substring(0, 800) + "...";
			throw new IOException("请求失败: HTTP " + response.statusCode() + " " + uri + "\n" + bodyPreview);
		}

		JsonElement root = JsonParser.parseString(response.body());
		if (!root.isJsonObject())
			throw new IOException("响应不是 JSON 对象: " + uri);
		return root.getAsJsonObject();
	}

	private static JsonArray fetchRepoTree(String baseUrl, String repoId, String revision, int timeoutSeconds)
			throws IOException, InterruptedException {
		HttpClient client = createHttpClient(timeoutSeconds);
		URI uri = URI.create(baseUrl + "/api/models/" + repoId + "/tree/" + revision + "?recursive=1");
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(uri)
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("User-Agent", "hf-model-crawler/0.0.1 (+https://huggingface.co)")
				.header("Accept", "application/json");
		String token = System.getenv("HF_TOKEN");
		if (token != null && !token.isBlank()) {
			requestBuilder.header("Authorization", "Bearer " + token.trim());
		}
		HttpResponse<String> response = client.send(requestBuilder.GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			String bodyPreview = response.body() == null ? "" : response.body();
			if (bodyPreview.length() > 800)
				bodyPreview = bodyPreview.substring(0, 800) + "...";
			URI fallbackUri = URI.create(baseUrl + "/api/models/" + repoId + "/tree/" + revision);
			HttpRequest.Builder fallbackBuilder = HttpRequest.newBuilder().uri(fallbackUri)
					.timeout(Duration.ofSeconds(timeoutSeconds))
					.header("User-Agent", "hf-model-crawler/0.0.1 (+https://huggingface.co)")
					.header("Accept", "application/json");
			if (token != null && !token.isBlank()) {
				fallbackBuilder.header("Authorization", "Bearer " + token.trim());
			}
			HttpResponse<String> fallbackResponse = client.send(fallbackBuilder.GET().build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (fallbackResponse.statusCode() >= 200 && fallbackResponse.statusCode() < 300) {
				JsonElement fallbackRoot = JsonParser.parseString(fallbackResponse.body());
				if (fallbackRoot.isJsonArray())
					return fallbackRoot.getAsJsonArray();
				return new JsonArray();
			}
			throw new IOException("请求失败: HTTP " + response.statusCode() + " " + uri + "\n" + bodyPreview);
		}
		JsonElement root = JsonParser.parseString(response.body());
		if (!root.isJsonArray())
			return new JsonArray();
		return root.getAsJsonArray();
	}

	private record SearchApiPage(JsonArray models, String nextCursor) {
	}

	private static SearchApiPage fetchModelsSearchApiPage(String baseUrl, String query, String cursor, int limit,
			int timeoutSeconds) throws IOException, InterruptedException {
		HttpClient client = createHttpClient(timeoutSeconds);

		String q = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
		StringBuilder url = new StringBuilder();
		url.append(baseUrl).append("/api/models?search=").append(q).append("&filter=gguf&full=true&limit=")
				.append(Math.max(1, limit));
		if (cursor != null && !cursor.isBlank()) {
			String c = URLEncoder.encode(cursor, StandardCharsets.UTF_8).replace("+", "%20");
			url.append("&cursor=").append(c);
		}
		URI uri = URI.create(url.toString());
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(uri)
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("User-Agent", "hf-model-crawler/0.0.1 (+https://huggingface.co)")
				.header("Accept", "application/json");

		String token = System.getenv("HF_TOKEN");
		if (token != null && !token.isBlank()) {
			requestBuilder.header("Authorization", "Bearer " + token.trim());
		}

		HttpResponse<String> response = client.send(requestBuilder.GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			String bodyPreview = response.body() == null ? "" : response.body();
			if (bodyPreview.length() > 800)
				bodyPreview = bodyPreview.substring(0, 800) + "...";
			throw new IOException("请求失败: HTTP " + response.statusCode() + " " + uri + "\n" + bodyPreview);
		}

		JsonElement root = JsonParser.parseString(response.body() == null ? "" : response.body());
		JsonArray models = root != null && root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
		String nextCursor = response.headers().firstValue("X-Next-Cursor").orElse(null);
		if (nextCursor == null || nextCursor.isBlank()) {
			nextCursor = parseNextCursorFromLinkHeader(response.headers().firstValue("Link").orElse(null));
		}
		return new SearchApiPage(models, nextCursor);
	}

	private static void collectModelSearchHits(JsonArray models, int limit, Set<String> seen, List<ModelSearchHit> hits,
			String baseUrl) {
		if (models == null || models.isEmpty())
			return;
		int safeLimit = Math.max(1, limit);
		if (hits.size() >= safeLimit)
			return;
		for (JsonElement el : models) {
			if (el == null || !el.isJsonObject())
				continue;
			JsonObject obj = el.getAsJsonObject();
			String repoId = getString(obj, "modelId");
			if (repoId == null || repoId.isBlank())
				repoId = getString(obj, "id");
			if (repoId == null || repoId.isBlank())
				continue;
			if (!seen.add(repoId))
				continue;

			String modelUrl = baseUrl + "/" + repoId;
			String pipelineTag = getString(obj, "pipeline_tag");
			String lastModified = getString(obj, "lastModified");
			String libraryName = getString(obj, "library_name");
			Long downloads = parseLongOrNull(getNumberString(obj, "downloads"));
			Long likes = parseLongOrNull(getNumberString(obj, "likes"));
			String parameters = null;
			JsonObject cardData = getObject(obj, "cardData");
			if (cardData != null) {
				parameters = firstNonBlank(getString(cardData, "parameters"), getString(cardData, "parameter_count"),
						getString(cardData, "param_count"), getString(cardData, "model_parameters"));
			}

			hits.add(new ModelSearchHit(repoId, modelUrl, pipelineTag, lastModified, libraryName, downloads, likes,
					parameters));
			if (hits.size() >= safeLimit)
				break;
		}
	}

	private static String normalizeToRepoId(String input) {
		String trimmed = input.trim();

		if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
			try {
				URI uri = URI.create(trimmed);
				if (uri.getHost() == null)
					return null;
				if (!isSupportedHost(uri.getHost()))
					return null;
				String path = Optional.ofNullable(uri.getPath()).orElse("");
				while (path.startsWith("/"))
					path = path.substring(1);
				if (path.isBlank())
					return null;
				String[] segments = path.split("/");
				if (segments.length < 2)
					return null;
				String owner = segments[0];
				String name = segments[1];
				if (owner.isBlank() || name.isBlank())
					return null;
				return owner + "/" + name;
			} catch (Exception e) {
				return null;
			}
		}

		String noPrefix = trimmed;
		while (noPrefix.startsWith("/"))
			noPrefix = noPrefix.substring(1);
		if (noPrefix.contains("?"))
			noPrefix = noPrefix.substring(0, noPrefix.indexOf('?'));
		if (noPrefix.contains("#"))
			noPrefix = noPrefix.substring(0, noPrefix.indexOf('#'));
		if (!noPrefix.contains("/"))
			return null;
		String[] parts = noPrefix.split("/");
		if (parts.length < 2)
			return null;
		String owner = parts[0];
		String name = parts[1];
		if (owner.isBlank() || name.isBlank())
			return null;
		return owner + "/" + name;
	}

	private static String parseNextCursorFromLinkHeader(String link) {
		if (link == null || link.isBlank())
			return null;
		String[] parts = link.split(",");
		for (String part : parts) {
			if (part == null)
				continue;
			String p = part.trim();
			if (!p.contains("rel=\"next\""))
				continue;
			int start = p.indexOf('<');
			int end = p.indexOf('>');
			if (start < 0 || end <= start)
				continue;
			String url = p.substring(start + 1, end);
			try {
				URI uri = URI.create(url);
				String query = uri.getQuery();
				if (query == null || query.isBlank())
					continue;
				String[] params = query.split("&");
				for (String kv : params) {
					if (kv == null)
						continue;
					int eq = kv.indexOf('=');
					if (eq <= 0)
						continue;
					String k = kv.substring(0, eq);
					if (!"cursor".equals(k))
						continue;
					String v = kv.substring(eq + 1);
					return v.isBlank() ? null : v;
				}
			} catch (Exception ignored) {
				int idx = url.indexOf("cursor=");
				if (idx >= 0) {
					String v = url.substring(idx + "cursor=".length());
					int amp = v.indexOf('&');
					if (amp >= 0)
						v = v.substring(0, amp);
					return v.isBlank() ? null : v;
				}
			}
		}
		return null;
	}

	private static String firstNonBlank(String... values) {
		if (values == null)
			return null;
		for (String v : values) {
			if (v == null)
				continue;
			String s = v.trim();
			if (!s.isBlank())
				return s;
		}
		return null;
	}

	private static List<GGUFFileInfo> extractGGUFFiles(String baseUrl, String repoId, String revision, JsonArray tree) {
		List<GGUFFileInfo> gguf = new ArrayList<>();
		for (JsonElement el : tree) {
			if (el == null || !el.isJsonObject())
				continue;
			JsonObject obj = el.getAsJsonObject();
			String type = getString(obj, "type");
			String path = getString(obj, "path");
			if (type == null || path == null)
				continue;
			if (!"file".equalsIgnoreCase(type))
				continue;
			if (!path.toLowerCase(Locale.ROOT).endsWith(".gguf"))
				continue;
			Long size = parseLongOrNull(getNumberString(obj, "size"));
			JsonObject lfs = getObject(obj, "lfs");
			String lfsOid = null;
			Long lfsSize = null;
			if (lfs != null) {
				lfsOid = getString(lfs, "oid");
				lfsSize = parseLongOrNull(getNumberString(lfs, "size"));
			}
			String url = buildResolveUrl(baseUrl, repoId, revision, path);
			gguf.add(new GGUFFileInfo(path, size, lfsOid, lfsSize, url));
		}
		return gguf;
	}

	private static String buildResolveUrl(String baseUrl, String repoId, String revision, String path) {
		String p = path;
		while (p.startsWith("/"))
			p = p.substring(1);
		return baseUrl + "/" + repoId + "/resolve/" + revision + "/" + encodePathPreserveSlashes(p);
	}

	private static String encodePathPreserveSlashes(String path) {
		if (path == null || path.isBlank())
			return "";
		String[] parts = path.split("/");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0)
				sb.append("/");
			String seg = parts[i];
			String enc = URLEncoder.encode(seg, StandardCharsets.UTF_8);
			enc = enc.replace("+", "%20");
			sb.append(enc);
		}
		return sb.toString();
	}

	private static boolean looksLikeSha(String revision) {
		if (revision == null)
			return false;
		String r = revision.trim();
		if (r.length() != 40)
			return false;
		for (int i = 0; i < r.length(); i++) {
			char c = r.charAt(i);
			boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
			if (!ok)
				return false;
		}
		return true;
	}

	private static Long parseLongOrNull(String value) {
		if (value == null)
			return null;
		String v = value.trim();
		if (v.isBlank())
			return null;
		try {
			return Long.parseLong(v);
		} catch (Exception e) {
			return null;
		}
	}

	private static String getString(JsonObject obj, String key) {
		if (obj == null)
			return null;
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull())
			return null;
		if (!el.isJsonPrimitive())
			return null;
		try {
			return el.getAsString();
		} catch (Exception e) {
			return null;
		}
	}

	private static String getNumberString(JsonObject obj, String key) {
		if (obj == null)
			return null;
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull())
			return null;
		if (!el.isJsonPrimitive())
			return null;
		try {
			return Objects.toString(el.getAsNumber());
		} catch (Exception e) {
			return null;
		}
	}

	private static JsonObject getObject(JsonObject obj, String key) {
		if (obj == null)
			return null;
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull() || !el.isJsonObject())
			return null;
		return el.getAsJsonObject();
	}

	private static boolean isSupportedHost(String host) {
		if (host == null || host.isBlank())
			return false;
		String h = host.trim().toLowerCase(Locale.ROOT);
		return h.equals("hf-mirror.com") || h.endsWith("huggingface.co");
	}

	private static String pickBaseUrl(String input, String baseUrlOrChoice) {
		String forced = normalizeBaseUrlOrChoice(baseUrlOrChoice);
		if (forced != null)
			return forced;
		return determineBaseUrlFromInputOrDefault(input);
	}

	private static String determineBaseUrlFromInputOrDefault(String input) {
		if (input == null)
			return getDefaultBaseUrl();
		String trimmed = input.trim();
		if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
			try {
				URI uri = URI.create(trimmed);
				String host = uri.getHost();
				if (host != null && host.trim().equalsIgnoreCase("hf-mirror.com"))
					return HF_MIRROR_BASE;
				if (host != null && host.trim().toLowerCase(Locale.ROOT).endsWith("huggingface.co"))
					return HF_BASE;
			} catch (Exception ignored) {
			}
		}
		return getDefaultBaseUrl();
	}

	private static String normalizeBaseUrlOrChoice(String baseUrlOrChoice) {
		if (baseUrlOrChoice == null)
			return null;
		String v = baseUrlOrChoice.trim();
		if (v.isEmpty())
			return null;

		String lower = v.toLowerCase(Locale.ROOT);
		if (lower.equals("mirror") || lower.equals("hf-mirror") || lower.equals("hf-mirror.com"))
			return HF_MIRROR_BASE;
		if (lower.equals("official") || lower.equals("huggingface") || lower.equals("huggingface.co"))
			return HF_BASE;

		if (lower.equals(HF_MIRROR_BASE) || lower.equals(HF_BASE))
			return lower;

		if (lower.startsWith("http://") || lower.startsWith("https://")) {
			try {
				URI uri = URI.create(v);
				String host = uri.getHost();
				if (host == null || !isSupportedHost(host))
					return null;
				if (host.trim().equalsIgnoreCase("hf-mirror.com"))
					return HF_MIRROR_BASE;
				if (host.trim().toLowerCase(Locale.ROOT).endsWith("huggingface.co"))
					return HF_BASE;
			} catch (Exception ignored) {
				return null;
			}
		}
		return null;
	}

	private static String getDefaultBaseUrl() {
		String env = System.getenv("HF_BASE_URL");
		if (env != null && !env.isBlank()) {
			String v = env.trim();
			if (v.endsWith("/"))
				v = v.substring(0, v.length() - 1);
			if (v.equalsIgnoreCase(HF_BASE) || v.equalsIgnoreCase(HF_MIRROR_BASE))
				return v;
		}
		return HF_MIRROR_BASE;
	}
}

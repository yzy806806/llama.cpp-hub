package org.mark.test.mcp.tools.file;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;

import com.google.gson.JsonObject;

/**
 * 	写文件的小工具。
 *
 * 	安全防护:
 * 	- 所有写入路径必须在 fallbackRootPath（user.dir）范围内
 * 	- 禁止写入敏感系统目录（Unix: /etc, /proc, /sys 等; Windows: \Windows\System32 等）
 * 	- 防止路径遍历攻击（../../etc/crontab）
 * 	- 防止符号链接绕过（normalize() 不解析符号链接，使用 toRealPath() 二次验证）
 */
public class WriteTextFileTool implements IMCPTool {

	private final Path fallbackRootPath;
	private final Path homeDirPath;
	private final boolean windowsRuntime;

	public WriteTextFileTool() {
		this.fallbackRootPath = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
		this.windowsRuntime = File.separatorChar == '\\';
		this.homeDirPath = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
	}

	@Override
	public String getMcpName() {
		return "write_text_file";
	}

	@Override
	public String getMcpTitle() {
		return "写入文本文件";
	}

	@Override
	public String getMcpDescription() {
		return "将文本内容写入文本文件，支持 txt、md、html、js 等文本格式文件，支持覆盖写入或追加写入。"
				+ "优先传入明确的绝对路径；如果你不知道保存到哪里，就保存到根目录下，根目录路径为 " + this.fallbackRootPath
				+ " 。工具支持 Windows 和 Linux 的常见路径写法；如果传入的是相对路径，会自动按根目录解析。"
				+ "如果目标路径中包含不存在的文件夹，工具会自动创建所需的父目录。";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema()
				.addProperty("absolutePath", "string",
						"目标文件路径。支持 Windows/Linux 绝对路径；如果不确定目录结构，也可以传相对路径，工具会默认保存到根目录 "
								+ this.fallbackRootPath + " 下。例如 README.md、output/result.txt、C:\\temp\\note.md",
						true)
				.addProperty("content", "string", "要写入的文本内容（UTF-8）", true).addProperty("append", "boolean", "是否追加写入，默认false", false)
				.addProperty("createParentDirectories", "boolean", "是否自动创建父目录，默认true；路径中有不存在的文件夹时建议保持默认值", false);
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		String content = this.getContent(arguments);
		if (content == null) {
			return new McpMessage().addText(JsonUtil.toJson(this.error("content不能为空")));
		}
		String absolutePathText = JsonUtil.getJsonString(arguments, "absolutePath");
		boolean append = this.getBoolean(arguments, "append", false);
		boolean createParentDirectories = this.getBoolean(arguments, "createParentDirectories", true);
		try {
			Path filePath = this.resolveFilePath(absolutePathText);
			this.checkPathAllowed(filePath);
			Path parent = filePath.getParent();
			if (parent != null && createParentDirectories) {
				Files.createDirectories(parent);
			}
			if (append) {
				Files.writeString(filePath, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
						StandardOpenOption.WRITE);
			} else {
				Files.writeString(filePath, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
						StandardOpenOption.WRITE);
			}
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("success", true);
			response.put("path", filePath.toString());
			response.put("rootPath", this.fallbackRootPath.toString());
			response.put("mode", append ? "append" : "overwrite");
			response.put("byteSize", content.getBytes(StandardCharsets.UTF_8).length);
			return new McpMessage().addText(JsonUtil.toJson(response));
		} catch (IOException e) {
			return new McpMessage().addText(JsonUtil.toJson(this.error("写入失败: " + e.getMessage())));
		} catch (Exception e) {
			return new McpMessage().addText(JsonUtil.toJson(this.error("路径非法或参数错误: " + e.getMessage())));
		}
	}

	private Path resolveFilePath(String pathText) {
		if (pathText == null || pathText.trim().isEmpty()) {
			throw new IllegalArgumentException("absolutePath不能为空。如果你不知道保存到哪里，请至少提供文件名，工具会默认保存到根目录: "
					+ this.fallbackRootPath);
		}
		String normalizedText = pathText.trim();
		if (this.isRuntimeAbsolutePath(normalizedText)) {
			Path resolved = Paths.get(normalizedText).toAbsolutePath().normalize();
			// Absolute path: still must be within allowed root
			if (!resolved.startsWith(this.fallbackRootPath)) {
				throw new SecurityException("写入被拒绝: 绝对路径 " + normalizedText + " 不在允许的目录 " + this.fallbackRootPath + " 范围内");
			}
			// Symlink resolution: resolve symbolic links and re-validate containment
			this.validateSymlinkContainment(resolved);
			return resolved;
		}
		if (this.looksLikeForeignAbsolutePath(normalizedText)) {
			throw new IllegalArgumentException("当前系统无法解析该绝对路径: " + normalizedText + "，请改用当前系统的绝对路径，或传相对路径保存到根目录 "
					+ this.fallbackRootPath);
		}
		Path resolved = this.fallbackRootPath.resolve(normalizedText).toAbsolutePath().normalize();
		// Containment check: resolved path must stay within fallbackRootPath
		if (!resolved.startsWith(this.fallbackRootPath)) {
			throw new IllegalArgumentException("路径越界: " + normalizedText + " 超出了允许的根目录范围 " + this.fallbackRootPath);
		}
		// Symlink resolution: resolve symbolic links and re-validate containment
		this.validateSymlinkContainment(resolved);
		return resolved;
	}

	/**
	 * 解析符号链接并重新验证路径包含性。
	 * normalize() 不解析符号链接，攻击者可在允许目录内创建符号链接指向外部敏感文件。
	 * 对已存在的路径使用 toRealPath() 解析；对新文件（尚不存在）使用父目录的 toRealPath()。
	 */
	private void validateSymlinkContainment(Path resolved) {
		try {
			if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
				// Path already exists: resolve the full real path
				Path realPath = resolved.toRealPath();
				if (!realPath.startsWith(this.fallbackRootPath)) {
					throw new SecurityException("写入被拒绝: 符号链接解析后路径 " + realPath + " 不在允许的目录 " + this.fallbackRootPath + " 范围内");
				}
			} else {
				// New file: resolve the nearest existing parent directory
				Path parent = resolved.getParent();
				if (parent != null) {
					Path realParent = parent.toRealPath();
					if (!realParent.startsWith(this.fallbackRootPath)) {
						throw new SecurityException("写入被拒绝: 父目录符号链接解析后路径 " + realParent + " 不在允许的目录 " + this.fallbackRootPath + " 范围内");
					}
				}
			}
		} catch (IOException e) {
			// If toRealPath() fails (e.g. broken symlink, permission denied), block the write
			throw new SecurityException("写入被拒绝: 无法验证路径安全性 (" + e.getMessage() + "): " + resolved);
		}
	}

	private boolean isRuntimeAbsolutePath(String pathText) {
		if (pathText == null || pathText.trim().isEmpty()) {
			return false;
		}
		if (this.windowsRuntime) {
			return this.isWindowsAbsolutePath(pathText);
		}
		return this.isLinuxAbsolutePath(pathText);
	}

	private boolean looksLikeForeignAbsolutePath(String pathText) {
		if (pathText == null || pathText.trim().isEmpty()) {
			return false;
		}
		if (this.windowsRuntime) {
			return this.isLinuxAbsolutePath(pathText);
		}
		return this.isWindowsAbsolutePath(pathText);
	}

	private boolean isWindowsAbsolutePath(String pathText) {
		return pathText.matches("^[a-zA-Z]:[\\\\/].*") || pathText.startsWith("\\\\");
	}

	private boolean isLinuxAbsolutePath(String pathText) {
		return pathText.startsWith("/");
	}

	private String getContent(JsonObject arguments) {
		if (arguments == null || !arguments.has("content") || arguments.get("content").isJsonNull()) {
			return null;
		}
		try {
			return arguments.get("content").getAsString();
		} catch (Exception e) {
			return null;
		}
	}

	private boolean getBoolean(JsonObject arguments, String key, boolean fallback) {
		if (arguments == null || key == null || key.trim().isEmpty() || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return arguments.get(key).getAsBoolean();
		} catch (Exception e) {
			return fallback;
		}
	}

	private Map<String, Object> error(String message) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("success", false);
		response.put("error", message == null ? "" : message);
		return response;
	}

	/**
	 * 检查解析后的路径是否指向敏感系统目录（防御性深度检查）。
	 * 即使 startsWith(fallbackRootPath) 通过，也不允许写入关键系统路径。
	 * 覆盖 Unix 和 Windows 系统目录。
	 */
	private boolean isSensitiveSystemPath(Path filePath) {
		String path = filePath.toAbsolutePath().normalize().toString();
		// Unix sensitive paths
		if (path.startsWith("/etc") || path.startsWith("/proc") || path.startsWith("/sys")
				|| path.startsWith("/boot") || path.startsWith("/dev") || path.startsWith("/run")) {
			return true;
		}
		// Windows sensitive paths
		if (this.windowsRuntime) {
			String upper = path.toUpperCase();
			if (upper.contains("\\WINDOWS\\SYSTEM32") || upper.contains("\\WINDOWS\\SYSTEM")
					|| upper.contains("\\WINDOWS\\SYSWOW64") || upper.contains("\\WINDOWS\\SYSVOL")
					|| upper.contains("\\PROGRAMDATA") || upper.contains("\\PROGRAM FILES\\")
					|| upper.contains("\\PROGRAM FILES (X86)\\")) {
				return true;
			}
		}
		if (this.homeDirPath != null) {
			Path sshDir = this.homeDirPath.resolve(".ssh").normalize();
			if (filePath.startsWith(sshDir)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 安全检查：验证路径在允许范围内且不是敏感系统路径。
	 * 在 execute() 中文件写入前调用。
	 */
	private void checkPathAllowed(Path filePath) {
		// Primary check: must be within allowed root
		if (!filePath.startsWith(this.fallbackRootPath)) {
			throw new SecurityException("写入被拒绝: 路径 " + filePath + " 不在允许的目录 " + this.fallbackRootPath + " 范围内");
		}
		// Defense-in-depth: block sensitive system directories
		if (isSensitiveSystemPath(filePath)) {
			throw new SecurityException("写入被拒绝: 不允许写入系统敏感路径 " + filePath);
		}
	}
}

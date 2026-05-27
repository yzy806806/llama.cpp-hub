package org.mark.test.mcp.tools.others;
 
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
 
import org.mark.llamacpp.server.LlamaServer;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;
 
import com.google.gson.JsonObject;
 
public class ApplicationConfigTool implements IMCPTool {
 
	@Override
	public String getMcpName() {
		return "application_config_tool";
	}
 
	@Override
	public String getMcpTitle() {
		return "Application Config Tool";
	}
 
	@Override
	public String getMcpDescription() {
		return "获取、备份 llama.cpp-hub 的配置信息。";
	}
 
	@Override
	public McpToolInputSchema getInputSchema() {
		McpToolInputSchema schema = new McpToolInputSchema();
		schema.addProperty("action", "string", "The action to perform: 'read' or 'backup'", true);
		schema.addProperty("backupName", "string", "Optional custom name for the backup file (e.g., 'my_backup.json'). If not provided, defaults to 'application.json.bak'", false);
		return schema;
	}
 
	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		McpMessage message = new McpMessage();
		String action = arguments.has("action") ? arguments.get("action").getAsString() : "";
 
		if ("read".equals(action)) {
			JsonObject config = LlamaServer.readApplicationConfig();
			if (config == null || config.entrySet().isEmpty()) {
				message.addText("The application.json configuration file is empty or could not be read.");
			} else {
				message.addText("Current application.json configuration:\n" + config.toString());
			}
		} else if ("backup".equals(action)) {
			String backupName = arguments.has("backupName") ? arguments.get("backupName").getAsString() : "application.json.bak";
			if (!backupName.endsWith(".json") && !backupName.endsWith(".bak")) {
				backupName += ".json";
			}
			
			try {
				Path source = Paths.get("config/application.json");
				Path target = Paths.get("config", backupName);
				
				if (!Files.exists(source)) {
					message.addText("Error: application.json does not exist. Cannot create backup.");
					return message;
				}
				
				Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
				message.addText("Backup successfully created at: " + target.toString());
			} catch (IOException e) {
				message.addText("Error creating backup: " + e.getMessage());
			}
		} else {
			message.addText("Invalid action. Please use 'read' or 'backup'.");
		}
 
		return message;
	}
}

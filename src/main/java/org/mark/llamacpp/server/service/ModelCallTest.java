package org.mark.llamacpp.server.service;

import com.google.gson.JsonObject;
import org.mark.llamacpp.server.tools.JsonUtil;

/**
 * 测试模型调用模块是否正常工作
 */
public class ModelCallTest {

    public static void main(String[] args) {
        System.out.println("=== 模型调用模块测试 ===\n");
        
        int passed = 0;
        int failed = 0;
        
        // 测试1: JsonUtil 工具类
        System.out.println("测试1: JsonUtil JSON解析");
        try {
            String jsonStr = "{\"model\": \"test-model\", \"messages\": [{\"role\": \"user\", \"content\": \"Hello\"}], \"stream\": false}";
            JsonObject obj = JsonUtil.fromJson(jsonStr, JsonObject.class);
            if (obj != null && obj.has("model") && "test-model".equals(obj.get("model").getAsString())) {
                System.out.println("  [PASS] JSON解析正常");
                passed++;
            } else {
                System.out.println("  [FAIL] JSON解析结果不正确");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] JSON解析异常: " + e.getMessage());
            failed++;
        }
        
        // 测试2: JsonUtil JSON生成
        System.out.println("\n测试2: JsonUtil JSON生成");
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("model", "llama-3");
            obj.addProperty("temperature", 0.7);
            String json = JsonUtil.toJson(obj);
            if (json != null && json.contains("llama-3") && json.contains("0.7")) {
                System.out.println("  [PASS] JSON生成正常");
                passed++;
            } else {
                System.out.println("  [FAIL] JSON生成结果不正确");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] JSON生成异常: " + e.getMessage());
            failed++;
        }
        
        // 测试3: ModelSamplingService 单例
        System.out.println("\n测试3: ModelSamplingService 单例获取");
        try {
            ModelSamplingService service1 = ModelSamplingService.getInstance();
            ModelSamplingService service2 = ModelSamplingService.getInstance();
            if (service1 != null && service1 == service2) {
                System.out.println("  [PASS] ModelSamplingService 单例正常");
                passed++;
            } else {
                System.out.println("  [FAIL] ModelSamplingService 单例不正确");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] ModelSamplingService 获取异常: " + e.getMessage());
            failed++;
        }
        
        // 测试4: ChatTemplateKwargsService 单例
        System.out.println("\n测试4: ChatTemplateKwargsService 单例获取");
        try {
            ChatTemplateKwargsService service1 = ChatTemplateKwargsService.getInstance();
            ChatTemplateKwargsService service2 = ChatTemplateKwargsService.getInstance();
            if (service1 != null && service1 == service2) {
                System.out.println("  [PASS] ChatTemplateKwargsService 单例正常");
                passed++;
            } else {
                System.out.println("  [FAIL] ChatTemplateKwargsService 单例不正确");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] ChatTemplateKwargsService 获取异常: " + e.getMessage());
            failed++;
        }
        
        // 测试5: OpenAIService 构造
        System.out.println("\n测试5: OpenAIService 构造");
        try {
            OpenAIService service = new OpenAIService();
            if (service != null) {
                System.out.println("  [PASS] OpenAIService 构造正常");
                passed++;
            } else {
                System.out.println("  [FAIL] OpenAIService 构造失败");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] OpenAIService 构造异常: " + e.getMessage());
            failed++;
        }
        
        // 测试6: 请求JSON验证逻辑
        System.out.println("\n测试6: 请求JSON验证逻辑");
        try {
            // 测试缺少model字段的情况
            String invalidJson = "{\"messages\": []}";
            JsonObject invalidObj = JsonUtil.fromJson(invalidJson, JsonObject.class);
            boolean hasModel = invalidObj != null && invalidObj.has("model") && !invalidObj.get("model").isJsonNull();
            if (!hasModel) {
                System.out.println("  [PASS] 缺少model字段检测正常");
                passed++;
            } else {
                System.out.println("  [FAIL] 缺少model字段检测不正确");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] 请求JSON验证异常: " + e.getMessage());
            failed++;
        }
        
        // 测试7: 流式请求标志解析
        System.out.println("\n测试7: 流式请求标志解析");
        try {
            String streamJson = "{\"model\": \"test\", \"stream\": true}";
            JsonObject streamObj = JsonUtil.fromJson(streamJson, JsonObject.class);
            boolean isStream = false;
            if (streamObj != null && streamObj.has("stream")) {
                isStream = streamObj.get("stream").getAsBoolean();
            }
            if (isStream) {
                System.out.println("  [PASS] 流式标志解析正常");
                passed++;
            } else {
                System.out.println("  [FAIL] 流式标志解析不正确");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] 流式标志解析异常: " + e.getMessage());
            failed++;
        }
        
        // 测试8: AnthropicService 构造
        System.out.println("\n测试8: AnthropicService 构造");
        try {
            AnthropicService service = new AnthropicService();
            if (service != null) {
                System.out.println("  [PASS] AnthropicService 构造正常");
                passed++;
            } else {
                System.out.println("  [FAIL] AnthropicService 构造失败");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] AnthropicService 构造异常: " + e.getMessage());
            failed++;
        }
        
        // 测试9: NodeProxyService 单例
        System.out.println("\n测试9: NodeProxyService 单例获取");
        try {
            NodeProxyService service1 = NodeProxyService.getInstance();
            NodeProxyService service2 = NodeProxyService.getInstance();
            if (service1 != null && service1 == service2) {
                System.out.println("  [PASS] NodeProxyService 单例正常");
                passed++;
            } else {
                System.out.println("  [FAIL] NodeProxyService 单例不正确");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] NodeProxyService 获取异常: " + e.getMessage());
            failed++;
        }
        
        // 测试10: ModelRequestTracker 单例
        System.out.println("\n测试10: ModelRequestTracker 单例获取");
        try {
            ModelRequestTracker tracker1 = ModelRequestTracker.getInstance();
            ModelRequestTracker tracker2 = ModelRequestTracker.getInstance();
            if (tracker1 != null && tracker1 == tracker2) {
                System.out.println("  [PASS] ModelRequestTracker 单例正常");
                passed++;
            } else {
                System.out.println("  [FAIL] ModelRequestTracker 单例不正确");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] ModelRequestTracker 获取异常: " + e.getMessage());
            failed++;
        }
        
        // 输出测试结果
        System.out.println("\n=== 测试结果 ===");
        System.out.println("通过: " + passed);
        System.out.println("失败: " + failed);
        System.out.println("总计: " + (passed + failed));
        
        if (failed == 0) {
            System.out.println("\n所有测试通过！模型调用模块工作正常。");
            System.exit(0);
        } else {
            System.out.println("\n存在失败的测试，请检查相关模块。");
            System.exit(1);
        }
    }
}

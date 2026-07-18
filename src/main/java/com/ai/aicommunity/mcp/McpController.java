package com.ai.aicommunity.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final String JSON_RPC_VERSION = "2.0";

    private final McpToolService mcpToolService;
    private final ObjectMapper objectMapper;

    public McpController(McpToolService mcpToolService, ObjectMapper objectMapper) {
        this.mcpToolService = mcpToolService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public McpResponse handle(@RequestBody McpRequest request) {
        try {
            Object result = switch (request.method()) {
                case "initialize" -> initializeResult();
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> callTool(request.params());
                default -> throw new IllegalArgumentException("不支持的 MCP 方法: " + request.method());
            };
            return McpResponse.success(request.id(), result);
        } catch (Exception e) {
            return McpResponse.error(request.id(), -32000, e.getMessage());
        }
    }

    private Map<String, Object> initializeResult() {
        return Map.of(
                "protocolVersion", "2024-11-05",
                "serverInfo", Map.of(
                        "name", "ai-community-mcp",
                        "version", "0.1.0"
                ),
                "capabilities", Map.of(
                        "tools", Map.of("listChanged", false)
                )
        );
    }

    private Object callTool(Map<String, Object> params) throws JsonProcessingException {
        String name = String.valueOf(params.get("name"));
        Map<String, Object> arguments = mapParam(params.get("arguments"));
        Object data = mcpToolService.call(name, arguments);
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", objectMapper.writeValueAsString(data)
                )),
                "isError", false
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapParam(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private List<Map<String, Object>> tools() {
        return List.of(
                tool("search_articles", "按关键词搜索文章，返回分页结果。", objectSchema(Map.of(
                        "keyword", stringSchema("文章标题或正文关键词"),
                        "current", integerSchema("页码，默认 1"),
                        "size", integerSchema("每页数量，最大 20")
                ), List.of())),
                tool("get_article_detail", "按文章 ID 查询文章详情，会复用项目里的文章缓存链路。", objectSchema(Map.of(
                        "articleId", integerSchema("文章 ID")
                ), List.of("articleId"))),
                tool("list_hot_articles", "查询热门文章列表。", objectSchema(Map.of(
                        "limit", integerSchema("返回数量，最大 20")
                ), List.of())),
                tool("list_training_camps", "分页查询训练营列表。", objectSchema(Map.of(
                        "current", integerSchema("页码，默认 1"),
                        "size", integerSchema("每页数量，最大 20")
                ), List.of())),
                tool("get_training_camp", "按训练营 ID 查询训练营信息。", objectSchema(Map.of(
                        "campId", integerSchema("训练营 ID")
                ), List.of("campId")))
        );
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        return Map.of(
                "name", name,
                "description", description,
                "inputSchema", inputSchema
        );
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required
        );
    }

    private Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    private Map<String, Object> integerSchema(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public record McpRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {
    }

    public record McpResponse(String jsonrpc, Object id, Object result, McpError error) {

        public static McpResponse success(Object id, Object result) {
            return new McpResponse(JSON_RPC_VERSION, id, result, null);
        }

        public static McpResponse error(Object id, int code, String message) {
            return new McpResponse(JSON_RPC_VERSION, id, null, new McpError(code, message));
        }
    }

    public record McpError(int code, String message) {
    }
}

# AI 知识社区 MCP 工具层

这个项目新增了一个轻量 MCP JSON-RPC 入口：`POST /mcp`。

它的作用不是替代原来的 HTTP 接口，而是把项目里的只读能力封装成工具，方便 AI Agent 或外部客户端按统一协议调用。

## 支持的工具

- `search_articles`：按关键词搜索文章。
- `get_article_detail`：按文章 ID 查询详情，会复用文章缓存链路。
- `list_hot_articles`：查询热门文章。
- `list_training_camps`：分页查询训练营。
- `get_training_camp`：按训练营 ID 查询训练营。

## 查看工具列表

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

## 调用工具示例

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_article_detail","arguments":{"articleId":2075969134183776257}}}'
```

## 面试说法

我在项目里补了一层轻量 MCP Server，把文章查询、热门文章、训练营查询这类只读业务封装成标准工具。外部 AI Agent 不需要理解我原来的 Controller 路由，只要通过 `tools/list` 获取工具，再通过 `tools/call` 调用，就能复用项目已有的 Service、缓存和数据库能力。

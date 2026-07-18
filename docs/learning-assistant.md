# 学习问答助手

学习问答助手入口：

```text
POST /learning-assistant/ask
```

这个接口需要登录 JWT，因为它是面向用户的 AI 问答能力。

## 请求示例

```bash
curl -X POST http://localhost:8080/learning-assistant/ask \
  -H "Authorization: Bearer <你的登录JWT>" \
  -H "Content-Type: application/json" \
  -d '{"question":"Redis缓存怎么做？"}'
```

## 处理流程

```text
用户问题
-> 提取关键词
-> 检索相关文章和训练营资料
-> 组装上下文 Prompt
-> 调用大模型生成回答
-> 返回答案和引用来源
```

如果 `app.ai.enabled=false` 或没有配置 `AI_API_KEY`，接口不会调用大模型，而是返回检索到的文章/训练营引用，方便本地开发和演示。

## 面试说法

我在项目里补了一个学习问答助手。它不是直接让大模型自由回答，而是先从社区文章和训练营数据里检索相关上下文，再把上下文和用户问题一起交给大模型生成回答。这样可以让回答更贴合项目已有内容，也能避免模型脱离资料乱编。

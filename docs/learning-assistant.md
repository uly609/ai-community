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

继续同一个会话时带上返回的 `conversationId`：

```bash
curl -X POST http://localhost:8080/learning-assistant/ask \
  -H "Authorization: Bearer <你的登录JWT>" \
  -H "Content-Type: application/json" \
  -d '{"conversationId":1,"question":"那缓存击穿怎么处理？"}'
```

## 处理流程

```text
用户问题
-> 创建/加载会话
-> 保存用户消息
-> 提取关键词
-> 检索相关文章和训练营资料
-> 拼接最近对话历史和项目上下文 Prompt
-> 调用大模型生成回答
-> 保存助手回复
-> 返回答案、conversationId 和引用来源
```

如果 `app.ai.enabled=false` 或没有配置 `AI_API_KEY`，接口不会调用大模型，而是返回检索到的文章/训练营引用，方便本地开发和演示。

## 面试说法

我在项目里补了一个学习问答助手。它不是直接让大模型自由回答，而是先从社区文章和训练营数据里检索相关上下文，再结合最近会话历史生成回答，并把用户问题和助手回复落库，支持多轮对话和上下文记忆。

## 新增配套能力

- `POST /chats/messages`：发送站内私信。
- `GET /chats/conversations/{otherUserId}`：查询和某个用户的聊天记录。
- `PUT /chats/conversations/{otherUserId}/read`：标记聊天已读。
- `WS /ws/chat`：在线用户实时接收聊天消息。
- `POST /files/upload`：上传文章或评论附件。
- `GET /files/{id}/download`：下载附件。

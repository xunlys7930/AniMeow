# AI 看番风格分析后端契约

客户端只负责发送用户的本地追番统计摘要，不保存 DeepSeek API Key，也不直接访问 DeepSeek。

客户端还提供两种不依赖本后端的本地方式：

- 复制提示词：APP 只在本地生成包含统计摘要的提示词，用户自行粘贴到其他 AI 官网或 APP。
- 自定义模型：APP 直连用户填写的 OpenAI 兼容接口；用户 API Key 只用于本次请求头，不写入本地数据库，不发送到追番喵云端。

## 接口

`POST /api/user/anime-analysis`

鉴权：`Authorization: Bearer <cloud user token>`

请求体：

```json
{
  "model": "deepseek-v4-flash",
  "client_version": "1.3.4",
  "stats": {
    "schema_version": 1,
    "generated_at": "2026-06-12T12:00:00.000",
    "summary": {},
    "status_counts": [],
    "top_tags": []
  }
}
```

响应体：

```json
{
  "status": "success",
  "data": {
    "id": 123,
    "model": "deepseek-v4-flash",
    "analysis": "可爱的中文分析文本",
    "created_at": "2026-06-12T12:00:00.000Z",
    "remaining_today": 0
  }
}
```

## 服务器要求

- 仅允许已注册并登录的云账号调用。
- 每个用户每天最多成功生成一次，按服务器时区或明确的业务时区统计。
- DeepSeek API Key 只保存在服务器环境变量中，例如 `DEEPSEEK_API_KEY`，不要下发给客户端。
- DeepSeek OpenAI 兼容地址使用 `https://api.deepseek.com`。
- 固定使用模型 `deepseek-v4-flash`。
- 建议服务器数据库保存调用记录：用户 id、模型、统计摘要、分析结果、创建时间、状态、错误信息。
- 返回 `429` 或业务错误时，响应 JSON 中提供可展示的 `message`。

完整 MySQL 建表语句和 Express 路由参考见 `backend/docs/anime_analysis_backend_implementation.md`。

## Prompt 建议

服务器可以把客户端的 `stats` 转成系统提示词的一部分，要求模型用可爱、温柔、简短的中文分析用户的看番习惯、偏好标签、完成度、评分倾向和近期活跃度，并避免输出医疗、心理诊断或过度确定的结论。

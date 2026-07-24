# AniMeow Backend

这是追番喵客户端配套的可自建参考后端，提供公开数据代理、云账号、云备份、反馈、角色群组分享、版本更新统计和可选的 AI 看番分析。

仓库只包含可公开的源码与示例配置，不包含官方服务器的真实环境变量、数据库、邀请码、日志、部署路径或安装包。自行部署的实例与官方服务彼此独立。

## 快速开始

要求：Node.js 20+、MySQL 8+ 或兼容的 MariaDB。

```bash
cd backend
cp .env.example .env
npm install
npm start
```

启动前请创建数据库与最小权限数据库账号，并编辑 `.env`。服务首次启动时会创建所需表。

生产环境至少必须配置：

- `DB_HOST`、`DB_PORT`、`DB_USER`、`DB_PASSWORD`、`DB_NAME`
- `API_TOKEN`：管理员和旧版受保护接口使用
- `USER_TOKEN_SECRET`：云账号会话签名使用，必须与 `API_TOKEN` 不同
- `PUBLIC_BASE_URL`：反向代理后的公网 HTTPS 根地址
- `UPDATE_DOWNLOAD_URL`：真实安装包地址；不配置时下载接口返回 404

可用下面的方式分别生成两个随机值：

```bash
openssl rand -hex 32
```

## 安全注意事项

- 不要提交 `.env`、邀请码导出、数据库 dump、日志或 `public/` 下的发布包。
- 客户端内的 `API_TOKEN` 可以被逆向提取，不能把它当作用户身份凭证。公开部署应优先使用登录后的用户令牌，并把管理员接口限制在可信网络或额外网关之后。
- 生产环境必须使用 HTTPS，并在反向代理层配置请求频率、请求体大小、超时与访问日志脱敏。
- 云备份包含用户数据；部署者需要自行负责数据库加密、备份、访问控制、隐私告知与删除机制。
- `DEEPSEEK_API_KEY` 只放在服务器环境变量中，绝不能下发到客户端。

## 常用命令

```bash
npm run check
npm run sync-covers
npm run generate-invites
npm run export-invites
```

生成和导出的邀请码文件已被 `.gitignore` 排除。运行管理脚本前仍应确认当前目录和数据库指向的是预期实例。

## 主要接口

- `/api/check-update`、`/api/download/latest`
- `/api/auth/register`、`/api/auth/login`、`/api/auth/reset-password`
- `/api/user/backups`
- `/api/user/anime-analysis`
- `/api/community/character-groups`
- `/api/feedback`
- Bangumi / AniList 数据代理接口

具体请求格式可结合 `routes/`、`docs/` 和客户端 `lib/services/`、`lib/api/` 查看。

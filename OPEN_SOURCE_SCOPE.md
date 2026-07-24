# Open Source Scope

本仓库公开 `1.3.7+24` 客户端及经过脱敏的自建后端参考实现。原则是：公开复现功能所需的源码、资源、测试与示例配置；不公开任何生产数据、真实凭据或个人开发环境产物。

## 建议公开

- Flutter 客户端：`lib/`、`test/`、`pubspec.yaml`、`pubspec.lock`。
- 平台工程：`android/`、`windows/`，以及供社区自行移植的 `ios/`、`macos/`、`linux/`、`web/`。
- 构建所需资源：`assets/icon.png`、`assets/raw/`、Android 多图标资源。
- 可复用构建工具：`tool/*.ps1`、`tool/animeow_installer.iss`、`tools/gen_alt_icons.py`。
- 自建参考后端：`backend/app.js`、`backend/config/`、`backend/db/`、`backend/middleware/`、`backend/routes/`、`backend/utils/`、安全的管理脚本、`backend/package*.json`、`backend/.env.example` 和公开文档。
- 项目文档：`README.md`、`USER_GUIDE.md`、`CODE_WIKI.md`、`SECURITY.md`、功能测试清单和 `LICENSE`。

## 必须排除

- 真实环境变量和构建参数：`.env`、`.env.*`（示例文件除外）、`dart_defines.local.json`。
- 密钥与签名：`API_TOKEN`、`USER_TOKEN_SECRET`、`DB_PASSWORD`、`DEEPSEEK_API_KEY`、私钥、证书、JKS/keystore、`android/key.properties`。
- 生产数据：数据库文件 / dump、用户备份、邀请码导出、上传文件、日志和真实 API 响应。
- 部署内部信息：`backend/BACKEND_CONTEXT.md`、服务器路径、PM2 进程名、面板信息、真实主机 IP / 内网地址。
- 构建和发布产物：`build/`、`android/build/`、APK、AAB、EXE、安装包、压缩包。
- 本地工具上下文、IDE 配置、调试脚本、临时报告、代码备份和个人面试文档。
- 非构建必需的宣传生成物与富文档包：`promo_assets/`、`promo_*.md/html`、`animeow-user-manual/`。需要公开时应另行核对图片、字体和第三方资源许可证。

## 已确认可保留的公开信息

- GitHub 仓库地址、公开交流群号和公开个人主页属于产品内主动展示的联系信息，不按密钥处理。
- `dart_defines.local.example.json`、`backend/.env.example` 只保留占位符，不包含真实值。
- `API_TOKEN` 等变量名和鉴权实现可以公开；真正需要保护的是运行时的值。

## 发布前检查

```bash
git status --short --ignored
git diff --cached --name-only
git grep -n -I -E "47\\.103|/www/wwwroot|BEGIN .*PRIVATE KEY|github_pat_|ghp_|AKIA|AIza"
flutter analyze --no-fatal-infos
flutter test
cd backend && npm ci && npm run check && npm audit --omit=dev
```

暂存区中不应出现 `.env`、数据库、邀请码输出、日志、签名文件、真实服务器地址或构建产物。如果凭据曾进入 Git 历史，应先立即轮换凭据，再评估历史清理方案。

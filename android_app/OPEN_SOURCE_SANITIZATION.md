# AniMeow 开源脱敏与发布清单

本清单必须在首次公开仓库、每次迁移仓库和每次公开 Release 前执行。不要因为文件被 `.gitignore` 忽略就认为历史提交安全；一旦凭据进入 Git 历史，应立即轮换并清理历史。

## 1. 绝不提交的内容

- 根目录 `PROJECT_CONFIG_KEYS.md`。
- 任意 `.env`，只保留值为占位符的 `.env.example`。
- Android `local.properties`。
- `keystore.properties`、`.jks`、`.keystore`、`.p12`、`.pfx`、私钥和证书私钥。
- 真实用户数据库、云备份、原版个人备份、恢复点、本地封面和导出的表格。
- 运行日志、崩溃日志、后端 stdout/stderr、请求转储和数据库转储。
- Debug/Release APK、AAB、APKS 和签名中间产物。
- 测试账号、邀请码、群管理凭据、后台管理员令牌和生产数据库密码。

允许公开的二进制测试数据只有 `test-fixtures/legacy_v19_full.db` 与 `.zip`；它们由脚本使用合成内容生成，不含真实用户数据。

## 2. 当前配置边界

Android 构建支持以下键名：

- `CLOUD_API_BASE`
- `API_TOKEN`

值可来自 Gradle 属性、环境变量或 `local.properties`。公开仓库中只出现键名和空值/占位符。

重要：`BuildConfig` 字符串会写入 APK，可被反编译。`API_TOKEN` 不能是生产管理员密钥、数据库密钥或其他特权凭据。发布 APK 只能使用：

- 无令牌的公共接口；或
- 专门为客户端创建、权限最小、可撤销、限速且允许公开的只读令牌。

若服务器必须使用真正秘密的凭据，应由服务器在内部持有，客户端通过账号令牌或受限公开接口访问，不能把秘密编译进 APK。

## 3. 已配置的忽略规则

根目录 `.gitignore` 已忽略：

- 私密配置和签名文件。
- Gradle、Android Studio、Node 和覆盖率输出。
- 日志、诊断、恢复点和个人备份。
- APK/AAB/APKS。
- SQLite 运行数据。

`backend/.gitignore` 额外忽略 `.env`、`node_modules`、日志、上传目录和数据库文件。

初始化 Git 后执行：

```powershell
git status --short --ignored
git check-ignore -v PROJECT_CONFIG_KEYS.md backend/.env android_app/local.properties
git ls-files | rg '(?i)(\.env$|local\.properties$|keystore|\.jks$|\.p12$|\.pfx$|PROJECT_CONFIG_KEYS\.md$)'
```

最后一条应无输出。

## 4. 自动路径与内容审计

从根目录运行：

```powershell
.\scripts\open_source_audit.ps1
```

脚本只输出候选文件路径，不打印匹配到的秘密值。它会：

- 列出本机存在的私密文件候选。
- 跳过 `.env` 等已知私密文件后，扫描源码中的私钥、token、key、password、secret、Bearer、固定 API 地址、IPv4 地址和明文网络白名单模式。
- 若已初始化 Git，检查私密文件是否被跟踪。

候选并不一定是真泄露，例如模型字段名或脱敏正则可能误报；必须人工打开确认，但不得把实际凭据复制到问题、提交信息或公开聊天。

## 5. 人工源码审计

重点检查：

- `android_app/app/build.gradle.kts`：只保留从外部注入配置的逻辑。
- `android_app/local.properties`：不得提交；确认只含本机 SDK 路径和私密值。
- `backend/.env`：不得提交；确保 `.env.example` 全是占位符。
- `backend/config/`：默认值不得包含生产密码、生产令牌或私人下载链接。
- 网络安全配置：当前构建在 `CLOUD_API_BASE` 使用 `http://` 时会全局启用 `usesCleartextTraffic`，并非按域名白名单放行；公开 Release 必须优先改用 HTTPS，并确认最终 Manifest 中该值为 `false`。
- README、测试方案、更新日志和代码注释：不得出现真实账号、群管理密码、邀请码库存、个人目录或内网地址。
- 测试源和 fixtures：不得包含从真实备份复制的标题、评论、账号名或封面。
- Git 提交信息、Issue、PR、CI 日志和截图。

## 6. 后端脱敏

公开前：

1. 复制 `.env.example` 到临时环境并用全新测试值启动。
2. 确认缺失生产配置时服务拒绝危险操作或明确降级。
3. 删除 stdout/stderr 日志、邀请码导出、数据库 dump、上传文件和临时备份。
4. 检查 CORS、限速、账号 token 密钥、重置密码接口和管理员路由。
5. 确认 `/api/check-update`、下载地址和社区公开接口只返回可公开数据。
6. 确认管理员 API 不依赖编译进客户端的同一 token。

若任何已使用的秘密曾出现在文件、日志或终端录屏中，即使后来删除，也应轮换。

## 7. Android 签名与 Release

- 正式 keystore 存放在仓库外，并有离线备份。
- CI 使用受保护 Secret 注入路径和密码，不把值写入 Gradle 日志。
- Release 构建完成后检查签名：

```powershell
$releaseApk = 'X:\path\to\signed-release.apk'
apksigner verify --verbose --print-certs $releaseApk
```

- 不公开上传签名配置文件、别名密码或证书私钥。
- 公钥证书指纹可公开，用于让用户核对发行签名。

## 8. APK 内容审计

即使源码已脱敏，也要检查最终 APK：

```powershell
$releaseApk = 'X:\path\to\signed-release.apk'
apkanalyzer manifest print $releaseApk
apkanalyzer files list $releaseApk
```

再用反编译/字符串工具搜索：

- 真实 API token、密码和私钥头。
- 本机用户名、盘符路径、内网主机名和生产数据库地址。
- 测试账号、邀请码和调试后门。
- 不应存在的广告、统计或远程调试 SDK。

允许出现公开 API 域名、开源仓库链接、包名和公开证书指纹。

## 9. Git 历史处理

若秘密从未提交，只需保持忽略规则并重新审计。若已经提交：

1. 立即轮换秘密。
2. 暂停公开/发布。
3. 使用 `git filter-repo` 或等价工具从所有分支和标签清除文件/字符串。
4. 强制推送清理后的历史并通知所有协作者重新克隆。
5. 清理 Release 附件、CI 缓存、镜像、Fork、Issue 和 PR 日志。
6. 再次执行完整审计。

历史清理不能替代凭据轮换。

## 10. 开源发布前最终勾选

- [ ] `PROJECT_CONFIG_KEYS.md` 未跟踪。
- [ ] `backend/.env` 与 `android_app/local.properties` 未跟踪。
- [ ] 无 keystore、私钥、密码文件和个人数据库。
- [ ] `.env.example`、README 和示例配置仅含占位符。
- [ ] 客户端 APK 不含特权 `API_TOKEN`。
- [ ] 后端管理员接口使用服务端秘密，不复用客户端令牌。
- [ ] 日志、备份、恢复点、上传文件、APK/AAB 均未提交。
- [ ] 合成 v19 fixture 已重新生成并抽查内容。
- [ ] `open_source_audit.ps1` 无未解释命中。
- [ ] Git 跟踪文件和完整历史已扫描。
- [ ] 最终 APK 已做 Manifest、文件列表和字符串审计。
- [ ] 正式签名证书指纹已记录，私钥仍在仓库外。
- [ ] README、许可证、第三方鸣谢和隐私说明已审核。

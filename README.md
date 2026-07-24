# 追番喵 (AniMeow) 🐾

一个萌萌的二次元追番进度管理工具，帮你优雅管理心爱的动漫、漫画、小说。

![Logo](assets/icon.png)

**开源仓库**：[github.com/xunlys7930/AniMeow](https://github.com/xunlys7930/AniMeow)

**官方维护的平台**：**Android**、**Windows**。本仓库仍带有 Flutter 默认生成的 iOS / macOS / Linux / Web 等目录，便于有需要的开发者自行编译或移植；**维护者不保证这些平台可编译、可运行或行为正确**，相关 PR 欢迎，但可能无法及时跟进。

**对外品牌**：中文名「**追番喵**」，英文名 **AniMeow**。为兼容已安装用户，**未修改** Android `applicationId` / `namespace`（`com.example.anime_tracker`）、本地数据库文件名（`anime_tracker_v5.db`）、Windows 可执行文件名（`anime_tracker.exe`）；Windows「文件说明 / 产品名称」等元数据与界面文案已统一为 AniMeow。Dart 包名 `pubspec.yaml` 中的 `name: anime_tracker` 仅影响代码里的 `package:` import，可按需在未来大版本再议。macOS 工程产物目录名仍为 `anime_tracker.app`（与 Xcode 配置绑定），若需与 AniMeow 完全一致需另行调整工程文件。

## 🔓 开源范围

本仓库按“可复现、无私密数据”原则发布：包含 Flutter 客户端源码、必要资源、测试，以及经过脱敏的自建后端参考实现。官方服务器部署记录、真实环境变量、数据库、邀请码文件、日志和发布产物不在开源范围内。

本应用完全免费、无内购无广告，所有客户端源码均已公开。

## ✨ 特色功能

- **多源数据同步**：Bangumi + AniList 双源搜索匹配，封面/简介/集数/评分一键回填
- **灵活的状态管理**：想看 / 在看 / 看完 / 弃坑 + 自定义无限个状态、颜色
- **4 套首页布局**：智能聚合 / 精致卡片流 / 沉浸海报墙 / 紧凑索引
- **详情页可定制**：4 套预设布局 + 模块拖拽重排 + 显隐切换，查看页/编辑页拆分
- **强大的系列聚合**：同系列番剧自动聚合，列表整洁
- **可视化统计**：月度观看节奏、制作公司分布等
- **追番日历**：每周放送一目了然
- **追番提醒**：每周提醒不再错过
- **安全备份**：本地 SQLite，全量导出/恢复，数据隐私可控
- **Excel 导入**：从旧清单一键迁移

## 📖 用户使用手册

详细的使用教程和功能说明请参考：[USER_GUIDE.md](USER_GUIDE.md)

## 🛠️ 技术栈

- **Core**: Flutter (Channel Stable) + Dart 3.x
- **Database**: SQLite (sqflite)
- **State**: Provider + ValueNotifier
- **Network**: http / dio
- **UI**: Material 3 Expressive 设计系统
- **官方支持平台**: Android、Windows（见上文说明；其余平台目录仅作社区自主使用）

## 🚀 快速开始

### 前置要求

- Flutter SDK ≥ 3.24
- Dart SDK ≥ 3.5
- 一个可选的云端代理后端（见 [`backend/README.md`](backend/README.md)）；若未部署或未在客户端配置 `CLOUD_API_BASE`，云端搜索、封面同步、检查更新等会不可用，但本地追番、统计、备份等都正常

### 客户端运行

1. 克隆仓库
   ```bash
   git clone https://github.com/xunlys7930/AniMeow.git
   cd AniMeow
   ```
2. 安装依赖
   ```bash
   flutter pub get
   ```
3. 运行（自建后端时，把后端发的 `API_TOKEN` 与对外根地址 `CLOUD_API_BASE` 一起注入，不要带末尾 `/`）
   ```bash
   flutter run \
     --dart-define=CLOUD_API_BASE=https://your-api.example.com \
     --dart-define=API_TOKEN=your_token_here
   ```
4. 打包发布
   ```bash
   # Android
   flutter build apk --release \
     --dart-define=CLOUD_API_BASE=https://your-api.example.com \
     --dart-define=API_TOKEN=your_token_here

   # Windows
   flutter build windows --release \
     --dart-define=CLOUD_API_BASE=https://your-api.example.com \
     --dart-define=API_TOKEN=your_token_here
   ```

为了避免每次手动输入，可以在 IDE 配置里固化。VS Code `.vscode/launch.json` 示例：

```json
{
  "configurations": [
    {
      "name": "AniMeow (debug)",
      "request": "launch",
      "type": "dart",
      "toolArgs": [
        "--dart-define=CLOUD_API_BASE=https://your-api.example.com",
        "--dart-define=API_TOKEN=your_token_here"
      ]
    }
  ]
}
```

## 🌐 后端与协议（自建参考）

`backend/` 现在提供可自行部署的参考实现。它不是官方托管服务，也不包含官方实例的任何凭据或部署信息。完整安装和安全说明见 [`backend/README.md`](backend/README.md)。

- **鉴权**：所有 `/api/*` 接口走 `Authorization: Bearer <API_TOKEN>` 头。客户端通过 `--dart-define=API_TOKEN=...` 在编译期注入同样的值。
- **根地址**：客户端通过 `--dart-define=CLOUD_API_BASE=https://your-api.example.com` 指定，不要带末尾 `/`。
- **接口集合**：参考 `lib/api/bangumi_service.dart`、`lib/api/anilist_service.dart`、`lib/api/update_service.dart` 中的 `Uri.parse('$base/api/...')` 调用，按需实现云端搜索、封面同步、检查更新等。

> 🚨 **重要**：`API_TOKEN` 一旦在客户端编译期注入，相当于打进了 APK / EXE，只能作为兼容旧接口或低敏感代理接口的访问门槛，不能当作用户身份凭证。管理员接口应通过反向代理、网络 ACL 或其他额外措施保护；真实值只能放在本地环境变量中。

## 📂 项目结构

```
lib/
├── api/                 # Bangumi / AniList / 自研后端 接口
├── db/                  # SQLite 表结构与 CRUD
├── providers/           # 全局状态（DataRefreshProvider 等）
├── settings/            # 设置页与子页
├── ui/
│   ├── anime_detail/    # 番剧详情查看页（4 套布局 + 6 个模块）
│   ├── components/      # 共用组件（封面、状态徽章等）
│   ├── views/           # 首页 4 套布局
│   └── ...
├── utils/               # 工具（日志、API config、通知服务）
└── ...
backend/                  # 脱敏后的 Node.js + MySQL 自建参考后端
```

## 🤝 贡献

欢迎 Issue 和 PR。提 PR 前请先：

1. 跑 `flutter analyze --no-fatal-infos`，确保没有 error / warning
2. 跑 `flutter test`，确保已有测试通过
3. 不要把 `.env`、`API_TOKEN` 等敏感信息 commit 进去

发布前请同时检查 [`SECURITY.md`](SECURITY.md) 和 [`docs/FUNCTIONAL_TEST_CHECKLIST.md`](docs/FUNCTIONAL_TEST_CHECKLIST.md)。

## 📄 开源边界

- 客户端、后端参考实现和示例配置按 MIT License 发布。
- `.env`、数据库备份、邀请码导出、服务器路径、日志、签名文件和 APK/EXE 等本地或生产产物不会随源码发布。
- `API_TOKEN`、`USER_TOKEN_SECRET`、`DB_PASSWORD`、`DEEPSEEK_API_KEY` 等值必须由部署者自行生成并保存在运行环境中。

## 📝 许可证

[MIT License](LICENSE) · Copyright © 2026 XunLys

---

*记录每一份热爱，追番喵陪你一起看世界。*

# 追番喵 (ZhuiFanMiao) 🐾

一个萌萌的二次元追番进度管理工具，帮你优雅管理心爱的动漫、漫画、小说。

![Logo](assets/icon.png)

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

## 🛠️ 技术栈

- **Core**: Flutter (Channel Stable) + Dart 3.x
- **Database**: SQLite (sqflite)
- **State**: Provider + ValueNotifier
- **Network**: http / dio
- **UI**: Material 3 Expressive 设计系统
- **平台**: Android、iOS、Windows（macOS / Linux 理论支持，未深度测试）

## 🚀 快速开始

### 前置要求

- Flutter SDK ≥ 3.24
- Dart SDK ≥ 3.5
- 一台运行追番喵后端服务的服务器（见下方[后端部署](#-后端部署)）；如果只想跑客户端做本地体验，所有依赖云端的功能（云端资源发现、版本检查）会失效，但本地追番、统计、备份等都正常

### 客户端运行

1. 克隆仓库
   ```bash
   git clone https://github.com/tanpeng343-ui/anime_tracker.git
   cd anime_tracker
   ```
2. 安装依赖
   ```bash
   flutter pub get
   ```
3. 运行（注意需要传入 `API_TOKEN`，与后端 `.env` 里的 `API_TOKEN` 必须一致）
   ```bash
   flutter run --dart-define=API_TOKEN=your_token_here
   ```
4. 打包发布
   ```bash
   # Android
   flutter build apk --release --dart-define=API_TOKEN=your_token_here

   # Windows
   flutter build windows --release --dart-define=API_TOKEN=your_token_here
   ```

为了避免每次手动输入，可以在 IDE 配置里固化。VS Code `.vscode/launch.json` 示例：

```json
{
  "configurations": [
    {
      "name": "anime_tracker (debug)",
      "request": "launch",
      "type": "dart",
      "toolArgs": ["--dart-define=API_TOKEN=your_token_here"]
    }
  ]
}
```

## 🌐 后端部署

仓库根目录的 `app.js` 是 Node.js + Express + MySQL 实现的云端代理后端，负责：

- 抓取 Bangumi / AniList 的番剧元数据
- 提供云端搜索 / 资源发现 / 封面同步
- 客户端版本检查

### 部署步骤

1. 准备一台能跑 Node.js 18+ 和 MySQL 8.x 的服务器
2. 复制并填好环境变量
   ```bash
   cp .env.example .env
   vi .env  # 把 DB_PASSWORD / API_TOKEN 填进去
   ```
3. 安装依赖并启动（建议用 [pm2](https://pm2.keymetrics.io/) 或 systemd 守护）
   ```bash
   npm install express mysql2 axios dotenv
   node app.js
   ```
4. 把 `API_TOKEN` 同步给客户端构建命令（见上面的 `--dart-define`）

后端首次启动会自动建表（`animes`）。

> 🚨 **重要**：`API_TOKEN` 必须用强随机字符串。可以用 `openssl rand -hex 32` 生成。

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
app.js                   # 云端后端服务
```

## 🤝 贡献

欢迎 Issue 和 PR。提 PR 前请先：

1. 跑 `flutter analyze`，确保没有 error / warning
2. 跑 `flutter test`，确保已有测试通过
3. 不要把 `.env`、`API_TOKEN` 等敏感信息 commit 进去

## 📝 许可证

[MIT License](LICENSE) · Copyright © 2026 XunLys

---

*记录每一份热爱，追番喵陪你一起看世界。*

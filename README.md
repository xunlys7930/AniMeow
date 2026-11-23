# AniMeow（追番喵）

AniMeow 用于记录追番、观看与阅读进度、作品资料、角色关系与个人感想，支持离线优先的本地资料库与多端备份互通。

本仓库开源 **Android 客户端** 与 **Windows 桌面端（AniMeow Studio）** 的源代码。

## 仓库结构

- `android_app/`：Android 原生客户端（当前版本 `2.2.5`，Kotlin + Jetpack Compose + Room + DataStore + Media3 + WorkManager）。
- `desktop_studio/`：Windows 桌面端 AniMeow Studio（当前版本 `2.3.12`，Rust + Tauri 2 + React 19 + TypeScript）。

## 主要功能

### Android 客户端（`android_app/`）

- 多类型收藏与进度记录：支持动画、书籍（轻小说/漫画）、游戏、音声分类管理，以及系列归组、标签、自定义状态、观看历史、放送日程、提醒、查重合并、统计与神作榜。
- 资料检索与发现：接入 Bangumi、AniList、trace.moe、VNDB 等公开资料接口与 Bangumi 收藏导入。
- 角色与社区：角色档案、作品关联、关系图谱、角色组与社区互动。
- 本地备份与跨端互通：支持完整 `.animeow.zip` 原生备份、按范围选择性恢复，以及与旧版数据的迁移兼容。
- 高度自定义外观：支持多套布局与主题、主题/强调色渐变、内置开源字体、自定义图标与桌面小组件。

### Windows 桌面端（`desktop_studio/`）

- 宽屏策展工作台：提供律表（虚拟滚动）、状态看板与书脊画册三视图、全局快捷命令（`Ctrl+K`）、统一跨来源检索、多窗口独立编辑与五套排版主题。
- 跨端双库隔离：`animeow.db` 与 Android 端共享跨端备份格式，`desktop_local.db` 独立管理本机媒体库、游戏启动器计时、剪贴板灵感手帐、关系画布布局与神作榜。
- 本机媒体与伴侣能力：内置本机回环流媒体播放与字幕支持、可选的画质增强着色器、已绑定游戏游玩时长记录与白名单本地播放器进度感知。
- 可选 AI 整理助手：支持接入 OpenAI 兼容、Gemini、Ollama 等自定义模型服务商与 MCP 扩展，提供本地资料核实、提案确认与撤销机制。
- Windows 安全存储：云端会话与自定义 API Key 通过 Windows DPAPI 加密保护。

## 构建说明

### Android（`android_app/`）

需要 **Android SDK（compileSdk 36）** 与 **JDK 17+**（可使用 Android Studio 自带 JBR）。

```powershell
cd android_app
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```

正式版签名需使用仓库外的私钥 keystore。

#### 可选云端配置（Android）

本地资料库与离线功能不依赖云端服务。如需连接自建或官方云端接口，可通过 Gradle 属性、环境变量或未提交的 `android_app/local.properties` 提供：

```properties
CLOUD_API_BASE=https://your-server.example
API_TOKEN=replace-with-a-restricted-read-token
```

公网地址要求使用 HTTPS（仅本地回环与私有局域网允许 HTTP 调试）。请勿将特权凭据写入公开仓库或发行 APK；`BuildConfig` 字段可被反编译读取。

### Windows 桌面端（`desktop_studio/`）

需要 **Node.js 20+**、**Rust（stable，MSVC 工具链）** 与 Windows WebView2 运行时。

```powershell
cd desktop_studio
npm ci

# 运行 Rust 核心引擎测试
cargo test -p animeow-core

# 前端类型检查与构建
npm run build

# 构建内嵌前端资源的独立可执行程序（不要直接运行裸 cargo build --release）
npm run build:exe

# 构建 Windows x64 NSIS 安装包
npm run build:nsis
```

## 开源说明与许可

- 本仓库采用 [MIT License](LICENSE) 开源。
- 后端服务与其他内部维护脚本暂不包含在本仓库中；本地记录、导入导出与离线功能均可独立使用。
- 第三方开源组件与资产声明：
  - 画质增强着色器改编自 [Anime4K](https://github.com/bloc97/Anime4K)（MIT License，Copyright 2019–2021 bloc97），各 `.glsl` 文件内均保留完整授权声明。
  - Android 内置子集字体基于 Noto Sans SC、Noto Serif SC 与 LXGW WenKai Lite，均遵循 SIL Open Font License 1.1，授权全文见 `android_app/app/src/main/assets/font-licenses/`。
